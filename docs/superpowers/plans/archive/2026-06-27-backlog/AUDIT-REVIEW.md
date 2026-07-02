# Backlog stream plans — 2-pass audit review

Status: AUDIT SUMMARY. Owner: single-owner. Date: 2026-06-27.

This is the consolidated readiness verdict for the **12 backlog implementation-stream plans** in this
directory. Each plan was put through **two independent audit passes** (the second auditor re-opened every
load-bearing source file from scratch, did not trust pass-1's transcript, re-derived the parity argument
end-to-end, and hunted for what both the author and pass-1 missed). Every plan now carries an
`## Audit pass 1 findings` + `## Audit pass 2 findings` block with its corrections applied **in place**.

- **Index + execution roadmap:** [README.md](README.md)
- **Upstream follow-ups (the FU2 tag-gate template these streams copy):**
  [FU1 — expand manual checks](../2026-06-27-followup1-expand-manual-checks.md) ·
  [FU2 — soft dots → hard gates](../2026-06-27-followup2-soft-dots-to-hard-gates.md) ·
  [FU audit summary](../2026-06-27-followups-plan-audit-summary.md)

---

## 1. Overall readiness statement

**All 12 streams are implementation-ready, verdict `sound-with-open-points` on BOTH passes** (no plan
regressed or escalated between passes). The architecture, the FU2 parity-safe-additive tag-gate posture,
and the `[P]`/`[S]` classification held up under independent re-verification for every stream. What the
audits found and fixed was **citation accuracy and under-specified build steps**, not design defects:
~85 stale/wrong source-line cites corrected, ~47 parity-classification points re-checked (one genuine
re-classification per the most affected streams), and ~68 missing build/fan-out steps added — **~200
in-place corrections total, zero blocking design reworks.**

The single recurring systemic defect was **stale `docs/strategy-audit/*.md` gap-source line numbers**
(the audit `.md` files were regenerated with v2/v3 rows after the plans captured them) — the
content→feature mapping is correct everywhere, but the pointers drifted ~2–25 lines; every plan now
carries a "find by row text" warning plus corrected load-bearing cites.

Each stream still carries owner-decision **Open Points** (point bands, seeded risk-cap defaults, the
economic-event feed source, the equity-capture sizing/cadence, etc.). These are build-time decisions with
a recommended safe default each — they gate *arming on a real strategy*, not *writing the code*.

---

## 2. Readiness table (per stream)

`bad-cites` = stale/wrong source citations corrected · `parity` = parity-classification points re-checked
(incl. any re-classification) · `missing` = under-specified build/fan-out steps added. Both passes
returned `sound-with-open-points` for every stream.

| Stream | Plan | Bad-cites fixed | Parity concerns | Missing steps | Final verdict |
|---|---|---:|---:|---:|---|
| Trade management — targets / trailing / SL / exits | [trade-management-exits.md](trade-management-exits.md) | 12 | 3 | 7 | sound-with-open-points |
| OI fidelity — soft dots → hard gates + OI primitives | [oi-fidelity-gates.md](oi-fidelity-gates.md) | 18 | 5 | 5 | sound-with-open-points |
| Macro confluence — VIX / Dow / FII-DII / constituent | [macro-vix-global-fii.md](macro-vix-global-fii.md) | 11 | 4 | 4 | sound-with-open-points |
| IV fidelity — per-strike slope / abs band / both-flat | [iv-fidelity.md](iv-fidelity.md) | 6 | 5 | 7 | sound-with-open-points |
| RSI — multi-TF caps / per-strategy bands / cool-off | [rsi-multi-timeframe.md](rsi-multi-timeframe.md) | 6 | 5 | 7 | sound-with-open-points |
| Indicators — Supertrend / Volume-MA / patterns / trendline | [indicators-supertrend-volume.md](indicators-supertrend-volume.md) | 5 | 2 | 5 | sound-with-open-points |
| Strike / premium selection — band / recenter / skew | [strike-premium-selection.md](strike-premium-selection.md) | 2 | 2 | 7 | sound-with-open-points |
| VWAP-distance + probability-graded sizing | [vwap-and-sizing.md](vwap-and-sizing.md) | 9 | 2 | 4 | sound-with-open-points |
| Stock universe + Market-Movers *(foundational)* | [stock-universe-market-movers.md](stock-universe-market-movers.md) | 1 | 4 | 5 | sound-with-open-points |
| Risk governance — daily caps / 5-account ledgers / journal | [risk-governance.md](risk-governance.md) | 3 | 3 | 5 | sound-with-open-points |
| Per-strategy controls + bearish/PE seeding + BTST + Sensex | [per-strategy-controls-seeding.md](per-strategy-controls-seeding.md) | 4 | 5 | 5 | sound-with-open-points |
| Event/time gates + backtest-fidelity rails + SPAN sell legs | [event-time-backtest-span.md](event-time-backtest-span.md) | 8 | 7 | 7 | sound-with-open-points |
| **Totals** | | **85** | **47** | **68** | **12/12 sound-with-open-points** |

