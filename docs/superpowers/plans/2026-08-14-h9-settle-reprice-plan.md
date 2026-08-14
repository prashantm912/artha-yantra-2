# H9 — settle close divergence: REPORT-ONLY ledger first, mutation later

**v3, 2026-08-14.** v1 and v2 both returned **NEEDS_REWORK**. Owner decision after round 2: **ship a
report-only divergence ledger with ZERO money mutation**, and treat re-pricing as a separate,
later, owner-armed step. v3 is therefore not a smaller version of v2 — it is a different, narrower
deliverable chosen because v2's Criticals were structural.

⚠️ **Process note, recorded because it caused the churn.** v2 was reworked against a TRUNCATED
reading of round 1 — I read the review's tail and never saw Criticals 1 and 2, so v2 left the two
hardest findings untouched and burned a review round. Read a review in full before reworking against it.

## What v3 does NOT do

No `realized_pnl` change. No `paper_orders` mutation. No CAS, no money transaction, no backfill
horizon over historical money, no reconciler interaction. Those all belong to the mutation step and
are deliberately out of scope. **Nothing in this plan may change a single rupee in the book.**

## What v3 does

For every swing-settle exit, record what the exit priced at, what NSE's official `close_price` was,
and the delta — nightly, durably, with honest provenance. That converts H9 from a one-session
anecdote (≈₹749 across three exits on 2026-08-13) into a measured series, and it is the evidence
base the mutation decision needs.

## Premise — CONFIRMED (ledger H9 / H17 / H18)

- Kite 1d `close` never equals NSE `close_price`: 0 of 22 on 2026-08-13 (H17: 0 of 21 on 08-11).
- Kite 1d `volume` is short of NSE `ttl_trd_qnty` on 22 of 22 (92.95–99.91%) — the missing quantity
  is the 15:15–15:30 closing auction. **This is the mechanism.** `last_price` matching is NOT (7 of
  22 vs 13 of 21 — it does not generalise and nothing may be built on it).

## Carried-over review findings this design must still answer

Report-only removes the money Criticals but NOT these three. They are about whether the REPORT is
true, and a false report is the house's most common defect shape.

1. **Causality cannot be asserted, so it is RECORDED instead (round-2 Critical 1).**
   `swing_paper_effects` being `CONFIRMED/REQUIRED` does not prove the swing settle closed the
   position: `closeForPosition` returns success for an already-closed position
   (`PaperService.java:1717`) and the listener confirms on returned-count equality
   (`EngineExitListener.java:108`), so a manual or bracket close winning after target capture still
   yields an eligible effect. v2 papered over this by claiming the effect set "excludes manual and
   bracket closes by construction". It does not. **v3 does not claim causality at all**: each row
   stores the position's actual exit order id, its `ref_source`, and a `causality` enum
   (`SETTLE_PROVEN` / `AMBIGUOUS` / `NOT_SETTLE`), derived from what is observable. Rows that are not
   `SETTLE_PROVEN` are recorded and EXCLUDED from the headline divergence statistic rather than
   silently averaged into it.
2. **Rediscovery, not row-processing (round-2 Critical 2).** A missed cron creates NO row, so a
   sweep "over all non-terminal rows" finds nothing to do — this is exactly H18's shape, measured
   live this morning. The boot sweep must **rediscover eligible exits from source and anti-join them
   against already-recorded divergence rows**, never merely drain a queue.
3. **Presence is not completeness (round-2 Critical 3).** A partial bhavcopy ingest reads as
   "present" and would be skipped forever — the source path documents a real ~167-of-~2380-row day
   (`BhavcopyBackfillService.java:193`). A "symbol absent" conclusion is only meaningful when the
   trade date's row count clears a completeness floor; below it the correct answer is
   `SOURCE_INCOMPLETE`, retryable.

## Shape

1. **Official-close read surface on market-data.** `GET /api/v1/market/eod-close`, **typed response
   record + status enum, never `Map<String,Object>`** (the Map ratchet freezes handler counts per
   service). Reads `nse_eod_bhavcopy` with `series IN` the configured
   `artha.nse.bhavcopy.candle-series` set (EQ,BE — never hardcode EQ; an EQ-only filter drops real
   swing symbols and manufactures a false "missing data" result). Four outcomes: `FOUND` /
   `SYMBOL_ABSENT` (date complete) / `SOURCE_INCOMPLETE` (date below the completeness floor) /
   `NOT_LANDED`. Explicit refusal on multi-series match — never guess.
   ⚠️ **No gateway edit needed** — `/api/v1/market/**` is already allowlisted
   (`edge-gateway/application.yml:47`). v2 wrongly said a new prefix was required. Assert the route
   in a test instead of making a redundant edit.
   Re-capture the contract with `-Dtest=ContractCaptureTest` ONLY, never during a full verify.
