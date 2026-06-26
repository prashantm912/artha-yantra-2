# 2b-1 — 36 instrument-agnostic scalper variants

**What changed:** the 12 Siva scalper YAMLs were forked into **36 variants** = 12 strategies ×
{`-nifty`, `-sensex-niftyoi`, `-sensex-sensexoi`}, all signalling on the NIFTY continuous front
future (`NFO/NIFTY-FUT-CONT`, built in 2b-E1). Each carries a tailored `backtest.optimize` block so the
optimizer can sweep it. This is the strategy-config half of the scalper-tunable-infra (engine done in
2b-E1/E2/E2b). The strategies seed as **drafts** (opt-in `artha.scalper.seed-strategies=true`) — they
never auto-emit.

## The three-way decoupling per variant (ADR-0003)

| field | `-nifty` | `-sensex-niftyoi` | `-sensex-sensexoi` |
|---|---|---|---|
| `universe.underlying` (option root) | `NSE / NIFTY 50` | `BSE / SENSEX` | `BSE / SENSEX` |
| `universe.signal_underlying` (signal) | `NFO / NIFTY-FUT-CONT` | `NFO / NIFTY-FUT-CONT` | `NFO / NIFTY-FUT-CONT` |
| `universe.strike_reference` (ATM anchor) | *(default = signal)* | `BFO / SENSEX-FUT-CONT` | `BFO / SENSEX-FUT-CONT` |
| `backtest.oi_confluence_gate.index` | *(default → NIFTY 50)* | `NIFTY 50` | `SENSEX` |

The signal/indicators always run on the NIFTY future (the correlation play). SENSEX variants execute on
SENSEX option legs, anchoring the ATM strike on the SENSEX future. The two SENSEX versions differ ONLY in
the OI-confluence gate index — a forward-paper A/B (does gating on NIFTY-OI or SENSEX-OI trade better?).

## OI-confluence gate — enabled on the 4 OI-led, dormant on the rest

`enabled: true` on the continuation-style OI scalps: **connect-the-dots, trending-oi, two-candle,
open-high-low**. `enabled: false` (slot pre-created with the index set, for a future regime) on the other
8. The gate is muted on history (Dow + IV degrade to NEUTRAL), real on live — so it's a forward-paper
discriminator, not a backtest one. It is NOT enabled on the reversal strategies (trend-change, hero-zero)
because they intentionally fire against the prevailing trend.

## SENSEX premium band (live-only)

`ScalperConfig` §0B gains the SENSEX band **300–800** (grill-locked). It is read by the LIVE
`StrikePicker` only; the **backtest selector ignores the band** (picks nearest-strike-to-spot), so the
backtest variants are unaffected. Refine the band on 2c live paper.

## Verify

1. **Load test (CI gate):** `mvnw -pl services/strategy-signal-service -am test -Dtest=ScalperStrategyLoadTest`
   — every one of the 36 must be schema-valid, compile to an engine definition, carry the seam aliases
   (`vwma20/psar/rsi14/supertrend`), be `primary: 3m`, resolve `ScalperConfig.underlying()` to its index,
   and carry its gate tag. The seeder's `STRATEGIES` list must stay in lockstep.
2. **Seed (mock or live):** set `artha.scalper.seed-strategies=true` → boot → 36 drafts created (re-seed
   is idempotent, 409-skips). NOTE: the 2 ex-BankNifty ids (`scalp-gap-theory-banknifty`,
   `scalp-trend-change-banknifty`) were **renamed/re-homed**; if a prior boot seeded them, those 2 old
   drafts remain orphaned in the registry — archive them by hand (they are unpublished drafts).
3. **Functional backtest (2b-2):** run a full-window backtest on each — it must execute + trade sanely
   (no engine error). The `volume > 0`-gated strategies (hero-zero, straddle, btst-stbt) fire near every
   in-window bar in backtest because their real gate is the live-only §12.3 seam — expected; flag them as
   needing live evaluation, not backtest tuning.

## 2b-2 functional-verify run (2026-06-26, live stack, window 2026-02-02 → 2026-06-13)

All 36 submitted via `POST /api/v1/backtests/run` → **36/36 completed, zero engine errors**. (First run
surfaced one engine gap — the tick-wise runner rejected a 3m primary — fixed in **2b-E3** [#228]; all 36
then ran clean.) This is a FUNCTIONAL pass (executes + trades, no error); returns are NOT tradeable
(overfit + OI muted on history — tune on live forward paper, per `scalper-tuning-findings`).

- **E2b decoupling proven on live:** every SENSEX variant trades real SENSEX option legs (e.g.
  `SENSEX12FEB2684500CE`) off the NIFTY-FUT-CONT signal with SENSEX-fut strike anchoring; `backtest_runs`
  carries `NIFTY-FUT-CONT` as the signal series for all 36 (the option legs live in `backtest_trades`).
- **OI-gate A/B behaves as designed:** the 4 gate-ENABLED OI-led bases (connect-the-dots, trending-oi,
  two-candle, open-high-low) show `niftyoi ≠ sensexoi` (different trade counts/returns — the gate drops
  legs per its index); the 8 gate-DORMANT bases show `niftyoi == sensexoi` identical (gate off). The gate
  is muted on history but the wiring is correct → it is a FORWARD-paper discriminator, not a backtest one.
- **Trade counts (NIFTY variant):** connect-the-dots 293, two-candle 281, trending-oi 319, golden-crossover
  242, gap-theory 255, trend-change 212, open-high-low 95, morning-trade 922, hero-zero 30, straddle 192,
  market-movers 255, btst-stbt 1.
- **Flagged engine-unfaithful (real gate is the live-only §12.3 seam → judge on FORWARD paper, not this
  backtest):** morning-trade (`volume>0` fires every open → 922 trades, deeply negative), btst-stbt (1
  trade — the once/day carry barely fires without the live close-location/OI gate), hero-zero + straddle
  (`volume>0` + two-leg straddle replay). These RAN without error; their backtest P&L is not meaningful.
