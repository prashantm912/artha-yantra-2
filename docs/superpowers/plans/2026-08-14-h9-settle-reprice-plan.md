# H9 — two-phase settle re-pricing (decide 16:00, re-price ~18:52)

**v2, 2026-08-14.** v1 was reviewed cross-vendor and returned **NEEDS_REWORK**; this revision answers
every finding and records the three owner decisions that unblocked it. The v1 findings are kept at the
foot of this doc rather than deleted, because two of them corrected reasoning I had asserted as
"by construction".

Owner decision: the exit DECISION stays at 16:00/16:02 off the Kite daily bar (unchanged timing,
unchanged exit doctrine); the FILL is re-priced to NSE `close_price` once bhavcopy lands (~18:45).

## Premise — CONFIRMED (ledger H9 / H17 / H18)

- Kite 1d `close` never equals NSE `close_price`: 0 of 22 on 2026-08-13 (H17 measured 0 of 21 on 08-11).
- Kite 1d `volume` is short of NSE `ttl_trd_qnty` on 22 of 22 (92.95–99.91%). The missing quantity is
  the 15:15–15:30 closing auction. **This is the mechanism.** `last_price` matching is NOT — 7 of 22
  today vs 13 of 21 three days earlier, so it does not generalise and nothing may be built on it.
- Money: positions 23 / 26 / 59, gross deltas +280.80 / +421.20 / +46.80 at official close.

## Owner decisions (settled 2026-08-14 — do not re-litigate)

1. **Slippage: keep `LtpSlippageV1`.** Re-price through the identical fill pipeline, changing only the
   reference price. The corrected exit stays comparable to its own entry; the ~5bps makes the
   correction conservative rather than optimistic, which is the safe direction on a paper book.
2. **Revisions: versioned corrections, and `UNIQUE(position_id)` is DROPPED.** A later bhavcopy
   revision may produce a further correction row; full per-position history is preserved. This is what
   makes the phrase "official close" honest given a retro-mutable source. **Consequence, and it is the
   single biggest change from v1: the unique index can no longer be the idempotency backbone, so a
   real processing LEASE has to carry that weight** (below).
3. **Entry-side asymmetry: accepted and scoped separately.** Exits become uniformly official-close;
   entries stay mixed-provenance (official close where the bar was bhav-projected, LTP-close where
   Kite owns it — proven to the paisa 2026-08-14: CUPID 294.86 × 1.0005 = 295.01, RPEL 1388.80 ×
   1.0005 = 1389.49). ⚠️ **Results from this book must NOT be described as official-close-to-official-close.**

## Shape

A replay-safe, **versioned** "settle re-price" lifecycle in strategy-signal's `paper` module: for every
swing-settle exit, correct the FILL (exit order leg + `realized_pnl`) to NSE `close_price` once
bhavcopy lands, leaving the DECISION untouched (signal, EXIT row, `swing_paper_effects`,
`sell_decisions`, `closed_at`, `close_reason`, status).

Worklist derives from `strategy.swing_paper_effects` where `effect_type='EXIT'`, `status='CONFIRMED'`,
`decision='REQUIRED'` — the exact record of engine daily-bar settles. Excludes manual closes, bracket
closes, intraday MTM and expiry settles by construction, and needs zero change to the settle path.

## Idempotency: a LEASE, not a unique index (v1 finding 6)

v1 said "claim once: `INSERT ... ON CONFLICT DO NOTHING`". That prevents duplicate *creation*; it is
not a processing lease for an existing `PENDING` or a stale claimed row — and decision 2 removes the
unique index entirely. Replaced with the house canary-catch-up doctrine (**evaluate → claim → publish;
a lease needs an ACTOR**):

- `paper_settle_reprices` rows carry `(position_id, revision_no)`, `lease_owner`, `lease_expires_at`,
  `status`, `attempts`.
- Claiming is a conditional UPDATE that names the actor and takes the lease only when the row is
  retryable and unleased-or-expired. A lost claim is a normal outcome, never an error.
