(ns onyx.coordinator.single-peer-test
  (:require [midje.sweet :refer :all]
            [clojure.core.async :refer [chan tap >!! <!!]]
            [com.stuartsierra.component :as component]
            [zookeeper :as zk]
            [onyx.extensions :as extensions]
            [onyx.coordinator.async :as async]
            [onyx.sync.zookeeper :as onyx-zk]
            [onyx.coordinator.impl :as impl]
            [onyx.coordinator.sim-test-utils :refer [with-system]]))

(facts
 "new peer"
 (with-system
   (fn [coordinator sync]
     (let [peer (extensions/create sync :peer)
           pulse (extensions/create sync :pulse)
           shutdown (extensions/create sync :shutdown)
           offer-ch-spy (chan 1)
           failure-ch-spy (chan 1)]

       (extensions/create sync :born-log (:node peer))
       (extensions/write-node sync (:node peer)
                               {:id (:uuid peer)
                                :peer-node (:node peer)
                                :pulse-node (:node pulse)
                                :shutdown-node (:node shutdown)})
             
       (tap (:offer-mult coordinator) offer-ch-spy)
       (tap (:failure-mult coordinator) failure-ch-spy)
             
       (>!! (:born-peer-ch-head coordinator) true)
       (<!! offer-ch-spy)

       (facts "There is one peer"
              (let [peers (zk/children (:conn sync) (onyx-zk/peer-path (:onyx/id (:opts sync))))
                    peer-path (str (onyx-zk/peer-path (:onyx/id (:opts sync))) "/" (first peers))]
                (fact (count peers) => 1)
                (fact (:id (extensions/read-node sync peer-path)) => (:uuid peer))))))))

(facts
 "peer joins and dies"
 (with-system
   (fn [coordinator sync]
     (let [peer (extensions/create sync :peer)
           pulse (extensions/create sync :pulse)
           shutdown (extensions/create sync :shutdown)
           offer-ch-spy (chan 1)
           evict-ch-spy (chan 1)
           shutdown-ch-spy (chan 1)
           failure-ch-spy (chan 1)]

       (extensions/create sync :born-log (:node peer))
       (extensions/write-node sync (:node peer)
                               {:id (:uuid peer)
                                :peer-node (:node peer)
                                :pulse-node (:node pulse)
                                :shutdown-node (:node shutdown)})
             
       (tap (:offer-mult coordinator) offer-ch-spy)
       (tap (:evict-mult coordinator) evict-ch-spy)
       (tap (:shutdown-mult coordinator) shutdown-ch-spy)
       (tap (:failure-mult coordinator) failure-ch-spy)
             
       (>!! (:born-peer-ch-head coordinator) true)
       (<!! offer-ch-spy)
       (extensions/delete sync (:node pulse))
       (<!! evict-ch-spy)
       (<!! shutdown-ch-spy)

       (fact "There are no peers"
             (extensions/node-exists? sync (:node pulse)) => false)

       (fact "The only peer is marked as dead"
             (let [peers (zk/children (:conn sync) (onyx-zk/peer-path (:onyx/id (:opts sync))))
                   peer-path (str (onyx-zk/peer-path (:onyx/id (:opts sync))) "/" (first peers))
                   peer-id (:id (extensions/read-node sync peer-path))
                   state-path (extensions/resolve-node sync :peer-state peer-id)
                   state (:content (extensions/dereference sync state-path))]
               (:state state) => :dead))))))

