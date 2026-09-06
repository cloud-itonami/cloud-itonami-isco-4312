(ns statclerk.ledger
  "Audit entries for the ISCO-08 4312 community statistical, finance &
  insurance clerks actor.

  Measured on 3c3f9d8, before this namespace existed. Four runs through
  `statclerk.actor` — an automatic `:reconcile-batch`, a
  `:post-adjustment` that interrupted at `:request-approval` and was
  resumed through `actor/approve!`, a low-confidence `:reconcile-batch`
  that took the same interrupt-and-resume path, and a governor refusal
  — wrote these:

    {:disposition :commit :record {... :op :reconcile-batch ...}}   automatic
    {:disposition :commit :record {... :op :post-adjustment ...}}   human-signed
    {:disposition :commit :record {... :op :reconcile-batch ...}}   human-signed
    {:disposition :hold}

  Keys `(:disposition :record)` on every commit. The README says
  `:post-adjustment` — a write-off or correction with financial effect
  — ALWAYS requires human sign-off, and the ledger could not show that
  any given write had received it.

  The op name does not rescue it. Rows 1 and 3 are both
  `:reconcile-batch`; one was cleared by the governor alone and one was
  signed off by a human after a low-confidence escalation, and the
  entries differ only in the `:confidence` the advisor reported about
  itself. That is the advisor's own claim, not evidence that a human
  resumed the thread. An audit trail whose entries do not record what
  authorised the write cannot answer the one question it is kept for.

  So an entry names its `:authorisation` and this namespace refuses to
  build one without it:

    :governor-clear  the governor returned :ok? true; no human involved.
    :human-sign-off  the run interrupted at :request-approval and a
                     human resumed the thread. The act of resuming IS
                     the approval, so it is recorded as one.
    :governor-hold   the governor refused; nothing was committed.

  One rule here is specific to this actor rather than to the pattern.
  `:post-adjustment` moves money, so `statclerk.governor` escalates it
  unconditionally — it cannot reach `:commit` except through a human.
  An entry claiming a `:governor-clear` posted an adjustment is
  therefore not a mis-labelled row but a report that the escalation
  rule has been weakened, and it is refused as such.

  `entry` is total and pure: it either returns a well-formed entry or
  throws, and it never reaches a store. Building an entry is not
  appending one.")

(def authorisations #{:governor-clear :human-sign-off :governor-hold})

(def ^{:doc "Operations that carry financial effect and can never be
  committed on the governor's word alone (README; enforced in
  `statclerk.governor` by `risky-op?`)."}
  human-only-ops
  #{:post-adjustment})

(defn- fault
  "Why this entry is ill-formed, as a sentence — or nil if it is not."
  [{:keys [disposition authorisation record verdict]}]
  (cond
    (not (#{:commit :hold} disposition))
    (str ":disposition は :commit か :hold（受領: " (pr-str disposition) "）")

    (not (authorisations authorisation))
    (str ":authorisation が無い、または未知（受領: " (pr-str authorisation)
         "、既知: " (pr-str (sort authorisations)) "）"
         " — 台帳の項目は、その書き込みを何が許可したかを名乗らなければならない")

    (and (= :commit disposition) (= :governor-hold authorisation))
    ":commit を :governor-hold が許可することはない"

    (and (= :hold disposition) (not= :governor-hold authorisation))
    (str ":hold の :authorisation は :governor-hold のみ（受領: "
         (pr-str authorisation) "）")

    (and (= :commit disposition) (nil? record))
    ":commit には :record が要る"

    (and (= :commit disposition)
         (= :governor-clear authorisation)
         (human-only-ops (:op record)))
    (str (pr-str (:op record))
         " は金銭的影響を持つので governor 単独では commit できない"
         "（README / governor の risky-op?）—"
         " :governor-clear を名乗る項目は、台帳の誤記ではなく"
         " escalation 規則が緩んだという報告である")

    (and (= :hold disposition) (nil? verdict))
    ":hold には拒否理由としての :verdict が要る"))

(defn entry
  "Build one audit entry. Throws on anything ill-formed — a ledger that
  accepts an entry it cannot interpret is worse than one that refuses,
  because the refusal is visible and the bad entry is not."
  [{:keys [disposition authorisation record verdict] :as m}]
  (when-let [f (fault m)]
    (throw (ex-info (str "ill-formed ledger entry: " f) {:entry m :fault f})))
  (cond-> {:disposition   disposition
           :authorisation authorisation}
    record  (assoc :record record)
    verdict (assoc :verdict (select-keys verdict
                                         [:ok? :hard? :escalate? :confidence :violations]))))

(defn human-signed?
  "Did a human sign this entry off? The question the ledger exists to
  answer, asked of one entry."
  [e]
  (= :human-sign-off (:authorisation e)))

(defn authorisation-of
  "What authorised this write? nil for entries written before this
  namespace existed — which is the honest answer for them, and is
  deliberately not conflated with :governor-clear."
  [e]
  (:authorisation e))