---

## 3. Notable corrections

The audits closed real problems. The load-bearing ones, by category:

### Parity holes closed (a `[P]` change that would have silently broken a golden / moved a live signal)

- **Shared-scorer denominator break — the single most important catch (per-strategy-controls, §3.3–§3.6).**
  `ConnectTheDotsScorer.score(...)` sums `den` over **every** dot unconditionally. The plan's "soft
  confirming dots" would have shifted `den` for **every** scalper — breaking every existing scalper golden
  and moving live emissions. Fixed: dots must be **conditionally appended only when the tag is armed**, never
  added to the unconditional dot list. (Same FU2 "Dow-dot denominator" failure mode; flagged again as the
  correct pattern in OI-fidelity, IV-fidelity, indicators, and vwap-and-sizing.)
- **`gate.all` rail tightening is `[P]`, not `[S]` (event-time, §3.2).** Editing a scalper YAML's `gate.all`
  is **not** backtest-only — `SignalEngine` runs `EntryEvaluator.evaluate(...)` on the live definition *and*
  `BacktestRunner` compiles the same YAML, so it moves both live and backtest emission. Re-routed through a
  backtest-only `gate_override` / `*-bt.yaml` so the 36 live YAMLs stay byte-identical.
- **Golden-variant wired in the wrong harness (event-time, §3.4).** The `btst-preclose` golden runs through
  `TickwiseGoldenRunner.run`, **not** `SignalEngine`; the Friday-skip put only in `SignalEngine` would have
  been a false pass. Fixed: gate `avoidFriday()` in **both** paths (default-OFF keeps the existing golden
  byte-identical). Pass-2 strengthened this — `BacktestParityTest` delegates to the same runner, so the
  single edit covers all three consumers.
- **`drasticFloor` is `[P]`, not "[S] calibration" (OI-fidelity, P10).** It feeds the SOFT scorer on every
  scalper's unarmed path; retyping it to a per-index `Map` is a non-additive record-component change. Kept
  scalar (DB-tuned) for this stream.
- **`Macro.vixRising` producer read carved out (macro, §3.1).** Populating it unconditionally would flip the
  existing `vix` soft dot for every scalper; gating the producer read on `vixEnabled` (with a producer-level
  tripwire `macro(...,false) ⇒ vixRising==null`) is the load-bearing parity decision — confirmed sound.

### Citation drift (corrected; never changed a design conclusion)

- **Systemic stale gap-source line numbers** across all 12 streams (the `docs/strategy-audit/*.md` files
  drifted after capture). Content mapping correct; pointers ~2–25 lines off. Every plan now warns + corrects.
- Service mislocation: `MarketOiClient` is a strategy-signal-service HTTP **reader**, not a market-data
  producer (IV-fidelity) — narrowed the target-service list, removed a phantom market-data change.
- `ivFactor` sign trap (IV-fidelity): the index `ivFactor` is *falling-IV → bullish*; the new per-strike
  `iv_slope` dot is the **opposite** (rising-IV-in-strike → bull). Re-anchored to the deck row; added a
  warning against a future "fix" that would wire the wrong sign.
- `/options/trending` already declares `interval` (OI-fidelity) → passing it drifts no springdoc contract;
  a phantom `ContractCaptureTest` step removed. `/equity/index-contribution` already exists with param
  `name` (macro) → constituent gate adds no path, no contract drift.
- `bigOi()` ranks by `|oiChange|` (movers), **not** absolute standing OI (OI-fidelity) → the OI-wall S/R
  must come from the per-strike ladder, not `bigOi().items[0]`.

### Missing dependencies / build steps added (would fail at compile or first run)

- **Higher-TF series are NOT auto-warmed (RSI, CRITICAL).** A signal-future `RSI@5m`/`RSI@1d` is unwarmed →
  `IndicatorBank.build` throws every bar. Added a reload-time warm path + PR-3a; flagged that this same fix
  changes the LIVE emission of the already-shipped connect-the-dots scalper if `bias60m@1h` is currently
  throwing (Open Point 9 — owner sign-off, separate from golden byte-identity).
