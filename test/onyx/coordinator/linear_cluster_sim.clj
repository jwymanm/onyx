(ns onyx.coordinator.linear-cluster-sim
  (:require [midje.sweet :refer :all]
            [clojure.core.async :refer [chan <!! >!! tap timeout]]
            [com.stuartsierra.component :as component]
            [simulant.sim :as sim]
            [simulant.util :as u]
            [datomic.api :as d]
            [onyx.system :refer [onyx-coordinator]]
            [onyx.extensions :as extensions]
            [onyx.coordinator.sim-test-utils :as sim-utils]
            [onyx.api]))

(def config (read-string (slurp (clojure.java.io/resource "test-config.edn"))))

(def cluster (atom {}))

(defn create-birth [executor t]
  [[{:db/id (d/tempid :test)
     :agent/_actions (u/e executor)
     :action/atTime t
     :action/type :action.type/register-linear-peer}]])

(defn create-death [executor t]
  [[{:db/id (d/tempid :test)
     :agent/_actions (u/e executor)
     :action/atTime t
     :action/type :action.type/unregister-linear-peer}]])

(defn generate-linear-scaling-data [test executor]
  (let [model (-> test :model/_tests first)
        limit (:test/duration test)
        rate (:model/peer-rate model)
        peers (:model/peek-peers model)
        gap (:model/silence-gap model)
        births (mapcat (partial create-birth executor)
                       (range 0 (* peers rate) rate))
        deaths (mapcat (partial create-death executor)
                       (range (+ (* peers rate) gap)
                              (+ (* (* peers rate) 2) gap)
                              rate))]
    (concat births deaths)))

(defn create-linear-cluster-test [conn model test]
  (u/require-keys test :db/id :test/duration)
  (-> @(d/transact conn [(assoc test
                           :test/type :test.type/linear-cluster
                           :model/_tests (u/e model))])
      (u/tx-ent (:db/id test))))

(defn create-executor [conn test]
  (let [tid (d/tempid :test)
        result @(d/transact conn
                            [{:db/id tid
                              :agent/type :agent.type/executor
                              :test/_agents (u/e test)}])]
    (d/resolve-tempid (d/db conn) (:tempids result) tid)))

(defmethod sim/create-test :model.type/linear-cluster
  [conn model test]
  (let [test (create-linear-cluster-test conn model test)
        executor (create-executor conn test)]
    (u/transact-batch conn (generate-linear-scaling-data test executor) 1000)
    (d/entity (d/db conn) (u/e test))))

(defmethod sim/create-sim :test.type/linear-cluster
  [sim-conn test sim]
  (-> @(d/transact sim-conn (sim/construct-basic-sim test sim))
      (u/tx-ent (:db/id sim))))

(def sim-uri (str "datomic:mem://" (d/squuid)))

(def sim-conn (sim-utils/reset-conn sim-uri))

(sim-utils/load-schema sim-conn "simulant/schema.edn")

(sim-utils/load-schema sim-conn "simulant/coordinator-sim.edn")

(def id (str (java.util.UUID/randomUUID)))

(def system
  (onyx-coordinator
   {:hornetq/mode :udp
    :hornetq/server? true
    :hornetq.udp/cluster-name (:cluster-name (:hornetq config))
    :hornetq.udp/group-address (:group-address (:hornetq config))
    :hornetq.udp/group-port (:group-port (:hornetq config))
    :hornetq.udp/refresh-timeout (:refresh-timeout (:hornetq config))
    :hornetq.udp/discovery-timeout (:discovery-timeout (:hornetq config))
    :hornetq.server/type :embedded
    :hornetq.embedded/config (:configs (:hornetq config))
    :zookeeper/address (:address (:zookeeper config))
    :zookeeper/server? true
    :zookeeper.server/port (:spawn-port (:zookeeper config))
    :onyx/id id
    :onyx.coordinator/revoke-delay 2000}))

(def components (component/start system))

(def coordinator (:coordinator components))