2. **Migration V062 (strategy lineage), append-only.** `strategy.settle_close_divergence`:
   `position_id`, `session_date`, `tradingsymbol`, `series`, `exit_order_id`, `exit_ref_source`,
   `causality`, `decision_price`, `official_close`, `delta_abs`, `delta_pct`, `qty`,
   `implied_pnl_delta`, `source_row_count`, `bhav_fetched_at`, `observed_at`. Unique on
   `(position_id, bhav_fetched_at)` so a later bhavcopy revision appends a NEW observation rather
   than overwriting — history is additive and no row is ever rewritten.
3. **Recorder.** Rediscovery query → for each eligible exit, resolve official close → append a row.
   No lease needed: the work is idempotent and append-only, and the unique key makes a double-run a
   no-op. (This is the main simplification report-only buys.)
4. **Schedule.** ~18:52 IST, `zone = "Asia/Kolkata"` explicitly, trade date derived from the
   session key, never `CURRENT_DATE`. Plus the boot rediscovery sweep of finding 2.
   ⚠️ **The new cron MUST be added to `OperatingWindowTest`'s catalogue and the
   `CronPassthroughParityTest` ratchet.** This is not theoretical: PR #1354 went red on CI this
   morning for exactly this omission, and the annotation must carry a cron LITERAL, not a
   concatenation, because `OperatingWindowTest.codeDefault()` parses it out of the source text.
5. **Read surface for the result.** A typed GET exposing the series, so the mutation decision can be
   made from data rather than from this document.

## Tests

1. Divergence math — exact expected values, not "differs".
2. Causality classification — a manual close after target capture is recorded `NOT_SETTLE` and
   excluded from the headline statistic. Red-proof: classify everything `SETTLE_PROVEN` → the
   headline assertion moves, naming it.
3. Rediscovery IT — a session with NO pre-existing row is discovered and recorded by the boot sweep.
   **Red-proof: restore a row-draining sweep → nothing is found, exactly reproducing H18.**
4. Completeness floor — a partial-ingest date yields `SOURCE_INCOMPLETE`, retryable, never
   `SYMBOL_ABSENT`. Red-proof: restore presence-only logic → the partial date terminates wrongly.
5. Series-set — a BE-only symbol resolves. Red-proof: restore a literal `series='EQ'` filter → fails
   naming the BE symbol. Pins the H17/filter-artifact trap.
6. Revision append — a changed bhavcopy close appends a second observation; the first is retained.
7. Idempotency — running twice appends nothing new.
8. Schedule guards — `OperatingWindowTest` + `CronPassthroughParityTest` both cover the new cron.
9. Endpoint shape tests (four outcomes + multi-series refusal) and the gateway ROUTE assertion.
10. `ModularityTest`.
11. **A no-mutation ratchet**: assert this feature's code path performs no write to
    `paper_positions` or `paper_orders`. Red-proof: add a write → the ratchet reddens. This is the
    guard that keeps report-only actually report-only.

Every red-proof restores the LITERAL pre-fix body and is valid only if the failure names its own
assertion.

## Parity / gates

Zero edits in `libs/strategy-engine`, `backtest-service`, or golden fixtures. Golden+Parity rerun is
owed as a **negative firewall** only. No money math changes, so the money-path ladder does not apply
— which is the point of doing this step first.

## Deferred to the mutation step (explicitly NOT decided here)

Slippage treatment on a corrected fill (owner previously chose keep `LtpSlippageV1`); versioned
corrections vs one-shot (owner previously chose versioned); the backfill horizon over historical
money; causal binding of the effect to its exact exit order inside the winning close transaction;
per-position version fencing; entry-side asymmetry (owner: accept, scope separately — entries price
at mixed provenance today, proven to the paisa 2026-08-14, CUPID 294.86 × 1.0005 = 295.01 and RPEL
1388.80 × 1.0005 = 1389.49). ⚠️ **While that asymmetry stands, results from this book must NOT be
described as official-close-to-official-close.**

## Open doubts

- The completeness floor needs a defensible number; `BhavcopyCloseCanary`'s own `min-compared` floor
  is prior art but was chosen for a different question.
- `causality` classification is only as good as what the close path records today; if it cannot
  distinguish a settle close from a manual one at all, the honest outcome is that most rows are
  `AMBIGUOUS` — and that finding is itself worth having before any mutation is built.
- Whether the report should also cover swing ENTRIES (same mixed-provenance issue, opposite side).
  Not included; it would answer the deferred asymmetry question with data.