- **Record-constructor arity fan-outs** under-counted across nearly every stream: the positional
  `ScalperConfig` (16 fields, **8** `new ScalperConfig(...)` test literals), `ScalperGateContext.Chart`
  (**17** call sites: 1 prod + `ScalperConfluenceGateTest` 8 + `ConnectTheDotsScorerTest` 4 +
  `ScalperGatesTest` 4), `ScalperOiProps.defaults()` (11→N nulls), `Macro` / `Oi` literals, and the
  `Catalog` interface (**5** fakes) — each a compile break the plans hadn't enumerated.
- **Engine-side instrument swap, not seam-side (stock-universe, MAJOR).** The per-stock pick + per-instrument
  `IndicatorBank` rebuild must happen engine-side in `scalperEntry` (the gate holds no `seriesStore`); added
  a hard prerequisite — the picked stock's candle series must be **subscribed** (distinct from the snapshot
  capture) — plus an instrument-master sync pre-flight (fail-silent if skipped).
- **Event-lockout date-bug (event-time, soundness).** The snippet queried `eodDate` (yesterday's calendar);
  the "high-severity event LATER today" gate needs the live bar's IST `tradeDate`. Would have shipped a
  wrong-by-one-day gate.
- **Cross-service compile + verify (event-time).** Adding a `Session` field breaks a literal in
  **backtest-service** too → `-pl services/backtest-service -am verify` required (a third Maven module the
  plan hadn't named).
- **Auto-journal must be `@TransactionalEventListener(AFTER_COMMIT)` (risk-governance)** or a journal failure
  rolls back the trade close; plus a NULL-`subaccount_idx` backward-compat fallback (the biggest correctness
  risk — 4 existing ITs insert idx-less trades) and a missing FE render block for the two new caps.
- **`vwap-break-volume-qualified` is NOT YAML-only (indicators, CRITICAL).** A conjunction `signal_exit`
  rule is rejected by both the JSON-schema pattern and the single-leaf `StrategyCompiler` → needs a typed
  seam primitive or an engine+schema grammar extension (PR re-rated S→M).

---

## 4. Parity-safety confirmation (every signal-affecting change)

**Confirmed across all 12 streams, on both passes:** every signal-affecting change is **tag-gated,
default-OFF, and rides a new generate-once golden where it touches the deterministic harness** — the
parity guarantee holds.

- **The firewall is real and re-verified byte-for-byte.** `GoldenDeterminismTest.FEATURES` and
  `BacktestParityTest.FEATURES` are the **identical** 5 pure-engine vectors
  `{ema-crossover, optional-indicator-activation, btst-preclose, exit-intrabar, context-series}` — **no
  scalper / no options strategy** in either. Neither harness instantiates `ScalperConfluenceGate` /
  `ConnectTheDotsScorer`, so no tag-gated scalper change can perturb a golden.
- **Default-OFF is grep-proven.** No shipped YAML (the 36 scalper variants) carries any new tag in any infra
  PR — confirmed by grep in each stream. Every new behaviour is a `ScalperConfig.requireXxx` flag + a
  `tags.contains("<tag>")` parse + an early-return hard gate, absent unless explicitly armed.
- **`[P]` changes that DO run on the deterministic harness carry a new FEATURE.** The trade-management
  `points`/`indicator` exit types and the strike/premium backtest band selector each ride a NEW
  generate-once golden; the 5 frozen goldens declare none of the new types, so the added `switch` case /
  optional param is inert (re-run + assert byte-identity, **never regenerate**).
- **Live-seam-only `[P]` changes are parity-safe by the firewall** (the scalper seam never runs on the
  golden/parity harness) and carry the seam-test triple. **`[S]` advisory changes** (`suggested_qty`,
  `scalper_detail`, account-side rails) are stamped **outside** the frozen `GoldenSignalsJson` /
  `ScoreBreakdownJson` serializers — invisible to the goldens.
- **No `[S]` is secretly signal-moving and no `[P]` is missing its tag/golden** — independently re-derived
  per stream. The one genuine re-classification (`drasticFloor` [S]→[P], OI-fidelity) was caught and the
  field kept scalar to preserve byte-identity.

**Bottom line:** `GoldenDeterminismTest` + `BacktestParityTest` (+ `OptionsPremiumGoldenTest` where the
premium replay is touched) stay byte-identical on every PR across all 12 streams, with no fixture
regenerated. Arming any tag on a real strategy remains a deferred, owner-driven, forward-paper step.
