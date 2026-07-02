# Macro confluence: directional VIX, global cues, FII/DII, constituent weight

Status: PLAN (implementation-ready). Owner: single-owner. Primary service:
`services/strategy-signal-service` (scalper confluence seam) + `services/market-data-service`
(macro analytics feeds). Date: 2026-06-27. Stream slug: **`macro-vix-global-fii`**.

> Read order for the executor: this plan is self-contained but assumes the
> **FU2 parity-safe-additive convention** (`docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md`)
> and the `oi-cross-filter` (#5) hard-gate template are the load-bearing precedents. Every
> parity-sensitive change here copies that exact shape: a NEW per-strategy YAML tag, absent from
> all 36 shipped configs, default-OFF, so existing emitted signals stay byte-identical. The CLAUDE.md
> "Extend engine records parity-safely" + "Kite wire DTO" + "directional-vix-gate is OUT of FU2" notes
> govern.

---

## 1. Goal & the packages/gaps this stream closes

This stream wires the **macro half** of the Connect-the-Dots confluence — the four feeds that are
currently *starved* or *unread* in `MarketOiClient.macro()` and `ConnectTheDotsScorer`, plus two
read-only analytics it depends on. The unifying theme: the `ScalperGateContext.Macro` record already
*declares* all the fields (`vixLevel`, `vixRising`, `advances`, `declines`, `fiiLongPct`), but the
producer passes `null`/unread values, so the corresponding dots/gates degrade to pass (no-ops). This
stream populates them and adds the two missing dots (FII, constituent), each parity-gated.

> **AUDIT pass 1 — source-row line numbers CORRECTED.** The original `<doc>.md Lxx` pointers below
> were stale (the strategy-audit files were regenerated; their line numbering drifted). The
> *package counts* are taken from the authoritative, current `GAP-DISPOSITION.md` and were verified
> correct (`directional-vix-gate` 11 @ GAP-DISPOSITION L118; `fii-dii-bias` 6 @ L126;
> `volume-pump-attribution` / `constituent-contribution` / `global-cues-feed` single-gap @ L157/L165/L173).
> The per-doc `Lxx` cites are now re-pointed to the actual rows (opened + confirmed 2026-06-27).

| Package | # gaps | Doc § | Disposition source rows (audit-corrected) |
|---|---:|---|---|
| **`directional-vix-gate`** | 11 | 4.5 / 4.14.1 / 4.14.5 / 4.17.5 / 3.10 / 3.12 / §7 | `indicators-oi-vix-iv.md` **L42** (VIX directional grid), **L43** (regime bands), **L44** (fresh-shorts/longs-exiting inferences), **L45** (vs prev-close) (4); `gates-strike-sr-fiidii.md` **L34** (VIX confluence) (1); `connect-the-dots.md` **L31** (VIX directional rule) (1); + the `open-high-low` / `two-candle` / `intro-terminology` / `session-additions` directional-VIX rows tallied in GAP-DISPOSITION L118 (11 total) |
| **`global-cues-feed`** | 1 (+ tail) | 3.10 / 4.7 | `connect-the-dots.md` **L33** (Dow dot, AUTOMATE — "scalper scorer Macro has NO dow field"); `gates-strike-sr-fiidii.md` **L14** (Dollar/Asian/Crude — KEEP_MANUAL, "Dollar Index / Asian / Oil are nowhere"). The single-gap `[P]` package is the **Dow dot** (GAP-DISPOSITION L173). |
| **`fii-dii-bias`** | 6 | 4.13 / 4.17.4 / 3.10 / 3.12 | `gates-strike-sr-fiidii.md` **L35** (participant-OI dot + FII>Pro>DII>Client), **L36** (L/U/B/C change-in-OI classifier), **L37** (leg-level seller read), **L38** (next-morning-only validity) (4); `connect-the-dots.md` **L36** (FII-DII not scored) (1) (GAP-DISPOSITION L126) |
| **`constituent-contribution`** | 1 | 3.12 / 4.14.4 | `trend-change.md` **L34** (Index-contribution / heavyweights must support the direction; data-side AUTOMATE; FU1 carries the manual reminder) (GAP-DISPOSITION L165) |
| **`volume-pump-attribution`** | 1 | 4.15.3 | GAP-DISPOSITION L157 (single-gap `AUTOMATE_PKG`). **Note:** the original `indicators-oi-vix-iv.md L32` cite is WRONG — L32 is the OI-Spurts 4-quadrant row (FULL coverage). The volume rows in that doc (L27/L38) are both FULL; the volume-pump *attribution* gap is the package-backlog row, not a per-doc gap row. Cite GAP-DISPOSITION, not the doc line. |

**Total: 20 AUTOMATE gaps** (11 + 1 + 6 + 1 + 1), all in the GAP-DISPOSITION §3 `AUTOMATE_PKG`
backlog, none in FU1/FU2. The FU1 manual reminders that shadow these (`vix_regime_bands`,
`fii_ls_ratio`, `constituent_contribution`, `global_cues_ok`) are already shipped and stay — this
stream is the *automation* behind them.

**Net deliverable:** populate `Macro.vixLevel/vixRising` (was null), add a `dow` macro field + dot,
add a `fii` confluence dot consuming the dead-wired `fiiLongPct` + a new participant L/U/B/C
classifier, add a `constituent` macro field + dot, and refine the `volume` dot to attribute
bull/bear pump direction. Every one that alters emission is behind a new default-OFF tag.

---

## 2. Current state (verified file:line)

All line numbers opened and confirmed against the working tree on 2026-06-27.

### 2.1 The macro record — already has the slots, producer feeds nulls
`services/strategy-signal-service/.../scalper/ScalperGateContext.java` L59-68:
```java
public record Macro(
    BigDecimal atmIv, BigDecimal ivRank,
    BigDecimal vixLevel, Boolean vixRising,   // <-- both null today
    int advances, int declines,
    BigDecimal fiiLongPct,                     // <-- read, but NO dot consumes it
    BigDecimal ceIvAvg6, BigDecimal peIvAvg6) {}
```

### 2.2 The producer — `MarketOiClient.macro(String underlying, LocalDate tradeDate)` (L351-398)
- Reads `atmIv`, `ivRank` (`/options/iv-history`), breadth (`/breadth` → `advances/declines`),
  `fiiLongPct` (`/fii-dii/long-short` → `latestFiiLongPct` **L626-641**), the IV pair (`/options/chain`).
- **L396-397 the VIX gap:** `return new Macro(atmIv, ivRank, null, null, breadth[0], breadth[1],
  fiiLongPct, ...)` — the comment at L394-395 states "VIX has no market-data endpoint yet (§12.2
  follow-up)". **This is the root of `directional-vix-gate`.** (NB: a `/vix` endpoint *does* exist —
  see 2.6 — so the comment is stale; the gap is that `macro()` never calls it.)
- `advanceDecline` **L620-623**, `latestFiiLongPct` **L626-641** already map the JSON.
- **AUDIT pass 1:** the cite was `L350-398` / `L619-623` / `L625-641`; corrected to the lines above
  (the method body is L351-398; `advanceDecline` L620-623; `latestFiiLongPct` L626-641). The JSON keys
  `latestFiiLongPct` reads — `items[].fiiLong` / `items[].fiiShort` — match the `LongShortRow` record
  (`FiiDiiController` L31 `fiiLong, fiiShort`), confirmed. **Note for the executor:** `macro()` has
  NO monthly-expiry early branch (that suppression lives in `oi()`, L267-274), so threading
  `vixEnabled` into `macro()` is a clean single return-site change.

### 2.3 The scorer — `ConnectTheDotsScorer.score(...)` (L63-118)
The 18-dot list (L74-98). Relevant rows:
- `add(dots, "volume", W, ScalperGates.volume(ctx.signalIndex(), c.volume()).pass(), ...)` L79 —
  a **floor-only** check; no bull/bear pump attribution (the `volume-pump-attribution` gap).
- `add(dots, "breadth", W, ScalperGates.breadth(m, side).pass(), ...)` L91.
- `add(dots, "vix", W, ScalperGates.vix(m, side).pass(), ...)` L92 — **the starved dot**: with
  `m.vixRising()==null` (2.2) `ScalperGates.vix` L137-138 returns pass, so this dot *always supports*.
- **There is NO `fii` dot, NO `dow` dot, NO `constituent` dot** (grep-confirmed: `fiiLongPct` has
  zero readers in the scorer). Aggregate denominator math: 18 dots, Σweights = 19.6 (FU2 §2.1 audit).

### 2.4 The gate library — `ScalperGates.java`
- `vix(Macro, side)` L136-143 — unknown direction PASSES (the fail-open the null feed relies on).
- `breadth(Macro, side)` L128-133 — `advances/declines > 32`.
- `volume(String underlying, BigDecimal)` L64-68 — floor only (no price-sign).
- **No `dow`, `fii`, `constituent` gate fns exist.**

### 2.5 The tag → gate wiring — `ScalperConfig.java`
- `record ScalperConfig(...)` L36-52 (**16 fields** today: exchange, underlying, signalIndex, oiIndex,
  rollDays, strikeParams, confluenceThreshold, requireTwoCandle, structuralStop,
  requireCallPutDeltaFilter, requireGapFill, requireTrendChange, requireOpenHighLow, openingTick,
  requireHeroZero, requireStraddle — ending `requireStraddle`). **AUDIT pass 1:** the original cite
  said "15 fields"; it is 16. The 5 new `requireXxx` booleans take it to 21.