(facts
 "planning one job with no peers"
 (with-system
   (fn [coordinator sync]
     (let [catalog [{:onyx/name :in
                     :onyx/type :input
                     :onyx/medium :hornetq
                     :onyx/consumption :sequential
                     :hornetq/queue-name "in-queue"}
                    {:onyx/name :inc
                     :onyx/type :transformer
                     :onyx/consumption :sequential}
                    {:onyx/name :out
                     :onyx/type :output
                     :onyx/medium :hornetq
                     :onyx/consumption :sequential
                     :hornetq/queue-name "out-queue"}]
           workflow [[:in :inc] [:inc :out]]
           offer-ch-spy (chan 1)
           job-ch (chan 1)
           node (extensions/create sync :plan)
           cb #(>!! job-ch (extensions/read-node sync (:path %)))]

       (tap (:offer-mult coordinator) offer-ch-spy)

       (extensions/on-change sync (:node node) cb)
       (extensions/create sync :planning-log
                          {:job {:workflow workflow :catalog catalog}
                           :node (:node node)})
       (>!! (:planning-ch-head coordinator) true)
       
       (<!! offer-ch-spy)

       (let [job-id (<!! job-ch)
             job-node (extensions/resolve-node sync :job job-id)]

         (facts "There is one job"
                (let [jobs (extensions/bucket sync :job)]
                  (fact (count jobs) => 1)))

         (facts "There are three tasks"
                (let [task-nodes (extensions/bucket-at sync :task job-node)
                      tasks (map #(extensions/read-node sync %) task-nodes)]
                  (fact (count task-nodes) => 3)
                  (fact (into #{} (map :task/name tasks)) => #{:in :inc :out})

                  (facts ":in has an ingress queue"
                         (let [task (first (filter #(= (:task/name %) :in) tasks))
                               in-queues (:task/ingress-queues task)]
                           (fact in-queues =not=> nil?)))

                  (facts ":inc's ingress queue is :in's egress queues"
                         (let [in (first (filter #(= (:task/name %) :in) tasks))
                               inc (first (filter #(= (:task/name %) :inc) tasks))]
                           (fact (:in (:task/ingress-queues inc)) =>
                                 (first (:task/egress-queues in)))))

                  (facts ":out's ingess queue is :inc's egress queue"
                         (let [inc (first (filter #(= (:task/name %) :inc) tasks))
                               out (first (filter #(= (:task/name %) :out) tasks))]
                           (fact (:inc (:task/ingress-queues out)) =>
                                 (first (:task/egress-queues inc)))))

                  (facts ":out's egress queue is generated"
                         (let [task (first (filter #(= (:task/name %) :out) tasks))
                               out-queues (:task/egress-queues task)]
                           (fact out-queues =not=> empty?))))))))))

(defn test-task-life-cycle
  [{:keys [id sync sync-spy ack-ch-spy seal-ch-spy completion-ch-spy offer-ch-spy
           status-spy seal-node-spy peer-node payload-node next-payload-node task-name
           pulse-node shutdown-node]}]
  (facts "The payload node is populated"
         (let [event (<!! sync-spy)]
           (fact (:path event) => payload-node)))

  (let [task (:task (extensions/read-node sync payload-node))
        state-path (extensions/resolve-node sync :peer-state (:id (extensions/read-node sync peer-node)))
        state (:content (extensions/dereference sync state-path))]

    (fact "It receives the task"
          (extensions/read-node sync (:task-node state)) => task)

    (fact "The peer's state is :acking the task"
          (:state state) => :acking)

    (fact "The payload node contains the other node paths"
          (fact (into #{} (keys (:nodes state)))
                => #{:node/payload :node/ack :node/completion
                     :node/status :node/catalog :node/workflow :node/task
                     :node/peer :node/exhaust :node/seal :node/cooldown}))

    (extensions/on-change sync (:node/status (:nodes state)) #(>!! status-spy %))
    (extensions/on-change sync (:node/seal (:nodes state)) #(>!! seal-node-spy %))

    (facts "Touching the ack node triggers the callback"
           (extensions/touch-node sync (:node/ack (:nodes state)))
           (let [event (<!! ack-ch-spy)]
             (fact (:path event) => (:node/ack (:nodes state))))))

  (extensions/write-node sync peer-node {:id id
                                          :peer-node peer-node
                                          :pulse-node pulse-node
                                          :shutdown-node shutdown-node
                                          :payload-node next-payload-node})
  
  (extensions/on-change sync next-payload-node #(>!! sync-spy %))

  (<!! status-spy)

  (facts "Touching the exhaustion node triggers the callback"
         (let [nodes (:nodes (extensions/read-node sync payload-node))]
           (extensions/touch-node sync (:node/exhaust nodes))
           (let [event (<!! seal-ch-spy)]
             (fact (:seal? event) => true)
             (fact (:seal-node event) => (:node/seal nodes)))))

  (<!! seal-node-spy)

  (facts "The resource should be sealed"
         (let [nodes (:nodes (extensions/read-node sync payload-node))]
           (fact (:seal? (extensions/read-node sync (:node/seal nodes))) => true)))

  (facts "Touching the completion node triggers the callback"
         (let [nodes (:nodes (extensions/read-node sync payload-node))]
           (extensions/touch-node sync (:node/completion nodes))
           (let [event (<!! completion-ch-spy)]
             (fact (:path event) => (:node/completion nodes)))))

  (<!! offer-ch-spy))

(facts
 "planning one job with one peer"
 (with-system
   (fn [coordinator sync]
     (let [peer (extensions/create sync :peer)
           pulse (extensions/create sync :pulse)
           shutdown (extensions/create sync :shutdown)

           in-payload (extensions/create sync :payload)
           inc-payload (extensions/create sync :payload)
           out-payload (extensions/create sync :payload)
           future-payload (extensions/create sync :payload)
                 
           sync-spy (chan 1)
           ack-ch-spy (chan 1)
           status-spy (chan 1)
           offer-ch-spy (chan 1)
           seal-ch-spy (chan 1)
           seal-node-spy (chan 1)
           completion-ch-spy (chan 1)
                 
           catalog [{:onyx/name :in
                     :onyx/type :input
                     :onyx/medium :hornetq
                     :onyx/consumption :sequential
                     :hornetq/queue-name "in-queue"}
                    {:onyx/name :inc
                     :onyx/type :transformer
                     :onyx/consumption :sequential}
                    {:onyx/name :out
                     :onyx/type :output
                     :onyx/medium :hornetq
                     :onyx/consumption :sequential
                     :hornetq/queue-name "out-queue"}]
           workflow [[:in :inc] [:inc :out]]]

       (tap (:ack-mult coordinator) ack-ch-spy)
       (tap (:offer-mult coordinator) offer-ch-spy)
       (tap (:seal-mult coordinator) seal-ch-spy)
       (tap (:completion-mult coordinator) completion-ch-spy)

       (extensions/create sync :born-log (:node peer))

       (extensions/create
        sync :planning-log
        {:job {:workflow workflow :catalog catalog}
         :node (:node (extensions/create sync :plan))})
       
       (extensions/write-node sync (:node peer)
                               {:id (:uuid peer)
                                :peer-node (:node peer)
                                :pulse-node (:node pulse)
                                :shutdown-node (:node shutdown)
                                :payload-node (:node in-payload)})

       (extensions/on-change sync (:node in-payload) #(>!! sync-spy %))

       (>!! (:born-peer-ch-head coordinator) true)
       (>!! (:planning-ch-head coordinator) true)

       (<!! offer-ch-spy)
       (<!! offer-ch-spy)

       (let [base-cycle {:id (:uuid peer)
                         :sync sync
                         :sync-spy sync-spy
                         :ack-ch-spy ack-ch-spy
                         :offer-ch-spy offer-ch-spy
                         :status-spy status-spy
                         :seal-ch-spy seal-ch-spy
                         :seal-node-spy seal-node-spy
                         :completion-ch-spy completion-ch-spy
                         :peer-node (:node peer)
                         :pulse-node (:node pulse)
                         :shutdown-node (:node shutdown)}]
         
         (test-task-life-cycle
          (assoc base-cycle
            :task-name :in
            :payload-node (:node in-payload)
            :next-payload-node (:node inc-payload)))

         (test-task-life-cycle
          (assoc base-cycle
            :task-name :inc
            :payload-node (:node inc-payload)
            :next-payload-node (:node out-payload)))

         (test-task-life-cycle
          (assoc base-cycle
            :task-name :out
            :payload-node (:node out-payload)
            :next-payload-node (:node future-payload))))))
   {:onyx.coordinator/revoke-delay 500000}))

(facts
 "evicting one peer"
 (with-system
   (fn [coordinator sync]
     (let [catalog [{:onyx/name :in
                     :onyx/consumption :sequential
                     :onyx/type :input
                     :onyx/medium :hornetq
                     :hornetq/queue-name "in-queue"}
                    {:onyx/name :inc
                     :onyx/type :transformer
                     :onyx/consumption :sequential}
                    {:onyx/name :out
                     :onyx/consumption :sequential
                     :onyx/type :output
                     :onyx/medium :hornetq
                     :hornetq/queue-name "out-queue"}]
           workflow [[:in :inc] [:inc :out]]

           peer (extensions/create sync :peer)
           pulse (extensions/create sync :pulse)
           shutdown (extensions/create sync :shutdown)
           payload (extensions/create sync :payload)
                 
           sync-spy (chan 1)
           offer-ch-spy (chan 3)]
             
       (tap (:offer-mult coordinator) offer-ch-spy)

       (extensions/create sync :born-log (:node peer))
       (extensions/write-node sync (:node peer) {:id (:uuid peer)
                                                  :peer-node (:node peer)
                                                  :pulse-node (:node pulse)
                                                  :payload-node (:node payload)
                                                  :shutdown-node (:node shutdown)})
       
       (extensions/on-change sync (:node payload) #(>!! sync-spy %))

       (>!! (:born-peer-ch-head coordinator) true)
       (<!! offer-ch-spy)

       (extensions/create sync :planning-log
                          {:job {:workflow workflow :catalog catalog}
                           :node (:node (extensions/create sync :plan))})
       
       (>!! (:planning-ch-head coordinator) true)
       (<!! offer-ch-spy)
       (<!! sync-spy)

       ;; Instant revoke.
       (<!! offer-ch-spy)

       (facts "The peer gets marked as :revoked after eviction"
              (let [peers (zk/children (:conn sync) (onyx-zk/peer-state-path (:onyx/id (:opts sync))))
                    path (extensions/resolve-node sync :peer-state (first peers))
                    state (:content (extensions/dereference sync path))]
                (fact (count peers) => 1)
                (fact (:state state) => :revoked)

                (facts "The status node gets deleted on sync storage"
                       (let [status-node (:node/status (:nodes state))]
                         (fact (extensions/read-node sync status-node) => (throws Exception))))))))
   {:onyx.coordinator/revoke-delay 0}))

(facts
 "Idempotency cases"
 (with-system
   (fn [coordinator sync]
     (let [peer (extensions/create sync :peer)
           pulse (extensions/create sync :pulse)
           shutdown (extensions/create sync :shutdown)
           payload (extensions/create sync :payload)
           offer-ch-spy (chan 5)
           ack-ch-spy (chan 5)
           evict-ch-spy (chan 5)
           completion-ch-spy (chan 5)
           failure-ch-spy (chan 10)]

       (extensions/create sync :born-log (:node peer))
       (extensions/write-node sync (:node peer)
                              {:id (:uuid peer)
                               :peer-node (:node peer)
                               :pulse-node (:node pulse)
                               :payload-node (:node payload)
                               :shutdown-node (:node shutdown)})
             
       (tap (:offer-mult coordinator) offer-ch-spy)
       (tap (:ack-mult coordinator) ack-ch-spy)
       (tap (:evict-mult coordinator) evict-ch-spy)
       (tap (:completion-mult coordinator) completion-ch-spy)
       (tap (:failure-mult coordinator) failure-ch-spy)

       (>!! (:born-peer-ch-head coordinator) true)
       (<!! offer-ch-spy)

       (facts "Adding a duplicate peer fails"
              (extensions/create sync :born-log (:node peer))
              (>!! (:born-peer-ch-head coordinator) true)
              (let [failure (<!! failure-ch-spy)]
                (fact (:ch failure) => :serial-fn)))

       (facts "Acking a non-existent node fails"
              (>!! (:ack-ch-head coordinator) {:path (str (java.util.UUID/randomUUID))})
              (let [failure (<!! failure-ch-spy)]
                (fact (:ch failure) => :ack)))

       (facts "Completing a task that doesn't exist fails"
              (>!! (:completion-ch-head coordinator) {:path "dead path"})
              (let [failure (<!! failure-ch-spy)]
                (fact (:ch failure) => :serial-fn)))))
   {:onyx.coordinator/revoke-delay 50000}))

(facts
 "Acking error cases"
 (with-system
   (fn [coordinator sync]
     (let [peer (extensions/create sync :peer)
           pulse (extensions/create sync :pulse)
           shutdown (extensions/create sync :shutdown)
           payload (extensions/create sync :payload)
           job (extensions/create sync :plan)
           offer-ch-spy (chan 5)
           sync-spy (chan 1)
           ack-ch-spy (chan 5)
           evict-ch-spy (chan 5)
           completion-ch-spy (chan 5)
           failure-ch-spy (chan 10)
           job-ch (chan 1)

           catalog [{:onyx/name :in
                     :onyx/type :input
                     :onyx/medium :hornetq
                     :onyx/consumption :sequential
                     :hornetq/queue-name "in-queue"}
                    {:onyx/name :inc
                     :onyx/type :transformer
                     :onyx/consumption :sequential}
                    {:onyx/name :out
                     :onyx/type :output
                     :onyx/medium :hornetq
                     :onyx/consumption :sequential
                     :hornetq/queue-name "out-queue"}]
           workflow [[:in :inc] [:inc :out]]]

       (extensions/create sync :born-log (:node peer))
       (extensions/write-node sync (:node peer)
                              {:id (:uuid peer)
                               :peer-node (:node peer)
                               :pulse-node (:node pulse)
                               :payload-node (:node payload)
                               :shutdown-node (:node shutdown)})
             
       (tap (:offer-mult coordinator) offer-ch-spy)
       (tap (:ack-mult coordinator) ack-ch-spy)
       (tap (:completion-mult coordinator) completion-ch-spy)
       (tap (:failure-mult coordinator) failure-ch-spy)

       (extensions/on-change sync (:node job) #(>!! job-ch %))
       (extensions/create sync :planning-log
                          {:job {:workflow workflow :catalog catalog}
                           :node (:node job)})

       (>!! (:planning-ch-head coordinator) true)
       (<!! offer-ch-spy)

       (extensions/on-change sync (:node payload) #(>!! sync-spy %))
       (>!! (:born-peer-ch-head coordinator) true)
       
       (<!! offer-ch-spy)
       (<!! sync-spy)

       ;;; Complete all the tasks.
       (let [job-node (extensions/read-node sync (:path (<!! job-ch)))
             task-path (onyx-zk/task-path (:onyx/id (:opts sync)) job-node)]
         (doseq [child (extensions/children sync task-path)]
           (impl/complete-task sync child)))

       (facts "Acking a completed task fails"
              (let [ack-node (:node/ack (:nodes (extensions/read-node sync (:node payload))))]
                (>!! (:ack-ch-head coordinator) {:path ack-node})
                (let [failure (<!! failure-ch-spy)]
                  (fact (:ch failure) => :serial-fn))))

       (facts
        "Acking with a peer who's state isnt :acking fails"
        (let [state-path (extensions/resolve-node sync :peer-state (:uuid peer))
              state (:content (extensions/dereference sync state-path))]
          (extensions/create-at sync :peer-state (:id state) (assoc state :state :idle)))

        (let [ack-node (:node/ack (:nodes (extensions/read-node sync (:node payload))))]
          (>!! (:ack-ch-head coordinator) {:path ack-node})
          (let [failure (<!! failure-ch-spy)]
            (fact (:ch failure) => :serial-fn)))))
   {:onyx.coordinator/revoke-delay 50000})))

