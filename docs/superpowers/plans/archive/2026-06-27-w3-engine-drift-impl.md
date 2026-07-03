# W3 — Engine-Drift Implementation Plan (parity-safe, default-OFF)

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


**Status:** PLAN (forward work). Drives the 6 ratified S24 engine-drift fixes.
**Date:** 2026-06-27.
**Ratification:** owner ratified S24 doc values in `docs/strategy-audit/RATIFICATION-PACK.md`
(Part 2 drift rows + Part 3 rulings) and the OIP-AI spec in
`docs/superpowers/plans/2026-06-27-oip-ai-probability-spec.md`.

## Inputs / source-of-truth
- **Drift values:** `RATIFICATION-PACK.md` Part 2 (D1–D48) + Part 3 rulings (U1–U8).
  Owner picks already inked: **D3/D26/D29/D41 → S24 ≥0.7 delta floor**; **U1 → PE 40–25**;
  **U2 → S24 doc 50–75 / 40–25 governs**; **U3 → S24 buy-floor 50 governs**;
  **D36 → BN ~100pt SL**; **D37 → N ~50–60pt SL**; **D5 → N 150–350 / BN 250–550 premium**.
- **OIP-AI %:** spec §4 G1–G4, owner tier→% = **HIGH 90 / MILD 60 / LOW 30 / AVOID 0**.
- **Recon:** the 5 read-only recon findings (RSI bands, golden/parity harness, tag-arming
  mechanism, delta/premium/SL config, OIP-AI Open=High probability).

---

## 0. THE PARITY-SAFE PATTERN (as the recon found it)

Every change here is **signal-affecting** (it changes which trades fire). The non-negotiable
constraint: the 5 frozen golden vectors stay **byte-identical** so `GoldenDeterminismTest`
(LIVE half) and `BacktestParityTest` (REPLAY half) stay green. The recon established **two
disjoint worlds** and that disjointness is the load-bearing fact:

### Two worlds
- **WORLD 1 — the parity harness** (`libs/strategy-engine`). `GoldenDeterminismTest` and
  `BacktestParityTest` both iterate ONE hard-coded
  `FEATURES = {ema-crossover, optional-indicator-activation, btst-preclose, exit-intrabar, context-series}`
  array over the 5 frozen `NSE_NIFTY50_1m_dayN.csv` days, serialize via the FROZEN
  `GoldenSignalsJson.write()`, and byte-compare `expected/<feature>.signals.json`.
  `ReplayEngine` has **zero** references to scalper / confluence / tags. **None of the 36
  `scalp-*.yaml` has a golden.**
- **WORLD 2 — the scalper tag-armed gates** (`services/strategy-signal-service/.../scalper/`).
  At LIVE signal time `SignalEngine` builds a `ScalperConfig` only when
  `tags.contains("scalper")` AND `universe.mode == options_of_underlying`; that config's
  values gate the entry through `ScalperConfluenceGate.evaluate(...)`. The golden/parity
  harness **never compiles or replays a tag-armed scalper YAML.**

### Consequence (the recipe)
A value that lives in WORLD 2 (`ScalperConfig` / `ScalperGates` / `ScalperOiProps` / a gate
class) can be changed **and** the goldens stay byte-identical automatically — *provided every
existing strategy still resolves to the old behavior*. The proven way to guarantee that is the
**default-OFF tag** mechanism, already in-tree for `open-high-low` / `oi-cross-filter`.

### The tag mechanism (concrete files — verified)
1. **Provenance** — `ScalperStrategySeeder.java:90-93`: a YAML's `tags:` list is parsed
   verbatim and stored on the registry strategy row.
2. **Arming switch** — `SignalEngine.java:192-196`: `scalper` tag + `options_of_underlying`
   ⇒ `ScalperConfig.from(config, strategy.tags())`; else `null` (strategy untouched).
3. **Dispatch** — `ScalperConfig.from` (`ScalperConfig.java:119-153`): each behavior tag
   decodes to `boolean tags.contains("<tag>")`, frozen onto the immutable record
   (constructor call `ScalperConfig.java:154-156`). A tag **absent** ⇒ flag `false` ⇒ default
   path. *All 36 seeded YAMLs lack any new tag ⇒ every existing config gets `false` ⇒ no
   value change anywhere.*
4. **Consumer** — `ScalperConfluenceGate.evaluate` (`ScalperConfluenceGate.java:100-280`):
   reads each `cfg.requireXxx()`; `true` arms a hard pre-gate / swaps a threshold / selects an
   SL anchor; `false` skips the whole `if`, so the path is byte-identical.

### The threshold-swap shape (the #2 open-high-low precedent — the template to mirror)
Rather than mutate shared `ScalperGates.rsiBand`, the team added an **additive sibling**
`ScalperGates.rsiAbove(rsi, floor)` (`ScalperGates.java:92-99`) + a config field
`openHighRsiFloor` on `ScalperOiProps` (default 50, wired `application.yml`
`artha.scalper.oi.open-high-rsi-floor: 50`), and branched on `cfg.requireOpenHighLow()` at the
**single** `ScalperConfluenceGate.java:157-160` ternary — leaving shared `rsiBand`
byte-identical for every other strategy. **Mirror this exactly.**

