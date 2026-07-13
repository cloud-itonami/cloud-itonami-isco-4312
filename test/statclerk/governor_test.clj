(ns statclerk.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [statclerk.store :as store]
            [statclerk.governor :as governor]))

(defn- fresh-store [header total-matches?]
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-batch! st {:batch-id "B-1" :client-id "client-1"
                               :header-total header
                               :line-items [{:line-id "L-1" :amount 100}
                                            {:line-id "L-2" :amount 250}]})
    st))

(defn- reconcile []
  {:op :reconcile-batch :effect :propose :batch-id "B-1"
   :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-when-totals-agree
  (let [st (fresh-store 350 true)
        v (governor/check req {} (reconcile) st)]
    (is (:ok? v))))

(deftest hard-on-aggregation-mismatch
  (testing "aggregation identity is arithmetic, not opinion"
    (let [st (fresh-store 351 false)
          v (governor/check req {} (assoc (reconcile) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :aggregation-mismatch (:rule %)) (:violations v))))))

(deftest hard-on-empty-batch
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-batch! st {:batch-id "B-1" :client-id "client-1"
                               :header-total 0 :line-items []})
    (let [v (governor/check req {} (reconcile) st)]
      (is (:hard? v))
      (is (some #(= :empty-batch (:rule %)) (:violations v))))))

(deftest hard-on-unknown-batch
  (let [st (fresh-store 350 true)
        v (governor/check req {} (assoc (reconcile) :batch-id "B-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-batch (:rule %)) (:violations v)))))

(deftest hard-on-foreign-batch
  (let [st (fresh-store 350 true)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (reconcile) st)]
      (is (:hard? v))
      (is (some #(= :batch-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-missing-batch-id
  (let [st (fresh-store 350 true)
        v (governor/check req {} {:op :reconcile-batch :effect :propose
                                  :confidence 0.9 :stake :low} st)]
    (is (:hard? v))
    (is (some #(= :no-batch (:rule %)) (:violations v)))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store 350 true)
        v (governor/check {:client-id "nobody"} {} (reconcile) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store 350 true)
        v (governor/check req {} (assoc (reconcile) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-adjustment-posting
  (let [st (fresh-store 350 true)
        v (governor/check req {} {:op :post-adjustment :effect :propose
                                  :batch-id "B-1" :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store 350 true)
        v (governor/check req {} (assoc (reconcile) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