- **The money mutation is ONE transaction**: position `realized_pnl` CAS, exit `paper_orders` leg
  update, and the audit row's terminal transition commit together or not at all. v1 never said this.
- A crash between effect and marker leaves a leased row whose lease expires and is re-evaluated; the
  CAS guard makes a re-run a no-op rather than a double-apply.

## Explicit state machine (v1 finding 6 — v1's terminal set was not exhaustive)

| status | class | meaning |
|---|---|---|
| `PENDING` | retryable | worklist row created, not yet processed |
| `LEASED` | retryable | an actor holds the lease; expiry returns it |
| `REPRICED` | terminal | corrected; audit holds before/after and `bhav_fetched_at` |
| `REPRICED_EQUAL` | terminal | official close equalled the decision price (delta zero) |
| `NO_BHAV_ROW` | terminal after budget | session landed, symbol absent from the EQ,BE set |
| `MULTI_SERIES_AMBIGUOUS` | terminal | >1 series row for (symbol, date) — refuse, never guess |
| `EXIT_LEG_AMBIGUOUS` | terminal | zero or >1 linked exit order — refuse |
| `CAS_CONFLICT` | retryable | position changed under us; re-evaluate from current state |
| `FILL_FAILED` | retryable | fill/instrument resolution failed |
| `ABANDONED_NO_BHAVCOPY` | terminal | session never landed within the attempt budget |
| `ABANDONED_RETRY_EXHAUSTED` | terminal | retryable class exceeded its budget — alerts |
| `SUPERSEDED` | terminal | a later revision produced a newer correction row |
| `OUT_OF_SCOPE_EXCHANGE` | terminal | defensive; cannot occur while `SwingBatchEngine.EX="NSE"` |

No row can sit non-terminal forever: every retryable class has an attempt budget ending in an
`ABANDONED_*` terminal that alerts.

## Phases

1. **Official-close read surface on market-data.** `GET /api/v1/market/eod-close`, **typed response
   record + status enum, never `Map<String,Object>`** (the Map ratchet freezes per-service handler
   counts), over `nse_eod_bhavcopy` with `series IN` the configured `artha.nse.bhavcopy.candle-series`
   set (EQ,BE — never hardcode EQ). Three distinguishable outcomes: found / session-landed-but-symbol-
   absent / session-not-landed; explicit refusal on multi-series match. **New path ⇒ re-capture the
   contract with `-Dtest=ContractCaptureTest` ONLY (never during a full verify), regen the TS client,
   and add the edge-gateway `Path=` route prefix** or the endpoint 404s live.
   `/candles` cannot serve this: bhavcopy projection is DO-NOTHING against Kite-owned bars, so a held
   symbol's 1d bar stays LTP forever.
2. **Migration V062 (strategy lineage).** `strategy.paper_settle_reprices` keyed
   `(position_id, revision_no)` — **no `UNIQUE(position_id)`** (decision 2) — storing decision vs
   official price, old/new `realized_pnl` and `fill_price`, `bhav_fetched_at` (pins which read won),
   lease columns, status, attempts.
3. **Re-price engine, dry-run first.** Lease → read official close → recompute through the SAME
   `PaperFillService` with reference = official close and `LtpSlippageV1` unchanged (decision 1) →
   one transaction (CAS + leg update + audit terminal). **Do not touch `libs/strategy-engine`
   FillSimulator** — shared backtest parity surface. Update the EXISTING exit leg in place; never
   insert a second leg.
4. **Scheduling + replay.** ~18:52 IST, `zone = "Asia/Kolkata"` explicitly, **trade date derived from
   the effect/session key, never `CURRENT_DATE`** (v1 omitted both). Presence-check is the gate, no
   polling. Plus an `ApplicationReadyEvent` sweep over all non-terminal rows, oldest first — the H18
   doctrine: a cron-only job on a machine off 19:00–08:00 has no replay path and fails silently.
