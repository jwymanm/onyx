(ns onyx.peer.multi-input-test
  (:require [midje.sweet :refer :all]
            [onyx.queue.hornetq-utils :as hq-util]
            [onyx.api]))

(def config (read-string (slurp (clojure.java.io/resource "test-config.edn"))))

(def n-messages 15000)

(def batch-size 1320)

(def k-inputs 4)

(def echo 1000)

(def id (str (java.util.UUID/randomUUID)))

(def in-queues (map (fn [_] (str (java.util.UUID/randomUUID))) (range k-inputs)))

(def out-queue (str (java.util.UUID/randomUUID)))

(def hq-config {"host" (:host (:non-clustered (:hornetq config)))
                "port" (:port (:non-clustered (:hornetq config)))})

(def coord-opts
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
   :onyx.coordinator/revoke-delay 5000})

(def peer-opts
  {:hornetq/mode :udp
   :hornetq.udp/cluster-name (:cluster-name (:hornetq config))
   :hornetq.udp/group-address (:group-address (:hornetq config))
   :hornetq.udp/group-port (:group-port (:hornetq config))
   :hornetq.udp/refresh-timeout (:refresh-timeout (:hornetq config))
   :hornetq.udp/discovery-timeout (:discovery-timeout (:hornetq config))
   :zookeeper/address (:address (:zookeeper config))
   :onyx/id id})

(def conn (onyx.api/connect :memory coord-opts))

(doseq [in-queue in-queues]
  (hq-util/create-queue! hq-config in-queue))

(hq-util/create-queue! hq-config out-queue)

(def messages
  (->> k-inputs
       (inc)
       (range)
       (map (fn [k] (* k (/ n-messages k-inputs))))
       (partition 2 1)
       (map (partial apply range))
       (map (fn [r] (map (fn [x] {:n x}) r)))))

(doseq [[q b] (map (fn [q b] [q b]) in-queues messages)]
  (hq-util/write-and-cap! hq-config q b echo))

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(def input-entries
  (map
   (fn [k]
     {:onyx/name (keyword (str "in-" k))
      :onyx/ident :hornetq/read-segments
      :onyx/type :input
      :onyx/medium :hornetq
      :onyx/consumption :concurrent
      :hornetq/queue-name (nth in-queues (dec k))
      :hornetq/host (:host (:non-clustered (:hornetq config)))
      :hornetq/port (:port (:non-clustered (:hornetq config)))
      :onyx/batch-size batch-size})
   (range 1 (inc k-inputs))))

(def catalog
  (concat
   input-entries
   [{:onyx/name :inc
     :onyx/fn :onyx.peer.multi-input-test/my-inc
     :onyx/type :transformer
     :onyx/consumption :concurrent
     :onyx/batch-size batch-size}

    {:onyx/name :out
     :onyx/ident :hornetq/write-segments
     :onyx/type :output
     :onyx/medium :hornetq
     :onyx/consumption :concurrent
     :hornetq/queue-name out-queue
     :hornetq/host (:host (:non-clustered (:hornetq config)))
     :hornetq/port (:port (:non-clustered (:hornetq config)))
     :onyx/batch-size batch-size}]))

(def workflow
  (concat
   [[:inc :out]]
   (map (fn [a] [(keyword (str "in-" a)) :inc])
        (range 1 (inc k-inputs)))))

(def v-peers (onyx.api/start-peers conn 1 peer-opts))

(onyx.api/submit-job conn {:catalog catalog :workflow workflow})

(def results (hq-util/consume-queue! hq-config out-queue echo))

(try
  ;; (dorun (map deref (map :runner v-peers)))
  (finally
   (doseq [v-peer v-peers]
     (try
       ((:shutdown-fn v-peer))
       (catch Exception e (prn e))))
   (try
     (onyx.api/shutdown conn)
     (catch Exception e (prn e)))))

(let [expected (set (map (fn [x] {:n (inc x)}) (range n-messages)))]
  (fact (set (butlast results)) => expected)
  (fact (last results) => :done))

