(ns jepsen.system.solr
  (:import (java.io IOException))
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info]]
            [jepsen.client :as client]
            [jepsen.control :as c]
            [jepsen.control.net :as net]
            [jepsen.db :as db]
            [jepsen.os.debian :as debian]
            [jepsen.util :refer [meh timeout]]
            [jepsen.nemesis :as nemesis]
            [clj-http.client :as http]
            [flux.core :as flux]
            [flux.query :as q]
            [flux.http :as fluxhttp]
            ))

(def index-name "jepsen2")

(defn find-in-replica-map [replicas state node_name]
  (filter
    (fn [[k v]]
      (and (= state (get v "state")) (= node_name (get v "node_name"))))
    replicas)
  )

(defn get-replica-map
  "Get information about all replicas for the shard from cluster state in ZK"
  [host-port]
  (let [
         api-call (str "http://" host-port "/solr/admin/collections?"
                       "action=clusterstatus&"
                       "collection=" index-name "&"
                       "shard=shard1&"
                       "wt=json")
         res (-> api-call
                (http/get {:as :json-string-keys})
                :body)
        ]
    ;(info (str "Got clusterstatus on " host-port " as " res))
    (get-in res ["cluster" "collections" index-name "shards" "shard1" "replicas"])
    )
  )

(defn get-node-info-from-cluster-state
  "Get complete node information (core, coreNodeName, baseUrl, coreUrl from cluster state in ZK"
  ([host-port]
   (get-node-info-from-cluster-state host-port "active"))
  ([host-port state]
   (let [node-info (find-in-replica-map (get-replica-map host-port) state
                              (str host-port "_solr"))]
     node-info
     )
   )
  )

(defn wait
  "Waits for solr to be healthy on the current node. Color is red,
  yellow, or green; timeout is in seconds."
  ([host-port timeout-secs]
   (wait host-port timeout-secs "active"))
  ([host-port timeout-secs wait-for-state]
   (timeout (* 1000 timeout-secs)
            (throw (RuntimeException.
                     "Timed out waiting for solr cluster recovery"))
            (info (str "Going to wait for " host-port " for timeout " timeout-secs " until we see state " wait-for-state))
            (loop []
              (when
                  (empty? (get-node-info-from-cluster-state host-port wait-for-state))
                (Thread/sleep 1000)
                (recur)
                )
              ))))

(defn get-host-name-from-node-info
  [node-info]
  (let [node-name (get (second (first node-info)) "node_name")]
    (.substring node-name 0 (.indexOf node-name ":"))
    )
  )

(defn get-leader-info
  [replica-map]
  (filter (fn [[k v]] (let [leader (get v "leader")] (and (not (nil? leader)) (= "true" leader)))) replica-map)
  )

(defn primaries
  "Returns a map of nodes to the node that node thinks is the current leader,
  as a map of keywords to keywords."
  [nodes]
  (->> nodes
       (pmap (fn [node]
               (let [replica-map (get-replica-map (str (name node) ":8983"))
                     leader-info (get-leader-info replica-map)
                     leader-host-name (if-not (empty? leader-info) (get-host-name-from-node-info leader-info))

                     ]
                 [node leader-host-name]
                 )
               )
             )
       (into {})))

(defn self-primaries
  "A sequence of nodes which think they are leaders."
  [nodes]
  (->> nodes
       primaries
       (filter (partial apply =))
       (map key)))

(def isolate-self-primaries-nemesis
  "A nemesis which completely isolates any node that thinks it is the primary."
  (nemesis/partitioner
    (fn [nodes]
      (let [ps (self-primaries nodes)]
        (nemesis/complete-grudge
          ; All nodes that aren't self-primaries in one partition
          (cons (remove (set ps) nodes)
                ; Each self-primary in a different partition
                (map list ps)))))))

(defn all-results
  "A sequence of all results from a search query."
  [client query]
  (flux/with-connection client
                        (try
                          (let [res (flux/query query {:rows 0})
                                hitcount (get-in res [:response :numFound])
                                res (flux/query query {:rows hitcount})]
                            (get-in res [:response :docs])
                            )
                          (catch Exception e
                            (println e)
                            (throw (RuntimeException. "Errored out"))
                            )
                          )
                        )
  )


