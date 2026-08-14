# H9 — two-phase settle re-pricing (decide 16:00, re-price ~18:52)

Owner decision 2026-08-14: the exit DECISION stays at 16:00/16:02 off the Kite daily bar
(unchanged timing, unchanged exit doctrine); the FILL is re-priced to NSE `close_price`
once bhavcopy lands (~18:45).

## Premise — CONFIRMED (see ledger H9 / H17 / H18)

- Kite 1d `close` never equals NSE `close_price`: 0 of 22 on 2026-08-13 (H17 measured 0 of 21 on 08-11).
- Kite 1d `volume` is short of NSE `ttl_trd_qnty` on 22 of 22 (92.95%-99.91%). The missing quantity is
  the 15:15-15:30 closing auction. This is the mechanism; `last_price` matching is NOT (7 of 22 today,
  13 of 21 on 08-11 - it does not generalise).
- Money: positions 23 / 26 / 59, gross deltas +280.80 / +421.20 / +46.80 at official close.

## CORRECTION to the planning agent's STEP 0 (read this before trusting that section)

The plan's STEP 0 states the morning forfeit "did NOT reproduce", citing
`ARTHA_SWING_CATCHUP_CRON=0 58 8` as evidence the pass was always going to run. **That override is
the Architect's manual recovery, applied at 08:53:43 IST, not pre-existing config.** The agent
observed post-intervention state. The forfeit is evidenced by: boot 08:37:46 (three minutes past the
`0 35 8` default), `swing_catchup_runs` carrying NO row for `session_date=2026-08-13` at 08:55,
`risk_audit` at 0 rows at 08:49, and entries appearing only at 08:58 after the override + recreate.
Ledger row H18 stands. Everything else in the plan's STEP 0 verified clean and is trustworthy.

## Shape

A replay-safe "settle re-price" lifecycle in strategy-signal's `paper` module: for every swing-settle
exit, correct the FILL (exit order leg + `realized_pnl`) to NSE `close_price` once bhavcopy lands,
leaving the DECISION untouched (signal, EXIT row, `swing_paper_effects`, `sell_decisions`,
`closed_at`, `close_reason`, status), with an append-only audit row and defined terminal states.

Worklist derives from `strategy.swing_paper_effects` where `effect_type='EXIT'`,
`status='CONFIRMED'`, `decision='REQUIRED'` - the exact record of engine daily-bar settles. This
excludes manual closes, bracket closes, intraday MTM and expiry settles by construction, and needs
zero change to the settle path.

## Phases

1. **Official-close read surface on market-data.** `GET /api/v1/market/eod-close?symbol&date` over
   `nse_eod_bhavcopy`, `series IN` the configured `artha.nse.bhavcopy.candle-series` set (EQ,BE - never
   hardcode EQ). Three distinguishable responses: found / session-landed-but-symbol-absent /
   session-not-landed. Refuse explicitly on multi-series match, never guess. `/candles` cannot serve
   this: bhavcopy projection is DO-NOTHING against Kite-owned bars, so a held symbol's 1d bar stays
   LTP forever.
2. **Migration V062 (strategy lineage).** `strategy.paper_settle_reprices`, `position_id` UNIQUE as the
   idempotency backbone, storing decision vs official price, old/new `realized_pnl` and `fill_price`,
   `bhav_fetched_at` (pins which read won), status, attempts.
3. **Re-price engine, manual/dry-run first.** Claim via `INSERT ... ON CONFLICT DO NOTHING`; recompute
   through the SAME `PaperFillService` pipeline with reference = official close, keeping the slippage
   simulator and statutory fees identical. **Do not touch `libs/strategy-engine` FillSimulator** - that
   is the shared backtest parity surface. Update `realized_pnl` via CAS; update the EXISTING exit
   `paper_orders` leg in place rather than inserting a second leg; never touch `closed_at`.
4. **Scheduling + replay.** ~18:52 IST evening attempt (bhavcopy lands 18:45:11-30; 18:54-18:58 are
   taken). Presence-check is the gate, no polling. Plus an `ApplicationReadyEvent` sweep over all
   non-terminal rows, oldest first, bounded by max-attempts - the `SwingBatchCatchUp` claim/attempts
   pattern. Terminal states: REPRICED / REPRICED_EQUAL / NO_BHAV_ROW / ABANDONED_NO_BHAVCOPY /
   OUT_OF_SCOPE_EXCHANGE. One-shot-with-snapshot: a later bhavcopy revision does NOT reopen a closed book.
5. **Docs, ledger, gates.** Parity surface NOT touched - zero edits in `libs/strategy-engine`,
   `backtest-service`, or golden fixtures. Golden+Parity rerun still owed as ritual for a money path.

## Reconciler impact - none, by construction

V5's entry lateral sums same-side legs inside `[opened_at, closed_at]` (untouched); the exit lateral
counts by `settles_position_id + leg_kind='EXIT'` with no lifetime bound and flags only
`exit_count==0`; V16 reads signals and entry orders; stranded-carry and dead-anchor operate on OPEN
positions only. The two invariants that WOULD break are exactly what the shape above avoids: moving
`closed_at`, and inserting a same-side correction leg (inflates `entry_qty`). Both get red-proofed.

## Tests

Unit fill math; lifecycle IT; **reconciliation-invariance IT with both hazards restored literally as
variant bodies**; idempotency IT; series-set test (restore a literal `series='EQ'` filter -> BE symbol
must fail as NO_BHAV_ROW, pinning the H17 trap forever); terminal-state ITs; replay IT; endpoint
shape tests; `ModularityTest`. Every red-proof restores the LITERAL pre-fix body and is valid only if
the failure names its own assertion.

## Open owner decisions

1. Slippage on the official-close fill - keep `LtpSlippageV1` (consistency) vs zero (an auction close
   is a single print). ~5bps.
2. Retroactive backfill horizon - re-price 08-13's three exits, or start clean.
3. **Entry-side asymmetry** - entries fill at mixed provenance today (official close where the bar was
   bhav-projected, LTP-close where Kite owns it). PROVEN 2026-08-14: CUPID 294.86 x 1.0005 = 295.01
   entry, RPEL 1388.80 x 1.0005 = 1389.49 entry, both exact. The fix makes exits uniform and leaves
   entries mixed. Symmetric treatment is a SEPARATE decision, not smuggled in.
4. Journal / graduation ripple - correcting journal entry? re-tag a win/loss flip on a near-breakeven?

## Open doubts

- `paper_events.kind='REPRICED'` - no DB constraint blocks it, but not every consumer of the events
  stream was traced for unknown-kind tolerance.
- NSE occasionally reissues bhavcopy files; one-shot-with-snapshot deliberately does not follow
  revisions. A read-only revision-detector canary is unbuilt.
- Whether any risk governor folds swing `realized_pnl` into its equity operand - if so a morning
  re-price marginally changes same-morning sizing. One grep in the build phase.
- `swing_paper_effects` as worklist assumes every future settle exit binds `target_position_ids`
  (3/3 on 08-13). A settle path that ever bypassed effects would silently escape re-pricing.
