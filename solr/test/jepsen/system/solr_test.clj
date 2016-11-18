(ns jepsen.system.solr-test
  (:use jepsen.system.solr
        jepsen.core
        jepsen.tests
        clojure.test
        clojure.pprint)
  (:require [clojure.string :as str]
            [jepsen.util :as util]
            [jepsen.os.debian :as debian]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.model :as model]
            [jepsen.generator :as gen]
            [jepsen.nemesis :as nemesis]
            [jepsen.store :as store]
            [jepsen.report :as report]
            [jepsen.db :as db]))

(deftest solr-test
         (let [test (run!
                      (assoc
                        noop-test
                        :name      "solr"
                        :client    (case (System/getProperty "jepsen.solr.client")
                                     "create-set-client" (create-set-client)
                                     "cas-set-client" (cas-set-client))
                        :model     (model/set)
                        :checker   (checker/compose {:set  checker/set})
                        :nemesis   (case (System/getProperty "jepsen.solr.nemesis")
                                     "partition-random-halves" (nemesis/partition-random-halves)
                                     "partition-random-node" (nemesis/partition-random-node)
                                     "partition-halves" (nemesis/partition-halves)
                                     "bridge" (nemesis/partitioner nemesis/bridge))
                        :generator (gen/phases
                                     (->> (range)
                                          (map (fn [x] {:type  :invoke
                                                        :f     :add
                                                        :value x}))
                                          gen/seq
                                          (gen/stagger 1/10)
                                          (gen/delay 1)
                                          (gen/nemesis
                                            (gen/seq
                                              (cycle
                                                [(gen/sleep 30)
                                                 {:type :info :f :start}
                                                 (gen/sleep 200)
                                                 {:type :info :f :stop}])))
                                          (gen/time-limit 600))
                                     (gen/nemesis
                                       (gen/once {:type :info :f :stop}))
                                     (gen/clients
                                       (gen/once {:type :invoke :f :read})))))]
              (is (:valid? (:results test)))
              (pprint (:results test))))