- `from(JsonNode, List<String> tags)` L101-157: each `tags.contains("<tag>")` parse (L119-153) +
  the single canonical `new ScalperConfig(...)` at L154-156. **Each new tag adds one
  `boolean requireXxx = tags.contains("<tag>")` line + one trailing arg in the canonical constructor.**
- 36 YAMLs under `services/strategy-signal-service/src/main/resources/scalper-strategies/` (12
  strategies × {nifty, sensex-niftyoi, sensex-sensexoi}); the arming field is the top-level `tags:`
  list. None carry any tag this stream introduces.

### 2.6 Market-data feeds that ALREADY exist (the data is captured; only the wiring is missing)
- **INDIA VIX:** `MarketSurfaceController.vix()` (`feed/MarketSurfaceController.java` **L60-82**) →
  `GET /api/v1/market/vix` returns `VixQuote{ltp, dayHigh, dayLow, dayOpen, prevClose, change,
  changePct, asOf}` from the pinned `INDIA VIX` quote. **AUDIT pass 1:** the response carries MORE
  fields than the original cite listed (dayHigh/dayLow/dayOpen too), but the `ltp` + `change` the
  `vixDirection()` reader (3.1) consumes are both present. **Critical wiring fact the reader must
  handle:** when there is no quote (off-hours / mock) `vix()` THROWS `ApiException(422, DATA_GAP)`
  — it does NOT return a null/empty body. The `MarketOiClient.get(...)` helper (L647-663) catches the
  HTTP error and returns the fallback, so a 422 degrades to `VixRead.EMPTY` (null/null → fail-open),
  exactly as 3.1 intends — but the executor must NOT assume a 200-with-null-fields body. The 1m
  candle series `read("NSE","INDIA VIX","1m",...)` is read by `ConnectingDotsService.vixByBucket`
  (`options/analytics/ConnectingDotsService.java` L353-367). **So the VIX feed exists end-to-end;
  `macro()` just never calls it.**
- **Dow:** `GlobalQuoteSource.latest(DOW)` (`kite/GlobalQuoteSource.java`) →
  `DOWJONES@GLOBAL_INDEX` LTP+prevClose, flag-gated (`artha.openalgo.global-quotes-enabled`).
  Consumed by `ConnectingDotsService.dowFactor` (L310-326, `int dow` L167). **No market-data
  REST endpoint surfaces it for the scalper** — only the connecting-dots matrix uses it.
- **FII participant matrix:** `GET /api/v1/market/fii-dii/participant-oi`
  (`nse/analytics/FiiDiiController.java` L49-55) → `NseEodReader.ParticipantOiRow` (L30-46) with
  `clientType`, `futureIndexLong/Short`, `optionIndexCallLong/PutLong/CallShort/PutShort`,
  `totalLong/ShortContracts`. **Two days are queryable (from/to) → the L/U/B/C delta classifier is
  pure data already present.** The `/long-short` endpoint (L57-78) already derives the FII
  index-future L/S ratio that `fiiLongPct` reads.
- **Constituent contribution:** `EquityIndexContributionService`
  (`nse/analytics/EquityIndexContributionService.java`, method `contribution(String index)` L61)
  computes per-constituent `weight × %change`
  → `IndexContribution{indexChangePct, advanceTotal, declineTotal, advances[], declines[]}` (record
  L49-53) from `StaticIndexWeights` + the EQ bhavcopy. **The directional read (advanceTotal vs
  declineTotal sign) is exactly the `constituent-contribution` signal.** **AUDIT pass 1 — the
  endpoint ALREADY EXISTS:** `EquityController.indexContribution` (`nse/analytics/EquityController.java`
  L54-59) maps `GET /api/v1/market/equity/index-contribution?name=<index>` → the `IndexContribution`.
  TWO consequences the original §3.4/§5.7 got wrong: (a) the query param is **`name`**, NOT `index`,
  and there is **NO `date` param** — `contribution(String)` reads the LATEST EQ bhavcopy internally
  (`max(trade_date)`, L169); (b) since it is a **pre-existing path**, adding the macro gate does NOT
  add a new `@GetMapping` → **the springdoc contract does NOT drift for this package** (correction to
  §5.7). So `constituent-contribution`'s `[S]` market-data work is effectively ZERO (reuse the
  endpoint as-is); only the macro-field + gate (`[P]`) remains.
- **Volume pump:** `ConnectingDotsService.volumeFactor(vol, prevVol, priceDelta)` L240-246 already
  signs a rising-volume bucket by price direction (bull/bear). The scalper's `volume` dot does NOT —
  that is the entire `volume-pump-attribution` gap (lift the same price-signed logic into the dot).

### 2.7 The parity firewall (unchanged, cited so the executor honours it)
- `ScalperConfluenceGate` is **LIVE-only** (class javadoc L29-33): OI/macro/chain reads never run on
  deterministic replay; the picked option + confluence persist at entry (V009 side-channel).
- `GoldenDeterminismTest` (`libs/strategy-engine/.../golden/GoldenDeterminismTest.java`) FEATURES +
  `BacktestParityTest` (`services/backtest-service/.../replay/BacktestParityTest.java`) FEATURES
  carry ZERO scalper YAMLs, so a tag-gated scalper change cannot perturb them. **The byte-identity
  proof is: no golden/parity YAML carries a tag this stream introduces (none can — the FEATURES
  arrays are fixed pure-engine features).**

---

## 3. Design — per package

> **Macro-record extension strategy (applies to all four `[P]` macro-field additions).** The
> `Macro` record is read by `ConnectTheDotsScorer` (a pure function) and assembled by `MarketOiClient`.
> Adding a field is a constructor-arity fan-out (the canonical `new Macro(...)` at `MarketOiClient`
> L396-397 + every test builder). It is **parity-safe ONLY because the scorer reads the new field via
> a NEW dot that is itself gated** — see §4. We do NOT thread a flag into `score(...)`; instead each
> new dot is added to the list but its *support* contribution is controlled by whether the producing
> tag populated the field (null field → dot reads false/neutral identically to today is NOT enough,
> because adding a dot to the list changes the denominator). **Therefore the new dots are added to the
> scorer list conditionally — see §4.0 for the mechanism that keeps the aggregate byte-identical when
> unarmed.**

### 3.0 Mechanism for parity-safe NEW dots (the load-bearing decision)
FU2 promoted *existing* dots to gates without touching the scorer (the dots were already in the list).
This stream is different for `fii`/`dow`/`constituent`: those dots **do not exist** in the scorer, so
adding them naively changes the denominator (19.6 → higher) on EVERY scalper bar → breaks live
confluence for every shipped strategy (and is exactly the trap FU2 §8 point 2 flagged for Dow).

Two options:

- **(A) Early-return HARD gate in `ScalperConfluenceGate.evaluate` (the #5 / FU2 template).** The new
  macro field is populated only when the strategy carries the tag; the dot is consulted as a hard
  precondition (early-return on fail), NOT added to the scorer list. **Scorer untouched → aggregate
  byte-identical when unarmed.** This is the recommended default for `directional-vix-gate`,
  `fii-dii-bias`, `constituent-contribution`, `global-cues-feed (Dow)`.
- **(B) New SCORED dot threaded behind a per-call boolean.** Would require changing `score(...)`'s
  signature and the denominator conditionally — strictly worse for parity isolation (FU2 §4.0
  rejected this). **Not used.**

**Decision: use (A) for all four signal-emitting packages.** Each becomes a `requireXxx` flag + a
`ScalperGates.xxx(...)` pure fn + an early-return block — identical in shape to FU2's four gates.
The VIX feed wiring into `macro()` (3.1 step 1) is the ONE producer change that is shared; it is
parity-safe on its own because **nothing reads `vixRising` until the `vix-gate` tag is armed** — the
existing `vix` *soft dot* (scorer L92) currently reads `m.vixRising()`, so populating it WOULD change
that dot. See the critical carve-out in 3.1.

> **AUDIT pass 2 — straddle short-circuits BEFORE the macro gates (placement constraint).** All five
> early-return gates land *after* `ctx = client.context(...)` (the `~L192` insert, the #5 template
> site). But the neutral-straddle path (`cfg.requireStraddle()`) returns at `ScalperConfluenceGate`
> **L132-147 — BEFORE `ctx` is built AND before `side` is decided** (`verify(client, never()).context(...)`
> at the test L754 proves a straddle never calls `context()`). Two consequences: (a) every macro gate is
> side-based, so it could never have run for a direction-neutral straddle anyway — correct; (b) **arming
> a macro tag (`vix-gate`/`fii-dii-gate`/`constituent-gate`/`global-cues-gate`/`volume-pump`) on a
> `scalp-straddle-*` variant is a SILENT NO-OP** (the macro read + gate never execute). Harmless for
> parity, but the PR-X arming step must NOT arm a macro gate on a straddle variant expecting it to bite.
> Recorded as Open Point §8.14.

### 3.1 `directional-vix-gate` (11 gaps) — `[P]`

**The carve-out that makes this non-trivial:** the scorer ALREADY has a `vix` soft dot (L92) reading
`m.vixRising()`. Today `vixRising` is null → the dot always passes. If `macro()` simply starts
returning a real `vixRising`, the EXISTING soft dot flips from always-pass to a real confirm/block on
EVERY scalper → **non-parity-safe even without any new tag.** So the producer change must be gated too.

**Step 1 — gate the VIX feed read in the producer.** `MarketOiClient.macro(...)` must only populate
`vixLevel/vixRising` when the calling strategy opts in. Since `macro()` does not know the strategy's
tags, thread a flag from the seam:

- `MarketOiClient.context(...)` (the assembler, **L80-93**, signature
  `context(underlying, signalIndex, istTime, eodDate, expiry, tradeDate, chart)` — 7 args today,
  call site `macro(underlying, eodDate)` at L92) already takes the oi-index/signal-index; add a
  `boolean vixEnabled` param it forwards to `macro(underlying, eodDate, vixEnabled)`. **The macro
  fan-out (3.2/3.3/3.4) adds three more booleans (`dowEnabled`/`fiiEnabled`/`constituentEnabled`),
  so the final `context(...)` signature gains FOUR trailing booleans.** To avoid a four-arg
  positional soup, the executor SHOULD pass a single `EnumSet<MacroFeed>` or a small
  `record MacroFeeds(boolean vix, boolean dow, boolean fii, boolean constituent)` instead of four
  loose `boolean`s — decided in Open Point §8.11. (The plan body below shows the loose-boolean form
  for clarity; collapse to one param at implementation time.)
- **AUDIT pass 1 — TEST-CALL-SITE FAN-OUT (do not miss):** `ScalperConfluenceGateTest` has **24**
  references to `client.context(...)` (every `when(client.context(eq("NIFTY 50"), any() ×6))` stub
  PLUS the straddle `verify(client, never()).context(any() ×7)` at L754-755). Adding even ONE param to
  `context()` breaks ALL 24 — each needs an extra `any()` matcher (and the `verify` an extra `any()`).
  This is a pure compile-time fan-out but it is invisible from §5.2's "8 ScalperConfig literals" note,
  which is a DIFFERENT fan-out. Both must land in the same PR or the test module won't compile. (If the
  signature is collapsed to ONE `MacroFeeds` param per §8.11, the fan-out is the SAME 24 sites but only
  one extra matcher each — strictly simpler, another reason to prefer the single-param form.)
