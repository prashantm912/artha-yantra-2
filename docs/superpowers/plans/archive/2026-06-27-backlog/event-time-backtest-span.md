# Stream — Event/time gates + backtest-fidelity rails + SPAN sell legs

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


Status: PLAN (implementation-ready). Owner: single-owner. Date: 2026-06-27.
Target services: `services/strategy-signal-service` (scalper seam), `services/backtest-service`
(replay + YAML `gate.all`), `services/margin-service` (SPAN), the scalper YAMLs.

> Read order for the executor: this plan is self-contained but assumes the two precedent plans, which
> live ONE directory UP from this backlog folder —
> `../2026-06-27-followup2-soft-dots-to-hard-gates.md` (FU2, the **parity-safe-additive default-OFF
> tag-gate** template) and `../2026-06-27-followup1-expand-manual-checks.md` (FU1, manual checks). Every
> `[P]` change here copies the FU2 `oi-cross-filter` (#5) shape EXACTLY. The CLAUDE.md
> "parity-safe-additive" convention is load-bearing: a change that alters an emitted signal must ride
> a NEW default-OFF strategy tag + a NEW golden variant; existing goldens stay byte-identical.

---

## 1. Goal & the packages/gaps this stream closes

This stream owns the **event/time-window gates**, the **backtest-vs-live fidelity rails**, and the
**SPAN-gated short-premium sell legs** — the leftovers that are time/calendar-driven, backtest-only,
or blocked on the margin appliance. Seven work-packages, **13 AUTOMATE_PKG gaps** plus the
already-FU2-covered Hero-Zero OI leg context:

| Package | # gaps | Effort | Doc-§ (source rows) | P/S |
|---|---:|:--:|---|:--:|
| `event-calendar-lockout` | 4 | M | gap-theory §3.4 L627; gates-strike-sr-fiidii §4.10; intro-terminology §1.2 Time; open-high-low §3.2 (9:15-10:00 window) | **[P]** |
| `backtest-fidelity-rails` | 4 | S | gap-theory §3.4 L628/L629/L631; risk-framework §2.10 r42 | **[S]** |
| `entry-window-230pm` | 1 | S | (single-gap pkg) — afternoon entry-window cap | **[P]** |
| `avoid-friday-skip` | 1 | S | btst-stbt §3.8 risk (`SignalEngine.java:510`) | **[P]** (BTST path) |
| `expiry-entry-timing` | 1 | S | hero-zero §3.7 S21 (`HeroZeroGate.RANGE_FROM` 14:30→~14:45) | **[P]** |
| `time-of-day-preference` | 1 | S | open-high-low / intro-terminology (9:15-10:00 ideal + 10:30 freshness) | **[P]** |
| `short-premium-span` (+ legs) | 5 | L | straddle §3.11 (short straddle ×5); btst-stbt §3.8 (Sell-PE/Sell-CE legs) | **[S]** |

Gap-count rollup: `4 + 4 + 1 + 1 + 1 + 1 + 5 = 17` package-level gaps. (The Hero-Zero ">50% OI"
leg is COVERED_FU2 and is **not** re-counted here; `expiry-entry-timing` is the per-strike-residual's
timing sibling, AUTOMATE_PKG.)

**Theme.** All but `backtest-fidelity-rails` and `short-premium-span` are *time/calendar
preconditions* on the existing confluence seam — small, mechanical, and (where they alter emission)
strictly default-OFF tag-gated. `backtest-fidelity-rails` is **backtest-only** (lift the live HARD
rails into the YAML `gate.all` so a backtest stops over-firing relative to live). `short-premium-span`
is the only **L**: it is blocked on the margin-service appliance (#47) and opens a brand-new SELL
path with no existing golden.

### Stream-level parity classification
- **[P] (alters emitted signals → new default-OFF tag + new golden variant):** `event-calendar-lockout`,
  `entry-window-230pm`, `avoid-friday-skip`, `expiry-entry-timing`, `time-of-day-preference`.
- **[S] (backtest-only / new sell path with no existing golden / read-only):** `backtest-fidelity-rails`
  **(only if delivered via a backtest-only `gate` override — editing the LIVE YAML in place is [P]; see
  §3.2 AUDIT correction)**, `short-premium-span` (+ legs).

---

## 2. Current state (verified file:line)

All line numbers opened against the working tree on 2026-06-27.

### 2.1 The confluence seam — `ScalperConfluenceGate.evaluate(...)`
`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/scalper/ScalperConfluenceGate.java`
- The time-window pre-flight is the FIRST gate (L112-118): `cfg.openingTick()` picks the opening
  window (`ScalperConfig.OPENING_FROM..OPENING_TO`), else `ScalperGates.timeWindow(istTime)`.
- Chain fetched L119-123; `Chart chart = chart(bank, index)` L124.
- Straddle neutral branch L132-147 (returns BEFORE the directional split).
- Side decided L149-152 (CE when `close >= vwap`, else PE).
- `#5 oi-cross-filter` hard gate L196-199 — **the canonical hard-pre-gate insertion template**.
- Each per-strategy gate behind a `cfg.requireXxx()` flag (two-candle L167, gap-fill L173, trend-change
  L204, open-high-low L218, hero-zero L236).
- `ctx` built L191-192; `score` + validity L250-253; empty `Optional` BLOCKS.

### 2.2 The time-rail library — `ScalperGates.java`
`.../scalper/ScalperGates.java`
- `NO_TRADE_BEFORE=09:45` L22, `MIDDAY_BLOCK_FROM=11:00` L23, `MIDDAY_BLOCK_TO=13:00` L24,
  `NO_FRESH_ENTRY_AFTER=15:30` L25.
- `timeWindow(LocalTime)` L33-44 (the ≥09:45 / 11-13 block / <15:30 default rails).
- `timeWindow(LocalTime, from, to)` L53-61 (#9 opening-tick overload).
- **There is NO event-calendar / day-of-week / afternoon-cap rail here** — these are the new gaps.

### 2.3 Tag → flag wiring — `ScalperConfig.java`
`.../scalper/ScalperConfig.java`
- `record ScalperConfig(...)` fields L36-52 (each `requireXxx` boolean / `StructuralStop`).
- `from(JsonNode config, List<String> tags)` L101-157: tag parses L119-153 (two-candle L119,
  gap-theory L121, trend-change L123, open-high-low L125, opening-tick L128, hero-zero L131, straddle
  L135, entry-candle-stop L149, **oi-cross-filter L153** = the #5 template). Constructor returns all
  flags L154-156.
- Opening-tick window constants `OPENING_FROM=09:15` L72, `OPENING_TO=09:30` L73,
  `VWAP_ACTIONABLE_FROM=10:30` L76.
- **Constructor arity is coupled** to the 8 `new ScalperConfig(...)` literals in
  `ScalperConfluenceGateTest` (per the FU2 audit) — every new field forces a positional update of all
  8 literals (compile-time fan-out, not a parity risk).

### 2.4 The Hero-Zero gate — `HeroZeroGate.java`
`.../scalper/HeroZeroGate.java`
- `RANGE_FROM = LocalTime.of(14, 30)` **L75** — the entry floor `expiry-entry-timing` tightens to ~14:45.
- `FRESH_ENTRY_CAP = 15:20` L77.
- `shortCovering(...)` L172-178: `sideDelta < 0` passes — **no magnitude floor** (`drasticFloor`
  is wired by the *connect-the-dots-oi* stream, not here; noted as a cross-stream dependency).

### 2.5 The BTST pre-close clock — `SignalEngine.java`
`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/signals/SignalEngine.java`
- `preCloseClock()` `@Scheduled(cron = "0 * 9-15 * * MON-FRI", ...)` **L510** — **fires every MON-FRI,
  including Friday** (the `avoid-friday-skip` gap). Per-strategy `pre_close_at` match L518.
- `preCloseEvaluate(...)` L532-571: evaluates the daily bar via `EntryEvaluator.evaluate` (L563-564)
  and on entry calls `emitEntry(..., null)` **L567-569** — passing a **null `Decision`**, i.e. the
  BTST path does **NOT** route through `ScalperConfluenceGate` / `StrikePicker` (the load-bearing
  `btst-route-through-gate` finding; out of THIS stream but the avoid-friday skip lands here).
- The intraday scalper path routes through `scalperEntry(...)` L445-468 → `scalperGate.get().evaluate(...)`
  L459-462. `emitEntry(...)` resolves side from `definition().direction()` L591-592 (`SHORT`→SELL else BUY)
  — so `direction: both` does NOT resolve a per-bar side on the BTST emit path (the `btst-side-resolver`
  finding; cross-stream).

### 2.6 The golden/parity firewall
- `GoldenDeterminismTest.FEATURES` (`libs/strategy-engine/.../golden/GoldenDeterminismTest.java` L33-36)
  = `{ema-crossover, optional-indicator-activation, btst-preclose, exit-intrabar, context-series}` —
  **no scalper YAML**. Per-feature: two runs byte-match + frozen `expected/<feature>.signals.json`
  byte-match (L54-71). Regenerate-once via `-Dgolden.generate=true` L61-63.
- The `btst-preclose` golden exists at `libs/strategy-engine/src/test/resources/golden/strategies/btst-preclose.yaml`
  + `expected/btst-preclose.signals.json` — this is the pure-engine BTST preclose shape (no scalper
  confluence) and is the **fixture the `avoid-friday-skip` change must NOT perturb** unless a NEW
  golden variant is added (see §4). **Verified:** the frozen `expected/btst-preclose.signals.json`
  ALREADY emits three entries — Wed `2026-01-07`, Thu `2026-01-08`, **Fri `2026-01-09`** (all at 15:19)
  — and the candle fixtures span Mon 2026-01-05 .. Fri 2026-01-09 (`candles/README.md`). So the Friday
  IS present and IS currently entered: the new `avoid_friday` variant has a real Friday to suppress, and
  the existing golden's Friday entry must stay (the flag is default-OFF). **Critical:** this golden runs
  through `TickwiseGoldenRunner` (the BTST `if (btst)` block L193-210), NOT `SignalEngine` — so the
  Friday-skip must be wired in BOTH places (§3.4 correction).
- `BacktestParityTest.FEATURES` carries no scalper either. The seam is LIVE-only
  (`ScalperConfluenceGate` javadoc L29-33) — it never runs on the deterministic replay path.

### 2.7 Backtest `gate.all` vs live HARD rails (the fidelity gap)
- A scalper YAML's `entry_rules.gate.all` is intentionally trivial (e.g. `scalp-gap-theory-nifty.yaml`
  `gate.all` block L47-50, clauses `close > vwap` L49 / `close > vwma20` L50; `scalp-btst-stbt-nifty.yaml`
  `gate.all` block L89-91, clause `volume > 0` L91). The
  REAL §0B rails (RSI band, 125k/50k volume floor, delta/premium band) live in the **live-only**
  `ScalperConfluenceGate` / `ScalperGates` / `StrikePicker` (§2.1-2.3), which the backtest replay does
  NOT run — so a backtest fires on bars the live gate would block. This is the `backtest-fidelity-rails`
  gap: lift those HARD clauses into the YAML `gate.all` (which the engine DOES evaluate in replay).

### 2.8 The SPAN appliance — `services/margin-service`
- FastAPI service (`app/api.py`): `POST /api/v1/margin` (raw SPAN) + `POST /api/v1/margin/size`
  (rail-bounded advisory lots/qty/margin/RR). `size()` is **advisory, never blocks** (api.py L31-34).
- Built **dormant** (MEMORY span-margin-appliance: #47 BUILT via #126; live-verify pending — real
  `.spn` broker-parity golden + NSE download URL outstanding). `StraddleLegPicker` returns **BUY legs
  only** (javadoc L23-24); the short straddle / BTST-STBT sell legs are unbuilt. This package is the
  consumer of the appliance once a SELL path is opened.

---

## 3. Design (per package)

### 3.1 `event-calendar-lockout` (4 gaps) — [P]

**Gaps:** (a) a TRUE "no fresh entry before a scheduled economic event after 15:30" lockout (gap-theory
§3.4 L627; gates-strike-sr-fiidii §4.10; intro-terminology §1.2) — needs an **economic-event feed**
(none exists; `MarketCalendar` is a static NSE-holiday CSV, §2.6). (b)-(d) the 9:15-10:00 ideal window +
10:30 freshness cut (open-high-low §3.2, currently only the ≥09:45 floor exists). These collapse into
ONE gate with two arms (a calendar-event arm + an ideal-window arm).

**Data flow.** A new economic-event source → market-data → consumed by the seam as a per-day flag.

**Step 1 — the event feed (market-data, [S] read-only).**
`services/market-data-service/.../marketdata` gains an `EconomicEventCalendar` reader (mirror the
`ConnectingDotsService` analytics shape): a thin `economic_events(date)` →
`List<EventWindow{instant, severity}>` over a NEW table `marketdata.economic_events`
(date, time_ist, title, severity HIGH/MED/LOW, source). Seed manually / via an admin POST (no public
free feed is assumed; this matches the `nse_eod_participant_oi` ingest pattern). Expose
`GET /api/v1/market/events?date=` returning `{items:[{time, severity, title}]}`.

**Step 2 — the seam gate (`ScalperGates` + `ScalperConfluenceGate`, [P]).**
Add to `ScalperGates`:

```java
  static final LocalTime EVENT_LOCKOUT_AFTER = LocalTime.of(15, 30); // §4.10 "events after 3:30 → off"
  static final LocalTime IDEAL_FROM = LocalTime.of(9, 15);
  static final LocalTime IDEAL_TO   = LocalTime.of(10, 0);

  /** Block a fresh entry after 15:30 IST when a HIGH-severity event is scheduled later today. */
  public static GateOutcome eventLockout(LocalTime ist, boolean highSeverityEventToday) {
    boolean block = highSeverityEventToday && !ist.isBefore(EVENT_LOCKOUT_AFTER);
    return new GateOutcome(!block, null, block ? "event after 15:30 lockout" : "no event lockout");
  }

  /** Restrict fresh entries to the 09:15-10:00 ideal window (opt-in; the ≥09:45 floor stays default). */
  public static GateOutcome idealWindow(LocalTime ist) {
    boolean ok = !ist.isBefore(IDEAL_FROM) && ist.isBefore(IDEAL_TO);
    return new GateOutcome(ok, null, ok ? "within 09:15-10:00" : "outside ideal window");
  }
```

In `ScalperConfig`: add `boolean requireEventLockout` + `boolean requireIdealWindow`, parsed from new
tags `event-lockout` and `ideal-window` (FU2 template, §2.3). In `ScalperConfluenceGate.evaluate`,
immediately after the existing time-window check (L118), add two default-OFF early-returns:

```java
    // AUDIT pass-2 fix: the event lockout asks "is a HIGH-severity event scheduled LATER TODAY",
    // so it must use the LIVE BAR'S IST DATE — NOT `eodDate` (that is the prior completed session
    // used for breadth/FII; see MarketOiClient.context javadoc + the §2.1 tradeDate-vs-eodDate split).
    // `tradeDate` is derived from `barInstant` (a parameter already in scope), but the production
    // code only computes it at L182 — so compute it here (or move this early-return below L182). The
    // ideal-window arm needs no date.
    LocalDate evtDate = barInstant.atZone(Ist.ZONE).toLocalDate(); // = the §2.1 tradeDate
    if (cfg.requireEventLockout()
        && !ScalperGates.eventLockout(istTime, client.highSeverityEventToday(evtDate)).pass()) {
      return Optional.empty();
    }
    if (cfg.requireIdealWindow() && !ScalperGates.idealWindow(istTime).pass()) {
      return Optional.empty();
    }
```

`MarketOiClient` gains a `highSeverityEventToday(LocalDate)` passthrough to the new market-data
endpoint (cached per-day; degrades to `false` = never blocks on a feed miss, mirroring the VIX
fail-open). **AUDIT pass-2 — date correctness:** pass `tradeDate` (the live bar's IST date), not
`eodDate`. The original snippet passed `eodDate` (the prior session), which would query *yesterday's*
event calendar and silently mis-fire the lockout. Note `Ist.ZONE` is already imported (`import
in.arthayantra.common.web.time.Ist;` at the top of `ScalperConfluenceGate`), and `barInstant` is an
existing `evaluate(...)` parameter, so `barInstant.atZone(Ist.ZONE).toLocalDate()` (the exact
expression L182 uses for `tradeDate`) compiles without new imports.

### 3.2 `backtest-fidelity-rails` (4 gaps) — [S] *(but see the AUDIT correction below — the naïve "edit the live YAML" is [P], not [S])*

**Gaps:** the live HARD rails (RSI band, volume floor, delta/premium band, ≥1yr publish gate) are NOT
in the backtest path. Lift the **expressible** ones so the engine evaluates them in replay.

> **AUDIT pass-1 correction (parity-critical).** The original plan said "edit each scalper YAML's
> `entry_rules.gate.all`" and classified it **[S] / YAML-only / no live-signal change**. That is
> **WRONG**: the scalper YAMLs under
> `services/strategy-signal-service/.../scalper-strategies/` are the **LIVE** strategy definitions, and
> `SignalEngine.evaluateAtBarClose` runs `EntryEvaluator.evaluate(strategy.definition(), bank, index)`
> — which evaluates `entry_rules.gate.all` — **BEFORE** the confluence seam
> (`SignalEngine.java:427-431`). Tightening `gate.all` on the shared YAML therefore changes what the
> LIVE chart gate returns, i.e. it CAN move emitted signals → it is **[P]**, not [S]. (In practice the
> live seam already re-applies the RSI band + volume floor in `ScalperConfluenceGate.evaluate`
> L157-163, so the FINAL live emission is usually unchanged — but "usually" is not "byte-identical", and
> the engine `evaluation.entry()` boolean DOES change, so this cannot be asserted parity-safe by
> inspection.) **Do NOT edit the live YAML in place.** Use ONE of the two backtest-only routes below so
> the live path is untouched and `[S]` holds honestly:
> - **(Recommended) a backtest-only `gate.all` override.** Add the RSI/volume clauses to a **separate
>   backtest variant** of each scalper (a `*-bt.yaml` sibling, or a `backtest.gate_override.all` block
>   the backtest submission merges into `gate.all` and the LIVE loader ignores). The live registry
>   keeps the trivial gate; only the replay sees the tightened gate. This is the clean [S].
> - **(Alt) lift the rails into the backtest replay selector/pre-flight code**, never the YAML — same
>   place the delta/premium band already lives (the `strike-premium-band-backtest` package). Heavier
>   but keeps all rail logic in one backtest-only seam.
>
> Either route keeps the 36 LIVE YAMLs byte-identical (so the FU2/CLAUDE.md parity guarantee holds) and
> still makes the BACKTEST fire on fewer bars. The text below is rewritten to the recommended route.

**Per-rail change (each scalper's BACKTEST-only `gate.all` override — NOT the live YAML):**
- **RSI band** (gap-theory row L628): add `"rsi14 > 60"` + `"rsi14 < 80"` for a CE family, the mirror
  `20..40` for a PE family (the §0B band from `ScalperGates.rsiBand` L76-84 — the gate is
  `v > 60.0 && v < 80.0` for CE, `v > 20.0 && v < 40.0` for PE). For #2 open-high-low use the relaxed
  `"rsi14 > 50"` (`ScalperGates.rsiAbove` L92-99, floor from `ScalperOiProps.openHighRsiFloor` default 50).
- **Volume floor** (gap-theory row L629): add `"volume >= 125000"` (NIFTY) / `"volume >= 50000"`
  (SENSEX/index) — the `ScalperGates.volume` floors (`NIFTY_VOL`/`INDEX_VOL` L28-29; `VOL_FLOOR` keys
  only `"NIFTY 50"`, all else falls to 50k via `getOrDefault`, L30/L65).
- **Delta/premium band** (gap-theory §3.4 L631): NOT expressible in `gate.all` (the engine has no
  delta/premium operand in replay — the backtest selector picks nearest-strike-to-spot, see
  `OptionsPremiumReplay` javadoc L41-42 + `ScalperConfig` L90-92). Surface it into the backtest
  **selector** instead — this overlaps the `strike-premium-band-backtest` package (another stream);
  here we only RECORD the dependency and add the RSI+volume clauses.
- **≥1yr publish gate** (risk-framework row r42): a publish-time check (backtest coverage ≥ 1 year
  before a strategy may be published live). Add to the registry publish path
  (`RegistryService` / `RegistryController` in `services/strategy-signal-service/.../registry`) — a
  guard that reads the latest backtest run's window span for the strategy and warns/blocks publish if
  `< 365d`. Advisory by default (a `publish.require_backtest_coverage_days` config, default 0 = off).
  **AUDIT note (completeness):** the registry lives in **strategy-signal-service** but `backtest_runs`
  (the window-span source) lives in **backtest-service's `ay_backtest` schema** — this is a
  cross-service read. strategy-signal-service has no backtest-DB datasource today, so the guard needs
  EITHER a new market-data-style HTTP client call to backtest-service (a `GET
  /api/v1/backtests/coverage?strategyId=` it does not yet expose — a new read endpoint to add) OR the
  coverage span must be passed in on the publish request. Pick the route in PR-4; do NOT assume a direct
  DB read. This is why the gate is **advisory default-0** (it can ship inert before the read path lands).

**Why [S] (with the correction above applied).** With the rails in a backtest-only override (not the
live YAML), the change is backtest-only: the tightened gate makes the BACKTEST fire on fewer bars
(closer to live), the live seam is untouched, and there is **no scalper golden** (the engine goldens
carry no scalper YAML, §2.6 — the 36 scalper YAMLs are not in `GoldenDeterminismTest.FEATURES`/
`BacktestParityTest.FEATURES`), so no existing golden moves. **Had the rails been added to the live
YAML in place, this would be [P]** (see the correction box).

> **Caveat (record in each YAML header):** per CLAUDE.md, OI/Dow/VIX degrade to NEUTRAL on derived
> history, but RSI + volume are real on the futures-spine series — so these two rails ARE faithful in
> backtest. The delta/premium band is the one that stays backtest-approximate (selector picks nearest
> strike), hence its deferral to `strike-premium-band-backtest`.

### 3.3 `entry-window-230pm` (1 gap) — [P]

**Gap:** an afternoon entry-window cap (the deck's "morning scalp; no fresh directional entry into the
last ~hour except Hero-Zero" — distinct from the 15:30 hard cap). Encode a configurable
`no-fresh-entry-after-1430` style cap for the **core directional** scalpers (Hero-Zero is exempt — it
fires AFTER 14:30 by design, `HeroZeroGate.RANGE_FROM`).

**Change.** `ScalperGates` gains `afternoonCap(LocalTime ist, LocalTime cap)`; `ScalperConfig` gains
`Optional<LocalTime> afternoonCap` parsed from a new `entry-cap-230pm` tag (defaulting the cap to
14:30, configurable via a `risk.session.fresh_entry_cap` YAML key). In `evaluate`, after the time
window:

```java
    if (cfg.afternoonCap().isPresent() && !cfg.afternoonCap().get().isAfter(istTime)) {
      return Optional.empty(); // no fresh directional entry after the cap (Hero-Zero is a different path)
    }
```

Hero-Zero strategies never carry the tag (their own gate owns the 14:30→15:20 window), so the cap can
never collide with the Hero-Zero floor.

### 3.4 `avoid-friday-skip` (1 gap) — [P] (BTST path)

**Gap:** `preCloseClock()` (§2.5, `SignalEngine.java:510`) fires every MON-FRI including Friday;
the deck skips BTST on Friday (weekend-gap risk). No gate exists.

**Change.** Add a `boolean avoidFriday` to the engine `StrategyDefinition.Session` record
(`libs/strategy-engine/.../config/StrategyDefinition.java` L68-78) read from a new YAML key
`risk.session.avoid_friday: true`. **AUDIT corrections (soundness + completeness) — three call sites,
not one:**

1. **`StrategyCompiler.compileSession`** (`.../config/StrategyCompiler.java` L175-198, the `new
   StrategyDefinition.Session(...)` literal at L187-197) must parse
   `node.path("avoid_friday").asBoolean(false)` and pass it as the NEW trailing `Session(...)` arg —
   else the field is always its zero-value and the YAML key is dead. Adding a `Session` field is a
   **compile-time fan-out**. **AUDIT pass-2 — corrected literal list (grepped `new (StrategyDefinition\.)?Session\(`):**
   every `new ...Session(...)` literal must gain the trailing `false`:
   - `StrategyCompiler.compileSession` L187 (production, strategy-engine)
   - `libs/strategy-engine/.../golden/SessionGateTest.java` L21-22 (helper)
   - `libs/strategy-engine/.../eval/EvalFixtures.java` L94-95 (helper)
   - **`services/backtest-service/.../replay/TouchBasisClassifierTest.java` L30** — a real `new
     Session(...)` literal in a DIFFERENT service that pass-1 MISSED. This is the load-bearing
     correction: adding a `Session` field breaks `TouchBasisClassifierTest`'s compilation in
     **backtest-service**, so the change cannot be built/verified with only the strategy-engine lib
     or strategy-signal-service — **backtest-service must recompile too** (`-pl
     services/backtest-service -am`). The verify in §5.4/§7 must cover it.

   **Pass-1 named `StrategyCompilerTest.java` here, but that file has NO `new Session(...)` literal** —
   it asserts on `definition.session().*` via `StrategyCompiler.compile(...)`, so it needs an ADDED
   assertion (covered in §5.4), not a constructor-arity edit. (Replace `StrategyCompilerTest` in this
   fan-out list with `TouchBasisClassifierTest`.)
2. **Live path — `SignalEngine.preCloseClock`** (`SignalEngine.java:510`): skip when FRIDAY + flag set.
   ```java
       if (strategy.definition().session().avoidFriday()
           && today.getDayOfWeek() == java.time.DayOfWeek.FRIDAY) {
         continue; // §3.8 risk: no BTST carry over the weekend gap
       }
   ```
3. **Golden/replay path — `TickwiseGoldenRunner.run`** (`libs/strategy-engine/.../golden/TickwiseGoldenRunner.java`
   L193-210, the `if (btst)` block): the BTST golden does **NOT** run through `SignalEngine` — it runs
   through this runner. The §5.4 `btst-preclose-friday-skip` golden can ONLY observe the skip if the
   runner ALSO honours the flag. Add, inside the `if (!preCloseDone && !barClose.isBefore(preCloseAt))`
   guard (or before the entry add), a `barDay.getDayOfWeek() == FRIDAY && definition.session().avoidFriday()`
   skip that suppresses the Friday `entryEvent`. **Verified blocker:** the existing
   `expected/btst-preclose.signals.json` ALREADY emits a Friday entry — `2026-01-09T15:19+05:30` (the
   fixture spans Mon 2026-01-05 .. Fri 2026-01-09 per `candles/README.md`) — so without (3) the new
   variant would NOT show a suppressed Friday and the test claim is false.

**Parity.** [P] — it suppresses a Friday BTST entry. The change in (3) touches the SHARED
`TickwiseGoldenRunner.run` method, so it MUST be gated behind `definition.session().avoidFriday()`
(default-OFF). The existing `btst-preclose.yaml` carries no `avoid_friday` key → `avoidFriday()==false`
→ the runner is byte-identical for it (the existing `btst-preclose` golden, which DOES contain the
Friday 2026-01-09 entry, is unchanged). A NEW golden variant (`btst-preclose-friday-skip`) reuses the
SAME 5-day fixture + the flag and proves `2026-01-09` is suppressed (see §5.4). **AUDIT pass-2 fact
correction:** the existing `btst-preclose` golden emits exactly THREE entries — Wed `2026-01-07`, Thu
`2026-01-08`, Fri `2026-01-09` — NOT five (SMA(3) on 1d cannot form on Mon/Tue, so there are no Mon/Tue
entries; verified in `expected/btst-preclose.signals.json`). So the variant must show the Friday
SUPPRESSED with the **Wed + Thu** entries remaining (two entries), NOT "Mon/Tue/Wed/Thu".

### 3.5 `expiry-entry-timing` (1 gap) — [P]

**Gap:** Hero-Zero `RANGE_FROM = 14:30` (`HeroZeroGate.java:75`); S21/S23 tightens to ~14:45.

**Change.** This is a behavioural threshold change on a SHIPPED gate, so it MUST be opt-in to stay
parity-safe. Two options:
- **(Recommended)** make the floor a `ScalperOiProps`-style config knob:
  `artha.scalper.herozero.range-from` (default **kept at 14:30**, so existing Hero-Zero configs are
  byte-identical) and read it in `HeroZeroGate.evaluate` instead of the `RANGE_FROM` constant
  (`HeroZeroGate.java:75`). The owner sets 14:45 on the forward-paper variant only.
  **AUDIT note (soundness):** `HeroZeroGate.evaluate` is a **static** method and takes no props today
  (its sole call site is `ScalperConfluenceGate.evaluate` L236-240, plus the `HeroZeroGateTest` cases).
  A `@ConfigurationProperties` bean can't be read from a static method — so the knob must be **threaded
  as a new `LocalTime rangeFrom` parameter** to `evaluate(...)` (sourced in `ScalperConfluenceGate` from
  a new injected `HeroZeroProps` bean, defaulting to 14:30), which forces a positional update to the
  one production call site AND every `HeroZeroGate.evaluate(...)` test call. Do NOT model it as a free
  `ScalperOiProps` field read inside the static method — that won't compile. (Either thread the param,
  or convert `HeroZeroGate` from a static util to an injected `@Component` — the param route is the
  smaller, FU2-shaped change.)
- (Alt) a new `hero-zero-late` tag that swaps 14:30→14:45. Heavier (a tag for one constant).

Either way the DEFAULT stays 14:30 (no shipped Hero-Zero YAML changes), so no existing Hero-Zero
behaviour moves. Because Hero-Zero is LIVE-only (no Hero-Zero golden — the seam is live-only, §2.1),
this is parity-safe by construction; the "new golden variant" requirement is satisfied by a seam
unit test, not an engine golden.

### 3.6 `time-of-day-preference` (1 gap) — [P]

**Gap:** the 9:15-10:00 ideal entry window + the 10:30 freshness cut (intro-terminology §1.2; open-high-low).
This is the **same `idealWindow` arm** built in §3.1 plus a 10:30 freshness cut for the open-high-low
"~90% of OH hit before 10:30" rule.

**Change.** Reuse `ScalperGates.idealWindow` (§3.1) for the 9:15-10:00 arm. Add a separate
`freshnessCut(LocalTime ist, LocalTime cut)` that blocks fresh OH entries after 10:30 when the
`open-high-low` family opts in (a `oh-freshness-1030` tag, default-OFF). Consume it inside the existing
`requireOpenHighLow` block in `evaluate` (L218-229), or as its own early-return. Default-OFF; new
golden N/A (live-only seam) → seam unit test.

### 3.7 `short-premium-span` (+ legs) (5 gaps) — [S]

**Gaps (straddle §3.11 ×4 + btst-stbt §3.8 ×1):** the SHORT straddle (SELL ATM CE + PE), the short
entry trigger (price below both-leg VWAP after 09:30), the short exit (decay/EOD/VWAP-rebreak), the
hard-SL-above-VWAP, and the BTST/STBT SELL legs (Sell-PE for BTST, Sell-CE for STBT). All blocked on
SPAN sizing (margin-service #47, §2.8).

**Design (a brand-new SELL path; no existing golden → [S]).**
1. **`StraddleLegPicker` short variant.** Add a `pickShort(...)` returning the same two ATM legs but
   marked `Side.SELL`. Today it returns BUY-only (javadoc L23-24). Gated behind a new `short-straddle`
   tag on `ScalperConfig` (`requireShortStraddle`); the straddle branch in `evaluate` (L132-147)
   chooses `pickShort` vs `pick` on the flag.
2. **SPAN sizing call.** Before emitting a SELL leg, `ScalperConfluenceGate` (or the paper/order layer)
   calls `POST /api/v1/margin/size` (margin-service, §2.8) to get the rail-bounded lots; if the SPAN
   feed is unavailable (the appliance is dormant), the SELL path is **suppressed** (fail-closed — never
   a naked unsized short). This is the hard dependency: **§6 sequencing — SPAN must be live before any
   SELL leg fires.**
3. **BTST/STBT SELL legs.** In the BTST emit path, once `btst-route-through-gate` + `btst-side-resolver`
   (other stream) land, add the Sell-PE (BTST) / Sell-CE (STBT) leg behind a `btst-sell-leg` tag, also
   SPAN-sized.
4. **Short exits.** A combined-premium-VWAP-rebreak + EOD-decay exit on the SELL legs — these are LIVE
   market-data series (the deterministic seam cannot recompute them; deferred to live management, same
   as the existing long-straddle live-deferred entry trigger).

**Why [S].** There is NO existing SELL golden and no shipped SELL strategy — this is a brand-new
variant path. It cannot perturb any existing golden (none covers SELL). Parity is satisfied by:
(a) the SELL path is tag-gated default-OFF, (b) margin-service is the hard gate (dormant → SELL never
fires), (c) a NEW golden/parity fixture covers the SELL replay if/when the deterministic portion (leg
selection + premium-pct SL) is exercised in `OptionsPremiumReplay`.

---

## 4. PARITY classification (every change)

| Change | Class | Tag (new, default-OFF) | Golden-variant plan |
|---|:--:|---|---|
| §3.1 event lockout + ideal window | **[P]** | `event-lockout`, `ideal-window` | Live-only seam → **seam unit tests** (pass/block/non-tagged-unaffected), no engine golden. Existing goldens carry no scalper → byte-identical. |
| §3.2 backtest RSI+volume rails | **[S] ONLY via a backtest-only override** (editing the LIVE `gate.all` in place is **[P]** — see the §3.2 correction box; `SignalEngine.java:427-431` runs `gate.all` live) | none (backtest-only `gate_override`/`*-bt.yaml`, NOT a tag) | LIVE 36 YAMLs stay byte-identical; the 36 are NOT in `GoldenDeterminismTest.FEATURES`/`BacktestParityTest.FEATURES` → engine goldens byte-identical. New **backtest determinism** assertion that the tightened override replays twice byte-identically (a re-run, not a frozen golden). |
| §3.2 ≥1yr publish gate | **[S]** | `publish.require_backtest_coverage_days` (default 0=off) | Publish-path guard; no signal emission, no golden. |
| §3.3 afternoon 14:30 cap | **[P]** | `entry-cap-230pm` | Live-only seam → seam unit test. Default-OFF → no shipped config moves. |
| §3.4 avoid-Friday BTST skip | **[P]** | `risk.session.avoid_friday` (Session flag) | **NEW engine golden variant** `btst-preclose-friday-skip` reusing the SAME 5-day fixture (Mon 2026-01-05 .. Fri 2026-01-09) + the flag — additive FEATURES entry + generate-once `expected/*.signals.json` proving the `2026-01-09` Friday entry SUPPRESSED. **Requires the change in BOTH `SignalEngine.preCloseClock` (live) AND `TickwiseGoldenRunner.run` L193-210 (the golden's actual BTST path) — see §3.4 correction.** Existing `btst-preclose` golden (no flag → `avoidFriday()==false`) stays byte-identical (still emits the Friday entry). |
| §3.5 Hero-Zero 14:30→14:45 floor | **[P]** | `artha.scalper.herozero.range-from` (default 14:30) | Live-only seam → seam unit test. Default keeps 14:30 → byte-identical. |
| §3.6 9:15-10:00 + 10:30 freshness | **[P]** | `ideal-window` (reused), `oh-freshness-1030` | Live-only seam → seam unit tests. Default-OFF. |
| §3.7 short straddle + BTST/STBT sell legs | **[S]** | `short-straddle`, `btst-sell-leg` | Brand-new SELL path, no existing golden. NEW SELL parity fixture in `OptionsPremiumReplay` if the deterministic leg-selection portion is exercised; live exits are live-deferred. SPAN-gated (fail-closed). |

**Rule applied throughout:** every `[P]` change is an `if (cfg.requireXxx() && !gate.pass()) return
Optional.empty();` early-return behind a tag/flag that is **absent from all 36 shipped YAMLs**
(default-OFF), so every existing config is byte-identical today. `[S]` changes are backtest-only,
publish-time, or a new SELL variant with no existing golden — they cannot move a frozen vector.

---

## 5. Tests (exact files/cases)

### 5.1 Unit — gate functions (`ScalperGatesTest.java`)
- `eventLockoutBlocksAfter1530WhenHighSeverityEventToday` + `eventLockoutPassesWhenNoEvent` +
  `eventLockoutPassesBefore1530`.
- `idealWindowOnlyInside0915To1000`.
- `afternoonCapBlocksAtOrAfterCap`.
- `freshnessCutBlocksAfter1030`.

### 5.2 Unit — seam wiring (`ScalperConfluenceGateTest.java`)
For EACH of `event-lockout`, `ideal-window`, `entry-cap-230pm`, `oh-freshness-1030`: one new
`*_CFG` literal + a 3-test triple (pass when in-window/no-event, block when out, **non-tagged CFG
unaffected**) — mirroring the FU2 `oi-cross-filter` template. **Update all 8 existing
`new ScalperConfig(...)` literals** for the added record fields (append `false`/empty in positional
order). Add `requireEventLockout`/`requireIdealWindow`/`afternoonCap`/freshness assertions to
`ScalperStrategyLoadTest` proving they are OFF for every seeded YAML (the regression tripwire).

### 5.3 Unit — Hero-Zero floor (`HeroZeroGateTest.java`)
- `defaultRangeFromIs1430` (byte-identity tripwire).
- `configurableRangeFromTo1445BlocksBetween1430And1445` (the opt-in tighten).

### 5.4 Engine golden — avoid-Friday BTST skip
- **Pre-req (see §3.4):** wire `avoid_friday` into `StrategyCompiler.compileSession` AND
  `TickwiseGoldenRunner.run` (L193-210) — NOT just `SignalEngine`. The golden runs through the runner;
  without the runner change the new fixture would still emit the Friday entry and this test would be a
  false pass.
- Add `btst-preclose-friday-skip` to BOTH `GoldenDeterminismTest.FEATURES` (L33-36, in
  **`libs/strategy-engine`**) and `BacktestParityTest.FEATURES` (L35-38, in
  **`services/backtest-service/.../replay/BacktestParityTest.java`** — NOT in strategy-engine; AUDIT
  pass-2 path correction) — they are two separate arrays and the parity test reuses the
  engine fixtures, so a new FEATURE must be added to both or BacktestParityTest won't cover it.
  **AUDIT pass-2 — single chokepoint confirmed:** `BacktestParityTest` runs the replay through
  `ReplayEngine.replay`, which delegates signal generation to `TickwiseGoldenRunner.run`
  (`ReplayEngine.java:90-92`). So wiring the Friday-skip into `TickwiseGoldenRunner.run` covers ALL
  THREE consumers (`GoldenDeterminismTest` direct + `BacktestParityTest` via `ReplayEngine` + the live
  `SignalEngine`); there is NO separate replay BTST path to patch. Author
  `golden/strategies/btst-preclose-friday-skip.yaml` (the existing `btst-preclose.yaml` + the
  `risk.session.avoid_friday: true` flag) over the **existing** 5-day fixture (it already spans Mon
  2026-01-05 .. Fri 2026-01-09 — no new candle CSV needed); generate-once
  `golden/expected/btst-preclose-friday-skip.signals.json` (`-Dgolden.generate=true`) showing the
  `2026-01-09T15:19+05:30` Friday entry SUPPRESSED (the **Wed `2026-01-07` + Thu `2026-01-08`** entries
  remain — those are the only two non-Friday entries; SMA(3) cannot form on Mon/Tue, so there is no
  Mon/Tue entry to "remain"). **Re-run
  `GoldenDeterminismTest` and `BacktestParityTest` and assert the 5 existing FEATURES stay
  byte-identical** (the existing `btst-preclose` carries no flag → `avoidFriday()==false`).
- Add `StrategyCompilerTest` coverage that `avoid_friday: true` compiles to `session().avoidFriday()==true`
  (and the default is `false`). **AUDIT pass-2 — corrected fan-out:** update the `new Session(...)`
  fixture literals in `EvalFixtures` (L94) / `SessionGateTest` (L21) / **`TouchBasisClassifierTest`
  (backtest-service, L30)** for the new trailing field — NOT `StrategyCompilerTest` (it has no `new
  Session(...)` literal; it gets the new ASSERTION above instead). The `TouchBasisClassifierTest` edit
  lives in backtest-service, so `mvnw -pl services/backtest-service -am verify` is REQUIRED for this PR
  (a strategy-engine-only verify would miss the broken backtest-service compile).

### 5.5 Backtest-fidelity-rails — replay regression (`backtest-service`)
- A test that a tightened scalper YAML (`gate.all` with the RSI+volume clauses) **replays
  deterministically twice byte-identically** (not a frozen golden — a determinism re-run) and that the
  trade count drops vs the trivial-gate version on a fixture where a sub-floor-volume bar previously
  fired (proving the rail bites in backtest).

### 5.6 SPAN sell legs
- `StraddleLegPickerTest.pickShortReturnsSellLegs`.
- A seam test that a `short-straddle` CFG is **suppressed when margin-service size returns no lots /
  the appliance is unavailable** (fail-closed) and emits two SELL legs when sizing succeeds (mock the
  margin client).
- `margin-service` `pytest` already covers `/size` (`tests/test_sizing.py`) — extend with a short-
  straddle basket sizing case if not present.

### 5.7 e2e
- No new e2e in the default-OFF PRs (nothing observable changes; `e2e/tests/signals.spec.ts` stays
  green as a regression check). When a tag is armed on a published strategy (owner-driven, like FU2
  PR-3), add the e2e assertion there.

---

## 6. Dependencies & sequencing

```
event feed (market-data table + endpoint)  ──► §3.1 event-lockout gate
                                                  (gate needs the feed wired first)

SPAN/margin-service LIVE (#47, real .spn + URL) ──► §3.7 short-premium sell legs
                                                       (SELL leg fail-closed until SPAN sizes it)

btst-route-through-gate + btst-side-resolver  ──► §3.7 BTST/STBT SELL legs
  (OTHER stream — BTST must route through the                (sell leg rides the routed BTST path)
   confluence gate + resolve `both`→side first)

(independent, no upstream): §3.2 backtest rails, §3.3 afternoon cap, §3.4 avoid-Friday,
                            §3.5 Hero-Zero floor, §3.6 ideal/freshness windows
```

Hard ordering:
1. **`event-calendar-lockout`:** the feed (market-data table + ingest + endpoint) MUST land before the
   seam gate can consume `highSeverityEventToday` — else the gate is starved (degrades to never-block,
   i.e. dead config).
2. **`short-premium-span`:** the margin-service appliance MUST be live (real `.spn` + NSE download URL,
   the outstanding #47 live-verify) before ANY sell leg fires — the SELL path is fail-closed on SPAN.
   The BTST/STBT sell legs ALSO depend on the cross-stream `btst-route-through-gate` (the BTST path
   currently emits with a null `Decision`, §2.5 — no StrikePicker, no leg selection).
3. The five time/calendar gates (§3.3-3.6 + the ideal-window arm of §3.1) are independent and can ship
   in any order; each is a self-contained default-OFF tag/flag + seam test.

Cross-stream note: `expiry-entry-timing` (§3.5) is adjacent to the connect-the-dots-OI stream's
`drastic-oi-floor` wiring into `HeroZeroGate.shortCovering` (§2.4) — coordinate so both land in one
Hero-Zero touch if scheduled together.

---

## 7. Effort (S/M/L) + suggested PR breakdown

| PR | Scope | Effort |
|---|---|:--:|
| **PR-1** `feat(strategy-signal): time-window opt-in gates (ideal-window, entry-cap-230pm, oh-freshness-1030, default-off)` | §3.3 + §3.6 + the `ideal-window` arm of §3.1 (no feed): `ScalperGates` fns + `ScalperConfig` flags + seam early-returns + seam/unit tests + load-test OFF assertions | **S** |
| **PR-2** `feat(strategy-engine + strategy-signal + backtest): avoid-Friday BTST skip + new golden variant` | §3.4: `Session.avoidFriday` flag (strategy-engine) + `compileSession` parse + `TickwiseGoldenRunner.run` skip + `preCloseClock` skip (strategy-signal) + `btst-preclose-friday-skip` golden (additive FEATURES in BOTH arrays + generate-once expected). **AUDIT pass-2: touches THREE Maven modules** — the `new Session(...)` fan-out includes `TouchBasisClassifierTest` in **backtest-service** and the FEATURES edit is in `BacktestParityTest` (backtest-service), so verify `-pl services/backtest-service -am` too (a strategy-signal-only verify misses the broken backtest-service compile). | **S** |
| **PR-3** `feat(strategy-signal): configurable Hero-Zero entry floor (default 14:30)` | §3.5: `HeroZeroGate` reads `range-from` config (default unchanged) + tests | **S** |
| **PR-4** `feat(backtest): lift live RSI/volume rails into a backtest-only gate override (+ ≥1yr publish gate)` | §3.2 **(corrected)**: a **backtest-only** `gate_override`/`*-bt.yaml` carrying RSI+volume per family (the LIVE 36 YAMLs stay byte-identical — editing them in place is [P]) + the publish-coverage guard (a NEW cross-service coverage read, see §3.2 AUDIT note) + a backtest determinism/trade-count test. Note the delta/premium-band dependency on `strike-premium-band-backtest` | **M** |
| **PR-5** `feat(market-data + strategy-signal): economic-event feed + event-lockout gate` | §3.1 (the calendar arm): new `marketdata.economic_events` table (Flyway, new suffix migration) + ingest/admin POST + `GET /market/events` + `MarketOiClient.highSeverityEventToday` + the `event-lockout` seam gate + tests. **Depends on the feed landing first within the same PR.** | **M** |
| **PR-6** `feat(strategy-signal + margin): short-premium straddle + BTST/STBT sell legs (SPAN-gated)` | §3.7: `StraddleLegPicker.pickShort` + `short-straddle`/`btst-sell-leg` tags + margin-service `/size` call (fail-closed) + SELL parity fixture + tests. **Gated on margin-service LIVE + cross-stream BTST routing.** | **L** |

Suggested order: PR-1 → PR-2 → PR-3 → PR-4 (independent, low-risk, ship first) → PR-5 (feed) → PR-6
(SPAN, last; the hard external dependency). Each: short-lived `feat/` branch, Conventional Commit
scoped to the touched service, squash-merge; build with the full reactor + `-am`
(`-pl services/<svc> -am verify`).

---

## Open Points

1. **Economic-event feed source.** No free public economic-calendar feed is assumed in-repo
   (`MarketCalendar` is a static NSE-holiday CSV). **Options:** (a) a manually-seeded
   `marketdata.economic_events` table + admin POST (recommended default — owner enters RBI/Fed/budget
   dates; matches the `nse_eod_participant_oi` manual-ingest pattern); (b) integrate a paid economic
   calendar API (out of budget scope); (c) leave the event-lockout arm UNbuilt and ship only the
   ideal-window arm (the `news_clear` manual check already covers the human read). **Recommend (a).**

2. **Event-lockout severity threshold.** Which severity triggers the lockout? **Options:** (a) HIGH only
   (recommended — avoids over-blocking on minor data); (b) HIGH+MED. **Recommend (a)**, owner-tunable.

3. **`entry-window-230pm` exact cap + which families.** The deck's afternoon cap is fuzzy. **Options:**
   (a) default cap 14:30 on the core directional scalpers, Hero-Zero exempt (recommended); (b) a softer
   "reduce size after 14:30" instead of a hard skip (overlaps `probability-graded-sizing`, another
   stream). **Recommend (a)** as the discrete gate; defer the sizing taper to the sizing stream.

4. **`avoid-friday-skip` scope — BTST-only or all carries?** The Friday rule is stated for BTST/STBT
   (weekend gap). **Options:** (a) BTST/STBT only (recommended — the carry is the risk); (b) also skip
   Friday for intraday Hero-Zero on a Friday weekly expiry (no weekend gap on an intraday close → not
   needed). **Recommend (a).** Also: should the new golden fixture's Friday fall on a holiday-adjacent
   week? Keep it a plain Friday for clarity.

5. **Hero-Zero floor: config knob vs new tag (§3.5).** **Options:** (a) `artha.scalper.herozero.range-from`
   config knob, default 14:30 (recommended — one constant, no tag churn, byte-identical default);
   (b) a `hero-zero-late` tag. **Recommend (a).** Owner to confirm the target value (14:45 per S21/S23,
   vs the intro-terminology §1.2 14:00 ambiguity — this is also an UNCERTAIN_OWNER row, "Hero-Zero start
   time code 14:30 vs §1 14:00"; resolve the intended start time before tuning).

6. **backtest-fidelity-rails RSI direction per family.** A `direction: both` BTST family (§2.5) has no
   single RSI band — CE wants 60-80, PE wants 20-40. **Options:** (a) skip the RSI clause for `both`
   families and keep only the volume floor (recommended — the live seam resolves side per-bar, the
   static YAML cannot); (b) add both clauses as an `any` group. **Recommend (a)** to avoid a
   contradictory `all` gate.

7. **≥1yr publish gate: block vs warn.** **Options:** (a) advisory warn by default, owner opts into a
   hard block via `publish.require_backtest_coverage_days: 365` (recommended — matches the "tune on
   live" principle and avoids blocking experimentation); (b) hard block always. **Recommend (a).**

8. **SPAN sell-leg fail-mode when the appliance is dormant.** **Options:** (a) suppress the SELL leg
   entirely (fail-closed, recommended — never a naked unsized short); (b) emit the leg with a flat
   placeholder size and a warning. **Recommend (a).** The whole `short-premium-span` package stays
   PARKED until #47 live-verify (real `.spn` + NSE URL) completes — record it as blocked, not in-flight.

9. **Short-straddle vs long-straddle auto-selection.** The straddle dimension has an UNCERTAIN_OWNER row
   ("long-vs-short auto-selection vs discretionary"). **Options:** (a) require an explicit `short-straddle`
   tag (recommended — no auto-flip; owner picks the variant); (b) auto-select long/short from an IV/range
   read (couples to `iv-per-strike`, another stream). **Recommend (a)** for this stream; defer auto-select.

10. **(AUDIT-added) backtest-fidelity-rails delivery vehicle — backtest-only override mechanism.** The
    rails must NOT edit the live YAML (that is [P], §3.2 correction). **Options:** (a) a `backtest.gate_override.all`
    block in the SAME YAML that ONLY the backtest submission merges into `gate.all` and the live loader
    ignores (recommended — one file, no duplicate drift, but needs a small loader change in the backtest
    submission path to read+merge it); (b) a sibling `*-bt.yaml` per scalper (no loader change, but 36
    duplicate files that drift from their live twin). **Recommend (a)**; confirm the backtest submission
    can merge an override block before committing to it. Owner to confirm there is no requirement that the
    backtest replay be EXACTLY the live gate (there isn't — the whole point is the live seam adds rails
    the engine can't, so backtest≈live is the goal, not byte-equality).

11. **(AUDIT-added) ≥1yr publish-coverage cross-service read.** The registry (strategy-signal-service)
    has no backtest-DB datasource; `backtest_runs` is in backtest-service's `ay_backtest` schema. **Options:**
    (a) add a read-only `GET /api/v1/backtests/coverage?strategyId=` to backtest-service + an HTTP client
    in strategy-signal-service (recommended — keeps schema ownership clean, matches the existing
    cross-service client pattern e.g. `MarketDataInstrumentClient`); (b) pass the coverage span on the
    publish request body from the FE (lighter, but the FE must already know it). **Recommend (a).** The
    gate ships inert (`require_backtest_coverage_days: 0`) so PR-4 can land before the read endpoint if
    sequencing demands.

12. **(AUDIT-added) Hero-Zero floor — param-thread vs bean-convert.** §3.5 needs the 14:30 floor
    configurable but `HeroZeroGate.evaluate` is static. **Options:** (a) add a `LocalTime rangeFrom`
    parameter threaded from a new `HeroZeroProps` bean in `ScalperConfluenceGate` (recommended — smallest
    diff, keeps the gate pure/static, FU2-shaped); (b) convert `HeroZeroGate` to an injected `@Component`
    (larger blast radius, touches every call + test). **Recommend (a).** Either way the default stays
    14:30 so shipped Hero-Zero behaviour is byte-identical.

---

## Audit pass 1 findings

Reviewer: automated audit (pass 1), 2026-06-27. Method: opened every cited file/line/test against the
working tree. Verdict: **sound-with-open-points** — the design intent is correct and almost all cites
are faithful, but TWO changes were mis-classified for parity and one golden-variant claim was unsound;
all are corrected in place above. Safe to hand to a developer once the corrected §3.2 / §3.4 routes are
followed.

**Citations verified TRUE (spot-checked against source):**
- §2.1 `ScalperConfluenceGate` lines (time-window L112-118, chain L119-123, chart L124, straddle
  L132-147, side L149-152, **#5 oi-cross-filter L196-199**, two-candle L167, gap-fill L173, trend-change
  L204, open-high-low L218, hero-zero L236, ctx L191-192, score L250-253) — all correct.
- §2.2 `ScalperGates` (NO_TRADE_BEFORE L22, MIDDAY block L23-24, NO_FRESH_ENTRY_AFTER L25, `timeWindow`
  L33-44, overload L53-61) — correct. `GateOutcome` is `(boolean pass, BigDecimal operand, String
  reason)` with `pass()/fail()` factories — the proposed `new GateOutcome(!block, null, ...)` + `.pass()`
  calls compile.
- §2.3 `ScalperConfig` (fields L36-52; tag parses two-candle L119 / gap L121 / trend L123 / OHL L125 /
  opening-tick L128 / hero-zero L131 / straddle L135 / entry-candle L149 / oi-cross-filter L153; ctor
  L154-156; OPENING_FROM L72 / OPENING_TO L73 / VWAP_ACTIONABLE_FROM L76) — correct. The "8 `new
  ScalperConfig(...)` literals in `ScalperConfluenceGateTest`" — confirmed (L44/49/56/62/68/74/81/87).
- §2.4 `HeroZeroGate` (RANGE_FROM 14:30 L75, FRESH_ENTRY_CAP 15:20 L77, `shortCovering` L172-178,
  `sideDelta < 0` passes) — correct.
- §2.5 `SignalEngine` (`preCloseClock` `@Scheduled(... MON-FRI ...)` L510 fires Friday; `preCloseAt`
  match L518; `preCloseEvaluate` L532-571; `emitEntry(..., null)` L567-569 null Decision; `scalperEntry`
  L445-468; `evaluate` L459-462; side from `direction()` L591-592) — correct.
- §2.6 golden firewall (`GoldenDeterminismTest.FEATURES` L33-36 = the 5 non-scalper features;
  generate-once L61-63; `BacktestParityTest.FEATURES` L35-38 same 5) — correct. **Newly verified:** the
  frozen `expected/btst-preclose.signals.json` emits a **Friday 2026-01-09** entry; fixtures span Mon
  2026-01-05..Fri 2026-01-09 (`candles/README.md`).
- §2.8 `margin-service` (`POST /api/v1/margin` + `/size`; `size()` advisory never blocks, api.py L31-34);
  `StraddleLegPicker` BUY-only (javadoc L23-24); `tests/test_sizing.py` exists — all correct.
- Gap-source rows: gap-theory rows L627 (after-9:45 + ideal-window + post-3:30 lockout), L628 (RSI),
  L629 (volume), L631 (delta/premium band) exist in `docs/strategy-audit/gap-theory.md` (these are AUDIT
  ROW IDs, not file lines — the file is 143 lines; harmless but now labelled "row" to avoid confusion).
  gap-theory.md L52 itself confirms the genuine gaps = the soft 9:15-10:00 ideal preference + the
  post-3:30 event lockout (the 11-13 block + 15:30 cut are already hard-blocked) — §3.1 scope is faithful.
- FU1/FU2 precedent plans EXIST at `docs/superpowers/plans/2026-06-27-followup{1,2}-*.md` (one dir UP
  from this backlog folder); the read-order block now carries the correct `../` relative path.

**Soundness / parity issues found and CORRECTED in place:**
1. **§3.2 mis-classified [S] (parity).** "Edit each scalper YAML's `gate.all`" is NOT backtest-only: the
   scalper YAMLs under `scalper-strategies/` are the LIVE definitions, and `SignalEngine` runs
   `EntryEvaluator.evaluate(... gate.all ...)` BEFORE the confluence seam (`SignalEngine.java:427-431`),
   so tightening `gate.all` changes the LIVE chart-gate result → [P], not [S]. The final live EMISSION is
   usually unchanged (the seam re-applies the same RSI/volume rails L157-163) but not byte-guaranteed.
   **Fix:** route the rails through a backtest-only `gate_override` / `*-bt.yaml`, leaving the 36 LIVE
   YAMLs byte-identical; [S] then holds honestly. Parity table + PR-4 + new Open Point #10 updated.
2. **§3.4 unsound golden claim.** The `btst-preclose` golden runs through `TickwiseGoldenRunner.run`
   (the BTST block L193-210), NOT `SignalEngine`. The plan put the Friday-skip only in `SignalEngine`, so
   the proposed `btst-preclose-friday-skip` golden would STILL emit the Friday entry → the test would be a
   false pass. **Fix:** the skip must be wired in BOTH `SignalEngine.preCloseClock` (live) AND
   `TickwiseGoldenRunner.run` L193-210 (golden), each gated on `definition.session().avoidFriday()`
   (default-OFF → existing `btst-preclose` golden, which DOES emit the Friday entry, stays byte-identical).
   §3.4 / §5.4 / parity table updated.
3. **§3.4 missing compiler + fan-out steps.** Adding `avoidFriday` to `StrategyDefinition.Session`
   (L68-78) requires `StrategyCompiler.compileSession` (L175-187) to parse `avoid_friday`, and a
   compile-time update to every `new ...Session(...)` literal (`EvalFixtures`, `SessionGateTest`,
   `StrategyCompilerTest`). Added to §3.4 + §5.4.
4. **§3.5 unsound config read.** `HeroZeroGate.evaluate` is a STATIC method — a `@ConfigurationProperties`
   bean can't be read inside it. The knob must be threaded as a `LocalTime rangeFrom` parameter (or the
   gate converted to a `@Component`); the "read a `ScalperOiProps`-style field inside `evaluate`" framing
   would not compile. Corrected in §3.5 + new Open Point #12.
5. **§3.2 ≥1yr publish gate cross-service gap.** The registry is in strategy-signal-service but
   `backtest_runs` is in backtest-service's `ay_backtest` schema — there is no direct DB read. Added the
   need for a new `GET /backtests/coverage` read endpoint + HTTP client (or request-body pass-through) to
   §3.2 + new Open Point #11.

**Parity confirmation (after corrections):** every `[P]` change remains a default-OFF tag/flag absent
from all 36 shipped YAMLs; the engine goldens carry no scalper YAML; the one engine-golden-touching
change (§3.4 in `TickwiseGoldenRunner`) is gated on the default-OFF `avoidFriday()` flag, so
`GoldenDeterminismTest` + `BacktestParityTest` stay byte-identical on the existing 5 FEATURES. The new
`btst-preclose-friday-skip` is an ADDITIVE FEATURE in both arrays (generate-once expected). With the §3.2
route corrected to a backtest-only override, no LIVE YAML changes — so no live-emission parity surface
remains uncovered.

**Minor cite tightenings applied:** §2.7 YAML line precision (gap-theory clauses L49-50 not "L47-50";
btst-stbt `volume > 0` L91); "L6xx" relabelled as AUDIT row IDs; FU1/FU2 `../` path; §2.6 Friday-entry
fact made explicit.

**Residual open points (genuine unknowns, see Open Points #1-12):** the economic-event feed source (no
free feed), the backtest-override delivery mechanism (#10), the cross-service publish-coverage read
(#11), the Hero-Zero param-threading route (#12), and the SPAN/BTST-routing external dependencies for
§3.7 (PARKED on #47 + the cross-stream `btst-route-through-gate`).

---

## Audit pass 2 findings

Reviewer: automated audit (pass 2, INDEPENDENT of pass 1), 2026-06-27. Method: re-opened every cited
file/line/test against the working tree (did NOT trust pass-1's "verified TRUE" list); re-derived the
parity classification end-to-end; re-checked the two pass-1 corrections (§3.2, §3.4) introduced no new
error; hunted for a broken data-flow step, an untested edge, a wrong fan-out, and a wrong dependency
order. Verdict: **sound-with-open-points** — the design is correct and the pass-1 corrections are right,
but I found THREE concrete new defects (one data-flow bug, one cross-service compile fan-out miss, one
factual count error) plus path/precision tightenings, all corrected in place.

### Pass-1 corrections re-verified (independently) — all CORRECT, no new error introduced
- **§3.2 [P]-not-[S] re-confirmed.** `SignalEngine.evaluateAtBarClose` runs
  `EntryEvaluator.evaluate(strategy.definition(), bank, index)` at **L427-428** BEFORE the scalper
  seam — verified. AND `BacktestRunner` compiles the SAME YAML via `StrategyCompiler.compile(config)`
  (`BacktestRunner.java:127`), so a live-YAML `gate.all` edit moves BOTH live and backtest emission. The
  backtest-only-override route is the correct fix. `StrategyCompiler.compile` reads only known `.path(...)`
  keys, so an extra `backtest.gate_override` block is silently ignored by the live loader (the recommended
  vehicle is mechanically viable; the merge step in the backtest submission path is the only new code,
  honestly flagged in Open Point #10).
- **§3.4 dual-wiring re-confirmed + STRENGTHENED.** The `btst-preclose` golden runs through
  `TickwiseGoldenRunner.run` — the BTST block is at **L193-210** exactly as cited, entry add at L201-207.
  NEW FINDING (strengthens the correction): `BacktestParityTest` (the second consumer) does NOT run a
  separate replay BTST path — `ReplayEngine.replay` delegates signal generation to the SAME
  `TickwiseGoldenRunner.run` (`ReplayEngine.java:90-92`). So the single `TickwiseGoldenRunner` edit
  covers all three consumers (golden-determinism + backtest-parity + live `SignalEngine`); there is no
  hidden third path to patch. Recorded in §5.4. The default-OFF gating (`avoidFriday()==false` for the
  existing `btst-preclose.yaml`) keeps the existing golden byte-identical — verified the frozen
  `expected/btst-preclose.signals.json` carries the Friday 2026-01-09 entry that must persist.
- **§3.5 static-method correction re-confirmed.** `HeroZeroGate.evaluate` IS static (no props arg) — a
  bean read inside it would not compile; the param-threading recommendation is right. The fan-out is
  heavier than pass-1 implied: **1 production call (`ScalperConfluenceGate.java:238`) + ~20 test calls
  in `HeroZeroGateTest`** — the plan §3.5 does disclose "every `HeroZeroGate.evaluate(...)` test call",
  so this is honest, just sizeable.

### NEW defects found (pass-1 + author both missed) — CORRECTED in place
1. **§3.1 DATA-FLOW BUG (soundness, corrected).** The event-lockout snippet called
   `client.highSeverityEventToday(eodDate)`. `eodDate` is the **prior completed session** (breadth/FII
   read date — see `MarketOiClient.context` javadoc L77 + the §2.1 `tradeDate`-vs-`eodDate` split). The
   lockout asks "is a HIGH-severity event scheduled LATER **TODAY**", which needs the **live bar's IST
   date** (`tradeDate = barInstant.atZone(Ist.ZONE).toLocalDate()`, the value the production code derives
   at L182). As written it would query **yesterday's** event calendar and silently mis-gate. Fixed the
   §3.1 snippet to compute `evtDate` from `barInstant` at the insertion point (or move the early-return
   below L182), with a note that `Ist.ZONE` is already imported and `barInstant` is an existing parameter
   (so no new import / compiles clean). This is the most material new finding — it would have shipped a
   wrong-by-one-day gate.
2. **§3.4 CROSS-SERVICE COMPILE FAN-OUT MISS (completeness, corrected).** Pass-1's `new Session(...)`
   literal list named `EvalFixtures`, `SessionGateTest`, `StrategyCompilerTest`. Grepping
   `new (StrategyDefinition\.)?Session\(` shows the ACTUAL literals are: `StrategyCompiler` L187 (prod),
   `SessionGateTest` L21, `EvalFixtures` L94, and **`TouchBasisClassifierTest` L30 in
   `services/backtest-service`** — pass-1 MISSED the backtest-service literal AND named
   `StrategyCompilerTest` which has NO `new Session(...)` literal (it asserts via `compile(...)`). Adding
   a `Session` field therefore breaks compilation in a THIRD Maven module (backtest-service), so the PR
   cannot be verified with a strategy-signal/strategy-engine-only build — `-pl services/backtest-service
   -am verify` is required. Corrected the fan-out list in §3.4 + §5.4, retitled PR-2 to span the three
   modules and added the explicit cross-service verify note.
3. **§3.4 FACTUAL COUNT ERROR (corrected).** The plan (lines 333 + 458) said the variant golden leaves
   "the Mon/Tue/Wed/Thu entries". The frozen `expected/btst-preclose.signals.json` emits exactly THREE
   entries (Wed 2026-01-07, Thu 2026-01-08, Fri 2026-01-09) — there are NO Mon/Tue entries because
   SMA(3) on a 1d series cannot form until the 3rd session. Suppressing Friday leaves **Wed + Thu (two
   entries)**, not four. Corrected both occurrences. (Does not change design soundness — the variant
   still correctly proves Friday-suppression — but the descriptive claim was wrong.)

### Spot-checked TRUE (independent re-verification, not inherited from pass-1)
- `ScalperConfluenceGate.evaluate`: time-window L112-118, chain L119-123, chart L124, straddle L132-147,
  side L149-152, RSI/volume re-apply L157-163, oi-cross-filter L196-199, two-candle L167, gap-fill L173,
  trend-change L204, open-high-low L218, hero-zero L236 — all correct. The constructor is a 3-arg
  `@Component` (client/oiProps/calendar), so adding a `HeroZeroProps` bean for §3.5 is feasible.
- `ScalperGates`: constants L22-25, `timeWindow` L33-44, overload L53-61, `rsiBand` (`>60 && <80` CE,
  `>20 && <40` PE) L76-84, `rsiAbove` L92-99, volume floors L28-30/L65 — all correct. `GateOutcome` is
  `(boolean, BigDecimal, String)` with `pass()/fail()` factories, so the proposed `new GateOutcome(...)`
  + `.pass()` calls in §3.1 compile.
- `ScalperConfig`: fields L36-52, tag parses (oi-cross-filter L153 etc.), ctor L154-156, opening
  constants L72/L73/L76 — correct. **36 scalper YAMLs** confirmed in
  `services/strategy-signal-service/src/main/resources/scalper-strategies/`.
- `HeroZeroGate`: RANGE_FROM 14:30 L75, FRESH_ENTRY_CAP 15:20 L77, `shortCovering` L172-178 (negative
  delta passes), `evaluate` STATIC L98-106 — correct.
- `SignalEngine`: `preCloseClock` `@Scheduled(... MON-FRI ...)` L510 fires Friday, `preCloseAt` match
  L518, `emitEntry(..., null)` L567-569, `scalperEntry` L445-468, `direction()` side L591-592 — correct.
- `StrategyDefinition.Session` record L68-78; `StrategyCompiler.compileSession` L175-198 (literal
  L187-197); `GoldenDeterminismTest.FEATURES` L33-36; `BacktestParityTest.FEATURES` L35-38 (in
  **backtest-service**, NOT strategy-engine — path tightened in §5.4) — correct.
- Source docs: `gap-theory.md` L52 (only the soft 9:15-10:00 ideal + post-3:30 lockout unautomated),
  rows 31/32/40 (RSI/volume "Automatable: true via gate.all", delta/premium deferred to selector), L54
  (live-vs-backtest gate split); `gates-strike-sr-fiidii.md` §4.10 row 27 (events-after-3:30 PARTIAL,
  "economic-calendar feed could gate"), row 25 (9:15-10:00 ideal NONE) — all faithful. `news_clear`
  manual check exists (`ScalperManualChecks.java:27`). `margin-service /size` advisory-never-blocks +
  `StraddleLegPicker` BUY-only — confirmed. `backtest_runs` lives in `deploy/flyway/backtest/V003`
  (backtest schema) and NO `GET /api/v1/backtests/coverage` endpoint exists today — both confirm the
  §3.2/#11 cross-service-read framing. `MarketDataInstrumentClient` exists (the precedent #11 cites).

### Minor (noted, not blocking)
- **RSI band source-vs-code mismatch (intentional, sound).** gap-theory row 31 states the RSI rule as
  "CE < 75 / PE > 25", but §3.2 lifts the CODE's `rsiBand` (`>60 && <80` CE / `>20 && <40` PE) — correct,
  because the goal is backtest≈LIVE (the `ScalperGates.rsiBand` javadoc L70-74 already reconciled the
  §4.2-vs-§3.10 doc discrepancy in code). No change needed; the executor should use the code band.
- **§3.6 freshness cut overlaps an existing 12:00 cutoff.** `OpenHighLowGate` already has a `~12:00`
  first-half cutoff (`OpenHighLowGate.java:72,96-98`); the new `oh-freshness-1030` is a stricter opt-in
  refinement (additive early-return, tighter wins) — not a new behaviour from nothing. Sound; worth the
  executor knowing the 12:00 cap pre-exists.
- **§3.3 `afternoonCap` logic verified:** `!cap.isAfter(istTime)` ⇒ block when `istTime >= cap` — correct
  "no fresh entry at/after the cap" semantics.

### Readiness verdict
**sound-with-open-points — ready to hand to a developer**, with the three pass-2 corrections applied.
The design intent is correct, the parity story is honest (every `[P]` change is a default-OFF tag/flag
absent from all 36 shipped YAMLs; the one golden-touching change is `avoidFriday()`-gated and proven
byte-identical against the existing fixture; the §3.2 backtest-override route keeps all 36 LIVE YAMLs
byte-identical). The remaining risks are the genuine externals already in Open Points #1-12 (no free
event feed → manual-seed table; SPAN/#47 + cross-stream BTST routing PARK §3.7; the backtest-override
merge mechanism #10 and the cross-service publish-coverage read #11 need a one-line confirmation each
before PR-4). PR-2 now correctly spans three Maven modules — the executor MUST verify backtest-service
in that PR or the build breaks undetected.
