# Stream — Event/time gates + backtest-fidelity rails + SPAN sell legs

Status: PLAN (implementation-ready). Owner: single-owner. Date: 2026-06-27.
Target services: `services/strategy-signal-service` (scalper seam), `services/backtest-service`
(replay + YAML `gate.all`), `services/margin-service` (SPAN), the scalper YAMLs.

> Read order for the executor: this plan is self-contained but assumes the two precedent plans —
> `2026-06-27-followup2-soft-dots-to-hard-gates.md` (FU2, the **parity-safe-additive default-OFF
> tag-gate** template) and `2026-06-27-followup1-expand-manual-checks.md` (FU1, manual checks). Every
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
- **[S] (backtest-only / new sell path with no existing golden / read-only):** `backtest-fidelity-rails`,
  `short-premium-span` (+ legs).

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
  golden variant is added (see §4).
- `BacktestParityTest.FEATURES` carries no scalper either. The seam is LIVE-only
  (`ScalperConfluenceGate` javadoc L29-33) — it never runs on the deterministic replay path.

### 2.7 Backtest `gate.all` vs live HARD rails (the fidelity gap)
- A scalper YAML's `entry_rules.gate.all` is intentionally trivial (e.g. `scalp-gap-theory-nifty.yaml`
  L47-50 `close > vwap`, `close > vwma20`; `scalp-btst-stbt-nifty.yaml` L90-91 `volume > 0`). The
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
    if (cfg.requireEventLockout()
        && !ScalperGates.eventLockout(istTime, client.highSeverityEventToday(eodDate)).pass()) {
      return Optional.empty();
    }
    if (cfg.requireIdealWindow() && !ScalperGates.idealWindow(istTime).pass()) {
      return Optional.empty();
    }
```

`MarketOiClient` gains a `highSeverityEventToday(LocalDate)` passthrough to the new market-data
endpoint (cached per-day; degrades to `false` = never blocks on a feed miss, mirroring the VIX
fail-open).

### 3.2 `backtest-fidelity-rails` (4 gaps) — [S]

**Gaps:** the live HARD rails (RSI band, volume floor, delta/premium band, ≥1yr publish gate) are NOT
in the backtest path. Lift the **expressible** ones into the YAML `gate.all` so the engine evaluates
them in replay (no engine code; this is a **YAML-only** change → backtest-only, no live-signal change).

**Per-rail change (each scalper YAML's `entry_rules.gate.all`):**
- **RSI band** (gap-theory §3.4 L628): add `"rsi14 > 60"` + `"rsi14 < 80"` for a CE family, the mirror
  `20..40` for a PE family (the §0B band from `ScalperGates.rsiBand` L76-84). For #2 open-high-low use
  the relaxed `"rsi14 > 50"` (`ScalperGates.rsiAbove`).
- **Volume floor** (gap-theory §3.4 L629): add `"volume >= 125000"` (NIFTY) / `"volume >= 50000"`
  (SENSEX/index) — the `ScalperGates.volume` floors (L27-30).
- **Delta/premium band** (gap-theory §3.4 L631): NOT expressible in `gate.all` (the engine has no
  delta/premium operand in replay — the backtest selector picks nearest-strike-to-spot, see
  `OptionsPremiumReplay` javadoc L41-42 + `ScalperConfig` L90-92). Surface it into the backtest
  **selector** instead — this overlaps the `strike-premium-band-backtest` package (another stream);
  here we only RECORD the dependency and add the RSI+volume clauses.
- **≥1yr publish gate** (risk-framework §2.10 r42): a publish-time check (backtest coverage ≥ 1 year
  before a strategy may be published live). Add to the registry publish path
  (`services/strategy-signal-service` publish endpoint) — a guard that reads the latest backtest run's
  window span for the strategy and warns/blocks publish if `< 365d`. Advisory by default (a
  `publish.require_backtest_coverage_days` config, default 0 = off).

**Why [S].** Backtest-only: tightening `gate.all` makes the BACKTEST fire on fewer bars (closer to
live). It does NOT touch the live seam and there is **no scalper golden** (the engine goldens carry no
scalper YAML, §2.6), so no existing golden moves. The 36 scalper YAMLs are not in `GoldenDeterminismTest.FEATURES`.

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

**Change.** Add a `boolean avoidFriday` to the BTST strategy config (a new YAML key
`risk.session.avoid_friday: true`, read into the engine `StrategyDefinition.Session`). In
`preCloseClock` (or `preCloseEvaluate`), skip when the day-of-week is FRIDAY and the flag is set:

```java
    if (strategy.definition().session().avoidFriday()
        && today.getDayOfWeek() == java.time.DayOfWeek.FRIDAY) {
      continue; // §3.8 risk: no BTST carry over the weekend gap
    }
