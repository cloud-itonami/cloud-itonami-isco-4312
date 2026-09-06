(ns statclerk.ledger-test
  "The ledger's job is to say what authorised each write. These tests
  exercise both directions: the entries it must build, and the ones it
  must refuse to build."
  (:require [clojure.test :refer [deftest is testing]]
            [statclerk.actor :as actor]
            [statclerk.advisor :as advisor]
            [statclerk.ledger :as ledger]
            [statclerk.store :as store]))

(def ^:private a-record
  {:client-id "client-1" :op :reconcile-batch :batch-id "B-1" :payload {}})

(defn- refused
  "The fault `entry` reports for `m`, or nil if it accepted it."
  [m]
  (try (ledger/entry m) nil
       (catch clojure.lang.ExceptionInfo e (:fault (ex-data e)))))

;; ── the entries it must build ──────────────────────────────────────

(deftest builds-a-governor-cleared-commit
  (let [e (ledger/entry {:disposition :commit :authorisation :governor-clear
                         :record a-record})]
    (is (= :governor-clear (ledger/authorisation-of e)))
    (is (not (ledger/human-signed? e)))))

(deftest builds-a-human-signed-commit
  (let [e (ledger/entry {:disposition :commit :authorisation :human-sign-off
                         :record a-record})]
    (is (ledger/human-signed? e))))

(deftest a-hold-keeps-only-the-verdict-summary
  (testing "the refusal reason is kept; the whole verdict map is not"
    (let [e (ledger/entry {:disposition :hold :authorisation :governor-hold
                           :verdict {:ok? false :hard? true :escalate? false
                                     :confidence 0.9 :violations [{:rule :empty-batch}]
                                     :internal :not-audit-material}})]
      (is (= [{:rule :empty-batch}] (get-in e [:verdict :violations])))
      (is (not (contains? (:verdict e) :internal))))))

;; ── the entries it must refuse ─────────────────────────────────────

(deftest refuses-an-entry-that-does-not-say-what-authorised-it
  (testing "the measured defect: entries carried :disposition and :record only"
    (is (some? (refused {:disposition :commit :record a-record})))))

(deftest refuses-an-unknown-authorisation
  (is (some? (refused {:disposition :commit :authorisation :seemed-fine
                       :record a-record}))))

(deftest refuses-an-unknown-disposition
  (is (some? (refused {:disposition :maybe :authorisation :governor-clear
                       :record a-record}))))

(deftest refuses-a-commit-authorised-by-a-hold
  (is (some? (refused {:disposition :commit :authorisation :governor-hold
                       :record a-record}))))

(deftest refuses-a-hold-authorised-by-anything-but-a-hold
  (is (some? (refused {:disposition :hold :authorisation :human-sign-off
                       :verdict {:hard? true}}))))

(deftest refuses-a-commit-with-nothing-committed
  (is (some? (refused {:disposition :commit :authorisation :governor-clear}))))

(deftest refuses-a-hold-with-no-reason
  (is (some? (refused {:disposition :hold :authorisation :governor-hold}))))

(deftest refuses-an-adjustment-that-claims-the-governor-cleared-it
  (testing ":post-adjustment moves money and always escalates, so this
            entry reports a weakened escalation rule, not a typo"
    (is (some? (refused {:disposition :commit :authorisation :governor-clear
                         :record (assoc a-record :op :post-adjustment)}))))
  (testing "the same adjustment is fine once a human has signed it"
    (is (nil? (refused {:disposition :commit :authorisation :human-sign-off
                        :record (assoc a-record :op :post-adjustment)})))))

;; ── an entry the ledger cannot speak for ───────────────────────────

(deftest an-older-entry-answers-nil-not-governor-clear
  (testing "entries written before this namespace existed are unknown,
            which is not the same as automatic"
    (let [old {:disposition :commit :record a-record}]
      (is (nil? (ledger/authorisation-of old)))
      (is (not (ledger/human-signed? old))))))

;; ── end to end: the two paths the ledger could not tell apart ──────

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-batch! st {:batch-id "B-1" :client-id "client-1"
                               :header-total 350
                               :line-items [{:line-id "L-1" :amount 100}
                                            {:line-id "L-2" :amount 250}]})
    st))

(def ^:private unsure-advisor
  "An advisor below `governor/confidence-floor`, so the governor
  escalates and a human has to resume the thread."
  (reify advisor/Advisor
    (-advise [_ _store request]
      {:op (:op request) :effect :propose :batch-id (:batch-id request)
       :stake :high :confidence 0.3 :rationale "not sure"})))

(deftest separates-two-reconciles-that-used-to-look-identical
  (testing "measured on 3c3f9d8: an automatic :reconcile-batch and one a
            human signed off differed only in the advisor's own
            :confidence — a claim, not an authorisation"
    (let [auto-store (fresh-store)
          signed-store (fresh-store)
          request {:client-id "client-1" :op :reconcile-batch :stake :low
                   :batch-id "B-1"}]
      (actor/run-request! (actor/build-graph {:store auto-store}) request {} "auto")
      (let [g (actor/build-graph {:store signed-store :advisor unsure-advisor})]
        (is (= :interrupted (:status (actor/run-request! g request {} "signed"))))
        (actor/approve! g "signed"))
      (let [auto-entry (first (store/ledger auto-store))
            signed-entry (first (store/ledger signed-store))]
        (is (= :reconcile-batch (get-in auto-entry [:record :op])))
        (is (= :reconcile-batch (get-in signed-entry [:record :op]))
            "same op — the ledger cannot lean on the op name")
        (is (not (ledger/human-signed? auto-entry)))
        (is (ledger/human-signed? signed-entry))))))

(deftest a-held-run-records-the-governors-refusal
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-batch! st {:batch-id "B-1" :client-id "client-1"
                               :header-total 999
                               :line-items [{:line-id "L-1" :amount 100}]})
    (actor/run-request! (actor/build-graph {:store st})
                        {:client-id "client-1" :op :reconcile-batch :stake :low
                         :batch-id "B-1"} {} "held")
    (let [e (first (store/ledger st))]
      (is (= :governor-hold (ledger/authorisation-of e)))
      (is (some #(= :aggregation-mismatch (:rule %)) (get-in e [:verdict :violations]))))))