(defrecord CreateSetClient [client]
  client/Client
  (setup! [_ test node]
    (let [
           client (fluxhttp/create (str "http://" (name node) ":8983/solr") index-name)]
      (.setConnectionTimeout client 1000)
      (.setSoTimeout client 3000)
      (flux/with-connection client
                            (flux/delete-by-query "*:*")
                            (flux/commit)
                            )
      (info (str "creating client for http://" (name node) ":8983/solr/" index-name))
      (CreateSetClient. client)))

  (invoke! [this test op]
    (case (:f op)
      :add (timeout 5000 (assoc op :type :info :value :timed-out)
                    (flux/with-connection client
                                          (try
                                            (let [r (flux/add {:id (:value op)})]
                                              (if
                                                (= 0 (get-in r [:responseHeader :status]))
                                                (assoc op :type :ok)
                                                (assoc op :type :info :value r)))
                                            (catch Exception e (assoc op :type :info :value :timed-out)))))
      :read (try
              (info "Calling commit on solr")
              (info "Waiting for recovery before read")
              (c/on-many (:nodes test) (wait (str c/*host* ":8983") 60 "active"))
              ;(Thread/sleep (* 10 1000))
              (info "Recovered; flushing index before read")
              (flux/with-connection client (flux/commit))
              (assoc op :type :ok
                        :value (->> (all-results client "*:*")
                                    (map (comp :id))
                                    (map #(Integer/parseInt %))
                                    (into (sorted-set))))
              (catch RuntimeException e
                (assoc op :type :fail :value (.getMessage e))))))

  (teardown! [_ test]
    (flux.client/shutdown client)))

(defn create-set-client
  "A set implemented by creating independent documents"
  []
  (CreateSetClient. nil))

(defn get-first-doc
  "Gets the first doc from a solr query response. Returns nil if no docs were found"
  [r]
  (let [docs (get-in r [:response :docs])]
    (when
        (= 0 (get-in r [:responseHeader :status]))
      (when
          (not-empty docs)
        (first docs)
        )
      )
    )
  )

; Use SolrCloud MVCC to do CAS read/write cycles, implementing a set.
(defrecord CASSetClient [doc-id client]
  client/Client
  (setup! [_ test node]
    (let [
           client (fluxhttp/create (str "http://" (name node)  ":8983/solr") index-name)]
      (.setConnectionTimeout client 1000)
      (.setSoTimeout client 3000)
      (flux/with-connection client
                            (flux/delete-by-query "*:*")
                            (flux/add {:id doc-id :values []})
                            (flux/commit)
                            )
      (CASSetClient. doc-id client)))

  (invoke! [this test op]
    (case (:f op)
      :add (timeout 5000 (assoc op :type :info :value :timed-out)
                    (flux/with-connection client
                                          (try
                                            (let [current (flux/query (str "id:" doc-id) {:wt "json"})
                                                  doc (get-first-doc current)
                                                  ]
                                              (println (str "Got first-doc: " doc))
                                              (if
                                                  (not (nil? doc))
                                                (let [version (get doc :_version_)
                                                       values (get doc :values)
                                                       values' (vec (conj values (:value op)))]
                                                   (println (str "Version: " version " values: " values " values': " values'))
                                                   (try
                                                     (println (str "Going to add doc: " {:id doc-id :values values' :_version_ version}))
                                                     (let
                                                         [r  (flux/add {:id doc-id :values values' :_version_ version})]
                                                       (println (str "Got response from add " r))
                                                       (if
                                                           (= 0 (get-in r [:responseHeader :status]))
                                                         (assoc op :type :ok)
                                                         (assoc op :type :fail)
                                                         )
                                                       )
                                                     (catch Exception e (assoc op :type :fail))
                                                     )
                                                   )
                                                ; Can't write without a read
                                                (assoc op :type :fail)
                                                )
                                              )
                                            )
                                          (catch IOException e (assoc op :type :info :value :timed-out))
                                          )
                    )
      )

    :read (try
            (info "Waiting for recovery before read")
            (c/on-many (:nodes test) (wait (str c/*host* ":8983") 200 "active"))
            ;(Thread/sleep (* 10 1000))
            (info "Recovered; flushing index before read")
            (flux/with-connection client (flux/commit)
                                  (try
                                    (let [r (flux/query (str "id:" doc-id) {:wt "json"})
                                          doc (get-first-doc r)
                                          ]
                                      (if (not (nil? doc))
                                        ;(assoc op :type :ok :value (into (sorted-set (get doc :_version_))))
                                        (assoc op :type :ok
                                                  :value (->> doc
                                                              :_version_
                                                              :values
                                                              (into (sorted-set))
                                                              )
                                                  )
                                        (assoc op :type :fail)
                                        )
                                      )
                                    (catch Exception e (assoc op :type :fail))
                                    )

                                  )
            (catch Exception e (assoc op :type :fail))
            )
    )

  (teardown! [_ test]
    (flux.client/shutdown client)))


(defn cas-set-client []
  (CASSetClient. "0" nil))