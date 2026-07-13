(ns statclerk.store
  "SSoT for the ISCO-08 4312 community statistical, finance & insurance
  clerks actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md
  Actors section). Modeled on cloud-itonami-isco-4311's
  bookkeeping.store.

  Domain:

    client — a registered organization (:client-id, :name)
    batch  — a registered document batch {:batch-id :client-id
             :header-total number :line-items [{:line-id amount}]}.
             The line items are the SSoT; the header total is a
             CLAIM about their sum — either it agrees with the
             arithmetic sum, or it does not.
    record — a committed operating record (reconciled batch) —
             written ONLY via commit-record!.
    ledger — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (batch [s batch-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-batch! [s b])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (batch [_ batch-id] (get-in @a [:batches batch-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-batch! [s b]
    (swap! a assoc-in [:batches (:batch-id b)] b) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :batches {} :records [] :ledger []}
                                   seed)))))