### The golden-variant recipe (only when a WORLD-1 change needs a frozen vector)
Most changes here are pure WORLD-2 and need **zero** golden work. When a change *could* be
exercised by the harness (it cannot, for any change in this plan — see each PARITY ARGUMENT),
the recipe is: drop `strategies/<variant>.yaml` into
`libs/strategy-engine/src/test/resources/golden/strategies/`, add its stem to the `FEATURES[]`
array in **BOTH** `GoldenDeterminismTest.java` AND `BacktestParityTest.java` (lockstep — two
consumers of one format), run once with `-Dgolden.generate=true` (only `GoldenDeterminismTest`
has generate mode; `BacktestParityTest` only reads) to emit
`expected/<variant>.signals.json`, then commit the generated JSON frozen. **Never** in-place
edit a committed `expected/*.json` (`docs/golden-vectors.md` §5 invariant 3; `.gitattributes`
pins `*.json eol=lf`).

> **Important nuance for these 6 changes:** because the new behavior is gated behind a
> default-OFF scalper tag that NO golden YAML carries, **no new golden variant is needed for
> the LIVE-signal parity harness** — the 5 frozen goldens are inert by construction. A golden
> variant is only relevant if a change touched WORLD-1 chart grammar (none here do). The
> scalper **premium-replay backtest** path (`OptionsPremiumReplay`) is a *separate* engine with
> its *own* dedicated parity golden (per CLAUDE.md); each change below states whether it can
> reach that path and what to assert there.

---

## CHANGES (ordered by independence / risk — RSI first)

| # | Change | Tag (default-OFF) | Code vs YAML | Touches replay/golden? |
|---|---|---|---|---|
| 1 | RSI bands → 50-75 buy / 40-50 no-trade / 40-25 sell, floor 50 | `rsi-s24-bands` | **Code constant** (`ScalperGates`) | No — WORLD 2 only |
| 2 | PE-side RSI → 40-25 (same band redef) | `rsi-s24-bands` (same) | **Code constant** (same method) | No |
| 3 | Delta floor → ≥0.7 | `delta-s24-floor` | **Code constant** (`ScalperConfig`) | No — live StrikePicker only |
| 4 | Point-SLs BN ~100 / N ~50-60 | `index-point-sl` | **NEW YAML basis + code** | Additive — see arg |
| 5 | Premium band N 150-350 / BN 250-550 | `premium-s24-band` | **Code constant** (`ScalperConfig`) | No — live StrikePicker only |
| 6 | OIP-AI OH-prob Tier→% + AVOID ΔOI>50% veto | `open-high-oi-veto` (veto only) | **Code** (% pure; veto tag-gated) | Veto = signal-affecting; % + surface = pure |

---

### CHANGE 1 — RSI bands → buy 50-75 / no-trade 40-50 / sell(CE-PE) [tag: `rsi-s24-bands`]
### CHANGE 2 — PE-side RSI → 40-25 (SAME tag, SAME method — one band redefinition)

> Changes 1 and 2 are **one** code change: the full band set (`CE 50<v<75`, `PE 25<v<40`,
> `40-50` no-trade complement) is redefined together in one new method. They are listed
> separately only because the ratification tracks the CE buy band (D14/D33/U3) and the PE sell
> band (D15/U1) as distinct drift rows. Implement once.

**Ratified target.** Owner picks: **U2** (S24 doc 50-75 / 40-25 governs the engine override),
**U3** (buy-floor 50 governs), **U1** (PE side band is **40-25**). So:
- CE buy band: **50 < v < 75** (was `60 < v < 80`).
- PE sell band: **25 < v < 40** (was `20 < v < 40`); oversold reject at `<= 20` is already the
  HeroZero `RSI_OVERSOLD` constant — leave that distinct path as-is.
- No-trade zone: **40-50** (implicit complement: a value in `40..50` fails both branches), was
  `40-60`.

**File(s) + line(s) — current → target.**
- `services/strategy-signal-service/.../scalper/ScalperGates.java`
  - **Current** (line 81): `boolean ok = side == OptionType.CE ? (v > 60.0 && v < 80.0) : (v > 20.0 && v < 40.0);`
  - **Do NOT mutate line 81.** Add an **additive sibling** method next to `rsiBand`
    (mirroring how `rsiAbove` sits beside `rsiBand` at lines 86-99):
    ```java
    /** S24 bands (tag rsi-s24-bands): CE buy 50-75, PE sell 25-40, 40-50 no-trade. */
    public static GateOutcome rsiS24Band(BigDecimal rsi, OptionType side) {
      if (rsi == null) return GateOutcome.fail(null, "rsi unavailable");
      double v = rsi.doubleValue();
      boolean ok = side == OptionType.CE ? (v > 50.0 && v < 75.0) : (v > 25.0 && v < 40.0);
      String want = side == OptionType.CE ? "CE wants 50-75" : "PE wants 25-40";
      return new GateOutcome(ok, rsi, ok ? want + " ok" : want + " (40-50 no-trade)");
    }
    ```
  - Rewrite the `rsiBand` javadoc rationale (lines 70-74): the §4.2-vs-§3.10 collision note no
    longer applies under the S24 50-75 / 40-50 split — note that `rsiBand` is the legacy band
    and `rsiS24Band` is the ratified band, tag-selected.
