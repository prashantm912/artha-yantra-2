> **ARCHIVED 2026-07-02 with the scalper-to-100 roadmap** — per-package design provenance; build complete.

# Scalper backlog — implementation-plan index & roadmap

> **Sequencing authority moved (2026-06-28).** These 12 stream files are now the **per-package
> design library** for finishing the scalper engine; the **build sequence + status + descopes** live in
> the consolidated roadmap [`../2026-06-28-scalper-to-100-roadmap.md`](../2026-06-28-scalper-to-100-roadmap.md)
> (epics E1–E12, anchored on the *debloated* operative doc). All 12 streams are KEEP-NEEDED (each still
> designs ≥1 unbuilt package). **Already BUILT** since this README was written: the 7 W3 drift tags +
> the 7 W4 gates + several indicator/exit primitives — see the roadmap §1 baseline, don't rebuild.
> **DESCOPED** (do not build): the ~5 packages in [S24-PRUNE.md](S24-PRUNE.md). The 246-gap count below
> is the bloated-audit number; the debloated remaining is ~95 packages minus those descopes.

Status: PLAN INDEX. Owner: single-owner. Date: 2026-06-27.

This directory holds the **12 implementation-ready stream plans** that close the **246-gap
`AUTOMATE_PKG` backlog** from the strategy audit. Each stream is a self-contained, executor-ready
plan (goal → current-state-by-file:line → per-package design → parity classification → tests →
sequencing → effort/PRs → open points). This README is the index + the dependency-aware execution
order.

> **Where this fits.** The audit dispositioned **427** gap rows
> (`docs/strategy-audit/GAP-DISPOSITION.md`). The two queued follow-up plans close a thin slice;
> these 12 streams are the rest of the automatable work:
> - **FU1** ([`2026-06-27-followup1-expand-manual-checks.md`](../2026-06-27-followup1-expand-manual-checks.md)) + **FU2** ([`2026-06-27-followup2-soft-dots-to-hard-gates.md`](../2026-06-27-followup2-soft-dots-to-hard-gates.md)) → **39** rows (26 manual checks + 13 soft-dot→hard-gate promotions).
> - **These 12 streams** → the **246 `AUTOMATE_PKG`** rows.
> - The remaining tail (not in these streams): **57** already shipped (`COVERED_EXISTING`,
>   the 7-item `ScalperManualChecks`), **32** kept-manual (`KEEP_MANUAL_NEW`), **36** accepted by
>   design (`ACCEPT_BY_DESIGN`), **17** owner-decision (`UNCERTAIN_OWNER`).
>
> Master coverage map: [`docs/strategy-audit/GAP-DISPOSITION.md`](../../../../strategy-audit/GAP-DISPOSITION.md).

**Load-bearing convention (read first).** Every stream copies the **FU2 parity-safe-additive
default-OFF tag-gate** template: a new behaviour = a `ScalperConfig` `requireXxx` flag + a
`tags.contains("<tag>")` parse + an early-return hard gate in `ScalperConfluenceGate.evaluate`,
**absent from all 36 shipped YAMLs** so every existing config stays byte-identical. The seam is
LIVE-only and never runs on the deterministic replay, so the 5 frozen engine goldens
(`GoldenDeterminismTest.FEATURES`) cannot be perturbed. Arming a tag on a real strategy is always a
deferred, owner-driven, forward-paper step ("tune on live, not backtest"). The `#5 oi-cross-filter`
gate is the canonical shape; FU2 is the precedent every `[P]` change cites.

---

## 1. The 12 streams at a glance

| Stream | Packages | Gaps | Effort | Parity-sensitive `[P]` | Open points | Plan |
|---|---:|---:|:--:|---:|---:|---|
| Trade management — targets / trailing / SL alternates / exits | 9 | 50 | **L** | 7 | 8 | [trade-management-exits.md](trade-management-exits.md) |
| OI fidelity — soft dots → hard gates + new OI primitives | 17 | 34 | **M** | 7 | 8 | [oi-fidelity-gates.md](oi-fidelity-gates.md) |
| Macro confluence — directional VIX / Dow / FII-DII / constituent | 5 | 20 | **M** | 5 | 9 | [macro-vix-global-fii.md](macro-vix-global-fii.md) |
| IV fidelity — per-strike IV direction / absolute band / both-flat | 3 | 14 | **M** | 4 | 9 | [iv-fidelity.md](iv-fidelity.md) |
| RSI — multi-timeframe caps / per-strategy bands / cool-off | 7 | 11 | **M** | 11 | 8 | [rsi-multi-timeframe.md](rsi-multi-timeframe.md) |
| Indicators — multi-TF Supertrend / Volume-MA / patterns / trendline | 11 | 13 | **M** | 9 | 8 | [indicators-supertrend-volume.md](indicators-supertrend-volume.md) |
| Strike / premium selection — backtest band / recenter / skew | 3 | 13 | **M** | 2 | 8 | [strike-premium-selection.md](strike-premium-selection.md) |
| VWAP-distance + probability-graded position sizing | 3 | 21 | **M** | 3 | 7 | [vwap-and-sizing.md](vwap-and-sizing.md) |
| Stock universe + Market-Movers per-stock track *(foundational)* | 11 | 17 | **L** | 0 | 9 | [stock-universe-market-movers.md](stock-universe-market-movers.md) |
| Risk governance — daily caps / 5-account ledgers / auto-journal | 4 | 11 | **M-L** | 0 | 8 | [risk-governance.md](risk-governance.md) |
| Per-strategy controls + bearish/PE seeding + BTST routing + Sensex scale | 17 | 27 | **M-L** | 12 | 8 | [per-strategy-controls-seeding.md](per-strategy-controls-seeding.md) |
| Event/time gates + backtest-fidelity rails + SPAN sell legs | 7 | 17 | **M** | 5 | 9 | [event-time-backtest-span.md](event-time-backtest-span.md) |
| **Totals** | **~97** | **~248** | — | **65** | **99** | |