5. **Docs, ledger, gates.** Parity surface NOT touched — zero edits in `libs/strategy-engine`,
   `backtest-service`, or golden fixtures. Golden+Parity rerun still owed as a **negative firewall**
   gate; per the reviewer it cannot validate the new behaviour, so focused persisted-money vectors and
   crash/replay tests are required alongside it.

## Reconciler impact — restated honestly (v1 finding 5)

v1 claimed "none, by construction" and used that to justify updating the leg in place. **The reviewer
showed the argument does not hold in the direction I used it:** the exit lateral flags only
`exit_count == 0`, so a *duplicate* linked exit would pass reconciliation silently. Reconciliation
therefore cannot be the safety net for this change in either direction.

What remains true: V5's entry lateral sums same-side legs inside `[opened_at, closed_at]` (untouched);
V16 reads signals and entry orders; stranded-carry and dead-anchor operate on OPEN positions only. So
the two hazards to avoid are still moving `closed_at` and inserting a same-side leg — but they must be
pinned by **direct assertions on exit-order identity and count**, not by "recon stays clean".

## Tests

1. Unit fill math at reference = official close, slippage unchanged.
2. Lifecycle IT — position, leg, audit, event.
3. **Exit-order identity IT** — after re-price: exactly ONE linked exit order, same id, same
   timestamps, side, quantity and leg kind; only `fill_price` / `slippage_applied` / `ref_source`
   differ. Red-proof: write the correction as a second leg → this test reddens **even though
   reconciliation stays clean**, which is the whole point.
4. Transaction-atomicity IT — fault injected between CAS and audit write leaves no partial state.
5. Lease IT — concurrent actors, one wins; expired lease is reclaimable; CAS makes a re-run a no-op.
6. **Revision IT** (decision 2) — a changed bhavcopy close produces `revision_no=2` and marks the
   prior row `SUPERSEDED`. Red-proof: restore `UNIQUE(position_id)` → insert fails, naming the constraint.
7. Series-set test — BE-only symbol must re-price. Red-proof: restore a literal `series='EQ'` filter →
   fails naming the BE symbol as `NO_BHAV_ROW`. Pins the H17 trap forever.
8. Terminal-state ITs for every retryable class reaching its `ABANDONED_*`.
9. **Risk-rail consequence test** (reviewer: testable consequence, not an open doubt) — assert what a
   re-priced `realized_pnl` does to the next session's book equity and pyramid-cap operand.
10. Endpoint shape tests + `ModularityTest`.

Every red-proof restores the LITERAL pre-fix body and is valid only if the failure names its own
assertion — a proof can break by staying green, by being too strong, or by reddening mechanically.

## Open doubts

- `paper_events.kind='REPRICED'` — no DB constraint blocks it, but not every consumer of the events
  stream was traced for unknown-kind tolerance.
- Whether any risk governor folds swing `realized_pnl` into its equity operand — now a TEST (9) rather
  than a doubt, but the grep still has to happen in the build phase to know what to assert.
- `swing_paper_effects` as worklist assumes every future settle exit binds `target_position_ids`
  (3/3 on 08-13). A settle path that ever bypassed effects would silently escape re-pricing.
- The `AutoJournalListener` note and any already-sent Telegram/ntfy message are historical; the
  correction event does not rewrite them, and a re-price can flip a near-breakeven win/loss tag.

## v1 review findings, kept for the record

Returned NEEDS_REWORK. (1) Duplicate linked exit does not fail reconciliation — it checks for zero
exits, not exactly one. (2) `INSERT ... ON CONFLICT DO NOTHING` is not a processing lease. (3) No
statement that the money mutation commits atomically. (4) Terminal set not exhaustive. (5) Refusing
bhavcopy revisions is not defensible while claiming an "official close" invariant, and
`UNIQUE(position_id)` would block a legitimate later correction. (6) Cron must pin the zone and derive
the trade date from the session key. (7) Endpoint needs a typed record and contract capture.