- `services/strategy-signal-service/.../scalper/ScalperConfig.java`
  - Add a `boolean requireRsiS24Bands` record field; decode in `from` (alongside line 153):
    `boolean rsiS24 = tags.contains("rsi-s24-bands");` and thread through the
    `new ScalperConfig(...)` call (lines 154-156).
- `services/strategy-signal-service/.../scalper/ScalperConfluenceGate.java`
  - **Current** (lines 157-160): the `requireOpenHighLow ? rsiAbove : rsiBand` ternary.
  - **Target** — gate the new band ahead of the existing ternary (false ⇒ pre-existing code):
    ```java
    boolean rsiOk =
        cfg.requireOpenHighLow()
            ? ScalperGates.rsiAbove(chart.rsi14(), oiProps.openHighRsiFloor()).pass()
            : cfg.requireRsiS24Bands()
                ? ScalperGates.rsiS24Band(chart.rsi14(), side).pass()
                : ScalperGates.rsiBand(chart.rsi14(), side).pass();
    ```
    (open-high-low keeps precedence — it already overrides to its own `>50` floor.)
- `services/strategy-signal-service/.../scalper/ConnectTheDotsScorer.java`
  - **Current** (line 78): `add(dots, "rsi", W, ScalperGates.rsiBand(c.rsi14(), side).pass(), "RSI band");`
  - **Decision (open Q):** the soft dot must use the SAME band the strategy's hard rail uses,
    else the dot and the rail disagree. Thread the flag into the scorer (the scorer already
    receives the `ScalperConfig`/side) and pick `rsiS24Band` when armed:
    `ScalperGates.rsiS24Band(...)` vs `rsiBand(...)`. If the scorer does not currently see the
    flag, add it to its call signature (WORLD-2 only — no golden impact).

**Tag (default-OFF):** `rsi-s24-bands` (mechanism-named, kebab-case — consistent with
`oi-cross-filter`). Absent on all 36 YAMLs ⇒ `false` ⇒ legacy `rsiBand` ⇒ no change.

**Golden variant:** **NONE.** This is WORLD 2; the harness never calls `rsiBand`/`rsiS24Band`
(the goldens score RSI via the YAML `rsi_momentum` engine preset
`Normalizers.RSI_MOMENTUM` 50→70, not the trade band). No `FEATURES[]` edit, no regenerate.

**Unit tests to add/extend.**
- `ScalperGatesTest.java` (lines 60-72 lock the CURRENT edges — **leave those for `rsiBand`
  untouched**): ADD a `rsiS24Band` block asserting the new edges:
  `rsiS24Band(50,CE)=false; (51,CE)=true; (74,CE)=true; (75,CE)=false; (40,PE)=false;
  (39,PE)=true; (26,PE)=true; (25,PE)=false; (44,CE)=false /*40-50 no-trade*/`.
- `ScalperConfluenceGateTest`: add a case proving a strategy carrying `rsi-s24-bands` routes
  through `rsiS24Band` (e.g. RSI 55 CE passes under S24 but fails under legacy `rsiBand`).
- `ConnectTheDotsScorerTest`: assert the soft dot follows the flag.
- Leave `Normalizers.RSI_MOMENTUM` (50/70) and every scalp `*.yaml` `rsi_momentum` decl
  UNTOUCHED.

**PARITY ARGUMENT (one line):** `rsiBand` is unchanged and the harness never invokes
`rsiBand`/`rsiS24Band` (goldens score RSI via the YAML `rsi_momentum` engine preset, a
different knob), so all 5 frozen goldens replay byte-identically; the new band only fires for a
strategy that carries the absent-by-default `rsi-s24-bands` tag.

**Pure-YAML vs code-constant:** **CODE CONSTANT** — the band edges are inline `double` literals
in `ScalperGates`, present in **no** YAML (every YAML declares RSI only as
`normalize: { type: rsi_momentum }`; optimize blocks tune `period`, not bands). No YAML change
can move the bands today.

**Carried open points (confirm before arming):**
- PE sell band edges: ratified `25 < v < 40` (strict, matching the existing strict `>`/`<`
  style); `<= 20` oversold reject stays the distinct HeroZero path. ✅ resolved by U1.
