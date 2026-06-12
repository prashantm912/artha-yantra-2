# ArthaYantra 2.0 — Stage C: Strategy Engine + Signals (MVP)

**Stage:** C — Strategy engine + signals
**Plan macro-phase:** Phase 2 ("Strategy engine + signals") — *ends at the MVP gate*
**Phases covered:** 18–27
**Prerequisite stages:** A (Foundations — compose/auth/mock substrate, gateway STOMP bridge, Flyway, Maven reactor, `common-web`/`market-calendar` libs, CI) and B (Market-data spine — instruments, candles hypertable + continuous aggregates, mock tick feed, options/Greeks, system status). Stage C consumes the mock candle stream, the instruments master, and the gateway WS bridge built in A/B.
**Common reference:** [ARTHAYANTRA_2_COMMON_REFERENCE.md](ARTHAYANTRA_2_COMMON_REFERENCE.md) — app-wide conventions, the full ADR (D1–D18 + amendments A1–A13), the CD-1..CD-17 default decisions, the canonical stack-version table, the error-code taxonomy (COMMON §8.3), the parameter-path whitelist grammar (COMMON §12.5), the monorepo layout (COMMON §10.1), and the phase index (COMMON §5).

**Stage goal.** Turn a versioned YAML document into a live signal in the browser, end-to-end, on the credential-free mock stack. Stage C builds the platform's center of gravity: `strategy-schema/v1` (the frozen authoring contract), the shared `strategy-engine` JAR (ta4j indicators + normalizers + the normative composite scoring + the score-breakdown contract), `strategy-signal-service` (versioned registry with publish/rollback/diff/audit, plus the live signal engine), the `index_constituents` accrual that seeds point-in-time universe history, the OpenAPI contracts + TS client, and the Angular SPA scaffold + signals page. **The Stage-C exit is the MVP gate** (plan §15.4): mock tick → candle → published EMA-crossover strategy → signal with per-indicator score breakdown → STOMP push → visible on a minimal Angular signals page, on one `ay up`, with zero credentials.

> **Supersession note (applies throughout).** Where ADR amendments A1–A13 (ratified 2026-06-12) conflict with older D-section or plan text, the **amendment governs**. Stage C is materially shaped by **A1** (composite-score normalization + optional-indicator activation semantics — stated normatively in §C-2.5 below).

---

## Part 1 — File map & how this stage is organized

This file is self-contained for implementing Phases 18–27 together with COMMON. It has three parts after this map:

- **Part 2 — Design reference** (`§C-2.*`): the inlined plan/review content these phases need at implementation time — the complete `strategy-schema/v1` (§C-2.1), rule grammar + score-breakdown contract (§C-2.4–2.6), both annotated YAML examples (§C-2.7), costs/fills + extensibility answers (§C-2.8–2.9), lifecycle/versioning (§C-2.10), the CRUD API table (§C-2.11), the `strategy` schema column tables (§C-2.12), the signal-engine spec + Flow 5 (§C-2.13–2.14), `index_constituents`/S8 (§C-2.15), validation/security (§C-2.16), contract & golden-determinism testing (§C-2.17–2.19), Playwright E2E (§C-2.20), and the frontend foundations (§C-2.21–2.27).
- **Part 3 — Phase specs** (`§C-3.*`): Phases 18–27 copied near-verbatim from the phases doc, cross-references rewritten to point at Part 2 / COMMON.
- **Part 4 — Stage exit gate**: the plan §15.2 Phase-2 acceptance row inlined as the MVP checklist (the S5 Friday gate ritual input).

**Repo locations touched this stage** (COMMON §10.1 — cite, not re-derived): `libs/strategy-schema/`, `libs/strategy-engine/`, `services/strategy-signal-service/`, `services/market-data-service/` (the `index_constituents` slice only), `frontend-ui/`, `contracts/`, `e2e/`, plus `deploy/flyway/{strategy,marketdata}/` migrations and `.github/workflows/`.

**Stack versions** (cite COMMON canonical stack-version table; the ones load-bearing here): Java 21 / Spring Boot 3.5.x / Spring Modulith 1.3.x; ta4j 0.22.x; SnakeYAML (SafeConstructor, CD-5); `networknt/json-schema-validator` draft 2020-12 (CD-4); springdoc-openapi 2.x + `openapi-diff` + `openapi-typescript` (CD-8); Angular 21.x / PrimeNG 21.x / `@ngrx/signals` 21.x / TypeScript 5.9 / RxJS 7.8; `@stomp/stompjs` 7.x; nginx 1.27-alpine; Vitest 3.x + jsdom; Playwright 1.x; JUnit 5 + AssertJ + Mockito + Testcontainers 1.20.

**Default decisions in force this stage** (cite COMMON §4): **CD-3** gateway STOMP-subset codec (built in Stage A — Stage C's WS client speaks to it); **CD-4** networknt json-schema-validator draft 2020-12; **CD-5** SnakeYAML SafeConstructor; **CD-8** committed OpenAPI specs + diff gate + `openapi-typescript`; **CD-11** editor form-mode v1 is metadata + indicator weight/optional toggles only (scope-reduction parked at Phase 36 in Stage E — flagged here because the schema's form-shaped fields exist now); **CD-14** ~5k-row mock instrument-dump fixture (Stage B; the swing example's universe resolves against it); **CD-16** Redis `volatile-lru` (the signal engine's caches carry TTLs); **CD-17** backtest-service ships without Modulith (not built this stage — noted so the parity peer is understood).

---

## Part 2 — Design reference (inlined plan/review content)

> Every section here is tagged with its source breadcrumb so verifiers can trace provenance. The four source docs will be deleted; everything load-bearing for Phases 18–27 is reproduced below.

### C-2.1 Strategy configuration schema — overview & blocks `[plan §7.1]`

YAML is the canonical authoring format; the editor (Monaco + monaco-yaml, Stage E) binds the published JSON Schema for inline autocomplete and validation, and the backend re-validates on every save with the same schema. Stored form is canonicalized JSONB + SHA-256 checksum (`strategy.strategy_versions`, §C-2.12). v1 hard-coded its three strategies across four service classes, left the `strategies` table decorative, and could not sweep a single parameter; the redesign inverts that — a strategy is a **versioned YAML document** validated by JSON Schema (`strategy-schema/v1`, ADR D18), executed by **one shared `strategy-engine` JAR** embedded in both `strategy-signal-service` (live) and `backtest-service` (replay) so a backtest provably exercises the identical rule code that produces live calls (ADR D6/D7, verified by the golden-vector tests §C-2.19).

**Top-level blocks:**

| Block | Purpose | Key fields |
|---|---|---|
| header | Identity + versioning | `schema`, `id`, `name`, `version` (semver), `description`, `author`, `tags[]`, `enabled` |
| `universe` | What instruments the strategy watches | `mode: explicit \| index_constituents \| options_of_underlying \| futures_of_underlying` *(last mode: A7)*, filters, strike/expiry selectors, `futures{contract, roll_days_before_expiry}` *(A7)* |
| `timeframes` | Bar intervals consumed | `primary` (signal clock), `additional[]` (confirmation indicators); interval vocabulary includes `1w` *(A7)* |
| `indicators[]` | Indicator instances | `name` (engine registry id), `alias` (unique ref), `timeframe`, `params{}`, `weight`, `optional`, `normalize{}`, optional `instrument{}` context override *(A7)* |
| `entry_rules` | When to open | `gate` (hard boolean grammar), `scoring` (composite threshold), `direction` |
| `exit_rules[]` | When to close | typed rules: `stop_loss`, `take_profit`, `trailing_stop`, `time_stop`, `signal_exit` |
| `risk` | Sizing + limits + session | `position_sizing`, `max_positions`, `max_daily_loss_pct`, `session{}` (incl. `pre_close_at`, `fill_timing`, `exit_intrabar` — A7) |
| `backtest` | Defaults + `optimize` block | data window/costs defaults (incl. optional `slippage_bps` and statutory `fees{}` — §C-2.8); `optimize.parameters[]`, `method`, `max_trials`, `objective` (incl. optional `fold_aggregation: mean \| min \| mean_minus_std`), `walk_forward` |

> The keys flagged **(A7)** above are 2026-06-12 owner-selection freeze-time additions (ADR amendment A7, COMMON §6) — present and validated at the Phase 18 freeze; obligation/consumer detail in §C-2.2. `[FP-5, FP-6, FP-8, FP-11a, FP-19, owner selection 2026-06-12]`

### C-2.2 Schema scope — the keys that must exist *at the freeze* `[plan §7.1, review S1A/Q1/BPB]`

`strategy-schema/v1` **freezes at the Stage-C exit gate** (Phase 27 / plan §15.2 Phase-2 gate). Several keys whose backend *consumers* land in later stages must nonetheless be present and validated in the schema now, so no post-freeze schema-shape change occurs. The review (timeline ledger §5) is explicit that these are not absorbed silently — they are freeze-time obligations:

- `backtest.defaults.costs.slippage_bps` and the statutory `costs.fees{}` schedule (Q1; consumers land Stage D Phase 29 / Stage F Phase 43) — §C-2.8.
- `backtest.optimize.objective.fold_aggregation: mean | min | mean_minus_std` (S1A; consumer lands Stage D Phase 32) — §C-2.5/§C-2.7.
- `backtest.optimize.walk_forward{train_days, test_days, step_days, anchored}` (BPC; consumer lands Stage D Phase 31).
- `entry_rules.scoring.{optional_min_score, optional_gate_margin}` (A1/BPB; consumer is the engine in this stage, Phase 20) — §C-2.5.

**A7 freeze-time additions (ratified 2026-06-12 from the owner feature selection — ADR amendment A7, COMMON §6).** The following keys are likewise schema-present and validated at the Phase 18 freeze; their consumers land in the phases noted (same freeze-time-obligation pattern as above):

