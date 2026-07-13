(ns statclerk.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [statclerk.actor :as actor]
            [statclerk.store :as store]))

(defn- fresh-store [header]
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-batch! st {:batch-id "B-1" :client-id "client-1"
                               :header-total header
                               :line-items [{:line-id "L-1" :amount 100}
                                            {:line-id "L-2" :amount 250}]})
    st))

(deftest commits-a-reconciling-batch
  (let [st (fresh-store 350)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :reconcile-batch :stake :low
                 :batch-id "B-1"}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-a-mismatched-batch
  (let [st (fresh-store 999)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :reconcile-batch :stake :low
                 :batch-id "B-1"}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-adjusts-on-human-approval
  (let [st (fresh-store 350)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :post-adjustment :stake :high
                 :batch-id "B-1"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