- CE buy band `50 < v < 75` (excludes 50, matching strict style); the §4.2/§3.10 collision is
  resolved by the 50-75 / 40-50 split. ✅ resolved by U2/U3.
- `Normalizers.RSI_MOMENTUM` (50→70) is a **different role** (engine soft pre-gate) — leave
  unchanged; the hard rail uses the new band, the soft preset still scores 50→70. Different
  roles, both legitimate.
- HeroZero `RSI_OVERBOUGHT=80 / RSI_OVERSOLD=20` (`HeroZeroGate.java:78-80`) already match the
  owner's exhaustion caps (D48) — **stays as-is**, NOT folded into the shared band.

---

### CHANGE 3 — Delta floor → ≥0.7 [tag: `delta-s24-floor`]

**Ratified target.** D3/D26/D29/D41 (one umbrella ruling) = **S24 ≥0.7 delta floor**.
`DELTA_LO` 0.6 → 0.7. Open Q (carried): with `DELTA_HI` also 0.7 the band collapses to a point.
The S24 doc's deferred near-expiry refinement is 0.7-0.8 — **recommend `DELTA_HI` widen to 0.8**
so the admitted band is `0.7 ≤ |Δ| ≤ 0.8` and `deltaTarget` recenters to 0.75; **confirm with
owner** whether they want `0.7-0.8` or a degenerate `0.7` floor=cap. (`deltaTarget` is the
midpoint, recomputed automatically.)

**File(s) + line(s) — current → target.**
- `services/strategy-signal-service/.../scalper/ScalperConfig.java`
  - **Current** (lines 82-83): `DELTA_LO = 0.6; DELTA_HI = 0.7;`
  - These pack into `StrikePicker.Params(DELTA_LO, DELTA_HI, ...)` at line 118.
- `StrikePicker.java:99-101` is the consumer:
  `if (absDelta < params.deltaLo() || absDelta > params.deltaHi()) continue;` then
  `err = |absDelta - deltaTarget()|` picks nearest the midpoint.

**Default-OFF requirement.** A bare edit of `DELTA_LO`/`DELTA_HI` would change strike selection
for **every** scalper immediately (not default-OFF). To honor "default-OFF until the owner arms
the 36 YAMLs," gate it the same way: add a `delta-s24-floor` tag → `requireDeltaS24Floor`
on `ScalperConfig`, and in `from` choose the `Params` delta bounds by the flag:
```java
double dLo = deltaS24 ? 0.7 : DELTA_LO;   // DELTA_LO stays 0.6 for untagged
double dHi = deltaS24 ? 0.8 : DELTA_HI;   // (or 0.7 if owner wants degenerate floor)
StrikePicker.Params params = new StrikePicker.Params(dLo, dHi, premium[0], premium[1], RATE);
```
Keep `DELTA_LO`/`DELTA_HI` constants as the untagged defaults (so untagged strategies are
byte-identical), exactly how `DEFAULT_OPEN_HIGH_RSI_FLOOR` back-fills. (If the owner instead
wants this GLOBAL/unconditional, drop the tag and edit the two constants — but that is not
default-OFF; flag the choice.)

**Tag (default-OFF):** `delta-s24-floor`. Absent on all 36 ⇒ 0.6-0.7 band ⇒ no change.

