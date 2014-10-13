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
            [flux.http :as fluxhttp]))

(def index-name "jepsen5x3")

(defn solr-node-name
  "Returns the solr node name for given host/port"
  [host-port]
  (str host-port "_solr"))

(defn all-replicas
  [host-port]
  (try
    (let [api-call (str "http://" host-port "/solr/admin/collections?"
                      "action=clusterstatus"
                      "&collection=" index-name
                      "&wt=json")
        res (-> api-call
                (http/get {:as :json-string-keys})
                :body)
        shards (get-in res ["cluster" "collections" index-name "shards"])]
    (into {}
          (map (fn [[k v]] (get v "replicas")) shards))
    )
    (catch Exception e (clojure.tools.logging/warn "Couldn't fetch clusterstate due to " e) {})))

(defn active?
  "Returns true if replica is in active state"
  {:static true}
  [replica]
  (= "active" (get-in (second replica) ["state"])))

(defn recovering?
  "Returns true if replica is in recovery state"
  {:static true}
  [replica]
  (= "recovery" (get-in (second replica) ["state"])))

(defn down?
  "Returns true if replica is in down state"
  {:static true}
  [replica]
  (= "down" (get-in (second replica) ["state"])))

(defn not-active?
  "Returns true if replica is not in active state"
  {:static true}
  [replica]
  (not (active? replica)))

(defn hosted-by?
  "Returns true if given replica is hosted locally by given host/port"
  {:static true}
  [replica host-port]
  (= (solr-node-name host-port) (get-in (second replica) ["node_name"])))

(defn leader?
  "Returns true if given replica is a leader"
  {:static true}
  [replica]
  (= "true" (get-in (second replica) ["leader"])))

(defn wait
  [host-port timeout-secs]
  (timeout (* 1000 timeout-secs)
           (throw (RuntimeException.
                    "Timed out waiting for solr node to be active"))
           (loop []
             (let [replicas (all-replicas host-port)]
               (when (or
                       (empty? replicas)
                       (some
                         not-active?
                         (filter
                           (fn [x] (hosted-by? x host-port))
                           (all-replicas host-port))))
                 (Thread/sleep 1000)
                 (recur)))
             )))

(defn find-in-replica-map [replicas state node_name]
  (filter
    (fn [[k v]]
      (and (= state (get v "state")) (= node_name (get v "node_name"))))
    replicas)
  )

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
                                            (info (str "Adding " (:value op) " to node " (.getBaseURL client)))
                                            (let [r (flux/add {:id (:value op)})]
                                              (if
                                                (= 0 (get-in r [:responseHeader :status]))
                                                (assoc op :type :ok)
                                                (assoc op :type :info :value r)))
                                            (catch Exception e (clojure.tools.logging/warn "Unable to write value=" (:value op) " on: " (.getBaseURL client) " due to: " e) (assoc op :type :info :value :timed-out)))))
      :read (try
              (info "Waiting for recovery before read")
              ; Sleep for a while because after it takes a few seconds to re-connect to zookeeper after partitions
              ; and if you ask for cluster state before that node has connected then you receive an error (zk session expired)
              ; and the recovery functions blows up
              (Thread/sleep (* 60 1000))
              (c/on-many (:nodes test) (wait (str c/*host* ":8983") 120))
              ;(Thread/sleep (* 10 1000))
              (info "Recovered; flushing index before read")
              (info "Calling commit on solr")
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
                                            (let [ _ (flux/commit)
                                                   current (flux/query (str "id:" doc-id) {:wt "json"})
                                                  doc (get-first-doc current)
                                                  ]
                                              (if
                                                  (not (nil? doc))
                                                (let [version (get doc :_version_)
                                                       values (get doc :values)
                                                       values' (vec (conj values (:value op)))]
                                                   (try
                                                     (info (str "Going to add doc: " {:id doc-id :values values' :_version_ version} " on " (.getBaseURL client)))
                                                     (let
                                                         [r  (flux/add {:id doc-id :values values' :_version_ version})]
                                                       (if
                                                           (= 0 (get-in r [:responseHeader :status]))
                                                         (assoc op :type :ok)
                                                         (assoc op :type :fail)
                                                         )
                                                       )
                                                     (catch Exception e (clojure.tools.logging/warn "Unable to write value=" (:value op) " on: " (.getBaseURL client) " due to: " e) (assoc op :type :fail))
                                                     )
                                                   )
                                                ; Can't write without a read
                                                (assoc op :type :fail)
                                                )
                                              )
                                            )
                                          (catch Exception e (clojure.tools.logging/warn "Unable to write value=" (:value op) " on: " (.getBaseURL client) " due to: " e) (assoc op :type :info :value :timed-out))
                                          )
                    )
      :read (try
              (info "Waiting for recovery before read")
              ; Sleep for a while because after it takes a few seconds to re-connect to zookeeper after partitions
              ; and if you ask for cluster state before that node has connected then you receive an error (zk session expired)
              ; and the recovery functions blows up
              (Thread/sleep (* 60 1000))
              (c/on-many (:nodes test) (wait (str c/*host* ":8983") 60))
              (Thread/sleep (* 1 1000))
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


    )

  (teardown! [_ test]
    (flux.client/shutdown client)))


(defn cas-set-client []
  (CASSetClient. "0" nil))