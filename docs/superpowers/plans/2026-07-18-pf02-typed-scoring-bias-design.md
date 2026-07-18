# PF-02 — Typed (side-aware) scoring bias — DESIGN

> **STATUS: DESIGN — AWAITING OWNER APPROVAL. NO BUILD.**
> Design-only artifact (audit 2026-07-18, PF-02, HOLD-tier, Architect-validated). Nothing here is
> implemented. The owner approves a direction before any code lands. Ledger row: `AUD-PF02`
> (`docs/superpowers/plans/2026-07-02-remaining-items.md:501`). Owner-decision item #3
> (`docs/audits/2026-07-18-comprehensive-audit.md:226`).
>
> **Evidence labels**: `[sourced file:line]` = read in the repo this pass · `[computed]` = derived from
> sourced facts · `[reviewer-verified]` = established by the adversarial-review pass with evidence I did
> not independently re-read · `[recalled]` = memory/CLAUDE.md · `[assumed]`.
> This is **scoring methodology only** — it recommends no trade and sets no trading numbers.

---

## Revision 2 — post-adversarial-review (2026-07-18)

Both adversarial reviews returned SURVIVES-WITH-FIXES. Changes applied in place:

1. **Premise corrected (§1):** the "9 mixed/neutral scalpers" are the **9 published NIFTY PE-mirror
   strategies** (`direction: long`, `option_types:[PE]`, gate `close < vwap`) crippled by the bullish
   composite — NOT `direction: both` configs. Exactly **6** `both` configs exist, all carry paths
   (hero-zero + btst/stbt). Urgency recalibrated: **active but narrow**.
2. **Load-bearing omission fixed (§2/§3):** the **hard gate binds BEFORE the composite**
   (`EntryEvaluator.java:31`) — flipping normalizers alone leaves a bidirectional strategy silent on
   bearish bars (gate-blocked upstream). Companion requirement added: Mode-B strategies need a
   side-neutral or side-conditioned gate.
3. **Mode disambiguation (§4):** the build serves **Mode A** (retrofit the 9 published PE mirrors —
   double-fire-safe, gates already bearish); Mode B (true bidirectional) is the deferred phase 2.
4. **Gating mechanism changed (§5):** the config **tag is dropped** — `StrategyDefinition` carries no
   tags, so a tag-gated composite would diverge live-vs-replay. The gate is a **definition-level**
   `scoring.bias` field; `Direction.BOTH` maps onto it in Mode B.
5. **Carry guard (§3/§5):** typed scoring is structurally rejected for carry/`forcedSide` strategies.
6. **Staging rule (§5):** retire a family's CE-base + PE-mirror before arming a Mode-B typed
   replacement (per-book risk gate + per-strategy `max_positions_per_underlying` do not deduplicate a
   same-bar double-fire).
7. **Precision fixes:** shipped-CE-config bearish bars return `CHART_GATE_FAILED` (not
   `COMPOSITE_BELOW_THRESHOLD`); VWAP is a **built-in operand**, not a declared indicator; hero-zero is
   on the VWAP-side path (only btst/stbt pins `forcedSide`); side-helper return type specified
   (neutral enum, no `black76-math` dep added); cold-start VWAP-anchor operational note added;
   checksum no-op proof added as a build-time obligation.

---

## 1. Problem

The live scalper entry has **two scoring stages in series**:

1. **Chart stage (parity surface, golden-serialized).** `EntryEvaluator.evaluate` fires only if
   `gate.passed() && composite >= threshold` `[sourced EntryEvaluator.java:29-31]` — **the hard gate
   binds first**; the composite is consulted only on gate-passing bars. The composite is a single
   `[0,1]` value from indicators normalized in ONE direction: the shipped scalper composite is `rsi14`
   rsi_momentum(w1) + `supertrend` direction(w1) at threshold 0.2 `[sourced SignalEngine.java:223-225]`,
   and the normalizers are **bullish presets** — `RSI_MOMENTUM = linear(50→0, 70→1)`, `DIRECTION =
   signum>0 ? 1 : 0` `[sourced Normalizers.java:24-25]`.