**Golden variant:** **NONE.** Strike selection is **live-only**: `StrikePicker` /
`HeroZeroStrikeSelector` run off the live `MarketOiClient` chain and are NEVER on the
deterministic-replay path (`HeroZeroStrikeSelector.java:25-28`: "a replay reads the persisted
leg, never re-runs this selector"). The golden ReplayEngine never reaches strike picking.

**Unit tests to add/extend.** `StrikePickerTest` (or equivalent): assert a candidate with
`|Δ|=0.65` is ADMITTED under no-tag but REJECTED under `delta-s24-floor`, and the in-band
nearest-to-target pick recenters to 0.75 when armed.

**PARITY ARGUMENT (one line):** the delta floor only gates **live** strike selection
(`StrikePicker`/`HeroZeroStrikeSelector`), which the deterministic golden ReplayEngine never
invokes (replay reads the persisted leg), so goldens are untouched; the tag is absent by
default.

**Pure-YAML vs code-constant:** **CODE CONSTANT** (`ScalperConfig.DELTA_LO/DELTA_HI`), not a
YAML param.

**Backtest note (carried open Q):** the **premium-replay backtest selector ignores the delta
band and picks nearest-strike-to-spot** — so this change has **ZERO backtest effect**; it is a
**live/forward-paper** tuning only. Confirm the owner is tuning for forward paper, not backtest.

---

### CHANGE 4 — Point-SLs: BN ~100pt / N ~50-60pt [tag: `index-point-sl`]

**Ratified target.** D36 (BN ~100), D37 (N ~50-60), D30/D46 (S24 wide point-SL set
N 50-60 / BN 100 / SENSEX 200-250). Owner ruled S24.

**Recon finding — there is NO point-SL constant today.** Stops are one of: (a) YAML
`stop_loss{basis:premium_pct,value:50}` (btst/hero-zero/straddle families), (b) YAML
`time_stop{max_bars}`, or (c) a CODE structural stop = a future **price level** (swing pivot /
VWAP / candle-extreme) chosen per family in `ScalperConfluenceGate.structuralStop`
(lines 288-302), which `SignalEngine` then overrides any YAML stop with, deriving
`stopDistance = |entry - stop|`. **No `Bank Nifty 100 / Nifty 50-60` point count exists
anywhere** — this is a **NEW capability**, not a constant edit.

**Implementation — additive YAML basis (mirrors the existing `premium_pct` path).** This is the
cleanest default-OFF: a new `stop_loss` basis that only opted-in strategies carry.
- `services/strategy-signal-service/.../signals/SignalEngine.java`
  - **Current** `levelFromRules(def, entryPrice, "stop_loss")` (line 583) handles
    `basis: premium_pct`; structural stop overrides it (lines 587-589); `stopDistance` derived
    at 607-610.
  - **Target:** add a `basis: index_points` arm in `levelFromRules` that computes
    `stopLevel = entryPrice ± value` (sign by side). Decide precedence vs the structural stop
    (open Q below).
- The point values live as the **YAML `value`** per strategy (e.g. `value: 100` for a BN YAML,
  `value: 55` for a NIFTY YAML) — so this is **partly pure-YAML** (the numbers) + **code** (the
  new basis arm). No `ScalperConfig` constant needed.

**Tag (default-OFF):** `index-point-sl` (mechanism-named). Default behavior = the existing
structural/premium_pct stop; only a strategy that adds the `index_points` `stop_loss` rule (and
optionally the tag, if you want gate-level branching) gets point SLs. Because existing YAMLs
carry **no** `index_points` rule, they are byte-identical.

**Open points (carried — MUST resolve with owner before build):**
- **REPLACE vs FALLBACK:** do BN~100 / N~50-60 point SLs **replace** the per-family structural
  stops (swing/VWAP/candle-extreme), or are they an additional **floor/cap** alongside them?
  This decides whether the new basis takes precedence over `decision.structuralStop()` at
  `SignalEngine.java:587-589` or only applies when no structural stop is set. Recommend
  **additive fallback/cap** (least invasive, keeps existing structural stops for tagged
  families) — confirm.
- **SENSEX 200-250** (D46) — include the SENSEX point band in the same basis if the owner wants
  the full set.
- Inclusive of the existing `time_stop` (it composes, not conflicts).

**Golden variant:** **NONE.** Stop levels are a WORLD-2 scalper concern; the 5 feature goldens
carry no `index_points` rule and no scalper tag. *However* — if a future tagged scalper YAML is
run through the **premium-replay backtest** and that engine reads the persisted `stop_loss`
level, assert the new basis in `OptionsPremiumReplay`'s **own** dedicated parity golden (verify
whether it reads `stop_loss`; the recon did not trace this). For the LIVE/golden harness: zero
impact.

**Unit tests to add/extend.** `SignalEngineTest` (or a `levelFromRules` unit): assert
`basis: index_points, value: 100` on a CE entry at price P yields `stopLevel = P - 100` and
`stopDistance = 100`; assert untagged strategies (no such rule) are unchanged; assert the
chosen precedence (replace vs fallback) against `structuralStop`.

**PARITY ARGUMENT (one line):** the new `index_points` `stop_loss` basis is additive and
present in **no** existing YAML, so every frozen golden (which carries no such rule and no
scalper tag) replays byte-identically; only opted-in strategies derive a point-based stop.

**Pure-YAML vs code-constant:** **HYBRID** — the **numbers** (100 / 55 / 225) are pure-YAML
`value:` params, but the new `basis: index_points` arm is a **code** addition in
`SignalEngine.levelFromRules` + the structural-stop precedence decision.

---

### CHANGE 5 — Premium band N 150-350 / BN 250-550 [tag: `premium-s24-band`]

**Ratified target.** D5 = N **150-350** (was 100-250) / BN **250-550** (was 250-400). SENSEX
(300-800) — confirm whether it also moves (open Q D5 / SENSEX band).

**File(s) + line(s) — current → target.**
- `services/strategy-signal-service/.../scalper/ScalperConfig.java`
  - **Current** (lines 93-98): `NIFTY_PREMIUM = {100, 250}; PREMIUM = Map.of("NIFTY 50",{100,250},
    "NIFTY BANK",{250,400}, "SENSEX",{300,800});`
  - **Target:** `"NIFTY 50" → {150, 350}`, `"NIFTY BANK" → {250, 550}` (BN hi 400→550),
    SENSEX unchanged unless owner rules.
- Consumer: `StrikePicker.java:93-95` rejects candidates whose `ltp` is outside `premiumLo/Hi`
  (BEFORE the delta gate).