- New helper `MarketOiClient.vixDirection(LocalDate tradeDate)`:
  ```java
  /** Live INDIA VIX level + intra-session direction for the directional-vix-gate. Reads GET /vix
   *  (ltp + prevClose); rising = ltp > prevClose. null/null when the feed is absent (off-hours,
   *  mock, or derived history) so the gate fail-opens (never blocks). */
  private VixRead vixDirection() {
    return get(uri -> uri.path("/api/v1/market/vix").build(),
        json -> {
          BigDecimal ltp = decimal(json.path("ltp"));
          BigDecimal chg = decimal(json.path("change"));   // ltp - prevClose, server-computed
          if (ltp == null || chg == null) return VixRead.EMPTY;
          return new VixRead(ltp, chg.signum() > 0);       // rising iff change > 0
        },
        VixRead.EMPTY, "vix");
  }
  record VixRead(BigDecimal level, Boolean rising) { static final VixRead EMPTY = new VixRead(null, null); }
  ```
- In `macro(...)`: `VixRead vix = vixEnabled ? vixDirection() : VixRead.EMPTY;` then return
  `new Macro(atmIv, ivRank, vix.level(), vix.rising(), breadth[0], breadth[1], fiiLongPct, ...)`.
  **When `vixEnabled` is false (every shipped strategy today), `vixLevel/vixRising` stay null →
  the existing `vix` soft dot behaves bit-identically to today.**

**Step 2 — the hard gate (the directional rule + the supporting inferences).** `ScalperConfig`:
add `boolean requireVixGate` (tag `vix-gate`). `ScalperConfluenceGate.evaluate`, after `ctx` is built
(after the `client.context(...)` call ~L192), insert:
```java
// directional-vix-gate: VIX falling confirms CE / rising confirms PE (the §4.5 inverse grid).
// Populated only when vix-gate is armed (macro() reads /vix); null direction DEGRADES to pass
// (fail-open, like #5/FU2) so off-hours / derived history never block. OUT of FU2 by design.
if (cfg.requireVixGate() && !ScalperGates.vix(ctx.macro(), side).pass()) {
  return Optional.empty();
}
```
The seam must pass `vixEnabled = cfg.requireVixGate()` into `client.context(...)`.