2. **Confluence stage (live-only, firewalled from replay).** Only bars the chart stage FIRED reach
   `ScalperConfluenceGate`, which resolves the CE/PE side (`close ≥ vwap → CE else PE`)
   `[sourced ScalperConfluenceGate.java:462-467]` and scores `ConnectTheDotsScorer` **for that side** —
   already fully bidirectional `[sourced ConnectTheDotsScorer.java:79-225; validity :938]`.

**The affected population (corrected).** The "9 mixed/neutral scalpers" of the audit finding are the
**9 published NIFTY PE-mirror strategies** — `direction: long`, `option_types:[PE]`, chart gate
`close < vwap` `[reviewer-verified config census]`; seeded as STEP-additive YAMLs by #381/#382
`[sourced docs/superpowers/plans/archive/2026-06-30-pe-mirror-bidirectional-scalpers.md:1-2]`. Their
**gates are already bearish** (they pass exactly on bearish bars), but their **composite is the shared
bullish preset** — on the very bars their gate admits, RSI < 58 / SuperTrend DOWN scores ~0 → below
threshold → `COMPOSITE_BELOW_THRESHOLD` — so "they can essentially only fire when their own thesis is
wrong" `[sourced docs/superpowers/plans/2026-07-02-remaining-items.md:312-313]` `[computed from
Normalizers.java:24-25 + EntryEvaluator.java:29-31]`. On a shipped **CE** config the same bearish bar
never reaches the composite at all — its bullish gate fails first and the outcome is
`CHART_GATE_FAILED` `[sourced classifyEntryOutcome SignalEngine.java:1147-1157]`.

There are exactly **6 `direction: both` configs, all carry paths** (hero-zero + btst/stbt)
`[reviewer-verified config census]` — out of scope here (§3 guard). **Urgency: active but narrow** —
9 live strategies are mis-scored on every bar their gate admits; no `both` intraday population exists.

**Why the obvious fix is not implementation-ready.** The composite is computed at
`SignalEngine.java:1126-1127` `[sourced]`; the CE/PE side resolves downstream at
`ScalperConfluenceGate.java:462` `[sourced]` inside a **live-only stage the deterministic golden replay
never runs** `[sourced TickwiseGoldenRunner.java:439-444, 206-224 — entry direction is the fixed
`definition.direction()`, no confluence call]`. There is no `resolved_side` at chart-eval time, and the
thing that resolves it is un-replayable — hence this dataflow design.

### Distinction from the owner-parked task (must not be conflated)
- **task_71a017e6 / task_79092520 (PARKED, owner)** = the **PE-mirror FEATURE + its bearish numbers**.
  The design choice was resolved as STEP-additive PE YAMLs, not a signed composite (#381/#382)
  `[sourced archive doc :1-2, 34-46]`; the operative doc under-specifies the bearish thresholds
  `[sourced archive doc :54-63]`.
- **PF-02 (this doc)** = the upstream **scoring-dataflow prerequisite**: how a composite can express a
  bearish conviction deterministically on BOTH the live and replay paths without breaking parity. The
  parked task cannot be finished faithfully without it. PF-02 sets **no bearish trading numbers** —
  those stay the owner's.

---

## 2. Current dataflow trace (all `[sourced]` this pass unless labeled)

| # | What | Where |
|---|------|-------|
| 1 | Bar close, no active entry → `decideEntry` | `SignalEngine.java:1109-1110` |
| 2 | Chart evaluation (**the PF-02 point**) | `SignalEngine.java:1126-1127` → `EntryEvaluator.evaluate` |
| 3 | **Hard gate FIRST**, composite only decides gate-passing bars | `EntryEvaluator.java:26-31` |
| 4 | Per-participant `[0,1]` normalize; required-null ⇒ unscoreable | `CompositeScorer.java:45-56`; `Normalizers.apply :17-27` |
| 5 | Bullish presets (RSI 50→0/70→1; ST up→1) | `Normalizers.java:24-25` |
| 6 | Chart "no" classified (gate-fail vs below-threshold) | `SignalEngine.java:1147-1157` |
| 7 | If FIRED + scalper → confluence gate | `SignalEngine.java:1132-1134` → `scalperEntry :1206-1231` |
| 8 | **Side resolved (CE/PE) from close vs VWAP** — live-only; btst/stbt pins `forcedSide` instead | `ScalperConfluenceGate.java:460-467; :311-345` |
| 9 | Bidirectional confluence scored for that side; hero-zero consumes the same VWAP-derived side | `ScalperConfluenceGate.java:932-938; :950-954`; `ConnectTheDotsScorer.java:79-225` |
| 10 | Emit: persist `side` column + frozen `score_breakdown` JSONB + `scalper_detail` side-channel | `SignalEngine.java:1611-1623`; channel `canonicalBreakdown :1637-1647` |

**Composite consumers.** (a) the entry decision `[EntryEvaluator.java:31]`; (b) persisted frozen
`ScoreBreakdown` → `score_breakdown` JSONB + the signals channel (byte-identical to persisted)
`[SignalEngine.java:1613-1614, 1637-1647]`; (c) the **golden parity surface** — `GoldenSignalsJson.write`
serializes `composite` (scale-4) + per-indicator `score/weight/activated`
`[sourced GoldenSignalsJson.java:78-87]`; the golden `SignalEvent` embeds `evaluation.breakdown()`
`[sourced TickwiseGoldenRunner.java:441-444]`. The CE/PE side is never in the golden — only the fixed
strategy `direction` `[sourced GoldenSignalsJson.java:77]`.

**Replay path.** `TickwiseGoldenRunner` calls the **same** `EntryEvaluator.evaluate`
`[sourced :206-207, :279-280, :327-328]` and never reaches `ScalperConfluenceGate` (live-only; the
picked option + confluence are persisted at entry and read back on replay)
`[sourced ScalperConfluenceGate.java:30-34]`. Any change to the composite value/shape for an unchanged
config moves goldens.

**Key enabling facts.** (i) The side rule's inputs (`chart.close` vs `chart.vwap`) are chart
quantities: VWAP is a **built-in operand** (`BarValues.isBuiltin`) `[reviewer-verified]`, a pure
session-anchored function built unconditionally by `IndicatorBank.build` on BOTH the live and replay
paths `[reviewer-verified]` — so a deterministic, chart-only side computation is possible at line 1126
`[computed]`. (ii) `StrategyDefinition` carries **no tags** `[sourced StrategyDefinition.java:13-24]`
and has an existing `Direction.BOTH` `[sourced :27-31]` that today collapses to LONG in the runner
`[sourced TickwiseGoldenRunner.java:439-440, 475-477]` — the definition, not the config tags, is what
the replay sees, which constrains how the new behaviour may be gated (§5).

---

## 3. Candidate designs

All candidates are **definition-gated default-OFF**: an unchanged definition takes the current
bullish-only path verbatim (goldens byte-identical); only an opted-in definition gets the new
behaviour, with new golden fixtures. Modulith honoured throughout: the new logic lives in
`strategy-engine` (shared by live + replay); `signals` never imports `notifier`/`paper`
`[recalled CLAUDE.md]`.

**Companion requirement (applies to any candidate serving Mode B — see §4):** because the hard gate
binds before the composite `[sourced EntryEvaluator.java:31]` and every intraday CE scalper gates on
bullish conditions (`close > vwap` / `vwma20 > vwap` / `supertrend > 0` `[reviewer-verified config
census]`), flipping the composite alone leaves a bidirectional strategy **silent on bearish bars —
gate-blocked upstream**. A true bidirectional strategy therefore needs a **side-neutral or
side-conditioned gate** alongside the side-aware composite. Mode A does NOT need this: the PE mirrors'
gates are already bearish.

**Carry guard (all candidates):** typed scoring must be **structurally rejected/ignored** for
carry/`forcedSide` strategies. The btst/stbt carry pins its side from day-close location
(`forcedSide`, `ScalperConfluenceGate.java:311-345, 460-467` `[sourced]`; `btstCarrySide`
`SignalEngine.java:1481-1497` `[sourced]`) — a VWAP-based pre-estimate would disagree with the pinned
side. The 6 existing `direction: both` configs are exactly these carry paths `[reviewer-verified]` and
the most tempting mis-tag target; the compiler/loader must refuse the combination (hero-zero, which
consumes the ordinary VWAP-derived side `[sourced ScalperConfluenceGate.java:950-954]`, is not a
`forcedSide` path but is excluded from scope with the rest of the carry families).

### Candidate A — Two-sided evaluation (score bull AND bear each bar; resolve at side time)
- Compute bullish + mirrored bearish composites every bar; chart fires if either ≥ threshold; live
  confluence resolves the traded side as today.
- **Parity problem:** the golden records ONE `composite` + ONE `direction`, and the replay has no
  confluence gate — it needs a chart-only tiebreak (`max(bull,bear)`), a **second, independent side
  source** that can disagree with the live gate's VWAP side on boundary bars.
- Exact: both composites. Approximate: golden side vs live traded side. **Effort M–L.**

### Candidate B — Deferred scoring (move the composite AFTER side resolution)
- **REJECT as stated:** the side today resolves inside the live-only confluence stage (needs the
  chain/OI context to run `[sourced ScalperConfluenceGate.java:382-467]`); deferring the composite
  behind it makes the parity surface depend on a stage the replay never runs → un-reproducible →
  breaks the parity firewall. Viable only if side resolution is demoted to a chart-only computation —
  which collapses B into C. **Effort M, not viable in literal form.**

### Candidate C — Chart-only deterministic side + side-conditioned scoring  ★ (two phases)
- **C-static (Mode A, the build):** a definition-level `scoring.bias` (default `bullish` = today's
  code verbatim; `bearish` = the mirror reading of the same participants — RSI_MOMENTUM
  `linear(50→0, 30→1)`, DIRECTION `signum<0 ? 1 : 0` as the faithful-mirror defaults, owner ratifies).
  A statically-`bearish` definition needs **no side pre-estimator at all** — the bias is a pure
  function of the definition, trivially identical live vs replay. This retrofits the 9 PE mirrors:
  their gates already admit exactly the bearish bars; only the composite reading changes.
- **C-dynamic (Mode B, deferred phase 2):** `scoring.bias: side_conditioned` — a pure helper in
  `strategy-engine`, `sidePreEstimate(bank, index)`, implementing the SAME `close vs vwap` rule the
  confluence gate uses `[sourced ScalperConfluenceGate.java:462-467]`, relocated so live and replay
  call it identically; the composite scores the reading for the pre-estimated side, and the live gate
  re-derives the side from the same rule + inputs ⇒ scored side ≡ traded side by construction. Wired
  to `Direction.BOTH` (see §5) plus the side-neutral-gate companion requirement above.
- **Determinism/parity:** both phases are pure chart-only functions reproduced by the replay without
  the confluence gate. **Effort: C-static S–M; C-dynamic M.**

> **Do NOT auto-pick the STEP catch-all "neutralise the composite" hack** — piloted (#327) and reverted
> (#328): the owner flagged it "loosens the CE filter" `[sourced archive doc :42-46]`. Any candidate
> must preserve the CE side's strictness.

> **Relation to archived "Option A — signed composite" (`[-1,+1]`)** `[sourced archive doc :34-41]`:
> the faithful long-term model but a frozen-schema change (scoring block + normalize enum) with broad
> golden re-capture. C keeps `[0,1]` and gates the mirror behind a definition field → strictly smaller
> blast radius; a later signed-composite refactor can subsume it.

---

## 4. Recommendation — **Candidate C, built as Mode A first (C-static)**

**Mode disambiguation (explicit):**

- **Mode A — retrofit the 9 published PE mirrors** (`scoring.bias: bearish` on their next published
  version). This is what the build serves. It fixes the ACTIVE defect population; it is
  **double-fire-safe** — a family's CE base (`close > vwap`) and PE mirror (`close < vwap`) have
  **mutually exclusive gates**, so both can stay published; and it needs no gate redesign (the mirrors'
  gates are already bearish). It IS a behaviour change on live strategies (they start firing on their
  intended side), so arming is per-strategy, behind owner-ratified mirror thresholds + forward-paper.
- **Mode B — new side-neutral-gate bidirectional strategies** (`Direction.BOTH` +
  `scoring.bias: side_conditioned` + side-neutral gates per the §3 companion requirement). Deferred
  phase 2; carries the §5 staging rule (retire the family's CE-base + PE-mirror before arming the
  typed replacement, because the per-book risk gate `[sourced SignalEngine.java:1505-1511]` and the
  per-strategy `max_positions_per_underlying` `[reviewer-verified]` do not deduplicate a same-bar
  double-fire across a typed strategy and its still-published single-sided siblings).

**Deciding criteria (unchanged from Rev 1, now applied per phase):**

1. **Parity-firewall preservation** — scoring stays a pure function the golden replay reproduces
   without the live-only confluence stage: C yes (static bias trivially; dynamic via the shared
   chart-only helper); B no; A yes for composites but needs a golden-only side tiebreak.
2. **Live↔replay side consistency** — the scored side provably equals the traded side: C yes (static:
   the definition IS the side; dynamic: one rule, one input set, re-derived identically); A no (two
   independent sources).
3. **Blast radius / minimality** — reuse the bidirectional confluence + existing side derivation, no
   frozen-schema change, and (new this revision) **fix the active population with the smallest
   mechanism**: C-static needs no pre-estimator, no gate work, no new golden tiebreak.

---

## 5. Gating, parity, persistence & migration implications

- **The gate is DEFINITION-LEVEL, not a config tag.** `StrategyDefinition` carries no tags
  `[sourced StrategyDefinition.java:13-24]` and `TickwiseGoldenRunner` sees only the definition — a
  tag-gated composite would diverge live-vs-replay. The flag is a new optional
  `scoring.bias ∈ {bullish (default), bearish, side_conditioned}` compiled by `StrategyCompiler` into
  `StrategyDefinition.Scoring` — visible to both halves of the parity pair. **The tag option is
  dropped.** `Direction.BOTH` `[sourced StrategyDefinition.java:27-31]` is the Mode-B carrier: in
  phase 2 it maps to `side_conditioned` (replacing today's collapse-to-LONG
  `[sourced TickwiseGoldenRunner.java:439-440, 475-477]`); it is NOT used for Mode A because the PE
  mirrors are `direction: long` (long-premium PE buyers) and flipping them to BOTH would entangle
  position/exit-direction semantics beyond scoring.
- **Side-helper type (Mode B).** `strategy-engine` does not depend on `black76-math`
  `[sourced libs/strategy-engine/pom.xml — deps: ta4j-core, market-calendar, strategy-schema,
  slf4j-api]`, so the helper does NOT return `OptionType`. Decision: a neutral two-value enum in the
  lib (`ScoringSide { BULLISH, BEARISH }`) — deliberately distinct from `ExitEvaluator.Direction`
  (position semantics ≠ scoring side); the service maps `ScoringSide → OptionType` at the seam. No new
  lib dependency.
- **Carry guard (structural).** `StrategyCompiler` rejects `scoring.bias != bullish` on carry-style
  definitions (btst session style / the forcedSide families), and the scalper loader refuses to arm it
  — the 6 `both` carry configs must be un-taggable by construction (§3).
- **Goldens.** Default `bullish` branch is verbatim-current ⇒ every committed fixture replays
  byte-identically (`GoldenDeterminismTest` + `BacktestParityTest` `[recalled CLAUDE.md]`). Mode A
  rides a NEW published version of each mirror (config edit) — existing fixtures pin old configs and
  do not move; the revised mirror config gets a fresh fixture.
- **`ScoreBreakdown` stays FROZEN** `[sourced ScoreBreakdown.java:8-14; ScoreBreakdownJson.java:17-24]`.
  No new serialized field: the bias expresses itself through the existing `score`/`composite` VALUES.
  Any diagnostic side-label rides a non-serialized side-channel (the V009 `scalper_detail` already
  carries the resolved side `[sourced SignalEngine.java:1618-1620]`).
- **Migration.** No DDL required (a config-JSON + compiler change). If any persisted column is ever
  wanted, next free strategy migration is **V042** `[sourced deploy/flyway/strategy/ — highest is
  V041__latency_stamps.sql]`, new-suffix only `[recalled CLAUDE.md]`.
- **Checksum no-op obligation (build-time proof).** Adding the optional `scoring.bias` schema field
  must leave every EXISTING strategy's compiled definition and config checksum **byte-identical**
  (absent field ⇒ canonical JSON unchanged) — prove it, don't assume it: diff the compiled form +
  checksum of all published configs before/after the schema change.
- **Operational note — cold-start VWAP anchor.** A live mid-session boot anchors the session VWAP at
  the first loaded bar (a pre-existing class of divergence), which becomes a **side-stamp risk** once
  `side_conditioned` scoring arms (Mode B): a mis-anchored VWAP could pre-estimate the wrong side on
  the boot bars. Mitigation to settle at Mode-B build time: warm-fetch depth assertion or a
  session-anchored VWAP validity check before the pre-estimate is trusted. Not a Mode-A concern
  (static bias). `[reviewer-verified class; mitigation assumed]`

---

## 6. Test plan

- **Parity (the gate):** `GoldenDeterminismTest` + `BacktestParityTest` zero-diff on every existing
  fixture — proves the default `bullish` branch is verbatim-current.
- **Checksum no-op proof:** compiled-definition + checksum diff over all published configs
  before/after the schema addition — must be empty (§5 obligation).
- **Mirror-normalize units (Mode A):** for a synthetic bearish bar (ST DOWN, RSI 45), a
  `scoring.bias: bearish` definition scores ≥ threshold while the identical `bullish` definition
  scores ~0 byte-identical to today; symmetric bullish-bar case (bearish bias scores ~0).
- **Gate-order regression:** on a shipped CE config a bearish bar still returns `CHART_GATE_FAILED`
  (never reaches the composite) `[per SignalEngine.java:1147-1157]`; on a retrofitted PE mirror the
  same bar passes its bearish gate AND its bearish composite.
- **Carry-guard unit:** `StrategyCompiler` rejects `scoring.bias != bullish` on a btst/carry
  definition; loader refuses to arm it.
- **New golden fixture(s):** the retrofitted PE-mirror config, authored fresh, covering a session
  with a PE-firing bearish leg; JSON `eol=lf` `[recalled CLAUDE.md]`.
- **Live↔replay consistency (IT):** one recorded bearish session through the live engine (mock stack)
  and `TickwiseGoldenRunner`; assert emitted `composite` + `direction` match. Existing harness
  (`*Test`/`*IntegrationTest` naming, singleton Testcontainers `[recalled CLAUDE.md]`).
- **Exit-side regression:** a PE entry exits on the held side — `scalperPositionDirection` maps
  PE→SHORT `[sourced SignalEngine.java:2136-2179]` (#334 `[sourced archive doc :20-22]`); assert a
  typed-scoring PE entry reaches those sites unchanged.
- **Mode-B additions (phase 2 only):** `sidePreEstimate` table-driven units (incl. the `>= ⇒ CE` tie
  mirroring `ScalperConfluenceGate.java:465` and null degrades) + a cross-check test failing if helper
  and gate ever drift + a side-neutral-gate fixture with BOTH a CE- and PE-firing bar + a cold-start
  VWAP-anchor scenario.

---

## 7. Build-brief-ready task decomposition (post-approval)

**Phase 1 — Mode A (the build):**
1. **`scoring.bias` schema + compiler** (`strategy-schema`/`strategy-engine`): optional field, default
   `bullish`; compiled into `StrategyDefinition.Scoring`; carry-guard rejection; **checksum no-op
   proof**. *Verify: parity zero-diff + checksum diff empty.* **S.**
2. **Mirror-normalize semantics in `CompositeScorer`/`Normalizers`** selected by the definition bias
   (default branch verbatim-current). *Verify: bull/bear units + parity zero-diff.* **S–M.**
3. **Retrofit configs:** new published versions of the 9 PE mirrors carrying `scoring.bias: bearish`
   (+ fresh golden fixture for the family). *Owner ratifies the mirror thresholds (parked
   task_79092520 numbers) before publish; arm per-strategy behind forward-paper.* **S + owner-gated.**
4. **Adversarial-review pass** (HOLD-tier parity surface) before PR `[recalled CLAUDE.md]`. **S.**

**Phase 2 — Mode B (deferred, separate approval):**
5. `sidePreEstimate` shared helper (`ScoringSide` enum) + gate refactor to call it + cross-check test. **S.**
6. `side_conditioned` bias wired to `Direction.BOTH` (runner per-bar direction replaces
   collapse-to-LONG) + the side-neutral-gate grammar per §3's companion requirement. **M.**
7. **Staging rule enforcement:** retire the family's CE-base + PE-mirror before arming the typed
   replacement (double-fire hazard, §4). **owner-gated.**
8. Cold-start VWAP-anchor mitigation (§5 note). **S.**

Canonical order: testing gate → cross-vendor review → Architect audit → owner sign-off on numbers
before arming.

---

## 8. Open doubts (mandatory)

1. **Rev-1 doubt #1 RESOLVED by the review's config census** (9 = the published PE mirrors; 6 `both` =
   carry only) — but the census itself is `[reviewer-verified]`, not independently re-read by me this
   pass; step 3's builder should re-enumerate the 9 slugs + their gates from the registry before
   editing configs.
2. **Bearish thresholds are under-specified** in the operative doc `[sourced archive doc :54-63]`; the
   faithful-mirror normalize defaults proposed here (RSI 50→30, DIRECTION down→1) are `[assumed]`
   until the owner ratifies. Do not publish the retrofits before that.
3. **Mode-A firing-profile change:** 9 live strategies start firing on bearish legs — intended, but it
   raises entry cadence on down legs and interacts with the per-book risk caps (PF-03) and the
   5-account discipline `[sourced SignalEngine.java:1213-1218, 1505-1511]`. Forward-paper staging is
   the mitigation; the owner accepts the cadence.
4. **`Direction.BOTH` semantics in Mode B** touch the runner's position/exit direction
   (`TickwiseGoldenRunner.java:439-440, 461-477`) and the golden `direction` field becomes per-bar — a
   golden-shape decision deferred to the phase-2 design pass. `[computed]`
5. **Checksum no-op is asserted, not yet proven** — the §5 obligation exists precisely because a
   schema addition that perturbs canonical JSON would re-checksum every published version; if the
   proof fails, `scoring.bias` moves out of the checksummed config subtree (fallback design). `[assumed]`
6. **`BarValues.isBuiltin` / VWAP-unconditional-build** claims are `[reviewer-verified]`; phase-2's
   builder should confirm them at `IndicatorBank.build` before relying on the pre-estimator's
   always-available VWAP.