**Default-OFF requirement.** Same as Change 3: a bare map edit is global, not default-OFF.
Gate it behind `premium-s24-band` → `requirePremiumS24Band` and select the band map by the flag
in `from` (keep the current `PREMIUM` map as the untagged default; add a parallel
`PREMIUM_S24` map for armed strategies). Untagged strategies keep `{100,250}/{250,400}` ⇒
byte-identical. (If owner wants this global, edit the map directly — but flag that it is not
default-OFF.)

**Tag (default-OFF):** `premium-s24-band`.

**Golden variant:** **NONE.** The premium band is **live-only** — the recon-confirmed comment
at `ScalperConfig.java:89-92` says "live StrikePicker only, the backtest selector ignores the
band and picks nearest-strike-to-spot." Strike picking is never on the golden ReplayEngine
path. **This change has ZERO backtest effect** (carried open Q — confirm owner tunes
live/forward, not backtest).

**Unit tests to add/extend.** `StrikePickerTest`/`ScalperConfigTest`: assert a NIFTY candidate
with `ltp=300` is REJECTED under the legacy `{100,250}` band but ADMITTED under
`premium-s24-band` `{150,350}`; assert a BN candidate at `ltp=500` admitted only when armed.

**PARITY ARGUMENT (one line):** the premium band only gates **live** strike selection (the
backtest selector ignores it; the golden ReplayEngine never picks strikes), so goldens are
untouched; untagged strategies keep the legacy band.

**Pure-YAML vs code-constant:** **CODE CONSTANT** (`ScalperConfig.PREMIUM` map keyed by
`universe.underlying`), not a YAML param.

---

### CHANGE 6 — OIP-AI Open=High probability: Tier→% (90/60/30/0) + AVOID ΔOI>50% veto

This is **three independent sub-parts** with different parity profiles (per spec §4 G1-G4 +
§5). G1 and G3 are **pure / live-only** (no tag, no golden). G2 (the veto) is
**signal-affecting** and gets the default-OFF tag `open-high-oi-veto`.

#### 6a (G1) — Tier → % [PURE, no tag]
- `services/strategy-signal-service/.../scalper/OpenHighLow.java`
  - Add a pure function `OpenHighLow.probabilityPct(Tier)` (or
    `record OhProbability(Tier tier, int pct, boolean badge)`):
    **HIGH→90, MILD→60, LOW→30, STAND_ASIDE/AVOID→0; `badge = tier == HIGH`.**
  - `Tier` enum is at `OpenHighLow.java:53-62`; add an **AVOID** constant here for 6b.
- **No tag, no golden** — pure deterministic derivation off the existing `tier(...)`.
- **Unit:** extend `OpenHighLowTest` with the 90/60/30/0 truth table + `badge` flag.
- **PARITY ARGUMENT:** a pure function consumed nowhere by the golden harness — zero signal/
  golden impact.

#### 6b (G2) — explicit AVOID + per-strike ΔOI>50% veto [tag: `open-high-oi-veto`, signal-affecting]
The spec's p20 AVOID = the **identified-strike ΔOI>50%** (per-strike), distinct from the
existing chain-wide `oi.spurtOiPct()` reject and the chain-wide `#5 oi-cross-filter`
(`callPutDeltaImbalancePct`) **confirm** gate — which is semantically the *inverse* of a veto
(`ScalperGates.callPutDeltaFilter` PASSes when imbalance is HIGH and degrades-to-pass on null).
**Recommend spec path (a): add a per-strike `oiChangePct`**, NOT reuse #5.

**Producer (market-data) — fold a per-strike session ΔOI%, zero new capture:**
- `services/market-data-service/.../options/analytics/OptionsSnapshotReader.java`
  - `PerStrikeSessionStat` (lines 50-59) is folded from `StrikePoint.ltp()`; `StrikePoint`
    already carries `oi()`/`oiChange()`. In `sessionStats()` (lines 214-235) fold a per-strike
    session ΔOI% (last-bucket `oi` vs first-bucket `oi`, or summed `oiChange`) — **no second DB
    read** (OI is already captured for `/spurt` + `/active-strikes`).
- `services/market-data-service/.../options/analytics/OpenHighStatsService.java`
  - Add `oiChangePct` to `StrikeSessionStat` (lines 31-44) and the `grade()` fold (60-95).
- `services/market-data-service/.../options/analytics/OptionsAnalyticsController.java`
  - `/strike-session-stats` (lines 711-732) returns a **typed record** → the new key surfaces
    in `/v3/api-docs` (additive, non-breaking; `ContractCaptureTest` gen-drift **warn** not
    fail; regen `contracts/gen/market-data-service.d.ts` + `tsc --strict`).

**Consumer (strategy-signal) — new field + AVOID tier + tag-gated veto:**
- `services/strategy-signal-service/.../scalper/MarketOiClient.java`
  - `StrikeStat` (lines 106-115) gains an `oiChangePct` field; `toOpenHighStats` reads
    `row.path("oiChangePct")` (back-compatible — tolerant mapper).