**Step 3 — the richer inferences (the 2 extra gaps: "rising VIX = fresh shorts", "VIX stable + price
falling = longs exiting", "VIX vs prev-day close").** These ride the SAME feed. The `change`-sign
already gives prev-close direction. Add a second optional rule inside the gate behind the same tag:
the "VIX abnormally spiking" magnitude is already manual-covered (`vix_normal`); encode only the
directional confirm here (the spike veto stays manual per the disposition). Document the "fresh
shorts / longs exiting" interpretation as the dot's reason string for the side-channel; no separate
numeric gate (it is the same `rising` sign already used).

**Data flow:** `/vix` → `MarketOiClient.macro` → `Macro.vixRising` → `ScalperGates.vix` →
early-return in the seam. No new persisted field (the `vix` dot already rides `scalper_detail`).

### 3.2 `global-cues-feed` (Dow dot, 1 gap) — `[P]`; Dollar/Asian/Crude — `[S]` (KEEP_MANUAL, no code)

**The Dow dot.** Dow LTP-direction exists (`GlobalQuoteSource`) but is consumed only by the
connecting-dots matrix, never surfaced to the scalper. Two sub-steps:

- **Market-data:** add `GET /api/v1/market/global-cues` to a new tiny controller (or extend
  `MarketSurfaceController`) returning `{dow: {ltp, prevClose, rising}}` by calling
  `GlobalQuoteSource.latest(DOW)` (the same bean `ConnectingDotsService` uses). Flag-gated identically
  (returns `rising=null` when `global-quotes-enabled=false`).
- **Strategy-signal:** add `Boolean dowRising` to `Macro` (arity fan-out); `macro()` reads it only
  when `dowEnabled` (threaded like `vixEnabled`); a new `ScalperGates.dow(Macro, side)`:
  ```java
  /** Dow rising confirms CE / falling confirms PE (global-cue alignment). Unknown never blocks. */
  public static GateOutcome dow(Macro m, OptionType side) {
    if (m.dowRising() == null) return GateOutcome.pass(null, "dow direction unknown");
    boolean ok = side == OptionType.CE ? m.dowRising() : !m.dowRising();
    return new GateOutcome(ok, null, "dow " + (m.dowRising() ? "rising" : "falling")
        + (ok ? " supports " : " opposes ") + side);
  }
  ```
- **Gate:** `ScalperConfig.requireDowGate` (tag `global-cues-gate`); early-return after `ctx`,
  fail-open on null. Same shape as VIX.

**Dollar Index / Asian / European / Crude / Bond / USD-INR (the L25 row).** Disposition =
KEEP_MANUAL_NEW: **no live feeds on the platform** → not automatable now. Coarsely covered by the
shipped `global_cues_ok` manual check. **No code in this stream.** Recorded as Open Point §8.1 (a
future feed-onboarding effort would extend `GlobalQuoteSource` keys, e.g. `DXY`/`CRUDEOIL`, then add
dots behind the same `global-cues-gate` tag).

### 3.3 `fii-dii-bias` (6 gaps) — `[P]`

Four data sub-rules collapse into one consuming dot + one classifier:

1. **Consume the dead-wired `fiiLongPct`** (already in `Macro`, no reader). The "FII index-future L/S"
   directional read: `fiiLongPct > 50` is FII-net-long (CE-favouring), `< 50` net-short (PE).
2. **The L/U/B/C change-in-OI classifier** (the most concrete sub-rule). New market-data analytic:
   add to `NseEodReader` / a new `ParticipantBiasService` a 2-day delta classifier over
   `participant-oi`:
   - For `clientType=FII` (and optionally Pro/DII), compare day-T vs day-(T-1):
     `ΔfutLong = futLong_T - futLong_{T-1}`, `ΔfutShort = futShort_T - futShort_{T-1}`,
     `Δprice` (index close delta, from the candle archive).
   - Classify into the 4×2 LB/SC/LU/SB table: price↑ & ΔlongOI↑ = Long Build-up (bull);
     price↑ & ΔshortOI↓ = Short Covering (bull); price↓ & ΔshortOI↑ = Short Build-up (bear);
     price↓ & ΔlongOI↓ = Long Unwinding (bear). Same enum as the existing `OiInterpretation`.
   - Importance weighting FII > Pro > DII > Client (the disposition's stated priority): take FII's
     classification as primary; Pro as tiebreak.
3. **Leg-level seller read** (4 participants × 6 legs): net-seller of Calls = bearish, net-seller of
   Puts = bullish. From the same `ParticipantOiRow`:
   `callNet = optionIndexCallLong - optionIndexCallShort`, `putNet = optionIndexPutLong -
   optionIndexPutShort`. FII net-short calls (callNet<0) + net-long puts skew bearish, mirror bullish.
4. **Next-morning-only validity / voided by strong global moves** — a scope qualifier (the bias is an
   EOD read valid only into the next session). Encode as: the FII gate is consulted only on the FIRST
   N bars of the session (e.g. before 10:00 IST); after that it degrades to pass. The "voided by
   strong global moves" leg rides the Dow gate (3.2) — if Dow strongly opposes, suppress the FII bias.
   v1: keep simple — gate active all session, fail-open on null; record the time-window refinement as
   Open Point §8.3.

**Wiring:**
- **Market-data:** `GET /api/v1/market/fii-dii/bias?date=<T>` → `{fiiClassification: "LONG_BUILDUP",
  bias: "BULL", fiiLongPct, callNet, putNet, legBias: "BULL"}` from the new `ParticipantBiasService`
  (reads `participant-oi` for T and T-1 + the index close delta). Reuses the existing repo.
- **Strategy-signal:** add `BigDecimal fiiBiasSign` to `Macro` (+1 bull / -1 bear / 0 neutral),
  populated by `macro()` only when `fiiEnabled`. (`fiiLongPct` already present; the new field carries
  the COMBINED classifier verdict so the dot is a single read.)
- **Gate:** `ScalperConfig.requireFiiGate` (tag `fii-dii-gate`); `ScalperGates.fii(Macro, side)`:
  ```java
  /** FII EOD bias: net-long / LB / SC + net-Put-seller = bullish -> CE; mirror -> PE. Combined into
   *  Macro.fiiBiasSign (+1/-1/0); 0 (no data / conflicting) DEGRADES to pass (fail-open). */
  public static GateOutcome fii(Macro m, OptionType side) {
    if (m.fiiBiasSign() == null || m.fiiBiasSign().signum() == 0)
      return GateOutcome.pass(null, "fii bias unavailable/neutral");
    boolean bull = m.fiiBiasSign().signum() > 0;
    boolean ok = side == OptionType.CE ? bull : !bull;
    return new GateOutcome(ok, m.fiiBiasSign(), "fii bias " + (bull ? "bull" : "bear")
        + (ok ? " supports " : " opposes ") + side);
  }
  ```
  Early-return after `ctx`, fail-open on null/zero.

**Derived-history caveat:** the participant matrix is real EOD NSE data (not OI-derived), so unlike
the OI gates this one DOES carry signal on a past EOD date — but the next-session intraday backtest
still reads the *prior* completed session's `eodDate` (the seam already passes `eodDate` for
breadth/FII). So `fii-dii-gate` is usable in backtest, unlike `futures-oi-gate`. Note this in the
YAML header when PR-3 arms it.

### 3.4 `constituent-contribution` (1 gap) — `[P]`

`EquityIndexContributionService` already computes `advanceTotal` vs `declineTotal` per index. The
directional read: `advanceTotal > |declineTotal|` ⇒ heavyweights pushing up (CE-favouring), mirror PE.

**Wiring:**
- **Market-data:** the endpoint **already exists** (AUDIT pass 1 — verified):
  `GET /api/v1/market/equity/index-contribution?name=<NIFTY 50>` (`EquityController` L54-59) returning
  the `IndexContribution` (advanceTotal/declineTotal/indexChangePct). **Reuse it as-is.** Param is
  `name` (not `index`); there is **no `date` param** (it serves the latest EQ bhavcopy — fine for the
  live macro read, which only ever wants the most-recent completed session). **No new `@GetMapping`,
  so no contract drift for this package.** `MarketOiClient` reads it via a new
  `constituentBias(String underlying)` helper that maps the `IndexContribution` to a +1/-1/0 sign.
  **Underlying-name caveat:** the seam threads the oi/option-root underlying (e.g. `"NIFTY 50"`); pass
  that name straight through — `contribution(String)` keys off the same index names `StaticIndexWeights`
  carries. **AUDIT pass 2 — seed verified:** `reference/index-weights.json` seeds exactly `"NIFTY 50"`,
  `"NIFTY BANK"`, `"NIFTY 200"` — **`"SENSEX"` is NOT seeded.** So a NIFTY `constituent-gate` fires;
  a SENSEX one is a silent no-op until BSE weights land (see Open Point §8.10). **404-not-null degrade
  (AUDIT pass 2):** `EquityIndexContributionService.contribution(String)` THROWS
  `NotFoundException(404, NOT_FOUND_RESOURCE)` when `weights(index)` is empty (L63-66) — it does NOT
  return a null/empty body. Exactly like the `/vix` 422, the `MarketOiClient.get(...)` catch (L659)
  degrades that 404 to the fallback, so `constituentBias(...)` must use the `get(...)` helper (or
  otherwise catch the HTTP error) → +0 sign → the gate fail-opens. The "fail-open on null" wording in
  the gate row below therefore covers BOTH a null body and a 404 throw, but the reader must NOT assume a
  200-with-empty body.
- **Strategy-signal:** add `BigDecimal constituentBiasSign` to `Macro` (+1/-1/0 from
  `signum(advanceTotal + declineTotal)` or `signum(indexChangePct)` weighted by breadth), populated by
  `macro()` only when `constituentEnabled`.
- **Gate:** `ScalperConfig.requireConstituentGate` (tag `constituent-gate`);
  `ScalperGates.constituent(Macro, side)` mirroring `fii(...)`; early-return after `ctx`, fail-open
  on null. FU1's `constituent_contribution` manual check stays (this automates it).

**Derived-history caveat:** uses the EQ bhavcopy + static weights → real on a past EOD date, but
needs the index daily close (the service notes `points` is null when the index close isn't archived).
The *sign* (advance vs decline total) is robust even without the level, so the gate works on history.

### 3.5 `volume-pump-attribution` (1 gap) — `[P]`

The scorer's `volume` dot (L79) is floor-only. The doc's >50K/125K dark-green/dark-red rule attributes
a high-volume candle to a bull vs bear pump by price sign (exactly `ConnectingDotsService.volumeFactor`
L240-246: rising volume + price up = bull, + price down = bear).

**Design:** add a NEW gate fn `ScalperGates.volumePump(Chart c, BigDecimal prevClose, String underlying,
OptionType side)`:
```java
/** Volume-pump attribution (§4.15.3): a candle clearing the floor AND closing in the side's
 *  direction (close > open for CE / < open for PE) is a confirming pump; clearing the floor against
 *  the side is an OPPOSING pump (block). prevClose/open null DEGRADES to pass. */
```
Use `Chart.close()` vs the bar `open` (the seam has the `future` series — pass `future.candle(index)`
open). **This is parity-sensitive** (it can turn a passing floor into a block when the pump opposes
the side). So it is a tag `volume-pump`, `requireVolumePump`, early-return after the volume/RSI rails.
The existing floor `volume` dot in the scorer is UNCHANGED (still floor-only) — the pump is an
*additional* hard precondition, not a replacement, keeping the aggregate byte-identical.

**Note on the open price:** `ScalperGateContext.Chart` has no `open`. Rather than extend `Chart`
(arity fan-out into the scorer), read the open from the `future` `EngineSeries` already in scope in
`evaluate` (`future.candle(index).open()`), exactly as the structural-stop code does (L294). So
`volumePump` takes the open as a param, not via `Chart`.

---

## 4. PARITY classification

| Package | Change | Class | Tag (new, default-OFF) | Golden-variant plan |
|---|---|:--:|---|---|
| `directional-vix-gate` | Populate `Macro.vixRising` in `macro()` **only when vix-gate armed** + early-return gate | **[P]** | `vix-gate` | None needed (LIVE-only seam; no golden YAML is a scalper). Proof = `ScalperStrategyLoadTest` asserts `requireVixGate` OFF for all 36 + the two engine tripwires stay byte-identical. |
| `global-cues-feed` (Dow dot) | New `Macro.dowRising` + `ScalperGates.dow` + early-return gate | **[P]** | `global-cues-gate` | Same as above. |
| `global-cues-feed` (DXY/Asian/Crude) | none (KEEP_MANUAL — no feed) | **[S]** | — | n/a (no code). |
| `fii-dii-bias` | New `ParticipantBiasService` (read-only analytic) | **[S]** | — | Read-only market-data analytic + endpoint; no signal change until the gate consumes it. |
| `fii-dii-bias` | `Macro.fiiBiasSign` + `ScalperGates.fii` + early-return gate | **[P]** | `fii-dii-gate` | Same LIVE-only-seam proof. |
| `constituent-contribution` | Endpoint over existing `EquityIndexContributionService` | **[S]** | — | Read-only; the service already exists. |
| `constituent-contribution` | `Macro.constituentBiasSign` + `ScalperGates.constituent` + gate | **[P]** | `constituent-gate` | Same. |
| `volume-pump-attribution` | `ScalperGates.volumePump` + early-return gate (scorer `volume` dot UNCHANGED) | **[P]** | `volume-pump` | Same. The floor dot stays in the aggregate untouched → byte-identical when unarmed. |

**Why no NEW golden vectors are created** (mirrors FU2 §5.4/§5.6): every `[P]` change here lives on the
**LIVE-only `ScalperConfluenceGate` path**, which `GoldenDeterminismTest` and `BacktestParityTest`
never instantiate (their FEATURES arrays are the 5 pure-engine YAMLs with no scalper). A tag-gated
gate **cannot** perturb those goldens as long as no golden/parity YAML carries a tag — and none can.
The byte-identity guarantee for shipped scalper configs is enforced by the **`ScalperStrategyLoadTest`
OFF-assertions** (each new `requireXxx` is asserted false for all 36 seeded strategies), NOT by a new
golden. **A new golden variant would only be required if a `[P]` change ran on the deterministic
replay path — none here do.** Producing one is explicitly out of scope (would require driving the
LIVE-only gate through `TickwiseGoldenRunner`; FU2 §5.6 / §8.8 deferred this). Recorded Open Point §8.6.

**The one subtle non-tagged parity risk** (flagged so the executor cannot miss it): populating
`Macro.vixRising` unconditionally would flip the EXISTING `vix` soft dot (scorer L92) for every
scalper. §3.1 step 1 prevents this by gating the producer read on `vixEnabled = requireVixGate`. The
`ScalperStrategyLoadTest` must therefore ALSO assert that, for an un-armed strategy, `macro()` still
yields `vixRising == null` (a producer-level tripwire, not just a config-flag one) — see §5.3.

---

## 5. Tests

### 5.1 Unit — gate functions (`ScalperGatesTest.java`)
- `vix(...)` already tested (`vixDirectionFavoursSideAndUnknownNeverBlocks` L118-122). **Reuse.**
- `breadth(...)` already tested (L110-115). **Reuse.**
- **New:** `dowDirectionFavoursSideAndUnknownNeverBlocks` (mirror the vix test: rising→CE pass,
  rising→PE pass, null→pass).
- **New:** `fiiBiasFavoursSideAndNeutralPasses` (+1→CE pass, +1→PE fail, 0→pass, null→pass).
- **New:** `constituentBiasFavoursSide` (mirror fii).
- **New:** `volumePumpConfirmsWithDirectionAndBlocksAgainst` (floor-clearing close>open + CE pass;
  floor-clearing close<open + CE block; below-floor → the floor gate already blocks separately;
  null open → pass). Add `macro(...)`/`chart(...)` builder overloads for the new `Macro` fields.

### 5.2 Unit — seam wiring (`ScalperConfluenceGateTest.java`)
- **Arity fan-out #1 (ScalperConfig):** the 5 new `requireXxx` fields force updating **all 8** existing
  `new ScalperConfig(...)` literals (L44,49,56,62,68,74,81,87) — append 5 trailing `false`s to each
  (none arm the new gates). Pure compile-time fan-out (FU2 §2.4 precedent: 8 literals, confirmed by
  audit against the live file).
- **Arity fan-out #2 (client.context mock — AUDIT pass 1, MUST land in the same PR):** adding the
  macro-feed param(s) to `MarketOiClient.context(...)` breaks **all 24** `client.context(...)` call
  sites in this test (the `when(...)` stubs use `eq("NIFTY 50"), any() ×6`; the straddle test's
  `verify(client, never()).context(any() ×7)` at L754-755). Each stub gains one extra `any()` matcher
  and the `verify` one extra `any()`. (See §3.1 step 1.) Forgetting this is a compile failure, not a
  silent parity bug — but it WILL block the build, so call it out in the PR checklist.
- **Forwarding assertion:** add a focused test asserting the macro-feed flag is forwarded — capture the
  `context(...)` call (Mockito `ArgumentCaptor` or a matcher on the new param position) and assert the
  vix-feed boolean is `true` ONLY for `VIX_CFG` and `false` for the bare `CFG`. (Originally phrased as
  "the 8th arg"; with the single-`MacroFeeds` form per §8.11 it is the LAST arg's `.vix()` — assert on
  the field, not a positional index.)
- For **each** of `vix-gate`, `global-cues-gate`, `fii-dii-gate`, `constituent-gate`, `volume-pump`
  add a CFG literal + the FU2 triple (pass / block / non-armed-unaffected), mirroring the #5
  `oi-cross-filter` template (the `confluenceConfirmsAndPicksTheInBandCe` + block + `nonXxxUnaffected`
  shape):
  - **vix-gate:** mock `client.context(...)` to return a `bullContext()` whose `Macro.vixRising=TRUE`
    (rising) → CE blocks; `FALSE` (falling) → CE passes; bare `CFG` (no tag) → passes despite rising
    (proving the soft dot, not the gate, governs un-armed). Plus assert the `vixEnabled` flag is
    forwarded: verify `client.context(...)` is called with the 8th arg true only for `VIX_CFG`.
  - **global-cues-gate:** `Macro.dowRising=FALSE` + CE → block; `TRUE` → pass; bare `CFG` → pass.
  - **fii-dii-gate:** `Macro.fiiBiasSign=bd("-1")` + CE → block; `+1` → pass; `0`/null → pass.
  - **constituent-gate:** `Macro.constituentBiasSign=bd("-1")` + CE → block; `+1` → pass.
  - **volume-pump:** a `future` series whose `candle(index)` closes DOWN (close<open) but clears the
    floor + CE → block; closes UP → pass; bare `CFG` → pass. (Use the real `EngineSeries` test
    fixture, since the gate reads `future.candle(index).open()`.)
  - Each gets a `non<Pkg>Unaffected` test using bare `CFG` against the same failing context →
    `.isPresent()`.

### 5.3 Load test (`ScalperStrategyLoadTest.java`)
- Add 5 OFF-assertions in the per-strategy loop (after L153), mirroring the `requireCallPutDeltaFilter`
  per-family assertion (L151):
  ```java
  assertThat(cfg.requireVixGate()).as(id + " vix-gate off").isFalse();
  assertThat(cfg.requireDowGate()).as(id + " global-cues-gate off").isFalse();
  assertThat(cfg.requireFiiGate()).as(id + " fii-dii-gate off").isFalse();
  assertThat(cfg.requireConstituentGate()).as(id + " constituent-gate off").isFalse();
  assertThat(cfg.requireVolumePump()).as(id + " volume-pump off").isFalse();
  ```
- **Producer tripwire (the §4 subtle risk):** add a focused test in `MarketOiClientTest` (or a new
  `MarketOiClientMacroTest`) that `macro(underlying, date, /*vixEnabled*/ false)` returns
  `vixRising == null` and `vixLevel == null` even when the mocked `/vix` endpoint would return a
  value — proving the un-armed path is byte-identical to today.

### 5.4 Market-data unit/IT — the new analytics
- `ParticipantBiasService` unit test: feed two `ParticipantOiRow`s (T-1, T) + a price delta → assert
  the LB/SC/LU/SB classification + the leg-level callNet/putNet sign + the combined `bias`.
- `ParticipantBiasController` slice test + a Testcontainers IT seeding `nse_eod_participant_oi` two
  days (the IT harness already migrates the NSE lineage) → assert `/fii-dii/bias` returns the verdict.
- `MarketSurfaceController` (or the new global-cues controller): a `/vix` direction + `/global-cues`
  slice test (mock `GlobalQuoteSource`); these are read endpoints (no contract-breaking change — new
  paths DO drift springdoc, so re-capture per §6).
- `EquityIndexContributionService` already has coverage; add an endpoint slice test if a new
  `@GetMapping` is introduced.

### 5.5 Golden / parity tripwires (MUST stay byte-identical — re-run, do NOT regenerate)
- `GoldenDeterminismTest` (`libs/strategy-engine`) — FEATURES carry no scalper → invisible. Assert
  byte-identical green.
- `BacktestParityTest` (`services/backtest-service`) — FEATURES carry no scalper → assert the three
  byte-match asserts stay green.

### 5.6 e2e
No scalper-specific e2e spec; the stream ships default-OFF (no YAML arms a tag) → no observable
behaviour change. Re-run `e2e/tests/signals.spec.ts` as a regression check only. If PR-arming
(deferred) later arms a tag, add an assertion there that the confluence chip row still renders.

### 5.7 Contracts (`ContractCaptureTest`)
The new market-data GET paths — **`/global-cues` (Dow) and `/fii-dii/bias`** — ADD `@GetMapping`
paths → **springdoc spec DOES drift** (per CLAUDE.md: new paths drift, generic Map returns do not).
Re-capture with `-Dcontracts.capture=true`, regen TS via `npx openapi-typescript@7` →
`contracts/gen/*.d.ts`, `tsc --strict`.
- **AUDIT pass 1 — `/equity/index-contribution` does NOT drift:** that path already exists
  (`EquityController` L54-59) and is NOT modified by this stream → no new path, no spec change for
  the constituent package. (Original §5.7 wrongly hedged "optionally `/equity/index-contribution`".)
- **`/vix` does NOT drift:** this stream adds no field to `VixQuote` and no new `/vix` path — it only
  *reads* the existing endpoint. No re-capture needed for VIX.
- Net: exactly **two** new paths drift the spec — `/global-cues` and `/fii-dii/bias`, both in PR-2.

---

## 6. Dependencies & sequencing

1. **VIX feed wiring (3.1) is the spine** — it establishes the `vixEnabled`-threaded
   `macro()`/`context()` signature that Dow/FII/constituent reuse. **Do it first.** The `/vix`
   endpoint already exists (no market-data work needed for VIX itself).
2. **`global-cues-feed` (Dow)** depends on the `GlobalQuoteSource` flag (`global-quotes-enabled`)
   being on for live signal; ships fail-open so it is harmless when off. Needs the new
   `/global-cues` endpoint before the gate can read Dow → **market-data PR precedes the gate PR.**
3. **`fii-dii-bias`** needs the `ParticipantBiasService` + `/fii-dii/bias` endpoint
   (market-data, `[S]`) BEFORE the `Macro.fiiBiasSign` producer read + gate (strategy-signal, `[P]`).
   **Two-step: analytic first, gate second.**
4. **`constituent-contribution`** needs the contribution endpoint (verify it exists from the oipulse
   wave; else add it) BEFORE the gate. Lightest — the service is built.
5. **`volume-pump-attribution`** is self-contained (no new feed; reuses the in-scope `future` series).
   Can ship independently of 1-4.
6. **No SPAN / equity-universe dependency** — this stream is all index-level macro, so it is NOT
   gated on `equity-fno-universe-screener` (unlike the market-movers per-stock packages) and NOT on
   the SPAN appliance (no sell legs). It can proceed immediately.
7. **Arming any tag on a real YAML (PR-X, deferred)** is owner-driven forward-paper, AFTER the
   infrastructure PRs, per "tune on live, not backtest" (MEMORY: scalper-tuning). Not in this delivery.

The macro-record arity fan-out (each `[P]` adds a `Macro` field) means **batch the field additions or
accept N successive arity bumps** to `MarketOiClient`'s `new Macro(...)` + all `Macro` test builders.
Recommend adding all four macro fields (`vixRising` already exists; add `dowRising`, `fiiBiasSign`,
`constituentBiasSign`) in the FIRST strategy-signal PR to pay the fan-out once. See Open Point §8.4.

---

## 7. Effort + PR breakdown

Overall effort: **M** (mostly wiring existing feeds; the one genuinely new analytic is the FII L/U/B/C
classifier). Suggested PRs (short-lived `feat/`|`feat(strategy-signal)`/`feat(market-data)` branches,
Conventional Commits, squash-merge, build with `-pl <svc> -am verify`):

- **PR-1 `feat(strategy-signal): directional-vix-gate (tag-gated, default-off)`** — **M.**
  Thread `vixEnabled` through `context()`/`macro()`, `vixDirection()` reader, `requireVixGate` flag +
  `vix-gate` tag, early-return gate, the seam triple + the producer null-tripwire, load-test OFF
  assertion. (The macro-field fan-out batch — adding `dowRising/fiiBiasSign/constituentBiasSign`
  placeholders — can land here to pay arity once; OR keep PR-1 minimal and fan out per-PR. §8.4.)
- **PR-2 `feat(market-data): participant FII/DII bias + global-cues + index-contribution endpoints`**
  — **M.** `ParticipantBiasService` + `/fii-dii/bias`, `/global-cues` (Dow), confirm/add
  `/equity/index-contribution`; unit + slice + IT tests; contract re-capture. (`[S]` — read-only.)
- **PR-3 `feat(strategy-signal): fii-dii / constituent / global-cues macro gates (tag-gated)`** —
  **M.** Consume PR-2's endpoints: `Macro.fiiBiasSign/constituentBiasSign/dowRising` reads,
  `ScalperGates.fii/constituent/dow`, three `requireXxx` flags + tags, three early-returns, the three
  seam triples, three load-test OFF assertions.
- **PR-4 `feat(strategy-signal): volume-pump attribution gate (tag-gated)`** — **S.** Self-contained:
  `ScalperGates.volumePump`, `requireVolumePump` + `volume-pump` tag, early-return reading
  `future.candle(index).open()`, the seam triple, load-test OFF assertion.
- **PR-X (DEFERRED, owner-driven) `feat(strategy-signal): arm <tag> on <variant>`** — forward-paper
  A/B (e.g. `vix-gate` + `fii-dii-gate` on the connect-the-dots family). Flip the matching load-test
  OFF assertion to a per-id expectation; re-run goldens (still green). Add the YAML-header
  derived-history note for `constituent-gate`/`fii-dii-gate`. Not part of this stream's delivery.

PR-1 and PR-4 are independent of PR-2/PR-3 (PR-4 needs no feed). PR-3 hard-depends on PR-2.

---

## 8. Open Points

1. **Dollar Index / Asian / European / Crude / Bond / USD-INR feeds (global-cues-feed L25).** No live
   source on the platform today (only Dow via `GlobalQuoteSource`). **Options:** (a) leave as the
   shipped `global_cues_ok` MANUAL check (disposition = KEEP_MANUAL_NEW) — **recommended default**;
   (b) onboard `DXY`/`CRUDEOIL`/etc. as new `GlobalQuoteSource` keys (OpenAlgo/Upstox global indices)
   then add dots behind the same `global-cues-gate` tag — a separate feed-onboarding effort.
   Recommend (a); revisit if the owner funds the feeds.

2. **VIX directional rule: live `/vix` change-sign vs intra-session candle slope.** §3.1 uses the
   `/vix` endpoint's `change` (ltp − prevClose) for direction = "vs prev-day close". The deck also
   mentions intra-session rising/falling (the candle slope `ConnectingDotsService.vixByBucket` uses).
   **Options:** (a) use the prev-close sign only (simplest, one read) — **recommended default**;
   (b) also read the last two 1m INDIA VIX candles for an intra-session slope and require both to
   agree. Recommend (a) for v1; the "erratic intraday VIX → ignore" nuance stays manual (`vix_normal`).

3. **FII bias validity window (fii-dii-bias gap 4 — "next-morning-only, voided by strong global
   moves").** §3.3 v1 keeps the gate active all session, fail-open on null. **Options:** (a) all-session
   (simplest) — **recommended default**; (b) restrict the FII gate to before 10:00 IST (next-morning
   only) and suppress it when the Dow gate strongly opposes (the "voided by global moves" leg).
   Recommend (a) for v1, (b) as a forward-paper refinement once armed.

4. **Where to pay the `Macro`-record arity fan-out.** Adding `dowRising`/`fiiBiasSign`/
   `constituentBiasSign` to the record forces updating every `new Macro(...)` + test builder.
   **Options:** (a) add all three placeholders in PR-1 (pay the fan-out once; PR-3 then only wires the
   reads) — **recommended default**; (b) add each field in its own PR (N smaller arity bumps, more
   churn). Recommend (a).

5. **`fii-dii-gate` / `constituent-gate` ARE usable on backtest (unlike the OI gates).** They read
   real NSE EOD participant/bhavcopy data, not OI-derived history, so they carry signal on a past
   `eodDate`. **Decision needed:** should the arming YAML (PR-X) mark them backtest-usable (a
   discriminator the OI gates can't be) or still forward-paper-only for consistency? Recommend:
   **backtest-usable** — they are a genuine historical filter; document the distinction in the YAML
   header. (Open for owner sign-off.)

6. **Positive deterministic (golden) coverage of the new gates.** Not built (would require driving the
   LIVE-only seam through `TickwiseGoldenRunner`; FU2 §8.8 deferred this). **Options:** (a) rely on the
   seam unit triples (pass/block/unaffected) — **recommended default**; (b) invest in a scalper golden
   harness (large, separate). Recommend (a).

7. **`volume-pump` open-price source.** §3.5 reads `future.candle(index).open()` rather than extending
   `Chart` with an `open` field (avoids a scorer arity fan-out). **Options:** (a) read from `future`
   in the seam (no `Chart`/scorer change) — **recommended default**; (b) add `Chart.open()` and a
   scorer `volume_pump` dot (a `[P]` denominator change — heavier, needs the FU2 carve-out). Recommend
   (a) — keeps the change a pure hard gate with the scorer untouched.

8. **Single `macro-confluence` mega-tag vs five granular tags.** This stream introduces 5 tags. **Options:**
   (a) five granular tags (`vix-gate`, `global-cues-gate`, `fii-dii-gate`, `constituent-gate`,
   `volume-pump`) — **recommended default**, lets the owner A/B each macro confirm independently
   (the scalper-tuning lesson: isolate each gate for the A/B); (b) one `macro-confluence` tag arming
   all five (fewer config tokens, no per-gate A/B). Recommend (a).

9. **Does the existing `/vix` response shape drift the springdoc contract?** §3.1 reads existing
   `/vix` fields (`ltp`, `change`); no new path, so **no drift** (AUDIT pass 1: confirmed — the
   stream adds no field to `VixQuote` and no new `/vix` path). `/global-cues` and `/fii-dii/bias`
   ARE new paths and DO drift. **Action:** run `ContractCaptureTest` after PR-2 and re-capture.

10. **(AUDIT pass 1) `StaticIndexWeights` coverage for SENSEX constituent-contribution.**
    `EquityIndexContributionService.contribution(String)` keys off `StaticIndexWeights` + the EQ
    bhavcopy. NIFTY 50 constituents are NSE-EQ; **SENSEX constituents are BSE.** Before arming
    `constituent-gate` on a SENSEX variant, confirm `StaticIndexWeights` carries `"SENSEX"` and that
    the bhavcopy table the service reads has BSE EQ rows — else the SENSEX read returns empty and the
    gate fail-opens (harmless but a no-op). **Decision:** v1 arms `constituent-gate` on NIFTY variants
    only; defer SENSEX until BSE constituent coverage is confirmed. (Open for owner sign-off.)

11. **(AUDIT pass 1) Collapse the four macro-feed booleans into one param.** §3.1/§3.2/§3.3/§3.4 each
    thread a `boolean xxxEnabled` into `MarketOiClient.context(...)`/`macro(...)`. Four loose trailing
    booleans is a positional-soup smell and multiplies the 24-call-site test fan-out. **Options:**
    (a) one `record MacroFeeds(boolean vix, boolean dow, boolean fii, boolean constituent)` (or an
    `EnumSet<MacroFeed>`) passed as the single last param — **recommended default**; (b) four loose
    booleans (matches the plan-body sketch but worse). Recommend (a). Either way the
    `MarketOiClient.macro(...)` return-site stays a single `new Macro(...)`.

12. **(AUDIT pass 1) `ScalperGates.volumePump` signature uses `open`, not `prevClose`.** §3.5's design
    text grades on close-vs-bar-`open` (the §4.15.3 dark-green/dark-red pump direction) and the "Note
    on the open price" reads `future.candle(index).open()` — but the sketched signature still lists
    `BigDecimal prevClose`. The implemented signature should be
    `volumePump(BigDecimal close, BigDecimal open, BigDecimal volume, String underlying, OptionType side)`
    (or take a `Chart` for close/volume + the `open` from `future`). Drop the `prevClose` param — it is
    a leftover from the floor-gate shape and is not what the pump rule tests.

13. **(AUDIT pass 1) FII `/fii-dii/bias` index-future-only vs full participant matrix.** §3.3 derives
    `fiiBiasSign` from the FII `ParticipantOiRow` (futures L/S + option-leg net). The existing
    `/long-short` (which `fiiLongPct` already reads) is **FII index-FUTURES only**; the new
    `ParticipantBiasService` must read the full `participant-oi` rows (it has the option-leg columns)
    for the leg-level seller read. Confirm the writer (`ParticipantOiFetcher` →
    `nse_eod_participant_oi`) populates the `optionIndexCall/PutLong/Short` columns (not just futures)
    for two consecutive days before relying on the leg-level term. If only futures are populated
    historically, the leg-level read degrades to neutral on those dates (fail-open, harmless).

14. **(AUDIT pass 2) Macro gates are inert on the straddle family.** The neutral-straddle path returns
    at `ScalperConfluenceGate` L132-147 BEFORE `ctx`/`side` exist, so a macro tag armed on a
    `scalp-straddle-*` variant never fires (silent no-op). **Decision:** PR-X arms macro gates on
    DIRECTIONAL families only (connect-the-dots, trending-oi, etc.) — never a straddle variant. If a
    volatility-direction confirm for straddles is ever wanted it needs a SEPARATE pre-`ctx` gate, out of
    scope. (No code change needed now; a documentation guardrail for the arming step.)

---

## Audit pass 1 findings

Auditor opened every cited file in the working tree on 2026-06-27. **Verdict: SOUND-WITH-OPEN-POINTS.**
The architecture is correct, compiles as described, and is parity-safe (every signal-altering change is
behind a NEW default-OFF tag + early-return hard gate, scorer untouched → existing goldens byte-identical).
Corrections were applied in place; the residue is open points, not blockers.

### Citations checked (✓ = confirmed exact / accurate after correction)
- ✓ `ScalperGateContext.Macro` L59-68 — record + fields exactly as claimed (`vixLevel/vixRising` null today).
- ✓ `MarketOiClient.macro(...)` — body L351-398, VIX gap return L396-397, comment L394-395. **Corrected:**
  signature is `macro(String, LocalDate)`; `latestFiiLongPct` L626-641 (was L625-641); `advanceDecline`
  L620-623 (was L619-623). `macro()` has no monthly-expiry branch (clean for `vixEnabled` threading).
- ✓ `ConnectTheDotsScorer.score` L63-118 — 18-dot list confirmed; `volume` L79, `breadth` L91, `vix` L92;
  Σweights = 19.6 recomputed exactly (2.5 + 14×1.0 + 1.5 + 0.8 + 0.8).
- ✓ `ScalperGates` — `vix` L136-143 (unknown PASSES L137-138), `breadth` L128-133, `volume` L64-68,
  `callPutDeltaFilter` L151-161 (the #5 fail-open precedent). `GateOutcome.pass/fail(BigDecimal,String)`
  exist (L13-20) → the proposed gate fns are type-correct.
- ✓ `ScalperConfig` — `from(...)` L101-157, canonical `new ScalperConfig(...)` L154-156, `oi-cross-filter`
  tag L153. **Corrected:** the record has **16** fields (not 15), ending `requireStraddle`.
- ✓ `ScalperConfluenceGate.evaluate` — `client.context(...)` IS at L191-192 (the plan's "~L192" insert
  point for the VIX early-return is correct); `eodDate` param threaded; structural-stop `future.candle(index)`
  precedent at L293-294.
- ✓ `EngineCandle` has `.open()` (L13) → §3.5 `future.candle(index).open()` is sound.
- ✓ `GoldenDeterminismTest.FEATURES` = 5 pure-engine YAMLs, no scalper (L33-36); `BacktestParityTest`
  FEATURES likewise (L35-38). The byte-identity proof holds: no golden/parity YAML can carry a new tag.
- ✓ `ScalperStrategyLoadTest` — 36 variants (12×3), `requireCallPutDeltaFilter` per-family assertion
  L151-153; the 5 OFF-assertions slot into the per-strategy loop (L106-165) cleanly.
- ✓ `ScalperConfluenceGateTest` — **8** `ScalperConfig` literals at exactly L44/49/56/62/68/74/81/87
  (matches FU2 §2.4 precedent).
- ✓ Market-data feeds: `MarketSurfaceController.vix()` (`/vix`, L60-82); `GlobalQuoteSource` interface +
  `ConnectingDotsService.dowFactor` L316-333 / `DOW=DOWJONES@GLOBAL_INDEX` L58 / `volumeFactor` L240-246 /
  `vixByBucket` L353-367; `FiiDiiController` `/participant-oi` L49-55, `/long-short` L57-78, `LongShortRow` L31;
  `NseEodReader.ParticipantOiRow` L30-46 (all option-leg fields present); `EquityIndexContributionService`
  `IndexContribution` L49-53.
- ✓ GAP-DISPOSITION package counts: directional-vix 11 (L118), fii-dii 6 (L126), volume-pump /
  constituent / global-cues single-gap (L157/L165/L173). VIX+Dow "explicitly OUT of FU2" — corroborated
  by FU2 plan L51-53 + L648-653 (the Dow-dot-denominator trap the §3.0 mechanism avoids).

### Wrong / stale cites — CORRECTED
1. **§1 per-doc source-row line numbers were systematically stale.** The strategy-audit files were
   regenerated and their line numbering drifted. The plan pointed at the WRONG rows: e.g.
   `indicators-oi-vix-iv.md L25/L27` are "Daily-RSI" / "Volume-threshold" (the actual VIX rows are
   **L42-45**); `connect-the-dots.md L23/L24/L25/L26` are RSI/PSAR/Volume/Two-candle (the actual
   VIX/Dow/FII rows are **L31/L33/L36**); `gates-strike-sr-fiidii.md L27-L31` are time-cap/expiry/S&R/OIP
   (the actual VIX row is **L34**, FII rows **L35-L38**); `trend-change.md L29` → actual constituent row
   is **L34**. Re-pointed in §1. The *content* of every row matches the plan's claims — only the pointers
   were wrong. (The package-level tallies, taken from the current GAP-DISPOSITION, were correct.)
2. **`volume-pump-attribution` doc cite `indicators-oi-vix-iv.md L32` is wrong** — L32 is the OI-Spurts
   4-quadrant row (FULL coverage). The volume rows there (L27/L38) are both FULL. The package is the
   single-gap `AUTOMATE_PKG` row in GAP-DISPOSITION L157; re-cited to the disposition, not a doc line.
3. **`/equity/index-contribution` ALREADY EXISTS** (`EquityController` L54-59) — param is **`name`**
   (NOT `index`) and there is **NO `date` param** (the service reads the latest EQ bhavcopy). §2.6/§3.4/§5.7
   corrected: the constituent package adds NO new path → NO contract drift; its `[S]` market-data work is ~zero.
4. **`/vix` throws 422 on no-quote** (not a null body) — the `MarketOiClient.get(...)` catch degrades it to
   `VixRead.EMPTY`, so fail-open still holds, but §2.6 now states this so the reader is implemented correctly.
5. **Field-count + minor line drifts** (§2.5 16-not-15 fields; §2.2 fii/breadth line numbers) corrected.

### Completeness gaps added
- **The 24 `client.context(...)` test call sites** (`ScalperConfluenceGateTest`) all break when `context()`
  gains a macro-feed param — invisible from the original §5.2 "8 literals" note. Added to §3.1 step 1 + §5.2
  as a same-PR compile-time fan-out. (Recommend collapsing the 4 feed booleans to one `MacroFeeds` param —
  Open Point §8.11.)
- **`/vix` and `/equity/index-contribution` do NOT drift the contract**; only `/global-cues` + `/fii-dii/bias`
  do (§5.7 tightened).

### Parity assessment (the critical axis) — PASS
- Every `[P]` change is on the **LIVE-only `ScalperConfluenceGate`** path (class javadoc L31-35 confirms
  OI/macro/chain reads never run on deterministic replay); golden/parity FEATURES carry zero scalper YAMLs;
  no shipped YAML carries any new tag (grep-confirmed empty). So `GoldenDeterminismTest` / `BacktestParityTest`
  stay byte-identical.
- The one **non-tagged** parity trap — populating `Macro.vixRising` would flip the EXISTING `vix` soft dot
  (scorer L92) for every scalper — is correctly carved out in §3.1 step 1 (gate the producer read on
  `vixEnabled`), and §4 + §5.3 add the producer-level tripwire (`macro(...,false)` ⇒ `vixRising==null`).
  This is the load-bearing parity decision and it is sound. **No new golden variant is required** (no `[P]`
  change runs on the replay path) — correctly argued in §4.
- Classification table (§4) is correct: the read-only analytics are `[S]`; the gate additions are `[P]`
  behind their own tag. No `[P]` change is mis-marked `[S]`.

### Dependency sequencing — correct
VIX spine first (establishes the feed-threading signature) → market-data analytic PR (PR-2) before the
gate PR that consumes it (PR-3) → volume-pump self-contained (PR-4). No SPAN / equity-universe dependency
(this is index-level macro, no sell legs) — correctly noted in §6.6. Arming on real YAMLs deferred to the
owner-driven forward-paper PR-X.

### Residual open points (do not block the developer)
The 13 Open Points (§8) capture the genuine unknowns; pass 1 added §8.10 (SENSEX `StaticIndexWeights`
coverage), §8.11 (collapse the 4 feed booleans to one `MacroFeeds` param), §8.12 (`volumePump` signature
uses `open` not `prevClose`), §8.13 (FII bias needs the full participant matrix, not just index-futures L/S).
None are blockers; all have a recommended default that ships safely (fail-open).

---

## Audit pass 2 findings

INDEPENDENT second audit, 2026-06-27. The auditor re-opened the working tree from scratch (did not trust
pass 1's pointers), re-verified a fresh sample of citations, re-checked the parity firewall end-to-end,
confirmed the pass-1 corrections, and hunted for anything both the author and pass 1 missed.
**Verdict: SOUND-WITH-OPEN-POINTS — implementation-ready.**

### Citations re-verified independently (✓ exact in the working tree)
- ✓ `ScalperGateContext.Macro` — record + slots (`vixLevel`/`vixRising`/`fiiLongPct`) exactly as cited;
  the package is `in.arthayantra.strategysignal.scalper` (the plan's `.../scalper/X.java` shorthand is
  fine — the `com.arthayantra.strategy.scalper` FQN that appears in §2.1's prose is cosmetic, not a path
  the executor uses).
- ✓ `MarketOiClient.macro(String, LocalDate)` L351-398, VIX-gap return L396-397, stale comment L394-395;
  `advanceDecline` L620-623, `latestFiiLongPct` L626-641 (keys `fiiLong`/`fiiShort`), `get(...)` helper
  L647-663 (`catch (Exception)` → fallback, L659 — so the 422/404 degrade arguments hold).
- ✓ `context(...)` L80-93 — the 7-arg signature + the `macro(underlying, eodDate)` call at L92.
- ✓ `ConnectTheDotsScorer.score` L63-118; weights W_VWAP=2.5/W_OI=1.5/W_IV=0.8/W=1.0 (L32-35);
  **Σweights = 19.6 recomputed = 2.5 + 1.5 + 0.8 + 0.8 + 14×1.0** (18 dots). `volume` L79, `breadth` L91,
  `vix` L92.
- ✓ `ScalperGates.vix` L136-143 (unknown PASSES, L137-138); `breadth` L128-133; `volume` L64-68;
  `callPutDeltaFilter` L151-161 (the #5 fail-open template).
- ✓ `ScalperConfig` — **16 fields** (re-counted, ending `requireStraddle`), `from(...)` L101-157,
  canonical ctor L154-156, `oi-cross-filter` tag L153.
- ✓ `ScalperConfluenceGate` LIVE-only javadoc L29-33; `client.context(...)` call L191-192 (the `~L192`
  insert is right); structural-stop `future.candle(index)` precedent L293-294; `evaluate(...)` has
  `future`/`index` in scope (L100-107) so `future.candle(index).open()` is reachable.
- ✓ `EngineCandle.open()` L13.
- ✓ Market-data feeds: `/vix` THROWS `ApiException(422, DATA_GAP)` on no-quote (L63-65), `change =
  ltp − prevClose` (L68); `EquityController.indexContribution` L54-59 (param **`name`**, **no `date`**,
  path PRE-EXISTS → no contract drift); `FiiDiiController` `/participant-oi` L49-55, `/long-short` L57-78
  (FII index-FUTURES only, confirming §8.13); `NseEodReader.ParticipantOiRow` L30-46 (option-leg columns
  `optionIndexCallLong/PutLong/CallShort/PutShort` present); `GlobalQuoteSource` flag-gated
  (`global-quotes-enabled`); `ConnectingDotsService.volumeFactor` L240-246 (`priceDelta>0?BULL:BEAR`).
- ✓ Tests: **24** `.context(` references in `ScalperConfluenceGateTest` (23 `client.context(` stubs + the
  `verify(client, never()).context(...)` at L754); **8** `new ScalperConfig(` literals
  L44/49/56/62/68/74/81/87; `ScalperStrategyLoadTest` iterates a **36-entry** `UNDERLYING` map (12×3,
  re-counted) with the `requireCallPutDeltaFilter` OFF-pattern at L151-153.
- ✓ GAP-DISPOSITION counts: `directional-vix-gate` 11 @ L118 ("explicitly OUT of FU2"), `fii-dii-bias`
  6 @ L126, `volume-pump-attribution`/`constituent-contribution`/`global-cues-feed` single-gap
  `[P]` @ L157/L165/L173. **20-gap total (11+1+6+1+1) confirmed.**
- ✓ FU2 plan corroboration: VIX+Dow "explicitly out of scope" (FU2 L51-54); FU2's own Dow note (L648-654)
  proposed adding a *scored dot* "accepting the live-confluence shift" — **this plan's §3.0 hard-gate
  early-return mechanism is a deliberate, STRICTLY BETTER choice** (scorer denominator untouched →
  byte-identical when unarmed). The load-bearing parity decision is correct.

### Pass-1 corrections re-checked — all correct, no new error introduced
Every pass-1 correction (16-not-15 fields; `macro()` body lines; `latestFiiLongPct`/`advanceDecline`
lines; `/vix` 422-not-null; `/equity/index-contribution` already-exists + `name`-not-`index` + no-date +
no-contract-drift; the 24 `context()` test sites; the volume-pump cite re-pointed to GAP-DISPOSITION; the
§1 per-doc row re-pointers) was re-verified against the tree and is accurate. Pass 1 introduced no
regressions.

### New issues pass 1 + the author BOTH missed — corrected in place
1. **Straddle short-circuits before the macro gates (placement constraint).** The `requireStraddle` path
   returns at `ScalperConfluenceGate` L132-147 — BEFORE `ctx`/`side` exist and before `client.context(...)`
   is even called (the test's `verify(client, never()).context(...)` proves it). So arming ANY macro tag
   on a `scalp-straddle-*` variant is a SILENT NO-OP. Harmless for parity, but a real arming trap. Added a
   note to §3.0 + Open Point §8.14 (PR-X arms macro gates on DIRECTIONAL families only).
2. **The constituent reader degrades on HTTP 404, not a null body.**
   `EquityIndexContributionService.contribution(String)` THROWS `NotFoundException(404)` when
   `StaticIndexWeights.weights(index)` is empty (L63-66) — it never returns an empty `IndexContribution`.
   The `MarketOiClient.get(...)` catch degrades that 404 to the fallback (same shape as `/vix`'s 422), so
   `constituentBias(...)` MUST route through `get(...)` (or catch the HTTP error) to fail-open. §3.4's
   "fail-open on null" wording silently assumed a null body. Corrected §3.4 to state the 404-throw path.
3. **`StaticIndexWeights` seed confirmed: `NIFTY 50` / `NIFTY BANK` / `NIFTY 200` only — NO `SENSEX`**
   (`reference/index-weights.json`, verified). This upgrades §8.10 from "confirm before arming" to a hard
   fact: a NIFTY `constituent-gate` fires; a SENSEX one is a no-op (404 → fail-open) until BSE weights are
   seeded. Corrected §3.4 with the concrete seed keys.

### Parity re-assessment (end-to-end) — PASS
Walked the full `evaluate(...)` order: time-window → chain fetch → **straddle return (L147)** → `side`
(L149-152) → volume/RSI rails (L157-163) → two-candle/gap (L164-179) → `ctx = context(...)` (L191-192) →
the existing hard gates (`callPutDeltaFilter` L196, `trendChange` L204, `openHighLow` L218, `heroZero`
L236) → **`ConnectTheDotsScorer.score(...)` (L250-252)**. Every existing hard gate early-returns BEFORE
`score()`; the five new macro early-returns slot into the same band (after `ctx`, before `score()`), so
the **scorer list/denominator is never touched** and the aggregate is byte-identical for the 36 shipped
(un-armed) configs. The one non-tagged trap (populating `vixRising` would flip the existing `vix` soft
dot) is correctly carved out by gating the producer read on `vixEnabled`, with the §5.3 producer-tripwire
(`macro(...,false) ⇒ vixRising==null`) as the guard. No `[P]` change runs on the deterministic-replay path
(`ScalperConfluenceGate` is LIVE-only), and no golden/parity FEATURES YAML is a scalper, so
`GoldenDeterminismTest` + `BacktestParityTest` stay byte-identical with no new golden vector. Classification
table (§4) maps `[S]`/`[P]` correctly. **No parity hole found.**

### Dependency / automatability re-check — correct
VIX spine first → market-data analytic (PR-2) before the consuming gate (PR-3) → volume-pump self-contained
(PR-4); PR-3 hard-depends on PR-2; PR-1/PR-4 independent. No SPAN / equity-universe dependency (index-level
macro, no sell legs). The "20 AUTOMATE gaps" are genuinely automatable EXCEPT the Dollar/Asian/Crude leg of
`global-cues-feed`, which the plan already (correctly) marks `[S]` KEEP_MANUAL (no live feed) — not
over-claimed. The lone genuinely-new analytic (the FII L/U/B/C classifier) is correctly sized M; everything
else is wiring existing feeds.

### Readiness verdict
**SOUND-WITH-OPEN-POINTS — ready to implement.** The four pass-2 corrections (straddle no-op guardrail;
constituent 404-degrade; the concrete SENSEX-absent seed fact; Open Point §8.14) are documentation
precision, not architectural changes. No blocker remains. The executor must honour: (a) the same-PR
24-site `context()` test fan-out; (b) route the constituent + VIX reads through the `get(...)` helper so
the 404/422 fail-open holds; (c) NEVER arm a macro tag on a straddle variant; (d) the §5.3 producer
tripwire proving `macro(...,false) ⇒ vixRising==null`.