(def offer-spy (chan 10000))

(def catalog
  [{:onyx/name :in
    :onyx/type :input
    :onyx/consumption :sequential
    :onyx/medium :hornetq
    :hornetq/queue-name "in-queue"}
   {:onyx/name :inc
    :onyx/type :transformer
    :onyx/consumption :sequential}
   {:onyx/name :out
    :onyx/type :output
    :onyx/consumption :sequential
    :onyx/medium :hornetq
    :hornetq/queue-name "out-queue"}])

(def workflow [[:in :inc] [:inc :out]])

(def n-jobs 60)

(def tasks-per-job 3)

(def job-chs (map (fn [_] (chan 1)) (range n-jobs)))

(tap (:offer-mult coordinator) offer-spy)

(doseq [n (range n-jobs)]
  (let [node (extensions/create (:sync components) :plan)]
    (extensions/on-change (:sync components) (:node node) #(>!! (nth job-chs n) %))
    (extensions/create (:sync components) :planning-log
                       {:job {:workflow workflow :catalog catalog}
                        :node (:node node)})
    (>!! (:planning-ch-head coordinator) true)))

(doseq [_ (range n-jobs)]
  (<!! offer-spy))

(def linear-model-id (d/tempid :model))

(def linear-cluster-model-data
  [{:db/id linear-model-id
    :model/type :model.type/linear-cluster
    :model/n-peers 5
    :model/peek-peers 50
    :model/peer-rate 200
    :model/silence-gap 5000
    :model/mean-ack-time 800
    :model/mean-completion-time 500}])

(def linear-cluster-model
  (-> @(d/transact sim-conn linear-cluster-model-data)
      (u/tx-ent linear-model-id)))

(defmethod sim/perform-action :action.type/register-linear-peer
  [action process]
  (let [peer (extensions/create (:sync components) :peer)]
    (swap! cluster assoc peer
           (sim-utils/create-peer
            linear-cluster-model
            components peer))))

(defmethod sim/perform-action :action.type/unregister-linear-peer
  [action process]
  (try
    (let [cluster-val @cluster
          n (count cluster-val)
          victim (nth (keys cluster-val) (rand-int n))]
      (let [pulse (:pulse-node (extensions/read-node (:sync components) (:node victim)))]
        (extensions/delete (:sync components) pulse)
        (future-cancel (get cluster-val victim))
        (swap! cluster dissoc victim)))
    (catch Exception e
      (.printStackTrace e))))

(sim-utils/create-peers! linear-cluster-model components cluster)

(def linear-cluster-test
  (sim/create-test sim-conn
                   linear-cluster-model
                   {:db/id (d/tempid :test)
                    :test/duration (u/hours->msec 1)}))

(def linear-cluster-sim
  (sim/create-sim sim-conn
                  linear-cluster-test
                  {:db/id (d/tempid :sim)
                   :sim/systemURI (str "datomic:mem://" (d/squuid))
                   :sim/processCount 1}))

(sim/create-fixed-clock sim-conn linear-cluster-sim {:clock/multiplier 1})

(sim/create-action-log sim-conn linear-cluster-sim)

(def pruns
  (->> #(sim/run-sim-process sim-uri (:db/id linear-cluster-sim))
       (repeatedly (:sim/processCount linear-cluster-sim))
       (into [])))

(doseq [job-ch job-chs]
  (let [id (extensions/read-node (:sync components) (:path (<!! job-ch)))]
    @(onyx.api/await-job-completion* (:sync components) (str id))))

(doseq [prun pruns] (future-cancel (:runner prun)))

(facts "All tasks of all jobs are completed"
       (sim-utils/task-completeness (:sync coordinator)))

(facts "Peer states only make legal transitions"
       (sim-utils/peer-state-transition-correctness (:sync coordinator)))

(facts "Sequential tasks are only executed by one peer at a time"
       (sim-utils/sequential-safety (:sync coordinator)))

(component/stop components)