- `services/strategy-signal-service/.../scalper/OpenHighLow.java`
  - `tier(...)` (112-174) returns the new **AVOID** when the representative OH strike's
    `premiumFall > 50% OR oiChangePct > 50%` (distinct from STAND_ASIDE — a vetoed-HIGH reads
    AVOID/0, not sideways).
- `services/strategy-signal-service/.../scalper/OpenHighLowGate.java` (Verdict at line 64;
  evaluate 85-112): when `cfg.requireOpenHighOiVeto()` and `tier == AVOID`, block. **Keep** the
  existing chain-wide `oi.spurtOiPct()>50%` / `oi.spurtPricePct()>50%` rejects (69-70/114-117)
  as-is.
- `ScalperConfig`: bind `requireOpenHighOiVeto` from the `open-high-oi-veto` tag, exactly as
  `requireCallPutDeltaFilter` binds `oi-cross-filter` (`ScalperConfig.java:153`).

**Tag (default-OFF):** `open-high-oi-veto` — a SEPARATE tag layered on `open-high-low`
strategies (do NOT fold into `open-high-low` unconditionally; that would change live behavior
for every open-high-low strategy without opt-in).

**Golden variant:** the veto is signal-affecting (can newly block a HIGH the chain-wide gate
let through). It is WORLD 2 (scalper tag) so the 5 LIVE-signal goldens are inert by
construction (no scalper YAML in `FEATURES[]`). **No new LIVE-signal golden variant is needed.**
Per spec §7, *if* you want a frozen vector for the veto-ON behavior in the scalper
premium-replay engine, add a variant there (its dedicated parity golden) — verify whether
`OptionsPremiumReplay` reaches this gate first.

**Unit tests to add/extend.** `OpenHighLowTest` / `OpenHighLowGateTest`: AVOID truth table
(`premiumFall>50% OR oiChangePct>50% ⇒ AVOID/0`; HIGH stays HIGH/90 when both ≤50%); a gate
test proving `open-high-oi-veto` armed blocks an otherwise-HIGH when the identified strike's
ΔOI>50%, and untagged strategies are unaffected. Market-data: `OpenHighStatsServiceTest` /
`OptionsSnapshotReaderTest` for the `oiChangePct` fold; `ContractCaptureTest` re-capture
(gen-drift warn).

**PARITY ARGUMENT (one line):** the per-strike ΔOI veto fires only when the default-OFF
`open-high-oi-veto` tag is present (absent on all 36 YAMLs and on every golden YAML), so the
LIVE-signal goldens replay byte-identically; the new market-data field is an additive,
back-compatible response key.

#### 6c (G3) — surface {tier, pct, badge} [PURE side-channel, live-only]
- `services/strategy-signal-service/.../scalper/ScalperConfluenceGate.java`
  - Thread `tier` (and `pct`) from `OpenHighLowGate.Verdict` into the `Decision` record
    (lines 71-87) as nullable `Tier ohTier` / `int ohPct`, set only when `requireOpenHighLow`.
- `services/strategy-signal-service/.../signals/SignalEngine.java`
  - In `scalperDetailJson` (667-704) add `root.put("oh_tier" / "oh_pct" / "badge")`; add the
    field to `SignalEmitted.ScalpDetail` (644-657). This rides the **LIVE-only** `emitEntry`
    path (`SignalEngine.java:642-643` notes replay never reaches here).
- **No tag, no golden** — additive presentational fields on the live emit/side-channel.
- **PARITY ARGUMENT:** the scalper detail JSON + `ScalpDetail` are LIVE-only (golden replay
  never reaches `emitEntry`), and `GoldenSignalsJson.write()` serializes none of these fields —
  golden bytes unchanged.

**Carried spec open points for Change 6 (owner/eng to confirm):**
1. **G2 input granularity** — confirm p20 AVOID is the **per-strike** ΔOI on the identified OH
   strike (favoring path (a)), not a chain-wide proxy. If a chain-wide proxy is acceptable, the
   OHL gate ALREADY rejects on `oi.spurtOiPct()>50%` — AVOID may then need only a distinct
   label, no new field (the cheapest third option the (a)/(b) framing omits).
2. **Per-strike ΔOI source** — confirm `StrikePoint.oi()/oiChange()` is populated in the SAME
   session-stats read window (it is captured for `/spurt` + `/active-strikes`) so the fold
   needs no second DB read.
3. **% as gate vs presentational** — today `OpenHighLowGate` hard-requires `Tier.HIGH` (==90%),
   so a min-% gate is redundant unless intermediate tiers should pass. If the % becomes a
   tunable gate threshold it must be an optimize-block parameter on `ScalperOiProps` + a new
   golden variant.
