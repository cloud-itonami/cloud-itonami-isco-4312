(ns statclerk.governor
  "StatFinanceInsuranceClerksGovernor — the independent safety/
  traceability layer for the ISCO-08 4312 community statistical,
  finance & insurance clerks actor (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor. Clerical twist: a
  batch's header total is a CLAIM about the sum of its registered line
  items — reconciliation is arithmetic equality, not review.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. batch basis       — a reconciliation must cite a REGISTERED
                           batch belonging to this client.
    4. aggregation identity — the batch's line items must sum EXACTLY
                           to its registered header total (line-item
                           sum is the ground truth; a header total
                           that disagrees is a mistake, not a
                           rounding matter of opinion).
    5. no-empty-batch     — a batch with zero line items cannot be
                           reconciled (nothing to sum).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :post-adjustment (write-off / correction with financial
                           effect).
    7. low confidence (< `confidence-floor`)."
  (:require [statclerk.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record b]
  (let [{:keys [op batch-id]} proposal
        reconcile? (= :reconcile-batch op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and reconcile? (nil? batch-id))
      (conj {:rule :no-batch :detail "reconcile は batch の引用が必須"})

      (and reconcile? batch-id (nil? b))
      (conj {:rule :unknown-batch :detail (str "未登録 batch: " batch-id)})

      (and reconcile? b (not= (:client-id b) (:client-id request)))
      (conj {:rule :batch-wrong-client :detail "batch が別 client のもの"})

      (and reconcile? b (empty? (:line-items b)))
      (conj {:rule :empty-batch :detail "明細ゼロの batch は照合不能"})

      (and reconcile? b (seq (:line-items b))
           (not= (:header-total b) (reduce + (map :amount (:line-items b)))))
      (conj {:rule :aggregation-mismatch
             :detail (str "明細合計 " (reduce + (map :amount (:line-items b)))
                          " != header 合計 " (:header-total b)
                          "（集計恒等式は算術であって意見ではない）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `statclerk.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        b (some->> (:batch-id proposal) (store/batch store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record b)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :post-adjustment (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