```

**Parity.** [P] — it suppresses a Friday BTST entry that the existing `btst-preclose` golden could
emit. BUT the existing golden (§2.6) is a pure-engine fixture whose candle days may or may not include
a Friday; **the change is gated behind the new `avoid_friday` flag, default-OFF**, so the existing
golden's strategy YAML (which does not carry the flag) is byte-identical. A NEW golden variant
(`btst-preclose-friday-skip`) proves the skip on a Friday-containing fixture (see §5).

### 3.5 `expiry-entry-timing` (1 gap) — [P]

**Gap:** Hero-Zero `RANGE_FROM = 14:30` (`HeroZeroGate.java:75`); S21/S23 tightens to ~14:45.

**Change.** This is a behavioural threshold change on a SHIPPED gate, so it MUST be opt-in to stay
parity-safe. Two options:
- **(Recommended)** make the floor a `ScalperOiProps`-style config knob:
  `artha.scalper.herozero.range-from` (default **kept at 14:30**, so existing Hero-Zero configs are
  byte-identical) and read it in `HeroZeroGate.evaluate` instead of the constant. The owner sets 14:45
  on the forward-paper variant only.
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
| §3.2 backtest RSI+volume rails in `gate.all` | **[S]** | none (YAML-only) | Backtest-only; the 36 scalper YAMLs are NOT in `GoldenDeterminismTest.FEATURES` → engine goldens byte-identical. New **backtest parity** assertion that the tightened YAML still replays deterministically (not a frozen golden — a determinism re-run). |
| §3.2 ≥1yr publish gate | **[S]** | `publish.require_backtest_coverage_days` (default 0=off) | Publish-path guard; no signal emission, no golden. |
| §3.3 afternoon 14:30 cap | **[P]** | `entry-cap-230pm` | Live-only seam → seam unit test. Default-OFF → no shipped config moves. |
| §3.4 avoid-Friday BTST skip | **[P]** | `risk.session.avoid_friday` (Session flag) | **NEW engine golden variant** `btst-preclose-friday-skip` (a Friday-containing candle fixture proving the skip) — additive FEATURES entry + new `expected/*.signals.json` (generate-once). Existing `btst-preclose` golden (no flag) stays byte-identical. |
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
- Add `btst-preclose-friday-skip` to `GoldenDeterminismTest.FEATURES` (additive). Author
  `golden/strategies/btst-preclose-friday-skip.yaml` (the existing `btst-preclose.yaml` + the
  `risk.session.avoid_friday: true` flag) over a Friday-containing candle fixture; generate-once
  `golden/expected/btst-preclose-friday-skip.signals.json` (`-Dgolden.generate=true`) showing the
  Friday entry SUPPRESSED. **Re-run `GoldenDeterminismTest` and `BacktestParityTest` and assert the 5
  existing FEATURES stay byte-identical** (the existing `btst-preclose` carries no flag).

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
| **PR-2** `feat(strategy-signal): avoid-Friday BTST skip + new golden variant` | §3.4: `Session.avoidFriday` flag + `preCloseClock` skip + `btst-preclose-friday-skip` golden (additive FEATURES + generate-once expected) | **S** |
| **PR-3** `feat(strategy-signal): configurable Hero-Zero entry floor (default 14:30)` | §3.5: `HeroZeroGate` reads `range-from` config (default unchanged) + tests | **S** |
| **PR-4** `feat(backtest): lift live RSI/volume rails into scalper gate.all (+ ≥1yr publish gate)` | §3.2: edit the 36 scalper YAMLs' `gate.all` (RSI+volume per family) + the publish-coverage guard + backtest determinism/trade-count test. Note the delta/premium-band dependency on `strike-premium-band-backtest` | **S-M** |
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