**Totals note.** Gaps sum to ~248 vs the 246 `AUTOMATE_PKG` rows: a couple of single-gap packages
(`pct-price-move-gate`, `volume-ma-indicator`) are titled in two streams because their index-level
primitive lands in the indicators stream while their per-stock re-key belongs to the equity
sub-epic — the streams cross-reference each other so the gap is closed once, not twice. The package
count is a rollup of the per-stream tables; some packages share one code mechanism (e.g. the OI
stream's 17 packages reduce to ~6 reusable gate primitives).

**One-line scope per stream**

1. **Trade management** — wire the engine's already-built `take_profit`/`trailing_stop` exit types
   into YAML variants, add a volume-qualified VWAP-break exit, structural (points/indicator) trails,
   profit-slice sizing. Bulk is cheap YAML wins; S/R-zone engine + scale-in ladder split to own plans.
2. **OI fidelity** — promote OI confluence dots to opt-in hard pre-gates (cross-required, slope-agree,
   divergence-magnitude, flat-OI stand-aside, direction-arrow, OI-wall S/R) + a 60m read + dynamic
   strike re-centre. 17 packages → ~6 reusable `ScalperGates` functions.
3. **Macro confluence** — populate the dead-wired `Macro` fields: directional VIX gate, Dow dot, FII/DII
   L/U/B/C bias classifier, constituent-contribution, volume-pump attribution. Mostly feed wiring.
4. **IV fidelity** — per-strike IV slope dot (off the existing active-strike IV series), absolute
   10–12 trend-play band, per-side IV>40 buyer cap, Hero-Zero both-sides-flat skip.
5. **RSI** — 5m + daily RSI overbought caps (engine is already multi-TF-capable), per-strategy band
   override, overbought cool-off / post-vertical recovery sequencers.
6. **Indicators** — 15m Supertrend confirmation, PSAR-distance durability, Volume-20 MA, two-candle
   arming + 1st/3rd substitution, a new diagonal-trendline-break primitive, pin PSAR params.
7. **Strike/premium** — band-aware **backtest** premium-replay selector (`[S]`, its own golden), the
   live S22-band swap, dynamic OI-window re-centre, per-side premium-skew warning dot.
8. **VWAP & sizing** — a VWAP-distance entry-skip gate + a probability-graded `suggested_qty` multiplier
   (confluence × OI-gap × VIX) that defaults to 1.0 (advisory, `[S]`). Prior-day VWAP deferred.
9. **Stock universe** *(foundational)* — equity-futures capture + a Market-Movers screener
   (N-day breakout / OH-OL / OI-interpretation / daily-RSI / liquidity) + the per-stock "scan-then-trade"
   seam hook. **Every per-stock package gates on this.**
10. **Risk governance** — account-side rails: daily profit/loss/deployment caps + seeds, 5-account
    ledgers (per-account 1% target + first-loss freeze), auto-journal on every closed paper trade.
11. **Per-strategy controls + seeding** — route BTST/STBT through the confluence gate, resolve
    `direction: both` → per-bar side, seed the bearish **PE** mirror variants, Sensex ~3× point-scaling,
    plus the small per-strategy knobs (two-candle / morning / gap / trend-change / straddle).
12. **Event/time + backtest + SPAN** — economic-event lockout, ideal-window / afternoon-cap / avoid-Friday
    / Hero-Zero-floor time gates, lift live RSI/volume rails into the backtest `gate.all`, and the
    SPAN-gated short-premium **sell** legs (blocked on margin-service #47).

---

## 2. Recommended execution order (dependency-aware roadmap)

The streams interlock through four foundations. Build the foundations first; the dependent gates ride
them. Within a stream, follow that plan's own PR order.

### Wave 0 — foundations (unblock the rest)

These have **no upstream dependency** and **unblock** large swaths of dependent work. Build first.

- **A. Equity-futures universe + Market-Movers screener** — *stream 9 (stock-universe-market-movers)*.
  The single most load-bearing feed: **every per-stock package gates on it** (per-stock RSI, OH/OL,
  OI-interpretation, volume exit, `pct-price-move`/`volume-ma` re-keys in streams 1/6). It also carries
  an **≥8-session calendar warm-up** before the breakout/daily-RSI filters return non-degenerate values,
  so start the capture **first of all**. `[S]` throughout (brand-new path, no existing golden).
- **B. SPAN appliance live-verify (#47)** — gates **every sell leg**: the short-premium straddle +
  BTST/STBT sell legs (stream 12 `short-premium-span`) and any future short-side management in streams 1
  and 4. Fail-closed until the real `.spn` + NSE download URL land. Sequence the SPAN-dependent legs
  **last**; start the live-verify early so it is ready when streams 11/12 reach the sell path.
- **C. The macro feeds before their gates** — *streams 3 (VIX/Dow/FII) and 4 (IV)*. The VIX feed wiring
  is the spine that threads the `vixEnabled` signature reused by Dow/FII/constituent; the FII
  participant-bias + constituent endpoints (market-data `[S]`) must land **before** their consuming
  gates. The IV slope reads off the existing active-strike series. These feeds also light up the VIX/OI
  factors of the **sizing multiplier** (stream 8) and the macro half of the OI confluence — build the
  feeds before anything that reads them.

### Wave 1 — the FU2 tag-gating pattern, applied broadly

With the feeds in place, the **bulk** of the work is mechanically identical FU2-shaped default-OFF gates.
Every `[P]` stream reuses this exact pattern (the `#5 oi-cross-filter` early-return + tag + the 8-literal
`ScalperConfig` arity fan-out + the seam-test triple). Build in roughly this order — independent, low-risk
first:

1. **OI fidelity** (stream 2) — the no-new-feed cross/slope gates first (PR-A), then the producer
   derivations. Highest-value (the defining Trending-OI edge).
2. **RSI** (stream 5) + **Indicators** (stream 6) — no feed wiring; every datum is on the engine
   `BarValues`/`EngineSeries` the seam already reads. Ship the free `[S]` wins first (PSAR-param pin,
   Volume-MA declare-only).
3. **Macro gates** (stream 3) + **IV gates** (stream 4) — once Wave-0(C) feeds land.
4. **VWAP & sizing** (stream 8) — the VWAP-distance skip has no dependency; the sizing **multiplier's**
   VIX factor is inert until Wave-0(C) and lights up for free.
5. **Trade management** (stream 1) — the YAML-only target/trail variants need no engine code; ship them
   first, then the few new engine exit types.

### Wave 2 — dependent / heavier streams

6. **Per-strategy controls + seeding** (stream 11) — **`btst-route-through-gate` must land first** within
   the stream (the BTST path emits a null `Decision` today; routing it unblocks the 3 downstream BTST
   packages and the `both`→side resolver). Sensex point-scaling rides the already-done 2b decoupling.
   PE-mirror seeding is parity-safe (brand-new slugs).
7. **Strike/premium** (stream 7) — the backtest band selector is self-contained `[S]` (own golden) and
   can land any time; the live S22-band swap is a separate owner-reviewed `[P]` commit; the OI-window
   re-centre depends on the market-data window param.
8. **Risk governance** (stream 10) — account-side `[S]` rails, no feed dependency; can land in parallel
   with anything. Deliberately does **not** wire the dead `max_daily_loss_pct`/`max_positions` YAML keys
   into the compiler (that would be `[P]`); relies on the DB rail + a seed instead.
9. **Event/time + backtest + SPAN** (stream 12) — the time/calendar gates are independent; the
   economic-event lockout needs its feed first; the **SPAN sell legs land last** (gated on Wave-0(B) and
   the stream-11 BTST routing).

**The dependency spine in one line:**
**equity universe → per-stock Market-Movers** · **VIX/FII/IV feeds → their gates → the sizing multiplier's
factors** · **FU2 tag-gating → every `[P]` stream** · **BTST routing → BTST sub-packages** ·
**SPAN → every sell leg**.

---

## 3. Totals & what these close

- **12 streams · ~97 work-packages · ~248 gaps · 65 parity-sensitive `[P]` changes · 99 open points.**
- These streams close the **246-gap `AUTOMATE_PKG`** backlog (`GAP-DISPOSITION.md` §3) — the entire
  automatable remainder of the strategy audit.
- The other 181 dispositioned rows are out of scope here and accounted for elsewhere:
  **39** in FU1/FU2, **57** already shipped (`COVERED_EXISTING`), **32** kept-manual (`KEEP_MANUAL_NEW`),
  **36** accepted by design (`ACCEPT_BY_DESIGN`), **17** owner open-points (`UNCERTAIN_OWNER`).
- The **99 open points** across the streams are the per-plan decisions (with options + a recommended
  default each) that want owner sign-off before or during execution — notably the per-index SL/target
  point bands, the Sensex 3× point-scale factor, the equity-capture sizing/cadence (live-DB OOM risk),
  the economic-event feed source, and the seeded risk-cap defaults (a live behaviour change).

**Parity guarantee, every stream:** no existing golden/parity fixture is regenerated; the 36 shipped
YAMLs carry no new tag in any infra PR; `GoldenDeterminismTest` + `BacktestParityTest` are re-run as
byte-identical tripwires on every PR. Arming any tag on a real strategy is a deferred, owner-driven,
forward-paper step.