4. **Where to surface** beyond the page — signal payload only, or Cockpit badge + scalp alerts.
5. **Page column** — add a 2nd "OIP Probability" column alongside the historical-frequency
   `Prob` (recommended) vs replace it. They answer different questions (today's deterministic
   FNO read vs historical odds).
6. **Contract drift** — `/strike-session-stats` returns a typed record, so `oiChangePct`
   surfaces in `/v3/api-docs` (gen-drift **warn**, regen TS). Confirm the additive-key path is
   acceptable (non-breaking).

---

## SEQUENCING — one `feat/` branch + PR per change, each independently parity-verifiable

Trunk-based, Conventional Commits (scope = `strategy-signal` / `market-data`), squash-merge,
one branch per change. Each PR runs the full verify and **must keep `GoldenDeterminismTest` +
`BacktestParityTest` green with zero golden edits**. Order = least-coupled / lowest-risk first.

| PR | Branch | Change(s) | Scope | Verify gate |
|----|--------|-----------|-------|-------------|
| **PR-1 (FIRST)** | `feat/rsi-s24-bands` | Change 1 + 2 (one band redef) | strategy-signal | `ScalperGatesTest` (new `rsiS24Band` block) + `ScalperConfluenceGateTest` + `ConnectTheDotsScorerTest`; goldens byte-identical (no `FEATURES[]` edit) |
| PR-2 | `feat/delta-s24-floor` | Change 3 | strategy-signal | `StrikePickerTest`; goldens unchanged (live-only) |
| PR-3 | `feat/premium-s24-band` | Change 5 | strategy-signal | `StrikePickerTest`/`ScalperConfigTest`; goldens unchanged (live-only) |
| PR-4 | `feat/index-point-sl` | Change 4 | strategy-signal | `SignalEngineTest` `levelFromRules`; goldens unchanged (additive basis) — **resolve replace-vs-fallback open Q first** |
| PR-5 | `feat/oip-ai-probability` | Change 6a + 6c (pure %/surface) | strategy-signal | `OpenHighLowTest` %; live-only side-channel; goldens unchanged |
| PR-6 | `feat/open-high-oi-veto` | Change 6b (veto + market-data ΔOI) | market-data + strategy-signal | `OpenHighStatsServiceTest`/`OpenHighLowGateTest`; `ContractCaptureTest` re-capture + TS regen; goldens unchanged (tag default-OFF) |

**Grouping rationale.** PR-1 is the headline RSI redefinition (CE+PE are one method). Delta
(PR-2) and premium (PR-3) are independent live-only `ScalperConfig` constant tweaks — split so
each is its own atomic parity proof. Point-SLs (PR-4) carry an unresolved precedence open Q, so
it is sequenced after the cheap constant edits. PR-5 (pure % + surfacing) and PR-6 (the
signal-affecting veto + the market-data per-strike ΔOI fold) are split because PR-6 alone
crosses a service boundary and touches the contract snapshot; PR-5 has zero gate impact and can
ship independently. **Recommended FIRST PR = PR-1 `feat/rsi-s24-bands`** (highest signal value,
fully self-contained in strategy-signal, no cross-service/contract churn).

---

## WHAT STAYS DEFAULT-OFF / WHAT THE OWNER MUST ARM TO GO LIVE

**Every change above is INERT in production until the owner arms it.** Merging all 6 PRs
changes **zero** live behavior. Live behavior changes ONLY when the owner edits the 36 scalper
YAMLs to add the arming tags (and, for Change 4, the new `index_points` `stop_loss` rule).

To go live, add to each target YAML's `tags:` array (and/or `stop_loss` rule):
- `rsi-s24-bands` — RSI bands move to 50-75 / 40-50 / 40-25 for that strategy.
- `delta-s24-floor` — strike selection floors delta at ≥0.7 (live StrikePicker; **no** backtest
  effect).
- `premium-s24-band` — strike premium band moves to N 150-350 / BN 250-550 (live StrikePicker;
  **no** backtest effect).
- `index-point-sl` + a `stop_loss: { basis: index_points, value: <pts> }` rule
  (BN ~100 / N ~50-60 / SENSEX ~200-250).
- `open-high-oi-veto` — arms the per-strike ΔOI>50% AVOID veto on open-high-low strategies.

**Never default-OFF (already live, unchanged by this plan):** the OIP-AI **% surfacing**
(Change 6a/6c) is pure/presentational and is computed for every open-high-low strategy as soon
as PR-5 ships — it changes no gate, only adds `{oh_tier, oh_pct, badge}` to the live signal
detail. HeroZero's `RSI_OVERBOUGHT=80 / RSI_OVERSOLD=20` stay as-is.

**Arm only after forward-paper validation.** Per the scalper-tuning findings (backtests are
overfit; derived-history OI mutes the OI factors), validate each armed tag on **forward paper
with real captured OI**, not a historical backtest — especially Change 6b (the veto) and
Changes 3/5 (live-only, zero backtest signal). The premium/delta/SL changes have no backtest
effect at all, so backtest cannot validate them.