- `timeframes` accepts `1w` (consumers: Stage B Phase 10's `candles_1w` continuous aggregate and the Phase 19 BarSeries/multi-timeframe adapter) `[FP-8, owner selection 2026-06-12]`.
- `risk.session.pre_close_at` (string `"HH:mm"` IST, default `"15:20"`, meaningful for `style: btst`) and `risk.session.fill_timing: next_open | at_close` (default `at_close` when `style: btst`, else `next_open`) (consumers: Phase 23's pre-close evaluation clock, Stage D Phases 29/30 `FillSimulator` + replay — A9) — §C-2.11 `[FP-6, owner selection 2026-06-12]`.
- `risk.session.exit_intrabar: true|false` (default `true` when the primary timeframe > 1m) (consumers: Phase 23's live 1m exit-level evaluation + Stage D Phases 29/30 — A9) — §C-2.11 `[FP-5, owner selection 2026-06-12]`.
- Optional indicator-level `instrument: {exchange, tradingsymbol}` override — the cross-instrument context-series mechanism (consumers: Phases 19/20 engine, Phase 23 subscriptions, Stage D Phase 30 replay pre-flight) — §C-2.3 `[FP-19, owner selection 2026-06-12]`.
- `universe.mode` gains `futures_of_underlying` with `futures: {contract: front_month | next_month, roll_days_before_expiry: int, default 1}` (consumers: Phase 23 live resolution + roll re-subscribe, Stage F Phase 44 universe resolver, Stage D replay over the Stage B Phase 15B continuous series — A11) `[FP-11a, owner selection 2026-06-12]`.

**Acceptance-FAIL guard for Phase 18 (plan):** making the indicator-name vocabulary enum *binding* is a failure (it must stay advisory — Q2, §C-2.9); any post-phase schema-shape edit without a recorded ADR amendment is a failure. (The A7 keys above are **part of the freeze itself** — ratified pre-freeze, 2026-06-12 — not a post-freeze edit.)

### C-2.3 `indicators[]` block — shape, normalize, weights `[plan §7.1]`

Each indicator instance carries:

- `name` — **engine-registry id** (string). Validated structurally as an identifier *pattern* plus an advisory known-name `anyOf` enum (the registry of Phase 19: `EMA, SMA, RSI, VWAP, ADX, MACD_HIST, SUPERTREND, VOLUME_RATIO, OI_CHANGE_PCT, ATR`; extended 2026-06-12 with the session-level and context families `ORB_HIGH, ORB_LOW, PREV_DAY_HIGH, PREV_DAY_LOW, PREV_DAY_CLOSE, DAY_HIGH, DAY_LOW, GAP_PCT, RS_VS_INDEX, VIX_LEVEL` — additive vocabulary per Q2, no schema v2 `[FP-18, FP-20, FP-14, owner selection 2026-06-12]`). Whether a `name` actually exists is a **server-side registry check at save/publish**, not a schema violation (Q2 — §C-2.9).
- `alias` — unique within the document; the stable reference used by gate rules, parameter `path` selectors, and the score breakdown.
- `timeframe` — must be one of the declared `timeframes` (`primary` or a member of `additional[]`).
- `instrument` — **optional context-series override** `{exchange, tradingsymbol}` (A7, ratified 2026-06-12): when present, the indicator evaluates against the *declared* instrument's series at its declared `timeframe` instead of the signal instrument's — the mechanism behind market-context rules like "BTST longs only when NIFTY > 200-DMA and VIX < 20" (`RS_VS_INDEX` and `VIX_LEVEL` require it). Whether the override instrument exists is a **publish-time check against the instruments master** (same server-side posture as the registry name check — §C-2.9; check lands Phase 21). The engine keeps a small **shared context-series cache** keyed `(instrument, interval)` so N strategies referencing NIFTY 1d cost one series, not N; the live engine adds context instruments to its subscription set (Phase 23 — series inputs only, never signal-emitting symbols), and backtest pre-flight coverage extends to them (Stage D Phase 30). `[FP-19, owner selection 2026-06-12]`
- `params{}` — indicator-specific (e.g. `period`, `multiplier`, `fast/slow/signal`, `lookback`).
- `weight` — numeric `≥ 0`.
- `optional` — boolean (default false). Optional indicators *reinforce* but can never gate or carry a signal alone (A1 — §C-2.5).
- `normalize{}` — maps the raw indicator value to a score `s ∈ [0,1]`. Built-in normalizers: `linear` between two bounds (`from`,`to`), `step` bands, `direction` ± (±1 mapped to 1/0), and indicator-native presets (`rsi_momentum`: 50→0, 70→1 on the long side). Non-gate indicators that contribute to the composite declare a `normalize` mapping.

### C-2.4 Rule grammar `[plan §7.1]`

Two complementary mechanisms, both evaluated **bar-close on the `primary` timeframe**:

1. **Gate rules** — hard boolean preconditions composed with `all` / `any` / `not` nesting. Leaf rules are either **typed** (`crossover: {fast, slow}`, `crossunder: {fast, slow}`) or **threshold expressions** over indicator aliases and built-in series (`close`, `volume`, `vwap`): e.g. `"rsi_1m < 70"`, `"volume_ratio > 1.5"`. Comparison operators `> >= < <= == !=`; operands are aliases, built-ins, or numeric literals. **No arbitrary arithmetic in v1 of the schema** — that keeps the grammar trivially parseable and the JSON Schema able to lint operand names.
2. **Composite scoring** — each non-gate indicator declares a `normalize` mapping from raw value to a score `s ∈ [0,1]`. The engine computes the weight-normalized composite (§C-2.5). Entry fires when **all gates pass AND `composite ≥ threshold`**. The composite value is emitted as **signal strength** (0–1), and every per-indicator `wᵢ·sᵢ` contribution is persisted with the signal for the reasoning UI (§C-2.26) — identically in live and backtest.

Parameter `path` syntax (used by `backtest.optimize.parameters[].path`) is JSONPath-like with **alias/type selectors** (`indicators[alias=ema20]`, `exit_rules[type=stop_loss]`) so paths survive reordering; **bare positional indices** (`indicators[0]`) from the owner's original sketch remain **accepted but linted by the editor**. The closed grammar is in COMMON §12.5 (cite) and restated in §C-2.16 below.

### C-2.5 Composite formula & optional-indicator activation — **NORMATIVE (ADR amendment A1)** `[plan §7.1 / ADR A1 / Flow 5 §3.4]`

> **A1 governs.** This supersedes D18's literal `sum(weight × normalized_score)` phrasing and pins "reinforcement only" as *optional indicators can only activate, never gate or carry a signal alone*. The BPB score-breakdown contract (§C-2.6), Flow 5 (§C-2.14), and the §10.3 golden-test row all align to **exactly this formula and nothing else**.

The engine computes the **weight-normalized** composite:

```
composite = ( Σ_required wᵢ·sᵢ + Σ_activated-optional wⱼ·sⱼ )
            / ( Σ_required wᵢ + Σ_activated-optional wⱼ )
```

An **optional** indicator is *activated* (counted in **both** numerator and denominator) only when **(a)** its own score `sⱼ ≥ optional_min_score` (default **0.6**) **AND (b)** the **required-only** composite already `≥ threshold − optional_gate_margin` (default **0.15**). So soft indicators can push a borderline setup over the line but can never veto or carry a signal alone — exactly the "weaker indicators reinforce, strong signals override" semantics the owner asked for.

- Required indicators always contribute (numerator and denominator).
- An optional indicator that fails (a) has `activationReason = SCORE_BELOW_MIN`; one that fails (b) has `activationReason = MARGIN_NOT_MET`; an activated optional has `ACTIVATED`; a required one has `REQUIRED` (§C-2.6).
- `entry_rules.scoring` carries `threshold`, `optional_min_score` (default 0.6), `optional_gate_margin` (default 0.15).
- The optional `objective.fold_aggregation: mean | min | mean_minus_std` knob (S1A) lives under `backtest.optimize.objective`; it is **schema-present at the freeze** but its consumer is the optimizer in Stage D — Stage C only validates its presence.

### C-2.6 Score-breakdown contract — **the BPB single-record design** `[plan §7.1 / review BPB / ADR A1]`

The per-indicator breakdown that the reasoning panel (§C-2.26) renders is a **single record type in the strategy-engine JAR** (`ScoreBreakdown`), serialized **identically** by the live signal engine (persisted to `strategy.signals.score_breakdown`, returned by `GET /api/v1/signals/{id}`, pushed on the `signals` topic) and by the backtest engine (per-trade contributions on `GET /api/v1/backtests/{id}/trades`, Stage D) — **one shape, two producers**, so the parity check is mechanical. A golden-vector test (§C-2.19) asserts **byte-identical** breakdowns live vs. replay for the same YAML + candles. The breakdown contract **freezes in Phase 20**, before any consumer exists.

| Field | Meaning |
|---|---|
| `composite`, `threshold`, `passed` | Emitted signal strength (0–1), config threshold, `composite ≥ threshold` |
| `requiredComposite` | Required-only composite — the value tested against `threshold − optional_gate_margin` for optional activation |
| `optionalMinScore`, `optionalGateMargin` | Echoed config values; the panel explains activation without re-fetching the version |
| `weightDenominator` | `Σ required wᵢ + Σ activated-optional wⱼ`; **renderer invariant:** `composite = Σ contributions / weightDenominator` |
| `gate` | Recursive `all`/`any`/`not` tree mirroring the YAML; leaves carry `{rule, passed, operands: {alias → rawValue}}` — the pass/fail checklist with actual values |
| `indicators[]` | `{alias, name, timeframe, score s∈[0,1], weight, contribution = w·s, optional, activated, activationReason, rawValue, params}`; `activated` is `true` for every required indicator; `activationReason ∈ REQUIRED \| ACTIVATED \| SCORE_BELOW_MIN \| MARGIN_NOT_MET` |

Timestamps in the enclosing signal DTO carry the `+05:30` offset (IST convention); prices stay NUMERIC-backed; the DTO retains the engine-pin triple `(strategyId, version, checksum)` per §C-2.10.

> **Provenance note (BPB).** The review's original DTO contradicted the plan's scoring semantics (its example numbers failed the §7.1 normalized average: `(0.85+0.52)/1.8 ≈ 0.761`, not its stated `0.72`) and omitted the weight denominator, the required-only composite, and the gate tree its own component template read. The final design above resolves the formula divergence via **ADR amendment A1**, not a silent plan edit.

### C-2.7 Annotated YAML example 1 — options scalping (NIFTY weekly ATM buying) `[plan §7.1 — verbatim accept fixture]`

```yaml
schema: strategy-schema/v1
id: nifty-atm-scalper          # stable slug; never changes across versions
name: "NIFTY ATM Scalper"
version: 1.2.0                  # semver; bumped automatically on save-as-draft
description: "Momentum scalp on nearest-weekly ATM NIFTY options, long premium only"
author: owner
tags: [options, scalping, intraday, nifty]
enabled: true

universe:
  mode: options_of_underlying
  underlying: { exchange: NSE, tradingsymbol: "NIFTY 50" }   # stable key, never token
  options:
    expiry: nearest_weekly      # nearest_weekly | nearest_monthly | offset: N (Nth expiry out)
    strikes: { selector: atm_window, width: 2 }   # ATM ± 2 strikes; step from instrument master
    option_types: [CE, PE]      # engine buys CE on long bias, PE on short bias

timeframes:
  primary: 1m                   # signal evaluation clock
  additional: [5m]              # confirmation indicators read the 5m continuous aggregate

indicators:
  - { name: EMA,        alias: ema_fast,   timeframe: 1m, params: { period: 9 },  weight: 1.0 }
  - { name: EMA,        alias: ema_slow,   timeframe: 1m, params: { period: 21 }, weight: 1.0 }
  - { name: VWAP,       alias: vwap_1m,    timeframe: 1m, weight: 1.0 }
  - { name: RSI,        alias: rsi_1m,     timeframe: 1m, params: { period: 14 }, weight: 0.8,
      normalize: { type: rsi_momentum } }          # built-in: 50→0, 70→1 long side
  - { name: SUPERTREND, alias: st_5m,      timeframe: 5m, params: { period: 10, multiplier: 2.0 },
      weight: 1.2, normalize: { type: direction } } # +1 if uptrend, mapped to 1/0
  - { name: VOLUME_RATIO, alias: vol_x,    timeframe: 1m, params: { lookback: 20 }, weight: 0.6,
      optional: true, normalize: { type: linear, from: 1.0, to: 3.0 } }
  - { name: OI_CHANGE_PCT, alias: oi_chg,  timeframe: 5m, params: { lookback: 6 }, weight: 0.5,
      optional: true, normalize: { type: linear, from: 0.0, to: 5.0 } }   # short-covering tell

entry_rules:
  direction: both               # long bias→buy CE, short bias→buy PE (premium-buying only)
  gate:                         # all hard conditions must hold at bar close
    all:
      - crossover: { fast: ema_fast, slow: ema_slow }
      - "close > vwap_1m"       # underlying trading above VWAP
      - not: "rsi_1m > 75"      # don't chase exhaustion
  scoring:
    threshold: 0.65
    optional_min_score: 0.6     # soft indicators count only when themselves convincing
    optional_gate_margin: 0.15  # ...and only when required score is already near threshold

exit_rules:
  - { type: stop_loss,    params: { basis: premium_pct, value: 20 } }   # −20% of paid premium
  - { type: take_profit,  params: { basis: premium_pct, value: 35 } }
  - { type: trailing_stop, params: { basis: premium_pct, activate_at: 20, trail_by: 10 } }
  - { type: time_stop,    params: { max_bars: 15 } }                    # scalp goes stale fast
  - { type: signal_exit,  params: { rule: "crossunder(ema_fast, ema_slow)" } }

risk:
  position_sizing: { method: premium_budget, params: { budget_inr: 15000 } }  # lots = floor(budget/(premium×lot))
  max_positions: 2
  max_positions_per_underlying: 1
  max_daily_loss_pct: 3.0       # of equity; engine stops emitting entries for the day
  session:
    style: intraday             # intraday | btst | expiry_day | positional
    window: { from: "09:20", to: "15:00" }   # IST; first 5m skipped for opening noise
    square_off: "15:12"         # forced exit before 15:30 close
    expiry_day: { allowed: true, window: { from: "09:20", to: "14:30" } }

backtest:
  defaults: { interval: 1m, lookback_days: 60, initial_capital: 200000,
              costs: { per_lot_inr: 25, slippage_ticks: 1 } }
  optimize:
    parameters:
      - { path: "indicators[alias=ema_fast].params.period", range: [5, 13], step: 1 }
      - { path: "indicators[alias=ema_slow].params.period", range: [15, 34], step: 1 }
      - { path: "entry_rules.scoring.threshold",            range: [0.55, 0.80] }
      - { path: "exit_rules[type=stop_loss].params.value",  choices: [15, 20, 25, 30] }
    method: tpe                 # grid | random | tpe | nsga2  (ADR D18)
    max_trials: 300
    objective: { metric: sharpe, direction: maximize }
    constraints: { min_trades: 40 }          # trials below this are marked invalid
    walk_forward: { train_days: 40, test_days: 10, step_days: 10, anchored: false }
```

### C-2.8 Costs & fills — the Q1 answer `[plan §7.1 / ADR A5(Q1)]`

`backtest.defaults.costs` keeps its existing brokerage legs (`per_lot_inr` for options, `pct_per_side` for equities) and slippage knob, with one addition: slippage may be expressed as `slippage_ticks` (integer ticks) **or** `slippage_bps` (basis points of fill price) — **at most one of the two** (the schema validator must reject both being set). When neither is set the engine applies **per-instrument-class fallbacks**: equities 5 bps; options `max(1 tick, half the quoted spread when bid/ask is known from the last tick or chain snapshot)`, degrading to 1 tick without a quote.

`costs` additionally accepts an **optional statutory fee schedule** `fees{}` — flat per-order brokerage plus side-aware percentage legs: STT on sell-side option premium, exchange transaction charge on premium, GST on brokerage + transaction charge, stamp duty on the buy side, SEBI turnover fee — defaulted from the current Zerodha/NSE schedule when omitted (`fees: {stt, exchange_txn, gst, stamp, sebi}` per Phase 18's schema). A flat per-lot cost knob understates the premium-proportional statutory charges on options, which is why the schedule exists.

The full `costs` block — brokerage legs, slippage, and fees — is consumed by the strategy-engine JAR's **`FillSimulator` port** (Stage D Phase 29) and applied **identically** in backtest replay and the paper ledger (same implementation, same JAR — parity by construction). The `slippage_bps` and `fees{}` keys are part of `strategy-schema/v1` **at the Phase 18 freeze** — validated from day one; their backend consumers land in Stages D and F — so no post-freeze schema-shape change occurs. (Resolves Q1; recorded as ADR amendment A5.) Statutory fee-schedule values are pinned at Stage-D implementation time (decisions-log open item §4.3).

### C-2.9 Schema extensibility — the Q2 answer `[plan §7.1 / ADR A5(Q2) / review Q2]`

`strategy-schema/v1` freezes the *shape* of `indicators[]`, **not its vocabulary**. `name` is validated structurally as an identifier pattern; whether a name exists is a **server-side registry check at save/publish**, and the JSON Schema's known-name enum is **advisory** (an `anyOf` of enum and pattern) so the editor autocompletes today's registry while unknown names surface as *warnings, not schema violations*. Adding indicator families later — including fundamentals (`PE_RATIO`, `EPS_TTM`, `DAYS_TO_EARNINGS`) when that use case is real — is therefore additive: **no `strategy-schema/v2`, no change to any existing version's canonical JSONB, hence no checksum churn**.

Fundamental *data* stays out of scope: **no stub categories and no placeholder evaluation** — a config naming an indicator the engine cannot evaluate is **rejected at publish, never silently null-scored** into a composite. (The review's "return null / NotYetImplemented" proposal was rejected: it would crash the engine or silently distort scores. There is no `strategies.schema_definition` column — that column does not exist.) What ships is the **vocabulary-extensibility note only**, codified at the Phase 18/27 freeze.

### C-2.10 Strategy lifecycle & versioning `[plan §7.2]`

Storage: `strategy.strategies` (identity row: slug id, name, current pointers) and `strategy.strategy_versions` (one **immutable** row per version: canonicalized JSONB config, SHA-256 checksum over the canonical bytes, semver, status, author, notes, `created_at`). An **append-only** `strategy_audit_log` records every mutating action (who — always the owner, what, when, from→to version, diff summary). Column tables in §C-2.12.

**Lifecycle state machine** (the diagram the Phase 21 IT asserts exactly):

```
[*] --> draft        : POST /strategies (v1.0.0)
draft --> draft      : PUT (new draft version, patch bump)
draft --> published  : POST /publish
published --> draft  : edit creates NEW draft version
published --> archived : POST /archive or supersession
archived --> draft   : POST /rollback (copy-forward as new draft)
draft --> [*]        : DELETE (drafts only)
```

**Rules:**

- **Versions are immutable.** `PUT` on a strategy never rewrites a published version; it creates a **new draft version** (auto patch-bump; author may set minor/major). **Checksum equality is the dedupe guard** — saving identical content is a no-op (`409 CONFLICT_NO_CONTENT_CHANGE`). Structurally: `UPDATE` only ever touches `status`/`published_at`.
- **Exactly one published version per strategy** at a time. `POST /publish` flips the target draft to `published`, moves the previously published version to `archived`, and writes the audit entry. Publish **re-validates** against `strategy-schema/v1` and refuses configs whose schema version the engine no longer supports.
- **Rollback = copy-forward, never history rewrite.** `POST /rollback {version: 1.1.0}` creates a *new* version whose config is a **byte-identical copy** of 1.1.0 (new semver, provenance note `rolled back from 1.4.0 to content of 1.1.0`), as a draft by default or published with `andPublish: true`. The audit trail stays linear.
- **Diff** is computed **server-side** between any two versions: a structured JSON diff (per-path add/remove/change, e.g. `indicators[alias=ema_fast].params.period: 9 → 11`) plus the raw YAML texts so the UI can render side-by-side Monaco diff. (Diff computed client-side is an explicit FAIL — Phase 21.)
- **Engine pinning.** The signal engine in strategy-signal-service loads only `published` versions of `enabled` strategies; each loaded instance pins `(strategy_id, version, checksum)` and every emitted signal records that triple, so a signal is forever traceable to the exact config that produced it. Publish/rollback emits a `strategy.changed` Redis pub/sub event; the signal engine **hot-swaps at the next bar boundary (never mid-bar)**. Backtests/optimizer trials reference an **explicit version** (defaulting to latest draft for quick-test, latest published otherwise) — never "whatever is current", which would destroy reproducibility.

### C-2.11 Annotated YAML example 2 — stock swing (NIFTY-100 EMA pullback) `[plan §7.1 — verbatim accept fixture]`

```yaml
schema: strategy-schema/v1
id: n100-swing-pullback
name: "NIFTY100 Swing Pullback"
version: 2.0.1
description: "Daily-bar trend-following swing entries on NIFTY 100 constituents, 5–30 day holds"
author: owner
tags: [stocks, swing, positional]
enabled: true

universe:
  mode: index_constituents
  index: "NIFTY 100"                      # resolved via market-data-service's constituents endpoint (§C-2.15)
                                          # — NSE Indices CSV feed; Kite's dump has no membership data
  filters: { min_avg_daily_volume: 500000, min_price: 50, exclude: ["NSE:IDEA"] }
  # mode: explicit would instead take instruments: [{exchange: NSE, tradingsymbol: RELIANCE}, ...]

timeframes: { primary: 1d, additional: [1h] }

indicators:
  - { name: EMA, alias: ema20, timeframe: 1d, params: { period: 20 }, weight: 1.0 }
  - { name: EMA, alias: ema50, timeframe: 1d, params: { period: 50 }, weight: 1.0 }
  - { name: RSI, alias: rsi14, timeframe: 1d, params: { period: 14 }, weight: 1.0,
      normalize: { type: linear, from: 40, to: 65 } }       # reward recovering momentum
  - { name: ADX, alias: adx14, timeframe: 1d, params: { period: 14 }, weight: 1.0,
      normalize: { type: linear, from: 15, to: 40 } }       # trend quality
  - { name: MACD_HIST, alias: macd_h, timeframe: 1d, params: { fast: 12, slow: 26, signal: 9 },
      weight: 0.7, optional: true, normalize: { type: direction } }
  - { name: VOLUME_RATIO, alias: vol_x, timeframe: 1d, params: { lookback: 20 },
      weight: 0.5, optional: true, normalize: { type: linear, from: 1.0, to: 2.5 } }

entry_rules:
  direction: long
  gate:
    all:
      - "ema20 > ema50"                   # established uptrend
      - "close > ema20"                   # price reclaimed the fast EMA after pullback
      - any:                              # at least one momentum confirmation
          - "rsi14 > 45"
          - crossover: { fast: ema20, slow: ema50 }
  scoring: { threshold: 0.60 }

exit_rules:
  - { type: stop_loss,     params: { basis: atr_multiple, value: 2.0, atr_period: 14 } }
  - { type: take_profit,   params: { basis: r_multiple, value: 2.5 } }   # 2.5× initial risk
  - { type: trailing_stop, params: { basis: atr_multiple, value: 3.0 } }
  - { type: time_stop,     params: { max_holding_days: 30 } }
  - { type: signal_exit,   params: { rule: "crossunder(ema20, ema50)" } }

risk:
  position_sizing: { method: atr_risk, params: { risk_pct_equity: 1.0 } }  # qty = (1% equity)/stop distance
  max_positions: 5
  max_daily_loss_pct: 4.0
  session: { style: positional, allow_overnight: true }    # btst style would force next-day exit window

backtest:
  defaults: { interval: 1d, lookback_days: 730, initial_capital: 1000000,
              costs: { pct_per_side: 0.05, slippage_ticks: 1 } }
  optimize:
    parameters:
      - { path: "indicators[alias=ema20].params.period", range: [10, 30], step: 2 }
      - { path: "indicators[alias=ema50].params.period", range: [40, 100], step: 5 }
      - { path: "exit_rules[type=stop_loss].params.value", range: [1.5, 3.0] }
    method: nsga2
    max_trials: 400
    objective:                              # NSGA-II is multi-objective (Optuna NSGAIISampler)
      - { metric: annualized_return, direction: maximize }
      - { metric: max_drawdown,      direction: minimize }
    constraints: { min_trades: 30 }
    walk_forward: { train_days: 360, test_days: 120, step_days: 120, anchored: true }
```

**Position-sizing methods supported in v1:** `fixed_quantity`, `percent_equity`, `premium_budget` (options), `atr_risk`, `kelly_fraction` (**hard-capped at 0.25**) — lot-size rounding, **never fractional lots**. The `session.style` enum (`intraday`/`btst`/`expiry_day`/`positional`) differentiates the owner's trade archetypes: `btst` permits exactly one overnight hold with a mandatory next-morning exit window; `expiry_day` activates only when the instrument's expiry equals the trading date (resolved against the instrument master and `MarketCalendar`).

**BTST pre-close semantics + intra-bar exits (A7/A9, ratified 2026-06-12).** The `btst` style gains two A7 session knobs. `risk.session.pre_close_at` (string `"HH:mm"` IST, default `"15:20"`): the engine evaluates BTST entries at this pre-close clock against a **deterministic pre-close bar view** assembled from cached 1m candles up to `pre_close_at` — built identically in live and replay (A9), so the evaluation never depends on when a cagg materialized. `risk.session.fill_timing: next_open | at_close` (default `at_close` when `style: btst`, else `next_open`): with `at_close`, the fill reference price is the **signal bar close**, so a BTST trade actually captures the overnight gap it exists to trade instead of filling at the next bar's open. `[FP-6, owner selection 2026-06-12]` Independently of style, `risk.session.exit_intrabar: true|false` (default `true` when the primary timeframe > 1m): when true, stop-loss/take-profit/trailing-stop levels are evaluated on **each closed 1m bar in BOTH live and replay** — parity at the 1m floor, deliberately NOT tick-level (A9). Replay drills into cached 1m candles, falling back to primary-bar high/low worst-of (gap-through fills at bar open) only where 1m coverage is missing, recording `touch_basis` per trade (Stage D — `backtest_trades.touch_basis`). Entry evaluation stays primary-bar-close. `[FP-5, owner selection 2026-06-12]`

### C-2.12 `strategy` schema column tables `[plan §6.4]`

> The `signals` table lands in Phase 23; `paper_orders`/`paper_positions`/`notification_events` are owned by strategy-signal-service but their *consumers* live in Stages E/F — they are reproduced here because the source groups them under schema `strategy`, and the Phase 21 migration creates `strategies`/`strategy_versions`/`strategy_audit_log` (plus the `notifications_enabled`/`notification_channel` columns on `strategies` now, to avoid a later checksum-adjacent migration; the notifier feature lands Stage E Phase 41).

**`strategies`** — identity + lifecycle head:

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `name` | TEXT UNIQUE | |
| `description`, `author` | TEXT | |
| `tags` | TEXT[] | GIN-indexed for `?tag=` filters |
| `enabled` | BOOLEAN | Master kill-switch for the signal engine |
| `notifications_enabled` | BOOLEAN | NOT NULL DEFAULT FALSE — per-strategy opt-in for signal pushes (notifier module, Stage E Phase 41) |
| `notification_channel` | TEXT NULL | CHECK IN (`NTFY`, `TELEGRAM`). Operational metadata deliberately on this row, **outside** the versioned YAML — `strategy-schema/v1` never carries notification settings, so toggling alerts or switching channels mints no `strategy_versions` row and perturbs no checksum (ADR D18) |
| `published_version_id` | UUID NULL → `strategy_versions` | Engines read only this (or an explicit version) |
| `created_at`, `updated_at` | TIMESTAMPTZ | |

**`strategy_versions`** — immutable content (ADR D18):

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK; `strategy_id` FK | |
| `version` | TEXT | Semver; UNIQUE `(strategy_id, version)` |
| `config_yaml` | TEXT | Author's original, byte-preserved for diff |
| `config` | JSONB | Canonicalized; validated against `strategy-schema/v1` |
| `schema_version` | TEXT | `strategy-schema/v1` |
| `checksum` | TEXT | SHA-256 of canonical JSONB; immutability guard |
| `status` | TEXT | `draft → published → archived` (CHECK) |
| `notes`, `created_by` | TEXT | Audit trail; optimizer writes `created_by='optimizer:{jobId}'` for promoted winners |
| `created_at`, `published_at` | TIMESTAMPTZ | Rows are never UPDATEd except `status`/`published_at` |

**`strategy_audit_log`** — **append-only** record of every mutating registry action (mandated by ADR D18). INSERT-only by convention **and by grant**: the strategy-signal-service role gets INSERT + SELECT, no UPDATE/DELETE.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT identity PK | |
| `strategy_id` | UUID FK → `strategies` | |
| `action` | TEXT | CREATE / UPDATE_DRAFT / PUBLISH / ROLLBACK / ARCHIVE (CHECK) — the lifecycle's mutating actions |
| `from_version`, `to_version` | TEXT NULL | Semver pair, e.g. publish `1.3.0 → 1.4.0`, rollback `1.4.0 → content of 1.1.0`; NULL where inapplicable (CREATE) |
| `diff_summary` | TEXT | Human-readable summary of the config delta (the `/diff` output headline) |
| `actor` | TEXT | `owner` or `optimizer:{jobId}` for promoted winners |
| `created_at` | TIMESTAMPTZ | Asia/Kolkata |

**`signals`** — generated calls with explainability payload (Phase 23):

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT identity PK | |
| `strategy_version_id` | UUID FK → `strategy_versions` | Pins the exact config that fired |
| `exchange`, `tradingsymbol`, `interval` | TEXT | |
| `signal_type`, `side` | TEXT | e.g. ENTRY/EXIT; BUY/SELL |
| `entry_price`, `stop_loss`, `target` | NUMERIC(18,4) | |
| `suggested_qty` | NUMERIC NULL | Engine-computed lot-rounded suggested quantity (sizing at emission). Lands as an **additive** column (D17) via Stage F Phase 43A's migration and is **populated from Phase 43A** (sized against the `strategy.paper_account` equity); NULL until then. Deliberately **outside** the frozen §C-2.6 `ScoreBreakdown` contract — adding it perturbs no breakdown bytes `[FP-43, owner selection 2026-06-12]` |
| `composite_score` | NUMERIC(8,4) | Normalized weighted composite per §C-2.5: `(Σ required w·s + Σ activated-optional w·s) / (Σ required w + Σ activated-optional w)` — **not** a raw weighted sum |
| `score_breakdown` | JSONB | Conforms to the §C-2.6 contract — the same strategy-engine JAR record the backtest engine persists per trade, so live/backtest parity is mechanical; feeds the reasoning panel |
| `status` | TEXT | ACTIVE / EXPIRED / TAKEN / DISMISSED (CHECK) — mirrors the lifecycle `active → taken/dismissed/expired`; DISMISSED set by `POST /api/v1/signals/{id}/dismiss` |
| `generated_at`, `expires_at` | TIMESTAMPTZ | |

**Indexing strategy `[plan §6.7]`** — strategy-schema secondary indexes, aligned to actual query patterns. Created in the owning phase's migration (Phase 21 for the registry tables, Phase 23 for `signals`); the `paper_orders`/`paper_positions` rows are in Stage F §F.6, the `jobs`/backtest rows in Stage D, the marketdata rows in Stage B.

| Table | Index | Serves |
|---|---|---|
| `strategies` | `name` unique; GIN on `tags` | `?tag=ema&status=published` |
| `strategy_versions` | `(strategy_id, created_at DESC)`; unique `(strategy_id, version)` | Version history, diff, rollback |
| `strategy_audit_log` | `(strategy_id, created_at DESC)` | Per-strategy audit timeline (the §C-2.10 lifecycle history view) |
| `signals` | `(generated_at DESC)`; partial `(status) WHERE status='ACTIVE'`; `(exchange, tradingsymbol, generated_at DESC)` | Dashboard active panel; per-symbol history |

**`paper_orders`** / **`paper_positions`** — the paper-trading ledger (consumer Stage F Phase 43). `paper_orders(id, signal_id NULL FK, exchange, tradingsymbol, side, qty, order_type, limit_price NUMERIC, status OPEN/FILLED/CANCELLED, placed_at, filled_at, fill_price NUMERIC)`. `paper_positions(id, exchange, tradingsymbol, side, qty, avg_entry_price NUMERIC, realized_pnl NUMERIC, status OPEN/CLOSED, opened_at, closed_at)` with a partial-unique constraint on `(exchange, tradingsymbol, side) WHERE status='OPEN'`. **Unrealized P&L is never stored** — computed on demand from the Redis last-tick map. `paper_orders` additionally carries fill-audit metadata written by the `FillSimulator` (Stage D §7.4): `fill_simulator TEXT` (e.g. `ltp_slippage/v1`), `slippage_applied NUMERIC(18,4)`, `quote_bid`, `quote_ask NUMERIC(18,4) NULL`. Partial fills are deliberately not modeled; future escape hatch is an additive `paper_fills(order_id FK, seq, qty, price, ts)` child table (additive-first, ADR D17).

**`notification_events`** — append-only audit of every signal-push attempt (consumer Stage E Phase 41). Same-schema FK only — no cross-schema references (ADR D10). `(id BIGINT PK, signal_id BIGINT FK → signals, channel TEXT CHECK(NTFY/TELEGRAM), status TEXT CHECK(SENT/FAILED/SUPPRESSED), attempts SMALLINT NOT NULL DEFAULT 1, detail TEXT, created_at TIMESTAMPTZ Asia/Kolkata)`.

**Resolved-universe snapshots — stored by copy, not by table.** strategy-signal-service owns universe semantics: it resolves `universe.mode: index_constituents` against the latest `marketdata.index_constituents` membership (§C-2.15) plus instrument-master filters into an ordered `(exchange, tradingsymbol)` list with a SHA-256 checksum over its canonical JSON. At backtest/sweep submission (Stage D/F) that resolved list is **copied into the job's `request` JSONB** and the hash lands in `backtest_runs.universe_checksum` — self-contained and auditable, **no strategy-side snapshot table, soft references only, no cross-schema FK** (ADR D10/§6.2). v1 resolves *current* membership; windows predating constituent capture carry the survivorship-bias caveat (§C-2.15); point-in-time `as_of: trade_date` resolution is a noted later enhancement, not built now.

### C-2.13 Strategy CRUD API `[plan §7.3]`

All routes via `edge-gateway` under the ADR D8 prefix `/api/v1/strategies/**`; owner session enforced at the gateway. Bodies are JSON envelopes; the `config` field carries the strategy as a **YAML string** (preserving comments/formatting) — the server stores **both raw YAML and canonical JSONB**. Errors use the standard envelope `{ code, message, details }` (COMMON §8.3 — cite; canonical-spelling pins: `STRATEGY_SCHEMA_INVALID` 400/422, `STRATEGY_NOT_PUBLISHED`, `CONFLICT_VERSION_IMMUTABLE`).

| Method | Path | Query / Body | Success | Errors |
|---|---|---|---|---|
| GET | `/api/v1/strategies` | `?status=draft\|published\|archived&tag=&q=&limit=50&offset=0` | 200 paged `[{id, name, currentVersion, publishedVersion?, status, tags, author, updatedAt, lastBacktestSummary?}]` | 400 bad filter |
| POST | `/api/v1/strategies` | `{name, description?, tags?, config(YAML)}` | 201 `{id, version:"1.0.0", status:"draft", checksum, createdAt}` | 400 `STRATEGY_SCHEMA_INVALID` (schema errors in `details[]`), 409 `CONFLICT_SLUG_EXISTS` |
| GET | `/api/v1/strategies/{id}` | `?version=` (default: latest) | 200 full `{id, name, version, status, config, configYaml, checksum, createdAt, updatedAt}` | 404 |
| PUT | `/api/v1/strategies/{id}` | `{config, versionBump?: patch\|minor\|major, notes?}` | 200 new draft version `{id, version, status:"draft", checksum}` | 400 `STRATEGY_SCHEMA_INVALID`, 404, 409 `CONFLICT_NO_CONTENT_CHANGE` |
| DELETE | `/api/v1/strategies/{id}` | — | 204 (hard-delete only if *all* versions are drafts; otherwise archives) | 404, 409 `CONFLICT_HAS_PUBLISHED_HISTORY` (archived instead, flagged in body) |
| GET | `/api/v1/strategies/{id}/versions` | `?limit&offset` | 200 `[{version, status, checksum, author, notes, createdAt}]` | 404 |
| GET | `/api/v1/strategies/{id}/versions/{version}` | — | 200 full config at that version | 404 |
| POST | `/api/v1/strategies/{id}/publish` | `{targetVersion?, notes?}` | 200 `{id, version, status:"published", publishedAt}` | 404, 409 `CONFLICT_NOT_A_DRAFT`, 422 `STRATEGY_SCHEMA_UNSUPPORTED` |
| POST | `/api/v1/strategies/{id}/rollback` | `{version, andPublish?: false}` | 201 `{id, newVersion, copiedFrom, status}` | 404 `NOT_FOUND_VERSION` |
| POST | `/api/v1/strategies/{id}/archive` | — | 200 `{id, status:"archived"}` (signal engine unloads) | 404 |
| GET | `/api/v1/strategies/{id}/diff` | `?from=1.1.0&to=1.4.0` | 200 `{structured:[{path, op, before, after}], yamlFrom, yamlTo}` | 404, 400 same-version |
| POST | `/api/v1/strategies/validate` | `{config(YAML)}` — stateless | 200 `{valid, errors:[{path, message, line}], warnings:[]}` | 400 unparseable YAML |
| GET | `/api/v1/strategies/schema/v1` | — | 200 the JSON Schema document (consumed by monaco-yaml) | — |

`POST /validate` is the editor's keystroke-debounced companion; it also runs the **semantic checks the JSON Schema can't express**: alias uniqueness, rule operands referencing **declared** aliases, optimize paths resolving (against the closed grammar §C-2.16), the timeframe of every referenced indicator declared, lot-size sanity for options universes, ≤ one slippage form, and (2026-06-12) indicator-level `instrument` context overrides resolving in the instruments master — surfaced here, **binding at publish** (§C-2.3) `[FP-19, owner selection 2026-06-12]`. The `GET /strategies/schema/v1` document is **served byte-identically** (no runtime mutation) — Phase 18 acceptance.

> **Publish guard (Phase 21).** `universe.mode: index_constituents` configs **validate and save as drafts but refuse publish** with `422 STRATEGY_UNIVERSE_UNSUPPORTED` until the Stage F Phase 44 universe resolver exists (explicit/options universes unaffected; guard lifted in Phase 44) — otherwise the Phase 23 live engine would evaluate a universe nothing can resolve.

### C-2.14 strategy-signal-service spec — signal engine half `[plan §5.2.3 / §3.4 Flow 5 / §3.3.1]`

**Responsibilities (Stage C scope):** the strategy registry (§C-2.10/13) and the **live signal engine** — subscribes to tick/candle channels, evaluates published strategies through the shared strategy-engine JAR, emits signals with per-indicator weighted score breakdown; signal lifecycle (`active → taken/dismissed/expired`).

> **Out of Stage C** (noted, not built here): the **paper-trading ledger** (simulated fills via the same `FillSimulator` JAR implementation backtest replay uses) lands in **Stage F Phase 43**; the **notifier** module (Q6 — ntfy primary + Telegram via plain HTTPS POST, opt-in per strategy, cooldown/dedup, `notification_events` audit) lands in **Stage E Phase 41**. The Phase 21 Modulith app declares `registry`, `signals`, `paper`, `notifier` modules — `paper` and `notifier` are **stubs** this stage.

- **Owned data:** PG schema `strategy` (strategies, strategy_versions JSONB+SHA-256, signals, paper_orders, paper_positions, notification_events, audit log — §C-2.12).
- **Events:** consumes `ticks.*`, `candles.1m.*`; publishes `signals` and `strategy.changed` (COMMON §7.3.1 pub/sub catalog).

**Signal & paper endpoints** (the `/api/v1/signals/**` set is Stage-C; `/api/v1/paper/**` is Stage F — listed for completeness):

| Method | Path | Purpose | Request / Response |
|---|---|---|---|
| GET | `/api/v1/signals` | Signal history | Query: status, strategyId, instrument, from/to, limit/offset. 200 paged list |
| GET | `/api/v1/signals/active` | Currently live calls | 200 list with strategy name, direction, entry/SL/target |
| GET | `/api/v1/signals/{id}` | Signal detail + reasoning | 200 with composite score, threshold, per-indicator contribution (weight × normalized score, optional flags) — feeds the scoring breakdown |
| POST | `/api/v1/signals/{id}/taken` | Owner executed manually at broker | Body: optional fill price/qty/note. 200 updated signal; optionally opens a paper position (Stage F) |
| POST | `/api/v1/signals/{id}/dismiss` | Reject a call | 200 updated signal |
| GET | `/api/v1/paper/positions` *(Stage F)* | Open simulated positions | 200 list with mark-to-market P&L from last-tick map |
| GET | `/api/v1/paper/trades` *(Stage F)* | Closed-trade ledger | 200 paged list |
| GET | `/api/v1/paper/pnl` *(Stage F)* | Aggregate P&L curve | 200 daily equity points + summary |
| POST | `/api/v1/paper/orders` *(Stage F)* | Simulate an entry from a signal | 201 position DTO |
| POST | `/api/v1/paper/positions/{id}/close` *(Stage F)* | Close at market/stated price | 200 realized trade DTO |
| POST | `/api/v1/paper/reset` *(Stage F)* | Wipe paper ledger | 204 (confirm flag in body) |
| POST | `/api/v1/strategies/{id}/notifications/test` *(Stage E)* | Send a test push | 200 delivery outcome; 422 if disabled/unconfigured |

**Signal-engine behavior (Phase 23):**

- Subscribes `ticks.*`/`candles.1m.*` **for published strategies' universes only** (never a firehose — evaluation on unsubscribed symbols is a FAIL). Bar-close evaluation on the `primary` timeframe; additional timeframes read from continuous aggregates; `max_daily_loss_pct`/session-window gating; engine pinning `(strategy_id, version, checksum)`; hot-swap at next bar boundary on `strategy.changed`; gated by `kite.status` (mock = always live).
- **2026-06-12 owner-selection additions (A7/A9/A11):** pre-close BTST evaluation at `risk.session.pre_close_at` against the deterministic pre-close bar view (§C-2.11) `[FP-6]`; context-instrument subscriptions + the shared context-series cache (§C-2.3 — context symbols are series inputs, never signal-emitting) `[FP-19]`; `futures_of_underlying` front-month resolution with roll re-subscribe `roll_days_before_expiry` days before expiry `[FP-11a]`; 1m exit-level evaluation for `exit_intrabar` strategies (§C-2.11) `[FP-5]` — all `[owner selection 2026-06-12]`, built in Phase 23.
- **Decoupling rule:** signal evaluation must **never run on the Redis receive thread** — a per-consumer latest-value **conflation map** + executor decouple it (plan §8.6 backpressure/conflation).
- 15:45 intraday-expiry sweep transitions stale ACTIVE signals to EXPIRED.
- Metrics `ay_signal_eval_duration_seconds`, `ay_signals_emitted_total` (COMMON §8.6).

**Flow 5 — Part B (live signal generation) + Flow 2 consumption side** `[plan §3.4]`:

```
# Part B — real-time signal generation (continuous, market hours)
redis  -) SSS : ticks.* + candles.1m.* for published strategies' symbols
SSS -> SSS    : engine JAR — composite = (Σ required w·s + Σ activated-optional w·s)
                / (Σ required w + Σ activated-optional w) ≥ threshold      (normative §C-2.5, ADR A1)
                ; optionals activate per optional_min_score / optional_gate_margin — reinforce, never gate
SSS -> PG     : INSERT signal + per-indicator breakdown JSONB (strategy schema)
SSS -> redis  : PUBLISH signals
redis -) GW   : relay
GW   -) Browser : STOMP /topic/signals → dashboard card + PrimeNG Toast with reasoning
```

Flow 2 consumption side (the browser leg the signals page rides — built Phase 26): the gateway relays `signals` to the STOMP `/topic/signals`; the WS client conflates per-frame and unshifts into a bounded ring buffer; on reconnect the store re-fetches the REST snapshot (at-least-once display semantics). The promoted-draft path of Part A (optimizer → new draft) is Stage D — the optimizer can propose, never deploy (D18 lifecycle).

### C-2.15 `index_constituents` accrual — S8 part 1 `[review S8 / plan §6.4, §7.1, §7.4]`

The reproducibility goal: NIFTY-100 membership drifts, and a backtest's universe must be reconstructable. **Final design (S8):**

1. **Source.** An **append-only `marketdata.index_constituents` accrual table** owned by market-data-service (single writer per D7/D10), **fetched daily from NSE's published constituent CSV** on the 08:30 IST sync, with a **mock fixture** (bundled `ind_nifty100list.csv`) for the credential-free path. Kite's instrument dump carries **zero** index-membership data — that is exactly why a separate NSE CSV source exists. **Source verification (format/URL stability/cadence/ToS) is an open item** (decisions-log §4.2, owner action **before** the live fetcher is built); until it passes, Phase 22 ships **the port + mock fixture only** and the live NSE fetcher is a follow-up slice.
2. **Resolution.** strategy-signal-service resolves `index_constituents` universes **via market-data-service REST** (per D8 routing and the consumer rule — strategy-signal-service holds **no `marketdata` grant**), returning the ordered list + as-of date + SHA-256 checksum over the canonical ordered list.
3. **Pinning by copy, never FK** (the Stage D/F half). The resolved list is embedded in the job's `request` JSONB at submission and every trial in a sweep reuses it; `backtest_runs.universe_checksum` enables cross-run comparison; all references stay **soft — no cross-schema FK anywhere** (a cross-schema FK is an explicit FAIL).

**Survivorship-bias honesty clause.** Membership is reconstructable **only from capture start** — NSE publishes the *current* list, so the pre-accrual portion of every backtest window permanently carries the documented survivorship-bias caveat. v1 resolves current membership; point-in-time `as_of: trade_date` is a noted later enhancement.

**Phasing.** Accrual table + fetcher (or port+mock) land **now** (Phase 22) because history is time-sensitive; pinning/checksum/editor "Published Universe (as of …)" label land in **Stage F Phase 44**.

Table shape (Phase 22 migration `marketdata/V008__index_constituents.sql`): append-only, **PK `(index_name, as_of_date, exchange, tradingsymbol)`**, plus `fetched_at`. Endpoint: `GET /api/v1/instruments/indices/{index}/constituents?asOf=` — ordered list + resolved as-of date + checksum; latest by default, exact date with `asOf`.

### C-2.16 Input validation & security `[plan §11.4 / COMMON §12.4–12.5 / CD-4, CD-5]`

Validation happens twice: **shape at the gateway, semantics at the owning service**.

| Input | Validator | Rule |
|---|---|---|
| Instrument symbols | market-data / strategy-signal | Must resolve in the `instruments` master by `(exchange, tradingsymbol)`; unknown → `404 NOT_FOUND_INSTRUMENT` |
| Date ranges | each service | `from < to`, ≤ 2 years span for candle queries, IST-normalized; reject open-ended scans |
| **Strategy YAML** | strategy-signal-service | **≤ 256 KB body limit at gateway**; parsed with **SnakeYAML in `SafeConstructor` mode** (no arbitrary type instantiation — CD-5); validated against JSON Schema `strategy-schema/v1` with the **networknt draft-2020-12 validator** (CD-4); semantic checks (indicator names against engine registry, weights ≥ 0, threshold sane; context-override instruments resolve in the instruments master `[FP-19, owner selection 2026-06-12]`) |
| Optimizer parameter paths | optimizer + backtest services | `optimize.parameters[].path` must match the closed whitelist grammar below; selectors matched **literally** against the *validated* config tree — no reflection, no expression evaluation, so path strings cannot reach arbitrary object graphs |
| Pagination / enums | gateway + services | `limit ≤ 500`, `offset ≥ 0`; enums (`interval`, `method`, `status`) bound to typed DTOs — invalid values fail Jackson binding with the standard envelope |

**Parameter-path whitelist grammar (closed; outside it → `400 INVALID_PARAMETER_PATH`)** — COMMON §12.5 is authority; restated for Phase 18's validator and the optimize-path semantic check:

```
path            := indicator-path | exit-path | scoring-path | risk-path
indicator-path  := "indicators[" selector "].params." ident
exit-path       := "exit_rules[" selector "].params." ident
scoring-path    := "entry_rules.scoring." ident            # e.g. entry_rules.scoring.threshold
risk-path       := "risk.position_sizing." ident           # fields enumerated in strategy-schema/v1 only
selector        := "alias=" ident | "type=" ident | int    # bare positional index accepted but linted (§C-2.4)
ident           := [a-z][a-z0-9_]*        int := [0-9]+    # literal match only — no wildcards, quoting, or nesting
```

Resolution is a **pure walk of the parsed config tree** — selectors compare literally against the `alias`/`type` fields the schema already requires — so the alias/type forms add **zero attack surface** over the positional form while keeping sweep definitions stable under indicator reordering. Threat-model T5 (malicious/buggy YAML or paths) is closed by exactly these controls: schema validation, size limits, path whitelisting.

### C-2.17 YAML canonicalization & checksum `[plan §7.1–7.2, §10.3]`

The Phase 18 lib pipeline: **YAML loader (SnakeYAML SafeConstructor, 256 KB cap) → canonicalization (stable key order, normalized number rendering) → SHA-256 over canonical bytes**. The checksum is the immutability/dedupe guard (§C-2.10). Stability requirement (golden test): **re-canonicalizing yields an identical hash** across runs and key re-ordering. Unit-tested in strategy-signal-service: `strategy-schema/v1` acceptance/rejection corpus; SHA-256 canonicalization stability (plan §10.3 YAML-validation row).

### C-2.18 Contract testing `[plan §10.6 / CD-8]`

Contracts are the **committed OpenAPI 3.1 specs** per service under `/contracts` (springdoc-generated for Java, FastAPI-native for Python). A Pact broker was considered and rejected — one owner, four providers, one consumer does not justify broker infrastructure. Three CI checks per PR:

1. **Provider verification** — the spec regenerated from code is diffed against the committed spec with `openapi-diff`; any breaking change (removed field, type change, new required param) fails the build. Every endpoint must use the `/api/v1/{domain}` prefix and the `{ code, message, details }` error envelope (D8) — asserted by a spec lint rule.
2. **Consumer verification (frontend)** — the Angular client types are generated from the specs via `openapi-typescript`; TypeScript 5.9 strict compilation fails on any drift.
3. **Consumer verification (inter-service)** — optimizer→backtest and gateway→service calls tested against WireMock stubs generated from the provider spec (respx for Python). *(This third leg materializes fully in Stage D when backtest/optimizer ship; the matrix grows in Phases 28/33.)*

CD-8 capture mechanism: CI boots each service (mock), dumps `/v3/api-docs`, diffs with `openapi-diff`; frontend types via `openapi-typescript`.

### C-2.19 Backtest determinism — golden tests `[plan §10.7 / D15]`

The golden-vector suite pins the platform's core promise: **same YAML + same candles → identical signals and metrics, live and backtest**. Fixtures are committed: **five trading days of synthetic 1m NIFTY candles** (generated once by the seeded mock generator with a fixed seed, then **frozen**), plus **one strategy YAML per schema feature**. The **fixture format was frozen in Stage A** (`docs/golden-vectors.md` — fixture directory layout, candle encoding, expected-signal encoding) so the Phase 23 live engine and the Stage D Phase 30 replay engine consume **one harness**.

| Family | Assertion |
|---|---|
| Metric exactness | Fixed dataset + strategy → metrics file (`returns`, `sharpe`, `maxDrawdown`, `winRate`, `tradeCount`) matched as **exact decimal strings** — any engine change altering output requires an explicit golden update in the same PR |
| Live/backtest parity | The same candle stream is (a) pushed through the signal engine tick-wise and (b) replayed by the backtest engine; the resulting signal lists (timestamps, scores, **per-indicator breakdowns**) must be **byte-identical** — guards the shared strategy-engine JAR seam (D7) |
| Version immutability | Re-evaluating an archived strategy version reproduces its stored SHA-256 checksum and original results (D18) |
| Optimizer reproducibility | Optuna with a fixed sampler seed reproduces the same trial sequence and best-trial params (Stage D) |

**Stage-C scope:** the **live half** of the parity pair is built and frozen in Phase 23 (`libs/strategy-engine/src/test/resources/golden/`); the **replay half** lands in Stage D Phase 30. The Phase 23 determinism test asserts tick-wise evaluation ⇒ expected signals byte-identical.

> **P1-1 / P1-3 caveats (review).** Golden vectors are a **determinism/parity** kill-switch only — a perfectly deterministic engine reproduces an *overfit* strategy's results exactly. Green golden tests must **never** be read as evidence a strategy generalizes; overfitting controls are the separate S1-cluster (Stage D).

### C-2.20 E2E — Playwright on the mock stack `[plan §10.9, §4.11]`

E2E runs against `docker compose -f compose.yaml -f compose.test.yaml up` (8 core containers, credential-free). Journeys mirror the Section-4 routes; the **Stage-C subset** the Phase 27 smoke suite must cover:

| Journey | Coverage (Stage C subset) |
|---|---|
| Mock login | Gateway form login (Argon2id test hash), session cookie set **HttpOnly/SameSite=Strict**, deep-link redirect; logout |
| Live signals | Start a `trend-up`-style seeded scenario; the deterministic mock feed fires a known signal; assert it appears with strategy name and per-indicator score breakdown |
| Charts + resilience | *(WS-reconnect chaos applies here in Stage C: restart the gateway container mid-session, assert recovery without reload)* |

The full strategy-lifecycle / options / paper journeys land in Stages D–F. Stage C adds `@axe-core/playwright` on the two live routes (login, signals). The E2E harness is the **regression net for every later UI phase** — later phases only *append* journeys.

### C-2.21 Frontend framework foundations `[plan §4.1 / D1, D5]`

**Decision (ADR D1):** Angular 21.x, **standalone components only, signals-first, zoneless** change detection, TypeScript 5.9 with `strict` + `strictTemplates`. Build with Angular CLI 21's esbuild `application` builder (D5). The v1 failure was not Angular but how Angular was used; the rebuild keeps the framework and replaces the idioms:

| Concern | v1 (current) | 2.0 (mandated) |
|---|---|---|
| Change detection | Zoneless by accident; `cdr.markForCheck()` + mutable fields; `setInterval` field writes that never render | Zoneless by design; all template state is `signal()`/`computed()`; **zero manual CD calls** |
| State | `BehaviorSubject`s in one service + component fields; 2 s/10 s/30 s polling sprawl | `@ngrx/signals` 21.x SignalStore per domain (D3); polling only where no WS topic exists |
| PrimeNG | Theme + icons only; zero components imported | Real component usage: DataTable, Select, Toast, Dialog, Tabs, Skeleton (D2) |
| Typing | Strict tsconfig but `Observable<any>` APIs, `any[]`, `as any` | DTO interfaces **generated from the OpenAPI 3.1 specs** (§C-2.18); `any` banned by ESLint |
| Environments | Hardcoded `http://localhost:8080` | `environment.ts` files + CLI dev proxy to `127.0.0.1:8080`; relative `/api/v1/...` URLs in production |
| Transport | STOMP over SockJS + `window.global` polyfills | **STOMP over native WebSocket** (D9); SockJS and polyfills deleted |

The library-agnostic chart datafeed core ports verbatim under Angular; the renderer binding is **lightweight-charts** (Phase 40) `[A13, 2026-06-12]` (its `/charts` home lands Stage E Phase 40, behind the lint-enforced containment boundary).

### C-2.22 State-management architecture `[plan §4.2 / D3]`

One `@ngrx/signals` 21.x **SignalStore per domain**, provided at root, consumed via `inject()`. RxJS 7.8 survives only at the WebSocket edge and inside `HttpClient`; everything a template reads is a signal. The Stage-C stores (others stubbed/expanded later):

| Store | Server-cache state | Client/UI state | Live WS feed |
|---|---|---|---|
| `SessionStore` | Auth status (gateway session), Kite token health (`GET /api/v1/system/status`, 10 s fallback poll) | Theme (dark/light), sidebar state, last-visited route | `kite.status` via `/topic/system` deltas |
| `SignalsStore` | Signal history pages (limit/offset) | Filters (type, symbol, date), selected signal for reasoning drill-down | `signals` topic (live unshift, **bounded ring buffer**) |
| `MarketStore` *(minimal this stage)* | Market/Kite connection status | Selected instrument, interval | `/topic/system` connection-status deltas |
| `StrategiesStore` *(Stage E expands)* | Strategy list, versions, diffs, JSON Schema | Monaco draft buffer, dirty flag | — |

**Rules of the architecture:**

- **Server-cache vs client state is explicit.** Server-cache slices carry `{ data, status: 'idle'|'loading'|'loaded'|'error', error, fetchedAt }`, populated only by store methods calling the typed API client; the standard `{ code, message, details }` envelope is mapped to a Toast by **one HTTP interceptor**. Client state never round-trips.
- **WebSocket-fed stores subscribe once.** A single `WsClientService` (§C-2.25) exposes typed RxJS streams; each store bridges its stream into signals in its constructor scope. **Components never touch STOMP.**
- **No polling where a topic exists — Kite status included.** Push-first: `kite.status` → gateway → `/topic/system`. The only remaining poll is a **low-frequency 10 s fallback** against `GET /api/v1/system/status` (Caffeine-cached 5 s server-side) — it heals missed deltas across WS reconnects and seeds initial state. v1's 30 s/10 s/2 s polls are deleted.
- **Derived data is `computed()`**: composite-score breakdown percentages, etc. — computed, memoized, never stored.

### C-2.23 Design system & theming `[plan §4.3 / D2]`

- **PrimeNG 21.x, Aura preset**, with *actual component usage*: `p-table` (virtual scroll) for signals/history, `p-select`/`p-datepicker`/`p-inputnumber` for forms, `p-toast` + `p-confirmdialog`, `p-tabs`, `p-skeleton`, `p-tag` for status chips, `p-splitter`.
- **One consolidated `--ay-*` token palette** layered on PrimeNG tokens — the v1 three-palette drift eliminated; **ESLint + Stylelint forbid raw hex** in component styles. Tokens cover surface/border/text scales, semantic `--ay-bull`/`--ay-bear`/`--ay-warn`, and chart-specific tokens.
- **Dark default, light optional**: theme toggled by `SessionStore` writing a `.ay-light` class on `<html>`; persisted to `localStorage`; respects `prefers-color-scheme` on first run.
- **Strategy editor** (Stage E): Monaco + monaco-yaml bound to the versioned `strategy-schema/v1` served by strategy-signal-service.

### C-2.24 Layout pattern & shell `[plan §4.4 / §4.5 routes]`

`AppShell` (zoneless root) → **TopBar** (instrument search, **market clock IST**, WS status, theme toggle) + collapsible **SideNav** rail + lazy **RouterOutlet** + **ToastHost + ConfirmDialog**. Non-dashboard pages use a fixed `p-splitter` two-pane (master list / detail). The Stage-C signals page is `SignalsPage: live + history + ReasoningBreakdownPanel`.

**Routes (all lazy `loadComponent`/`loadChildren`; auth-guarded against the gateway session except `/login`)** — the full table is plan §4.5; the Stage-C-relevant rows:

| Route | Purpose | Key components / stores |
|---|---|---|
| `/login` | Gateway form login (single owner) | PrimeNG form, `SessionStore` |
| `/signals` | Live feed + history with filters; click → reasoning breakdown: per-indicator normalized score × weight, optional-indicator reinforcement, threshold visual | `SignalsStore`, `ReasoningBreakdownPanel` (horizontal bar viz), virtualized history table |

*(Dashboard, charts, options, strategies, backtests, paper, watchlists, settings routes land in Stages E/F.)*

### C-2.25 Real-time update strategy — signals first `[plan §4.6 / §4.9 / D9]`

- **Client library:** `@stomp/stompjs` 7.x over **native WebSocket** to `wss?://…/ws` on the gateway — SockJS and both `window.global` polyfills deleted (D9). **Heartbeats 10 s/10 s; reconnect with exponential backoff (1 s → 30 s cap, jitter)** built into the client; on reconnect, each store **re-subscribes and re-fetches its REST snapshot** to heal gaps (at-least-once display semantics).
- **Per-symbol topics, not firehose:** subscriptions follow `ticks.{exchange}.{tradingsymbol}`; the datafeed's **refcounted subscribe/unsubscribe** pattern is generalized into `WsClientService` for all consumers.
- **Message conflation:** handlers write into a per-symbol "latest value" map; a single **`requestAnimationFrame` loop flushes the map into store signals at most once per frame (≈16 ms)**. Under burst load the UI renders the newest value, never a queue of stale ones — this plus zoneless signals is what keeps the tick-to-browser ≤ 150 ms p99 target achievable in the browser leg.
- **Rendering:** zoneless + signals means only components whose read signals changed re-render; `OnPush` is the default. No `markForCheck`, no Zone patching cost.
- **Virtualized tables:** signal history (and later chain/trade/watchlist tables) use `p-table` virtual scroll with fixed row height; only ~30 DOM rows exist at any time. Price-change flashes are CSS-class pulses, not row re-creation.

**Performance budgets** (CI-enforced via `angular.json`): **initial ≤ 500 KB gz** (PrimeNG tree-shaken, no chart lib in initial); any lazy chunk ≤ 400 KB gz; warning at 80%. Heavy libs lazy by route. Prices arrive as **JSON strings** and are formatted/compared via a thin decimal utility — never `parseFloat` for arithmetic (exact-decimal convention end-to-end).

### C-2.26 Reasoning breakdown panel `[plan §4.5, §7.7 / §C-2.6]`

The `/signals` reasoning drill-down renders the **Phase 20 score-breakdown contract** (§C-2.6): a **gate checklist with actual operand values** (from the recursive `gate` tree leaves), a **stacked contribution bar** (per-indicator `contribution = w·s`), and a **composite-vs-threshold gauge**. Optional-indicator reinforcement is shown via each indicator's `activated`/`activationReason`. The renderer obeys the invariant `composite = Σ contributions / weightDenominator`. Derived percentages are `computed()`, memoized, never stored.

### C-2.27 Frontend testing `[plan §4.11 / D5, D15]`

| Layer | Tooling | Scope and gates |
|---|---|---|
| Unit | **Vitest 3.x + jsdom** | SignalStores (state transitions, conflation flush logic), `WsClientService` (reconnect/backoff with **fake timers**), decimal utility; coverage gate on **stores/services ≥ 70 %** to match the backend bar |
| Component | Vitest + Angular `TestBed` (zoneless) + Testing Library idioms | Reasoning-breakdown rendering (the invariant), editor validation marker display (Stage E); PrimeNG components exercised through DOM |
| E2E | **Playwright 1.x** against the full mock-mode stack (credential-free) | Stage-C golden paths: login → shell; live signals appear with breakdown; WS-reconnect chaos (§C-2.20) |
| Lint/format | ESLint 9 (`no-explicit-any`, template a11y) + Prettier; Stylelint forbidding raw hex | Pre-commit + CI |

Trade-off pinned: the ADR uses **Vitest 3.x** (compatible with `@angular/build:unit-test`), not 4.0.x. No route ships without a Playwright path; no store without unit specs.

### C-2.28 MVP definition — *the Stage-C exit IS the MVP gate* `[plan §15.4]`

**MVP = the smallest slice that produces a live signal end-to-end:** mock or live Kite tick → market-data-service candle → published YAML strategy (one EMA-crossover, full D18 lifecycle) evaluated by the signal engine → signal persisted with per-indicator score breakdown → STOMP push through edge-gateway → visible on a deliberately minimal Angular signals page. It lands at the **end of Phase 2 / Stage C: ~week 11–13 full-time with the review additions (baseline week 9–11)**. Everything after the MVP is additive; everything before it is load-bearing. The MVP intentionally **excludes** backtesting, optimization, charts, options UI, and paper trading — but **includes** auth, mock mode, Flyway, and CI, because retrofitting foundations is the v1 mistake this rebuild exists to avoid. In this stage the MVP is **achieved at Phase 26**; Phase 27 wraps the regression net around it.

---

## Part 3 — Phase specs (18–27)

> Copied near-verbatim from the implementation-phases doc. Cross-references rewritten to point at Part 2 (`§C-2.*`) or COMMON. Original section tags retained as breadcrumbs.

### C-3 Phase 18 — `strategy-schema/v1` + validation/canonicalization lib

**Objective.** Author the complete versioned JSON Schema for strategies — including the review-mandated keys that must exist **at the freeze** (`slippage_bps`, `fees{}`, `objective.fold_aggregation`, `walk_forward` — see §C-2.2) and the A7 owner-selection keys (`1w` timeframe, `risk.session.{pre_close_at, fill_timing, exit_intrabar}`, indicator-level `instrument` override, `universe.mode: futures_of_underlying` + `futures{}` — §C-2.2) `[FP-5, FP-6, FP-8, FP-11a, FP-19, owner selection 2026-06-12]` — plus YAML→canonical-JSON conversion and SHA-256 checksumming (D18, §C-2.1).

**Why this phase is independent.** A pure library with a fixture corpus; no service, DB, or UI. The schema **freezes at the Stage-C exit gate**, so getting it complete now prevents post-freeze churn.

**Deliverables.**
- `libs/strategy-schema/` — `strategy-schema-v1.json` (draft 2020-12) covering: header (semver, tags, enabled), `universe` (explicit | index_constituents | options_of_underlying with expiry/strike selectors | futures_of_underlying with `futures{contract: front_month|next_month, roll_days_before_expiry int default 1}` — A7 `[FP-11a, owner selection 2026-06-12]`), `timeframes` (interval vocabulary incl. `1w` — A7 `[FP-8, owner selection 2026-06-12]`), `indicators[]` (`name` as identifier pattern + advisory known-name `anyOf` enum, `alias`, `timeframe`, `params`, `weight`, `optional`, `normalize{linear|step|direction|rsi_momentum}`, optional `instrument{exchange, tradingsymbol}` context override — A7 `[FP-19, owner selection 2026-06-12]` — §C-2.3), `entry_rules` (gate grammar `all/any/not` + typed `crossover/crossunder` + threshold strings; `scoring{threshold, optional_min_score, optional_gate_margin}` — §C-2.4–2.5), `exit_rules[]` (stop_loss/take_profit/trailing_stop/time_stop/signal_exit with `basis` enums), `risk` (sizing methods incl. `kelly_fraction` cap 0.25; `session.style` enum; windows; `session.{pre_close_at "HH:mm" default "15:20", fill_timing next_open|at_close, exit_intrabar bool}` — A7 `[FP-5, FP-6, owner selection 2026-06-12]` — §C-2.11), `backtest.defaults` (`costs{per_lot_inr|pct_per_side, slippage_ticks ⊕ slippage_bps, fees{stt,exchange_txn,gst,stamp,sebi}}`, `benchmark` default `NSE:NIFTY 50` — §C-2.8), `backtest.optimize` (`parameters[].path/range/choices/step`, `method grid|random|tpe|nsga2`, `max_trials`, `objective` single or multi + `fold_aggregation mean|min|mean_minus_std`, `constraints.min_trades`, `walk_forward{train_days,test_days,step_days,anchored}`).
- YAML loader (SnakeYAML SafeConstructor, 256 KB cap) → canonicalization (stable key order, normalized number rendering) → SHA-256 over canonical bytes (§C-2.17).
- Validation API returning pointer-level errors; semantic checks: alias uniqueness, rule operands reference declared aliases, optimize paths resolve against the closed grammar (§C-2.16), timeframes declared, **≤ one slippage form**.
- Corpus: the two annotated examples (§C-2.7, §C-2.11) as **accept fixtures**, **one bare-positional-index path fixture (accepted-but-linted)**, + **~15 reject fixtures** (bad enum, unknown alias, both slippage forms, arithmetic in gate strings…); checksum-stability test (re-canonicalize ⇒ identical hash). A7-key fixtures added 2026-06-12 — accept: a `btst` strategy with `pre_close_at`/`fill_timing: at_close`, a `1w` primary timeframe, an indicator `instrument` override, a `futures_of_underlying` universe; reject: bad `fill_timing` enum, `futures{}` under a non-futures `universe.mode` `[FP-5, FP-6, FP-8, FP-11a, FP-19, owner selection 2026-06-12]`.

**Minimal code/config.** Parameter-path grammar (closed): `indicators[<sel>].params.y` | `exit_rules[<sel>].params.y` | `entry_rules.scoring.y` | `risk.position_sizing.y`, where `<sel>` = `alias=ident` | `type=ident` | bare positional `int` (accepted but linted — §C-2.4/§C-2.16); literal match only — no wildcards, quoting, or nesting.

**DB changes.** none.

**Build & Run.**
```
./mvnw -pl libs/strategy-schema -am verify
```

**Tests & Verification.** Both annotated examples validate; every reject fixture fails with the expected pointer; checksum stable across runs and key re-ordering.

**Acceptance criteria.**
- PASS: corpus green; schema document served later byte-identically (no runtime mutation); `fold_aggregation`/`fees`/`slippage_bps` present now; the A7 keys (`1w`, `session.{pre_close_at, fill_timing, exit_intrabar}`, indicator `instrument` override, `futures_of_underlying` + `futures{}`) present and validated now `[FP-5, FP-6, FP-8, FP-11a, FP-19, owner selection 2026-06-12]`.
- FAIL: vocabulary enum made *binding* (must stay advisory — Q2, §C-2.9); any post-phase schema-shape edit without a recorded amendment (the A7 keys are part of the freeze itself — ratified pre-freeze, 2026-06-12 — not a post-freeze edit).

**Commit message.** `feat(strategy-schema): strategy-schema/v1 json schema with canonicalization, checksums and validation corpus`
**PR title.** `Phase 18: strategy-schema/v1 + validation lib`
**Time estimate.** 90–120 min. **Token size target.** ≤ 35k output tokens.
**If phase too big.** (a) schema document + corpus; (b) canonicalization/checksum + semantic checks.

---

### C-3 Phase 19 — strategy-engine: indicators + normalizers

**Objective.** Create the shared `libs/strategy-engine` JAR's indicator layer: ta4j 0.22.x wrappers for the v1 schema vocabulary plus the normalizer functions, pinned by known-good vector tests.

**Why this phase is independent.** Pure computation on synthetic bar series; consumed later by both engines. JaCoCo ≥ 70 % branch gate starts here.

**Deliverables.**
- `libs/strategy-engine/` — engine-registry: `EMA, SMA, RSI, VWAP, ADX, MACD_HIST, SUPERTREND, VOLUME_RATIO, OI_CHANGE_PCT, ATR` (registry id → ta4j wrapper + param schema); registry lookup used later by publish-time existence checks (Q2 — §C-2.9).
- **Session-level indicator family** `ORB_HIGH, ORB_LOW, PREV_DAY_HIGH, PREV_DAY_LOW, PREV_DAY_CLOSE, DAY_HIGH, DAY_LOW, GAP_PCT` — session-aware via `MarketCalendar` (ORB window length is a param; "day" boundaries are IST trading sessions, never calendar/UTC days). **Warm-up note (documented in the registry param schema):** `GAP_PCT`/`PREV_DAY_*` need prior-day 1d data in the warm-up window — strategies referencing them must have at least one prior trading day of coverage. `[FP-18, owner selection 2026-06-12]`
- **Context-mechanism indicators** `RS_VS_INDEX`, `VIX_LEVEL` — registered here; both require the A7 indicator-level `instrument` override (§C-2.3): `RS_VS_INDEX` compares the signal instrument's return series against the declared index series; `VIX_LEVEL` reads the pinned INDIA VIX index series (Stage B Phase 15A). Context-series *resolution* lands Phase 20; live subscription wiring lands Phase 23. `[FP-20, FP-14, owner selection 2026-06-12]`
- BarSeries adapter: NUMERIC candles → ta4j series; multi-timeframe series keyed `(instrument, interval)` (interval vocabulary includes `1w` — A7 `[FP-8, owner selection 2026-06-12]`).
- Normalizers: `linear(from,to)`, `step` bands, `direction` ±, `rsi_momentum` preset → `s ∈ [0,1]` (§C-2.3).
- Vector tests: committed CSV fixtures (TA-Lib reference values / hand-computed sheets) per indicator; BigDecimal `compareTo` assertions; normalizer truth tables (plan §10.3 indicator-math row). Reference/golden vectors **extended to the new families** — hand-computed session fixtures spanning two trading days (so `PREV_DAY_*`/`GAP_PCT` have prior-day data) and a two-series fixture for `RS_VS_INDEX`/`VIX_LEVEL` `[FP-18, FP-20, FP-14, owner selection 2026-06-12]`.
- Indicator value cache keyed `(instrument, interval, indicator, params-hash, lastBarTime)` (D11; **live path only** — replay computes in-stream; CD-16 TTL'd cache keys).

**Minimal code/config.** none — file list suffices.

**DB changes.** none.

**Build & Run.**
```
./mvnw -pl libs/strategy-engine -am verify
```

**Tests & Verification.** Per-indicator vector suites green; JaCoCo report ≥ 70 % branch on the module.

**Acceptance criteria.**
- PASS: every schema-v1 indicator name resolves in the registry and matches its reference vectors exactly (string-compare decimals).
- FAIL: indicator math duplicated outside this JAR; floats in outputs.

**Commit message.** `feat(strategy-engine): ta4j indicator registry and normalizers pinned by reference vectors`
**PR title.** `Phase 19: strategy-engine indicators + normalizers`
**Time estimate.** 90–120 min. **Token size target.** ≤ 30k output tokens.
**If phase too big.** (a) EMA/SMA/RSI/ATR/VWAP + normalizers; (b) ADX/MACD_HIST/SUPERTREND/VOLUME_RATIO/OI_CHANGE_PCT; (c) session-level + context families (`ORB_*`/`PREV_DAY_*`/`DAY_*`/`GAP_PCT`/`RS_VS_INDEX`/`VIX_LEVEL`) `[FP-18, FP-20, FP-14, owner selection 2026-06-12]`.

---

### C-3 Phase 20 — strategy-engine: gates, composite scoring, score breakdown

**Objective.** Implement rule evaluation and the **normative A1 composite formula** with optional-indicator activation, emitting the BPB score-breakdown record that both engines will serialize byte-identically.

**Why this phase is independent.** Still a pure library; evaluated bar-close on synthetic series. **The breakdown contract freezes here, before any consumer exists.**

**Deliverables.**
- Gate evaluator: `all/any/not` tree; typed `crossover/crossunder{fast,slow}`; threshold expressions (`"rsi_1m < 70"`) over aliases/built-ins (`close`, `volume`, `vwap`) — **comparison operators only, no arithmetic** (§C-2.4).
- Composite: `composite = (Σ required w·s + Σ activated-optional w·s) / (Σ required w + Σ activated-optional w)`; optional activates iff score ≥ `optional_min_score` AND required-only composite ≥ `threshold − optional_gate_margin` (defaults 0.6 / 0.15) — **normative per §C-2.5**.
- **Context-series resolution in scoring** (A7): where an indicator declares the `instrument` override, the gate evaluator and the composite read that indicator's value from the *declared* instrument's series at its declared timeframe, via the shared context-series cache (§C-2.3); the score breakdown's `indicators[]` entries carry the same alias/score/contribution shape regardless of source instrument — the §C-2.6 contract is unchanged. `[FP-19, owner selection 2026-06-12]`
- `ScoreBreakdown` record (§C-2.6 table): composite/threshold/passed, `requiredComposite`, echoed config, `weightDenominator`, recursive gate tree with leaf operand values, `indicators[]` entries with `activationReason ∈ REQUIRED|ACTIVATED|SCORE_BELOW_MIN|MARGIN_NOT_MET`; **stable canonical JSON serialization**.
- Exit-rule evaluators: stop_loss/take_profit (premium_pct, atr_multiple, r_multiple bases), trailing_stop, time_stop (bars/days), signal_exit; entry/exit precedence rules.
- Position sizing: `fixed_quantity`, `percent_equity`, `premium_budget`, `atr_risk`, `kelly_fraction` (cap 0.25) — **lot-size rounding, never fractional lots**.
- Truth-table tests incl. renderer invariant `composite = Σ contributions / weightDenominator`; serialization byte-stability test. Vectors extended with a **context-series case** — a `VIX_LEVEL` gate plus an `RS_VS_INDEX` contribution evaluated against a second synthetic series `[FP-19, owner selection 2026-06-12]`.

**Minimal code/config.** The formula above is **normative per ADR amendment A1 (ratified 2026-06-12)**, consciously superseding D18's literal `sum(weight × normalized_score)` phrasing; plan Flow 5 (§C-2.14) and plan §10.3 align to it.

**DB changes.** none.

**Build & Run.**
```
./mvnw -pl libs/strategy-engine -am verify
```

**Tests & Verification.** Truth tables: optionals reinforce but never gate; margin/min-score boundary cases; gate-tree leaf values captured; sizing lot-rounding cases; JaCoCo ≥ 70 % holds.

**Acceptance criteria.**
- PASS: breakdown JSON byte-stable across JVM runs; A1 formula cases all green.
- FAIL: optional indicator able to veto or carry a signal alone; breakdown shape diverging from §C-2.6.

**Commit message.** `feat(strategy-engine): gate grammar, normative composite scoring and score-breakdown contract`
**PR title.** `Phase 20: strategy-engine scoring + breakdown contract`
**Time estimate.** 90–120 min. **Token size target.** ≤ 30k output tokens.
**If phase too big.** (a) gates + composite + breakdown; (b) exit rules + position sizing.

---

### C-3 Phase 21 — strategy-signal-service: registry CRUD + lifecycle + diff

**Objective.** Stand up strategy-signal-service with the versioned registry: immutable JSONB versions, draft→published→archived lifecycle, publish/rollback/diff/validate endpoints, and the append-only audit log (D18, §C-2.10–2.11/2.13).

**Why this phase is independent.** Depends on libs from Phases 18–20 and infra only. Fully exercisable via REST in mock; the signal engine comes next phase.

**Deliverables.**
- `services/strategy-signal-service/` — Modulith app (modules `registry`, `signals`, `paper`, `notifier` — later modules as **stubs**; §C-2.14), Dockerfile + compose entry (internal 8082, `mem_limit: 640m`).
- Migrations: `strategies` (incl. `notifications_enabled` default false, `notification_channel` nullable — columns exist now to avoid a later checksum-adjacent migration; feature lands Stage E Phase 41), `strategy_versions` (JSONB + checksum + status CHECK + UNIQUE `(strategy_id, version)` + index `(strategy_id, created_at DESC)`), `strategy_audit_log` (INSERT+SELECT-only grant; index `(strategy_id, created_at DESC)`) — column tables + indexing table §C-2.12.
- Endpoints (§C-2.13 table, verbatim): list/create/get/put(new draft, auto patch-bump, checksum dedupe `CONFLICT_NO_CONTENT_CHANGE`)/delete(drafts-only hard delete), `/versions`, `/versions/{v}`, `/publish` (one published per strategy; re-validate; archive predecessor), `/rollback` (copy-forward), `/archive`, `/diff` (structured per-path ops + raw YAMLs), `POST /validate` (stateless; schema + semantic checks), `GET /strategies/schema/v1`.
- `strategy.changed` Redis publish on publish/rollback/archive.
- **Publish guard:** `universe.mode: index_constituents` configs validate and save as drafts but **refuse publish** with 422 `STRATEGY_UNIVERSE_UNSUPPORTED` until the Phase 44 universe resolver exists (explicit/options universes unaffected; guard lifted in Phase 44) — otherwise the Phase 23 live engine would evaluate a universe nothing can resolve (§C-2.13).
- **Publish-time context-instrument check** (extends the Q2 registry name check — §C-2.9): every indicator-level `instrument` override must resolve in the instruments master by `(exchange, tradingsymbol)` — resolved via market-data-service REST per D8 (strategy-signal-service holds no `marketdata` grant). An unknown context instrument **refuses publish** (surfaced in the standard error envelope `details[]`; `POST /validate` flags it too) — never silently null-scored. `[FP-19, owner selection 2026-06-12]`
- Seed: one sample EMA-crossover draft via repeatable seed migration (idempotent).

**Minimal code/config.** Version immutability is structural: UPDATE only ever touches `status`/`published_at`.

**DB changes.** `strategy/V002__strategies_versions_audit.sql`, `strategy/R__seed_sample_strategy.sql`.

**Build & Run.**
```
./mvnw -pl services/strategy-signal-service -am verify
./ay.sh up && curl 127.0.0.1:8080/api/v1/strategies -b cookies.txt
```

**Tests & Verification.**
- IT: full lifecycle create→edit→publish→edit→publish→rollback with audit rows asserted; identical-content save is a no-op; published version PUT mints a new draft; diff output matches fixture; archived strategy refuses publish of unsupported schema version.
- Unit: semantic validator messages with line anchors.

**Acceptance criteria.**
- PASS: lifecycle state machine enforced exactly per the §C-2.10 diagram; every mutating call writes an audit row; checksums verified on read.
- FAIL: any in-place version mutation; diff computed client-side.

**Commit message.** `feat(strategy-signal): versioned strategy registry with publish/rollback/diff and append-only audit`
**PR title.** `Phase 21: strategy registry CRUD + lifecycle`
**Time estimate.** 120–150 min. **Token size target.** ≤ 35k output tokens.
**If phase too big.** (a) service skeleton + migrations + CRUD/versions; (b) publish/rollback/archive + audit; (c) diff + validate + schema endpoint.

---

### C-3 Phase 22 — index_constituents accrual + constituents API (S8 part 1)

**Objective.** Start accruing point-in-time index membership in market-data-service — history is time-sensitive (S8) — and expose the REST endpoint strategy-signal-service will later resolve universes through (§C-2.15).

**Why this phase is independent.** Mock fixture CSV feeds the credential-free path; the live NSE CSV fetcher is isolated behind a port pending source verification (decisions-log §4.2).

**Deliverables.**
- Migration: `index_constituents` append-only — **PK `(index_name, as_of_date, exchange, tradingsymbol)`**, `fetched_at`.
- Fetcher port + impls: mock = bundled `ind_nifty100list.csv` fixture; live = NSE published-CSV download wired into the 08:30 sync — **built only if the source-verification owner action (decisions-log §4.2) has passed before this phase; otherwise this phase ships the port + mock fixture only** and the live fetcher becomes a follow-up slice.
- `GET /api/v1/instruments/indices/{index}/constituents?asOf=` — ordered list + resolved as-of date + SHA-256 checksum over the canonical ordered list.
- Survivorship-bias caveat documented (membership reconstructable only from capture start — §C-2.15).

**Minimal code/config.** none.

**DB changes.** `marketdata/V008__index_constituents.sql`.

**Build & Run.**
```
./mvnw -pl services/market-data-service -am verify
curl '127.0.0.1:8080/api/v1/instruments/indices/NIFTY%20100/constituents' -b cookies.txt
```

**Tests & Verification.** IT: two syncs on different mock dates accrue two as-of snapshots; endpoint returns latest by default and exact date with `asOf`; checksum stable for identical lists.

**Acceptance criteria.**
- PASS: append-only verified (second sync never mutates prior rows); checksum deterministic.
- FAIL: cross-schema FK anywhere; constituents read by any service other than via this REST endpoint.

**Commit message.** `feat(market-data): append-only index constituents accrual with point-in-time rest resolution`
**PR title.** `Phase 22: index constituents accrual (S8)`
**Time estimate.** 60–90 min. **Token size target.** ≤ 20k output tokens.
**If phase too big.** Not applicable.

---

### C-3 Phase 23 — Live signal engine + signals API + determinism goldens

**Objective.** Evaluate published strategies on the live (mock) tick/candle stream through the engine JAR, persist signals with full score breakdowns, publish to `signals`, and freeze the golden-vector determinism harness (Flow 5 Part B §C-2.14, §C-2.19).

**Why this phase is independent.** All inputs exist (mock candles Stage B Phase 10, registry Phase 21, engine Phases 19–20). Output observable on Redis + REST; the deterministic mock seed makes assertions exact.

**Deliverables.**
- Signal engine: subscribes `ticks.*`/`candles.1m.*` for **published strategies' universes only**; per-consumer latest-value conflation map (plan §8.6); bar-close evaluation on the primary timeframe; additional timeframes via caggs; `max_daily_loss_pct`/session-window gating; engine pinning `(strategy_id, version, checksum)`; hot-swap at next bar boundary on `strategy.changed`; gated by `kite.status` (mock = always live) — full behavior §C-2.14.
- **Pre-close BTST evaluation clock** (A9): a `MarketCalendar`-driven trigger evaluates `style: btst` strategies at `risk.session.pre_close_at` (default 15:20 IST) against the **deterministic pre-close bar view** assembled from cached 1m candles up to that instant (§C-2.11 — identical assembly rule in live and replay); `fill_timing: at_close` signals reference the signal bar close. `[FP-6, owner selection 2026-06-12]`
- **Context-instrument subscriptions** (A7): the engine adds every declared indicator-level `instrument` override to its subscription set (still never a firehose — context symbols are series inputs feeding the shared context-series cache, §C-2.3, and never themselves emit signals). `[FP-19, owner selection 2026-06-12]`
- **`futures_of_underlying` live resolution + roll re-subscribe** (A7/A11): resolves the `front_month` (or `next_month`) contract from the instruments master and re-subscribes to the next contract `roll_days_before_expiry` days before expiry (`MarketCalendar`-aware); live always trades the *actual* contract — the continuous-series replay counterpart is Stage B Phase 15B / Stage D, where the roll-day basis divergence is documented, not hidden. `[FP-11a, owner selection 2026-06-12]`
- **1m exit-level evaluation** (A9): for strategies with `exit_intrabar: true`, stop-loss/take-profit/trailing levels are evaluated on **each closed 1m bar** (the same rule replay applies at the 1m floor — §C-2.11); entry evaluation stays primary-bar-close. `[FP-5, owner selection 2026-06-12]`
- Migration: `signals` table (§C-2.12) incl. `score_breakdown` JSONB + status CHECK; indexes `(generated_at DESC)`, partial `(status) WHERE status='ACTIVE'`, `(exchange, tradingsymbol, generated_at DESC)` (§C-2.12 indexing table).
- Endpoints: `GET /api/v1/signals` (paged/filtered), `/signals/active`, `/signals/{id}` (reasoning payload), `POST /signals/{id}/taken`, `POST /signals/{id}/dismiss`; 15:45 intraday-expiry sweep.
- `signals` channel publish (gateway already relays — Stage A CD-3 STOMP bridge).
- **Golden-vector harness (format per Stage A's `docs/golden-vectors.md` freeze):** `libs/strategy-engine/src/test/resources/golden/` — five days of seeded synthetic 1m NIFTY candles (generated once, frozen) + one strategy YAML per schema feature + expected signal lists; determinism test: tick-wise evaluation ⇒ expected signals byte-identical (**replay half of the parity pair lands Stage D Phase 30** — §C-2.19).
- Metrics `ay_signal_eval_duration_seconds`, `ay_signals_emitted_total`.

**Minimal code/config.** Signal evaluation must **never run on the Redis receive thread** — conflation map + executor decouple it.

**DB changes.** `strategy/V003__signals.sql`.

**Build & Run.**
```
./mvnw -pl services/strategy-signal-service,libs/strategy-engine -am verify
./ay.sh up   # publish the seed strategy, watch: docker exec ay-redis redis-cli subscribe signals
```

**Tests & Verification.**
- Golden determinism suite green (same YAML + candles ⇒ identical signals, scores, breakdowns).
- Golden vectors **extended for the new execution semantics** (A9 — every new semantic gets a vector): a `btst` pre-close/`at_close` fixture, an `exit_intrabar` 1m exit-touch fixture, and a context-series (`instrument` override) fixture; the replay halves land Stage D Phases 30/30A. `[FP-5, FP-6, FP-19, owner selection 2026-06-12]`
- IT: publish seed strategy → signal row + Redis message with breakdown satisfying the renderer invariant; hot-swap mid-stream takes effect only at next bar; dismiss/taken transitions.

**Acceptance criteria.**
- PASS: publishing the seed EMA strategy on the mock stack produces a deterministic signal visible via `GET /signals` and on the `signals` channel; golden fixtures committed and frozen.
- FAIL: evaluation on unsubscribed symbols (firehose); breakdown differing between persisted row and channel payload.

**Commit message.** `feat(strategy-signal): live signal engine with score breakdowns and golden-vector determinism harness`
**PR title.** `Phase 23: live signal engine + determinism goldens`
**Time estimate.** 120–150 min. **Token size target.** ≤ 35k output tokens.
**If phase too big.** (a) engine subscription/evaluation + signals table + publish; (b) REST endpoints + lifecycle sweep; (c) golden harness freeze; (d) 2026-06-12 execution-semantics additions (pre-close clock, context subscriptions, futures roll re-subscribe, 1m exit evaluation) + their golden vectors `[FP-5, FP-6, FP-11a, FP-19, owner selection 2026-06-12]`.

---

### C-3 Phase 24 — OpenAPI contracts + spec-diff CI + TS client generation

**Objective.** Commit the OpenAPI 3.1 contracts for the three running services, gate breaking changes in CI with `openapi-diff`, and generate the typed TS client the frontend will consume (§C-2.18 / CD-8).

**Why this phase is independent.** Pure contract capture of already-shipped endpoints; CI-verifiable.

**Deliverables.**
- `contracts/{edge-gateway,market-data-service,strategy-signal-service}.openapi.json` — dumped from `/v3/api-docs` (springdoc) under mock profile via a build-time IT (CD-8).
- `.github/workflows/ci-contracts.yml` — regenerate → `openapi-diff` vs committed → fail on breaking; lint rule asserting `/api/v1/` prefix + error-envelope schema on every non-2xx. The matrix grows in Stage D Phases 28 and 33 when backtest- and optimizer-service ship their surfaces.
- Generated TS types emitted to `contracts/gen/` (checked in); the `gen:api` npm script lands with the Angular workspace in Phase 25 (avoids pre-seeding `frontend-ui/`, which `ng new` requires empty).
- Gateway-aggregated Swagger UI route for the owner (plan §5.3), mock profile.

**Minimal code/config.** none.

**DB changes.** none.

**Build & Run.**
```
./mvnw verify -Pcontracts          # regenerates specs
npx openapi-typescript contracts/strategy-signal-service.openapi.json -o contracts/gen/strategy.d.ts
```

**Tests & Verification.** CI: removing a response field in a scratch branch fails ci-contracts; additive field passes.

**Acceptance criteria.**
- PASS: three specs committed and diff-gated; generated types compile under `tsc --strict` (checked in CI once the frontend exists).
- FAIL: hand-edited spec files (must be generated).

**Commit message.** `ci(contracts): committed openapi 3.1 specs with breaking-change diff gate and ts client generation`
**PR title.** `Phase 24: OpenAPI contracts + spec-diff CI`
**Time estimate.** 60–90 min. **Token size target.** ≤ 20k output tokens.
**If phase too big.** Not applicable.

---

### C-3 Phase 25 — Angular scaffold + login + app shell + nginx container

**Objective.** Create the Angular 21 SPA (zoneless, standalone, signals-first) with PrimeNG Aura + `--ay-*` tokens, the login page, auth guard, `SessionStore`, and the nginx container served through the gateway (§C-2.21–2.24).

**Why this phase is independent.** Gateway auth endpoints (Stage A Phase 5) and system status (Stage B Phase 17) are live; the shell is verifiable in a browser on the mock stack without any other page.

**Deliverables.**
- `frontend-ui/` — Angular CLI 21 workspace (esbuild builder, zoneless `provideZonelessChangeDetection`, strict + strictTemplates); ESLint 9 (`no-explicit-any`, template a11y) + Prettier + Stylelint (no raw hex); Vitest 3.x + jsdom; environments + dev proxy → `127.0.0.1:8080`.
- PrimeNG 21 Aura + one `--ay-*` token palette (dark default, `.ay-light` toggle, `prefers-color-scheme` first run) — §C-2.23.
- `AppShell`: TopBar (market clock IST, WS status placeholder, theme toggle), collapsible SideNav, lazy RouterOutlet, ToastHost + HTTP interceptor mapping the error envelope to toasts (§C-2.22/2.24).
- `/login` page + auth guard against `GET /api/v1/auth/session`; `SessionStore` (auth status, theme, profile indicator incl. mock-mode banner).
- nginx Dockerfile (1.27-alpine, 32 MB, pre-compressed assets, immutable cache headers, `/healthz`) + compose entry; gateway fallback route now serves the SPA.
- `gen:api` npm script consuming `contracts/` specs into typed clients (Phase 24's `contracts/gen/` output adopted here).
- `.github/workflows/ci-frontend.yml` — ESLint + Prettier + Vitest (stores/services ≥ 70 % line) → production build → nginx image → GHCR; path-filtered `frontend-ui/**`, gitleaks step (plan §9.6).
- Vitest specs: SessionStore transitions, interceptor mapping; `npm run gen:api` types consumed.

**Minimal code/config.** none.

**DB changes.** none.

**Build & Run.**
```
cd frontend-ui && npm ci && npm test && npm run build
./ay.sh up    # browser: http://127.0.0.1:8080 → login → empty shell + mock banner
```

**Tests & Verification.** Vitest green; production build within budgets (initial ≤ 500 KB gz); login→shell→logout in a browser on the mock stack.

**Acceptance criteria.**
- PASS: SPA served through the gateway (same origin, **zero CORS config anywhere**); login round-trip works; bundle budget enforced in `angular.json`.
- FAIL: any hardcoded `localhost:8080` (must be relative/proxy); Zone.js present.

**Commit message.** `feat(frontend): angular 21 zoneless scaffold with primeng aura, login flow and nginx container`
**PR title.** `Phase 25: Angular scaffold + login + shell`
**Time estimate.** 90–120 min. **Token size target.** ≤ 35k output tokens.
**If phase too big.** (a) workspace + tooling + shell; (b) login/SessionStore/interceptor + nginx/compose.

---

### C-3 Phase 26 — Signals page + WS client — **MVP gate**

**Objective.** Ship `WsClientService` (STOMP over native WS with reconnect/backoff) and the signals page with live feed + reasoning breakdown — completing the plan's MVP: a published YAML strategy producing a live signal in the browser end-to-end (§C-2.25–2.26, §C-2.28).

**Why this phase is independent.** Everything upstream exists; this is pure frontend consuming running services.

**Deliverables.**
- `WsClientService` — `@stomp/stompjs` 7.x to `/ws`; heartbeats 10 s/10 s; exponential reconnect (1 s→30 s, jitter); typed topic streams; refcounted subscribe/unsubscribe; on reconnect, stores re-fetch REST snapshots (§C-2.25).
- rAF conflation: per-symbol latest-value map flushed once per frame into signals.
- `SignalsStore` — history pages (REST) + live unshift (bounded ring buffer) + filters; `MarketStore` minimal (connection status via `/topic/system`, 10 s fallback poll of `/api/v1/system/status`).
- `/signals` route: virtualized history table, live feed, `ReasoningBreakdownPanel` (gate checklist with values, stacked contribution bar, composite-vs-threshold gauge — **renders the Phase 20 contract** §C-2.6/2.26).
- Vitest: WsClient reconnect with fake timers; SignalsStore reducers; breakdown rendering invariant.

**Minimal code/config.** none.

**DB changes.** none.

**Build & Run.**
```
cd frontend-ui && npm test && npm run build
./ay.sh up   # publish seed strategy → signal appears on /signals with breakdown
```

**Tests & Verification.**
- On the mock stack: publish the seed strategy → a signal renders live with per-indicator contributions ≤ 150 ms after emission (eyeball + WS timestamps).
- Kill gateway container → UI shows reconnecting → recovery re-syncs history (manual chaos check; automated in Phase 27).

**Acceptance criteria.**
- PASS (**MVP**): mock tick → candle → published strategy → signal → STOMP → browser, zero credentials, on one `ay up`.
- FAIL: polling where the topic exists; unbounded signal buffer.

**Commit message.** `feat(frontend): ws client with reconnect and live signals page with reasoning breakdown (mvp)`
**PR title.** `Phase 26: signals page + WS client (MVP)`
**Time estimate.** 90–120 min. **Token size target.** ≤ 30k output tokens.
**If phase too big.** (a) WsClientService + stores; (b) signals page + breakdown panel.

---

### C-3 Phase 27 — Playwright E2E harness + smoke + ci-e2e

**Objective.** Stand up the Playwright harness against the full mock compose stack and a smoke suite (login, live signals), wired into CI — the regression net for every later UI phase (§C-2.20).

**Why this phase is independent.** Tests the MVP that already runs; later phases only append journeys.

**Deliverables.**
- `e2e/` — Playwright 1.x project; global setup boots `docker compose` mock stack and waits for healthchecks; test-user hash injected via env.
- Smoke journeys: login (cookie flags asserted) → shell; publish seed strategy via API fixture → `/signals` shows the live signal; WS-reconnect chaos test (restart gateway container, assert recovery); `@axe-core/playwright` on the two routes.
- `.github/workflows/ci-e2e.yml` — PRs to main + nightly: compose up (mock) → healthcheck wait → smoke suite → container logs dumped on failure.

**Minimal code/config.** none.

**DB changes.** none.

**Build & Run.**
```
cd e2e && npm ci && npx playwright test
```

**Tests & Verification.** Suite green locally and in CI; deliberately breaking login locally fails the suite.

**Acceptance criteria.**
- PASS: ci-e2e green on main; chaos test passes; axe reports no violations on login/signals; **Stage-C exit** — `PHASE_GATES.md` mirrors the plan §15.2 Phase-2 row (MVP achieved at Phase 26).
- FAIL: suite requiring credentials or market hours.

**Commit message.** `test(e2e): playwright harness on the mock compose stack with smoke journeys and ci gate`
**PR title.** `Phase 27: Playwright E2E harness + smoke`
**Time estimate.** 60–90 min. **Token size target.** ≤ 25k output tokens.
**If phase too big.** Not applicable.

---

## Part 4 — Stage exit gate (the MVP gate)

> The matching acceptance row from **plan §15.2, macro-Phase 2**, inlined as a checklist. This is the input to the **S5 Friday gate ritual** (`PHASE_GATES.md` mirrors this row; an unchecked box extends the phase). **Stage-C exit IS the MVP gate** (§C-2.28). The Phase-2 FT/PT baseline is **3–4 FT weeks / 10–14 PT weeks**, extended by the **+3.0 d** review additions (S8 accrual 1.5 · Q2 0.5 · BPB 1.0 — review ledger §5).

**Key deliverables (plan §15.2 Phase-2):** strategy-engine JAR (ta4j 0.22.x, composite weighted scoring, optional indicators); `strategy-schema/v1` JSON Schema; registry with immutable JSONB versions, checksum, draft→published→archived, publish/rollback/diff endpoints (D18); SIGNAL engine evaluating published strategies on live candles; per-indicator score breakdown persisted; minimal Angular signals page pulled forward (MVP). **Review additions:** `marketdata.index_constituents` point-in-time membership accrual from the NSE constituent CSVs + mock fixture (S8 part, +1.5 d); schema-extensibility note codified at freeze — indicator `name` as engine-registry id so fundamentals later arrive as new names under the unchanged `indicators[]` shape, no schema v2 (Q2, +0.5 d); normative composite-formula/score-breakdown contract serialized identically by live and backtest engines + breakdown persistence (BPB, +1 d).

**Acceptance criteria (demo-able) — the gate checklist:**

- [ ] **Golden-vector tests pin determinism** — same YAML + same candles ⇒ identical signals, scores, and per-indicator breakdowns (the live half of the parity pair is frozen; the replay half lands Stage D). `[Phase 23]`
- [ ] **Publishing a YAML EMA-crossover strategy produces a signal from live/mock ticks, pushed over gateway STOMP, visible in the browser ≤ 150 ms.** `[Phases 23+26 — the MVP statement]`
- [ ] `strategy-schema/v1` is **complete and frozen**: `slippage_bps`, `fees{}`, `objective.fold_aggregation`, `walk_forward`, and `scoring.{optional_min_score, optional_gate_margin}` all present and validated; the A7 freeze-time additions (`1w` timeframe, `risk.session.{pre_close_at, fill_timing, exit_intrabar}`, indicator-level `instrument` override, `universe.mode: futures_of_underlying` + `futures{}`) likewise present and validated `[FP-5, FP-6, FP-8, FP-11a, FP-19, owner selection 2026-06-12]`; the indicator-name enum stays **advisory** (Q2). `[Phase 18]`
- [ ] strategy-engine JAR: ta4j indicators match committed reference vectors exactly; the **normative A1 composite** with optional-activation semantics and the **byte-stable `ScoreBreakdown`** contract are pinned by truth-table + serialization tests; JaCoCo ≥ 70 %. `[Phases 19–20]`
- [ ] Registry: immutable JSONB versions + SHA-256 checksum; full draft→published→archived lifecycle with publish/rollback/diff/validate endpoints; **every mutating call writes an audit row**; the `index_constituents`-universe publish guard (422 `STRATEGY_UNIVERSE_UNSUPPORTED`) is in place. `[Phase 21]`
- [ ] `marketdata.index_constituents` accrual is append-only with point-in-time REST resolution (mock fixture path green; live NSE fetcher gated on source verification); **no cross-schema FK**; survivorship-bias caveat documented. `[Phase 22]`
- [ ] OpenAPI 3.1 specs for the three running services committed and **diff-gated** in CI; generated TS client compiles under `tsc --strict`. `[Phase 24]`
- [ ] Angular 21 SPA (zoneless, signals-first) served **through the gateway, same origin, zero CORS**; login round-trip works; bundle initial ≤ 500 KB gz enforced; no Zone.js, no hardcoded `localhost:8080`. `[Phases 25–26]`
- [ ] `WsClientService` reconnects with backoff and re-syncs REST snapshots; the `/signals` page renders the reasoning breakdown obeying `composite = Σ contributions / weightDenominator`. `[Phase 26]`
- [ ] **ci-e2e green on main**: Playwright smoke (login + live signals) passes, the WS-reconnect chaos test passes, axe reports no violations on login/signals; `PHASE_GATES.md` mirrors this row. `[Phase 27]`

### Stage-end notes

- **What freezes here (do not re-open without an ADR amendment):** `strategy-schema/v1` (Phase 18) and the `ScoreBreakdown` contract (Phase 20). Stage D consumes both unchanged — the `FillSimulator`/cost model (Phase 29) and the replay engine (Phase 30) read the frozen schema; the replay half of the golden parity pair asserts byte-identity against the live half frozen in Phase 23. The 2026-06-12 A7 keys (§C-2.2) freeze **with** the schema — ratified pre-freeze, they are part of `strategy-schema/v1`, not a post-freeze edit; their replay-side consumers (A9 `at_close`/intra-bar semantics, `futures_of_underlying` replay) land in Stage D Phases 29/30/30A unchanged `[FP-5, FP-6, FP-8, FP-11a, FP-19, owner selection 2026-06-12]`.
- **Carried-forward stubs:** the `paper` and `notifier` Modulith modules exist as stubs (Phase 21); `notifications_enabled`/`notification_channel` columns exist on `strategies` now. Their features land in Stage E (notifier, Phase 41) and Stage F (paper ledger, Phase 43).
- **S8 split:** accrual + fetcher landed here (Phase 22); universe **pinning/checksum/editor label** land in Stage F Phase 44. The anti-overfitting de-scope unit (S1A+S1B+S1C+BPC+**S8-pinning**) is a single §15.6 lever-1 unit (Stages D–F) — Stage C's accrual is the only S8 piece that is *not* part of that droppable unit (history is time-sensitive).
- **MVP achieved at Phase 26; Phase 27 is the regression net.** Everything in Stages D–G is additive on top of this gate.
- **Open items still tracked at this gate** (decisions-log §4): NSE index-constituents CSV source verification (before the Phase 22 live fetcher); statutory fee-schedule values (pinned at Stage-D Phase 29 implementation). Neither blocks the MVP demo on the mock stack.










