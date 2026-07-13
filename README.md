# cloud-itonami-isco-4312

Open Business Blueprint for **ISCO-08 4312**: Statistical, Finance and Insurance Clerks — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:implemented`** — StatFinanceInsuranceClerksAdvisor ⊣
StatFinanceInsuranceClerksGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
13 tests / 28 assertions green.

The clerical HARD invariant — aggregation identity, checked
deterministically:

1. **Aggregation identity** — a batch's line items must sum EXACTLY to
   its registered header total. The line-item sum is ground truth; a
   header total that disagrees is a mistake, not a rounding matter of
   opinion.
2. **No empty batch** — a batch with zero line items cannot be
   reconciled (nothing to sum).

Also HARD: invented/foreign batches, unregistered organization,
non-`:propose` effect. Escalations (always human sign-off):
`:post-adjustment` (write-off/correction with financial effect), low
confidence (< 0.6).



AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
