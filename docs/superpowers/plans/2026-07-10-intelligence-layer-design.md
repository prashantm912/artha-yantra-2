# Intelligence, Automation & Decision-Support Layer — Design (Program-2 Prompt 2)

**Date:** 2026-07-10 · **Fixed input:** `docs/audits/2026-07-10-app-platform-audit.md`
(the app-platform audit, main @ `db2d8a88`; "P1" below; its §11 items cited as P1-§11.n,
its roadmap phases as P1-Phase-n) · **Companion boundaries:**
`docs/audits/2026-07-10-research-fidelity-audit.md` (fidelity audit) and
`docs/superpowers/plans/2026-07-10-strategy-evolution-engine-design.md` (evolution engine).

**Scope:** design only — the intelligence, automation, and decision-support layer over the
non-backtest/livetest surfaces (signals, rejections, paper, orders, journal, strategies,
graduation, sell decisions, charts, options/futures/equity/FII-DII analytics, feature
pages, data-ops trust). No architecture redesign; no invented data; nothing self-arms.

---

## 0. Reading guide + hard boundaries

Three documents now govern three loops. This design is the **fast loop**:

| Loop | Owner doc | Question it answers | Cadence |
|---|---|---|---|
| Research loop | fidelity audit + evolution engine | "which strategy VERSION is good?" | days–weeks, campaign-based |
| **Decision loop (THIS)** | this design | "of what is in front of me NOW, what deserves attention, in what order, and why?" | seconds–daily |
| Platform loop | P1 audit | "do the pages, contracts, events, and trust rails exist?" | build phases |

Hard boundaries honored throughout:
- **No re-scoring of strategies.** Strategy composite scores are the frozen ScoreBreakdown
  (P1-§11.1); strategy-version ranking is the evolution engine's RobustScore. This layer
  ranks *signals, insights, and actions for triage* — it layers context/trust/risk ON TOP
  of the strategy's own score, never replaces it.
- **No new trade automation.** The only automated order flow remains the existing
  per-book `auto_paper_trade` (owner-armed, P1-§2.2). Everything this layer adds is
  display, propose-with-one-click-approve, or draft.
- **No invented data.** Every insight input is an existing table/endpoint from P1-§11 or
  a P1-roadmap ADD explicitly listed in §13. Where history is shallow (forward-only
  datasets, P1-§3.4), the design says so and degrades rather than extrapolates.
- **Deterministic, not ML.** Rules + weighted arithmetic with config-pinned weights and a
  stamped engine version. Rationale: single-owner platform, no labeled training corpus,
  explainability is a hard requirement, and the golden-vector culture (byte-identical
  replays) extends naturally to rule engines but not to models. LLM-generated insights
  were considered and rejected: non-deterministic, unexplainable at the evidence-pointer
  level, new infra. Revisit only after ≥6 months of `insight_feedback` data exists.

---

## 1. Intelligence and automation design

### 1.1 Three planes, existing services (no new service)

1. **Context plane — market-data-service.** Owns "what changed in the market." New typed
   *digest endpoints* (§6) computed from the same tables/folds the analytics pages already
   use (P1-§11.12–15). One new persisted table (`market_context_days`, §6.6) for the daily
   context snapshot; everything else stays compute-on-read like the rest of the OI suite
   (P1-§3.3 verdicts respected).
2. **Insight plane — strategy-signal-service, new Modulith module `insights`.** Owns
   insight generation, the priority model, the action queue, suppression, and feedback.
   Justification: it already owns signals/rejections/paper/journal/graduation AND already
   calls market-data over REST for gate context (`MarketOiClient` precedent, P1-§2.5).
   Modulith rule respected: `insights` may import `signals`/`paper` events the same way
   `notifier` does (listen, never be imported back).
3. **Presentation plane — frontend-react.** Focus queue, insight feed, explain drawers,
   per-page "What changed" strips, trust chips (§8). Reuses P1's shell decisions
   (DataTable, QueryState, freshness envelope, user_prefs, cmdk).

### 1.2 Automation tiers (what is automated / approved / manual)

| Tier | Meaning | Contents |
|---|---|---|
| **AUTO** (no approval; display/derive only) | computed and shown; reversible by ignoring | insight generation, priority scores, digests, badges, dedupe/suppression, journal-entry DRAFTS with links prefilled, watch-item creation, freshness/trust chips, EOD summary |
| **PROPOSE** (one-click approve; every action = an existing audited endpoint) | nothing executes without a click | take-signal prefill (calls `POST /signals/{id}/taken`), ticket prefill with book+qty (`POST /paper/orders`), watchlist add, journal draft accept (`POST/PUT /journal`), dismiss-stale-signals batch (`POST /signals/{id}/dismiss` per item), acknowledge sell-decision |
| **MANUAL** (this layer never proposes a one-click for these) | owner navigates and acts deliberately | risk-setting changes, kill switches, strategy publish/arm/enable, paper reset, any broker-order action (when OpenAlgo arms), backfills |

Guard rails: PROPOSE buttons are disabled when the underlying insight's `data_trust` is
DEGRADED for a load-bearing input (§7.4); every executed proposal writes the target
endpoint's normal audit trail plus an `insight_actions` row (§2.4) so "the app suggested
it and I clicked" is reconstructable end-to-end.

### 1.3 Current vs improved (objective-level)

| Today (P1 findings) | With this layer |
|---|---|
| Signals list is time-ordered; 39 published strategies can fire; no triage (P1-§2.1) | Priority-ordered Focus queue with A–D bands + per-component explanation |
| Rejection page is forensic but flat — no "which rejection almost fired" (P1-§2.1) | Near-miss ranking off the existing `margin` field + rail-trend insights |
| Context pages are siloed; nothing says "PCR flipped while you were on /paper" (P1-§1.4) | CONTEXT_SHIFT insights + per-page "What changed" strips off digest endpoints |
| Notifications fire-and-forget to ntfy/Telegram; no in-app queue (P1-§2.2.6) | Insight feed = the in-app notification center P1-§6.1 asked for, with severity floors and suppression |
| Freshness is a per-page accident (P1-§4.2.1) | Every insight and digest carries `asOf` + trust; DEGRADED renders as caveat, BLOCKED suppresses advice |
| Graduation board is a static table (P1-§2.7) | Qualification dossier with threshold-crossing timeline + evidence links |

---

## 2. Insight generation model

### 2.1 The insight record (one shape for everything)

New table `strategy.insights` (next strategy-lineage migration; append-only like
`signal_rejections`):

```sql
CREATE TABLE strategy.insights (
  id              UUID PRIMARY KEY,
  generated_at    TIMESTAMPTZ NOT NULL,
  type            TEXT NOT NULL,        -- enum below
  severity        TEXT NOT NULL CHECK (severity IN ('INFO','NOTICE','WARN','CRITICAL')),
  scope           TEXT NOT NULL,        -- 'signal:<id>' | 'strategy:<id>' | 'book:<book>'
                                        -- | 'underlying:<exch>:<name>' | 'market' | 'dataops'
  title           TEXT NOT NULL,        -- one line, FE-renderable as-is
  explanation     TEXT NOT NULL,        -- 2–4 sentences, plain language, numbers inline
  evidence        JSONB NOT NULL,       -- [{label, value, source:{endpoint,params,asOf}, ref:{signalId?,rejectionId?,ingestRunId?,positionId?}}]
  priority        NUMERIC(5,2),         -- 0–100, only for prioritizable types (§3)
  priority_detail JSONB,                -- component breakdown (§3.4)
  data_trust      TEXT NOT NULL CHECK (data_trust IN ('OK','DEGRADED','BLOCKED')),
  trust_reasons   TEXT[],
  dedupe_key      TEXT NOT NULL,        -- type + scope + semantic key (e.g. 'PCR_FLIP:NIFTY:2026-07-10')
  cooldown_until  TIMESTAMPTZ,          -- suppression window end for this dedupe_key
  suppressed      BOOLEAN NOT NULL DEFAULT FALSE,  -- generated but muted (§2.5) — still stored
  status          TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACKED','ACTED','DISMISSED','EXPIRED')),
  expires_at      TIMESTAMPTZ,          -- signal-scoped insights expire with the signal
  engine_version  TEXT NOT NULL,        -- git SHA (P1-V10 pattern)
  config_hash     TEXT NOT NULL         -- hash of the weights/thresholds config in force
);
CREATE INDEX ix_insights_open ON strategy.insights (status, severity, generated_at DESC);
CREATE UNIQUE INDEX uq_insights_dedupe ON strategy.insights (dedupe_key) WHERE status = 'OPEN';
```

Design rules: `evidence` is MANDATORY and non-empty — an insight with no evidence pointer
fails validation at write (traceability constraint). `title`/`explanation` are produced
from templates per type (deterministic string interpolation, no free text), so identical
inputs produce byte-identical insights — replayable and golden-testable.

### 2.2 Insight type catalog (initial; each row = one generator class)

| Type | Trigger | Inputs (all existing per P1-§11) | Example title |
|---|---|---|---|
| `SIGNAL_PRIORITY` | on `SignalEmitted` (+ re-eval on context change) | signal + score_breakdown + scalper_detail, graduation stats, margin-heat, digests | "NIFTY 25200 CE scalp — priority 82 (A)" |
| `REJECTION_NEARMISS` | 15-min sweep over `signal_rejections` | `margin` + `blocking_rail` + rail-counts | "3 rejections within 5% of firing on volume-floor since 10:00" |
| `REJECTION_RAIL_TREND` | EOD sweep | rail-counts day series | "supertrend-alignment blocked 41% today vs 12% 5-day avg" |
| `CONTEXT_SHIFT` | digest threshold crossings (§6) | options/futures/equity/FII digests | "NIFTY PCR 0.87→1.14 since open; max-pain drifted 25150→25250" |
| `MARKET_STRUCTURE` | digest, coarser | breadth thrust flags, sector rotation, vix band change | "Breadth thrust: A/D 4.1, 87% above 20-DMA — strongest in available history (34 sessions)" |
| `RISK_HEAT` | on `paper.events` + 5-min sweep | margin-heat, advised_lots vs actual, concentration | "Scalper book heat 78% of cap after this fill" |
| `RISK_STALE_TICK` | bracket-starvation counter (P1-§8 V3) | tick freshness | "SL/TP unevaluated 4 min on SENSEX 81200 PE — no ticks" |
| `DATA_TRUST` | on `ingest_runs` failure/hole + capture stall | ingest_runs (P1-§11.16), DataHealth | "Participant-OI missing for 2026-07-09 — FII long-short is 1 session stale" |
| `STRATEGY_EVIDENCE` | nightly after graduation eval | graduation rows, `strategy_graduations`, sell_decisions | "manas-arora-breakout crossed PF 1.3 (20 trades) — TAKE_ELIGIBLE" |
| `SELL_DECISION` | on swing batch / sell-decision persistence (P1-§7.2.4) | sell_decisions rows | "SBCL: SELL (stop 2×ATR breached) — held 11 sessions, +4.2%" |
| `HYGIENE` | EOD sweep | journal (auto+unrated), positions w/o journal links | "6 auto journal entries this week have no ratings" |
| `EXPIRY_EVENT` | T-1 15:30 (existing roll prompt) + calendar | positions vs expiry, holidays | "2 open NIFTY legs expire tomorrow; Thursday is a BSE holiday" |

Extensible: a generator = a class implementing `InsightGenerator { List<Insight> run(Ctx) }`
registered in a catalog (mirror `IndicatorRegistry` precedent); adding a type is additive.

### 2.3 Triggers (three, all existing mechanisms)

1. **Event-driven** — `@EventListener` on the existing in-process events (`SignalEmitted`,
   `PaperPositionOpened/Closed`) + the P1-Phase-2 `signals.status`/`paper.events` frames.
2. **Scheduled sweeps** — `@Scheduled` IST crons alongside the existing scheduler
   inventory (P1-§1.2): 15-min intraday sweep (market hours), 15:50 EOD digest, 20:30
   post-batch strategy-evidence sweep (after the 20:05 swing batch + graduation eval).
3. **On-read** — page-level "What changed" strips call digest endpoints directly (no
   insight row) — cheap folds, same compute-on-read policy as the OI suite.

### 2.4 Action + feedback records

```sql
CREATE TABLE strategy.insight_actions (
  id UUID PRIMARY KEY, insight_id UUID NOT NULL REFERENCES strategy.insights(id),
  action TEXT NOT NULL,            -- 'ACK','DISMISS','TAKE_SIGNAL','OPEN_TICKET','ADD_WATCHLIST','JOURNAL_DRAFT_ACCEPT','MUTE_TYPE'
  target_ref JSONB,                -- e.g. {signalId, orderId} — what the click called
  acted_at TIMESTAMPTZ NOT NULL, actor TEXT NOT NULL DEFAULT 'owner'
);
CREATE TABLE strategy.insight_feedback (
  insight_id UUID PRIMARY KEY REFERENCES strategy.insights(id),
  verdict TEXT NOT NULL CHECK (verdict IN ('USEFUL','NOT_USEFUL')),
  note TEXT, given_at TIMESTAMPTZ NOT NULL
);
```

Every PROPOSE click writes `insight_actions` BEFORE invoking the target endpoint; the
target endpoint's own audit (P1-§11.20) completes the chain.

### 2.5 Noise suppression (reduce-noise requirement)

Layered, all config-driven (`artha.insights.*` in application.yml, following the
env-placeholder rule):

1. **Dedupe**: unique OPEN `dedupe_key`; regeneration refreshes `generated_at`/evidence
   instead of duplicating.
2. **Cooldown**: per-type windows (e.g. `CONTEXT_SHIFT` per (underlying, metric) 45 min;
   `RISK_HEAT` 15 min) via `cooldown_until`.
3. **Severity floors per channel**: in-app feed shows all; WS toast ≥ NOTICE; ntfy ≥ WARN;
   Telegram ≥ CRITICAL (reuses the per-strategy channel opt-ins + `notification_events`
   audit, P1-§11.20).
4. **User mutes**: per-type/per-scope mute stored in `user_prefs` (P1-§11.18); a
   `MUTE_TYPE` action creates it.
5. **Digest batching**: INFO-severity insights never push — they collect into the 15:50
   EOD digest insight.
6. **Suppression ledger**: suppressed/cooled insights are still WRITTEN with
   `suppressed=true` — auditable ("why didn't it warn me?" has an answer), and §10's
   quality metrics count them.

Hygiene: a nightly job EXPIREs signal-scoped insights whose signal left ACTIVE, and
prunes `suppressed` INFO rows older than 90 days (owner-tunable; the only deletion).

---

## 3. Ranking and prioritization model

### 3.1 What gets ranked

Two queues, same mechanics:
- **Signal queue** — ACTIVE signals, ranked by `priority` (the `SIGNAL_PRIORITY` insight).
- **Attention queue** — everything else OPEN, ranked by (severity, priority, age). This is
  the "suggest which items deserve attention first" surface: a SELL_DECISION outranks a
  HYGIENE row regardless of score.

### 3.2 Signal priority: additive components × multiplicative trust cap

`priority = min(100, Σ wᵢ·cᵢ·100) × trustCap`, weights sum 1.00, every `cᵢ ∈ [0,1]`
computed from named evidence:

| Component | w | c computed from (all existing data) |
|---|---|---|
| Edge margin | 0.30 | (composite − threshold) from the signal's own frozen score_breakdown, normalized into the strategy family's configured band (`artha.insights.priority.edge-band.<family>`, e.g. scalper 0→0.10 maps 0→1). NOT a re-score — a normalization of the strategy's own margin. |
| Context alignment | 0.20 | scalper: fraction of confluence dots agreeing (scalper_detail) + options-digest direction agreement. swing: RS-rank percentile (Minervini serialized; Manas via §13 row 12) + equity-digest breadth state + sector-of-symbol rotation rank. |
| Strategy track record | 0.20 | graduation board stats for the emitting strategy: scaled PF and expectancy, win-rate stabilizer; < 20 closed trades → neutral 0.5 prior (never boost unproven strategies). TAKE_ELIGIBLE +0.1 bonus capped at 1.0. |
| Risk headroom | 0.15 | 1 − bookHeat/heatCap (margin-heat endpoint); −0.25 penalty if an open position already holds the same underlying+side (concentration); advised_lots == 0 → component 0. |
| Freshness/urgency | 0.15 | signal age vs family half-life (scalper 10 min, swing 2 sessions); proximity to session close/expiry reduces scalper urgency after 15:00. |

`trustCap`: OK → 1.0; DEGRADED → 0.79 (mechanically caps at B-band) and the caveat is
rendered; BLOCKED → no priority computed — the insight is emitted as `data_trust=BLOCKED`
with reasons and NO score (never rank on bad data; §7.4 lists blockers).

Bands: **A ≥ 80** (act-now candidate) · **B 60–79** (review) · **C 40–59** (context-
dependent) · **D < 40** (low). Ties break by `generated_at` desc. All weights/bands live
in config, stamped into `config_hash` — changing them is visible in every insight row.

### 3.3 Re-evaluation

Priority is recomputed (same insight row updated, history in `priority_detail.history[]`
capped at 10 entries) when: a digest threshold crossing touches the signal's underlying;
book heat changes by ≥10 pts; the signal ages past a band boundary. Recompute is
event/sweep-driven (§2.3), never on-read — reads are cheap and consistent.

### 3.4 Explain contract (mirrors the frozen ScoreBreakdown convention)

```json
priority_detail: {
  "score": 82.4, "band": "A", "trustCap": 1.0,
  "components": [
    {"key":"edge","weight":0.30,"c":0.92,"points":27.6,
     "evidence":[{"label":"composite vs threshold","value":"0.81 vs 0.72",
                  "source":{"endpoint":"/api/v1/signals/{id}","asOf":"2026-07-10T09:47:12+05:30"}}]},
    {"key":"context","weight":0.20,"c":0.75,"points":15.0,
     "evidence":[{"label":"confluence dots","value":"6/8 bullish"},
                 {"label":"PCR shift","value":"0.87→1.14 since open",
                  "source":{"endpoint":"/api/v1/market/context/options-digest","params":{"name":"NIFTY"},"asOf":"..."}}]},
    ...
  ]
}
```

FE renders this as the priority drawer (§8.3). Every number has a source; every source is
navigable (deep-link to the page that shows it).

---

## 4. Comparison and explanation model

### 4.1 Candidate-trade compare (decide between setups)

`GET /api/v1/insights/compare?signalIds=a,b,c` (2–6, mirrors the backtest-compare cap) —
one server-assembled typed record per signal, columns:

priority + band + component points (from §3.4) · option-leg cost (scalper_detail
option_ltp × advised_lots × lot) · margin estimate (existing `POST /market/margin` for
option legs; "unpriced" ride-through) · R:R from the signal's own SL/TP brackets ·
context-alignment summary (which dots/digest features agree, which oppose) · track-record
strip (strategy PF/expectancy/N) · trust chips per input · what-differs highlights (the
server marks the max-spread component across the set — "these two differ mainly on risk
headroom").

Guards (comparability, mirroring P1-§2.7's lesson): refuse (422, D8 envelope) to compare
signals from different sessions; render a banner when books differ.

### 4.2 Rejection explanation + near-miss model

The rejection page already explains WHY (blocking rail, operand, threshold, margin —
P1-§11.2). This layer adds ordering and trend, not new forensics:
- **Near-miss rank**: `closeness = margin / threshold` per rejection; the
  `REJECTION_NEARMISS` insight lists the top-N closest-to-firing per session with their
  single blocking rail — "what almost fired" is the owner's cheapest tuning signal
  (precedent: the 2026-07-02 volume-floor forensics, done by hand then, automated here).
- **Rail trend**: today's rail-count share vs trailing 5-session mean (rail-counts
  endpoint, per-day series accrued by reading it EOD into the insight evidence) —
  flags a rail that suddenly dominates (gate drift or data defect).
- **Fired-vs-rejected contrast** (P1-§6.6): for a chosen day + strategy, a two-column
  layout — fired signals' operand values vs rejected ones' on the same rails; server
  endpoint assembles from `signals.score_breakdown` + `signal_rejections.diagnostic`.

### 4.3 Explanation invariants (layer-wide)

1. Plain-language `explanation` ≤ 4 sentences, numbers inline, no jargon tokens the FE
   must decode.
2. Every claim in `explanation` has a matching `evidence[]` entry.
3. Every evidence entry names its endpoint + params + `asOf` — the FE renders it as a
   navigable chip; "trust but verify" is one click.
4. Templates versioned with the engine (`engine_version`); template changes are diffable
   in git like code.

---

## 5. Strategy qualification and graduation support

No new bars, no new scoring — the graduation thresholds (20/1.3/>0/≤25%) and the stricter
GRADUATED bar (≥50, Sharpe ≥0.5) stay canonical (P1-§11.9), and strategy-version ranking
stays the evolution engine's. This layer makes the EVIDENCE legible:

1. **Qualification dossier** — `GET /api/v1/insights/strategy-dossier/{strategyId}`:
   one server-assembled view per strategy = graduation row + criterion pass-map NOW,
   threshold-crossing timeline (first date each criterion held, from nightly
   `STRATEGY_EVIDENCE` insights — this creates the history the board lacks), equity curve
   of its paper book slice, rejection profile (its top blocking rails), shadow-variant
   summary if scalper (existing shadow endpoints), latest backtest refs (existing
   `/backtests/summary`), and open sell-decisions if swing. Assembly-only: every element
   is an existing read; the dossier is the join the owner currently does by hand across 5
   pages.
2. **Threshold-crossing insights** — nightly generator compares today's board vs
   yesterday's snapshot (persisted in the insight evidence itself; no new table): crossing
   UP (→ `STRATEGY_EVIDENCE`, NOTICE) or DOWN below a held bar (WARN — "manas-arora-vcp
   dropped below PF 1.3 after 3 losses"). Surfaces the GRADUATED stage the UI currently
   never shows (P1-§2.7) by consuming the orphan `/strategies/graduation/promotions`.
3. **Sell-decision follow-through** — consumes P1-Phase-2 `sell_decisions` persistence:
   SELL verdicts become `SELL_DECISION` insights with acknowledge action; un-acked SELL
   older than 1 session escalates to WARN. Follow-through metric (verdict date → close
   date lag) lands in the dossier.
4. **Evolution-engine seam**: when the evolution engine ships, its owner-gated proposals
   (PROPOSED→SURVIVOR→…) surface in this same feed as `STRATEGY_EVIDENCE` insights with
   deep-links into its proposal views — one attention queue, two evidence producers. No
   coupling now beyond reserving the scope string `strategy:<id>`.

---

## 6. Options, futures, equity, and FII/DII decision support

### 6.1 Digest endpoints (context plane, market-data)

Five new **typed-record** endpoints (ratchet at capacity forces typed — P1-§5.2 — which
is also what the contract gate needs). All are folds over existing tables, same
compute-on-read policy as the OI suite; each response field group carries `asOf` +
`trust` per the P1-§11.17 envelope.

1. `GET /api/v1/market/context/options-digest?name&expiry` — from `options_chain_snapshots`
   folds already powering the pages: PCR now / vs session open / vs prior day EOD;
   max-pain now + drift; top-3 OI gainer/loser strikes (spurt fold); active-strike
   migration (top-5 set now vs open — set diff); ATM straddle premium now vs open
   (premium fold); ATM IV vs its percentile over available `iv_daily_summary` history;
   4-state OI structure verdict (existing `OiInterpretation` aggregation).
2. `GET /api/v1/market/context/futures-digest` — per captured underlying (indices + 17
   banks ONLY, P1-§11.13): price%/ΔOI quadrant (long-buildup/short-covering/…, the
   existing spurt classification), basis vs spot, term-structure state (existing
   `/term-structure`), banks summary (existing banks fold).
3. `GET /api/v1/market/context/equity-digest?date` — from bhavcopy tables: A/D ratio +
   above-20/50-DMA counts (breadth fold) + day-over-day; breadth-thrust flag (10-day A/D
   moving-average crossing 0.618, standard Zweig definition, config-pinned); top/bottom-3
   sectors day-over-day rotation; delivery% z-score outliers vs each symbol's trailing
   20 sessions; index-contribution concentration (top-5 names' share of index move).
4. `GET /api/v1/market/context/fii-digest` — from `nse_eod_*`: FII cash net + streak
   (consecutive buy/sell days) + flip flag; DII divergence flag; FII index-futures
   long-short ratio + Δ + percentile over AVAILABLE history (§6.5 honesty); participant
   positioning day-over-day; derivative-stats availability flag (dead-by-default caveat,
   P1-§2.5, surfaces as trust DEGRADED with the reason "upstox analytics disabled").
5. `GET /api/v1/market/context/day-context` — the dashboard one-call: bundles 1–4's
   headline fields + VIX level/Δ/band + session phase + holiday proximity (market-calendar)
   + ingest-trust summary (§7). This is what the Focus panel and the day-context strip
   render.

### 6.2 Change detection → CONTEXT_SHIFT insights

The insight engine polls digests on the 15-min sweep and emits on config-pinned threshold
crossings (`artha.insights.context.*`), e.g.: |ΔPCR| ≥ 0.15 since open · max-pain drift ≥
1 strike step · active-strike set change ≥ 2 of 5 · straddle premium ±20% vs open · FII
streak flip · breadth thrust flag edge · sector rank jump ≥ 3 places · VIX band change.
Each threshold and its baseline (open / prior bucket / prior EOD — the three baselines
the folds already use, P1-§6.2-agent evidence) is named in the insight evidence.

### 6.3 Per-page "What changed" strips (on-read, no insight rows)

Options chain, OI analysis, futures OI, breadth, sector, FII pages each get a one-line
strip fed by the matching digest endpoint: 3–5 chips (metric, now-vs-baseline, spark
direction) + `asOf` + trust. Click → the relevant full page/section. This is the P1-§4.2.1
freshness fix and the context fix in one component (§8.4).

### 6.4 Comparison aids on analytics pages

- Chain/premium: "vs prior day EOD" toggle column set (the pivots already exist behind
  `baseline=peod` on trending — extend the convention to chain-table deltas).
- Cross-expiry compare (P1-§6.9): two `/trending` folds side-by-side, server-zipped
  (one new typed endpoint, `GET /market/context/expiry-compare?name&expiryA&expiryB`).
- Futures: quadrant view already exists client-side (spurt) — add the digest's
  classification history (last 5 sessions per underlying, from snapshots) so "3rd day of
  long-buildup" is visible.

### 6.5 History-honesty rule (no invented depth)

Every percentile/z-score/streak computed on a forward-only dataset (futures OI, chain
snapshots, FII/DII — P1-§3.4) carries `windowSessions` in evidence and renders
"(over N sessions)" until N ≥ 60; below N=20 the metric reports `LOW_CONFIDENCE` and
never triggers a CONTEXT_SHIFT insight on its own. Bhavcopy-backed metrics (breadth,
delivery, sectors) have real depth and no such cap.

### 6.6 One persisted table: `marketdata.market_context_days`

EOD job (after the 19:00/19:30 ingests; registered in `ingest_runs`) writes one row/day:
the day-context JSON + per-metric values used as next-day baselines. Purpose: (a) digest
"vs prior day" reads stop re-folding yesterday, (b) context history becomes queryable for
the dossier and future evolution-engine regime work, (c) it is the replay fixture for §10
determinism tests. This is the only new market-data persistence; everything else stays
compute-on-read (P1-§3.3 line honored).

---

## 7. Data-ops trust and freshness awareness

### 7.1 Trust oracle = P1's rails, composed

Single composition point `TrustService` (insights module) reading: `ingest_runs`
(P1-§11.16 — THE oracle for batch sources), `GET /market/health/data` (live capture +
tick/bar divergence), dot-health (gate inputs), the P1-§11.17 freshness envelope on every
digest read, and the market calendar (expected-data matrix — no FII data on a holiday is
OK, not a hole).

### 7.2 Trust states per data family

`OK` — last expected run COMPLETED and within staleness budget. `DEGRADED` — data exists
but: stale beyond budget, LOW_CONFIDENCE window (§6.5), derived-history provenance,
upstox-analytics-off families, or a known ingest hole inside the read window. `BLOCKED` —
load-bearing input absent/failed: no capture today (live), ingest FAILED with no
subsequent success, dot DEAD, tick starvation on the instrument.

### 7.3 Surfacing

- `GET /api/v1/insights/trust-summary` — per-family state + reason + last-good `asOf` +
  next-expected; rendered as the dashboard trust strip and the Data-Ops board header.
- `DATA_TRUST` insights (WARN default) on state transitions OK→DEGRADED/BLOCKED and
  recovery notes on return — this is also the FII "silent hole" killer (P1-§2.5) at the
  consumption layer, complementing P1-Phase-1's ingest canary at the ingestion layer.
- Every insight/digest response embeds the trust of ITS OWN inputs (`data_trust` +
  `trust_reasons`) — page-level chips come free.

### 7.4 Gating rules (advice never outruns data)

| Condition | Effect |
|---|---|
| Tick starvation on signal's instrument | SIGNAL_PRIORITY → BLOCKED (no score) |
| dot-health DEAD for a dot the strategy's gates use | scalper priority BLOCKED; insight explains which dot |
| FII/participant hole inside window | fii-digest fields DEGRADED; FII-context component of context alignment contributes its neutral midpoint (0.5), noted in evidence |
| Derived-history OI (pre-2026-06-15 reads) | digest DEGRADED, "derived (Dow/IV muted)" caveat — never a CONTEXT_SHIFT trigger |
| `ingest_runs` says screener/bhavcopy didn't run | equity-digest BLOCKED for that date; STRATEGY_EVIDENCE nightly skipped with a DATA_TRUST insight instead |
| LOW_CONFIDENCE window (§6.5) | metric shown, never triggers insights |

---

## 8. Frontend presentation requirements

### 8.1 Focus panel (dashboard, replaces the passive "Active Signals top-7")

Ranked list: band chip (A/B/C/D) + title + top-2 evidence chips + trust chip + age +
actions (Review → drawer; Take → existing ticket prefilled; Dismiss). Below it the
attention queue (non-signal OPEN insights, severity-ordered). Empty states are explicit:
"No A/B signals — 14 C/D suppressed (show)". WS-live via the `insights` channel (§9.3).

### 8.2 Insight feed page (`/insights`) — the notification center P1-§6.1 wanted

DataTable (P1-§9.6 adoption wave applies): columns time/type/severity/scope/title/trust/
status; filters type/severity/status/scope/day; free-text search over title+explanation;
saved views via `user_prefs`; bulk ack/dismiss (P1-§4.2.7 bulk-action gap); suppressed
toggle ("show what was muted and why"); 90-day history with day picker. Row click → the
same explain drawer everywhere.

### 8.3 Explain drawer (one component, used by Focus, feed, signal detail, compare)

Sections: title + severity + trust banner (with reasons when ≠ OK) · plain-language
explanation · priority component table (when scored — §3.4 render: weight × c = points
per row, evidence chips inline) · evidence list (each chip: label, value, `asOf`,
deep-link) · action row (tier-2 PROPOSE buttons; disabled+tooltip when trust-gated) ·
feedback (Useful / Not useful — writes `insight_feedback`) · history (prior priority
evaluations).

### 8.4 "What changed" strip + trust chips (shared components)

`<ContextStrip family=... />` on the §6.3 pages: 3–5 delta chips + asOf + trust; one
implementation, config per page. `<TrustChip state reasons asOf />` standardized
(extends P1's `FreshnessBadge` plan with the DEGRADED/BLOCKED vocabulary) — used by
digests, insight rows, compare columns, PROPOSE buttons.

### 8.5 Comparison view (`/insights/compare?signalIds=`)

Matrix per §4.1: signals as columns, components as rows, best-per-row highlighted,
what-differs row pinned on top, trust chips per cell group, ticket prefill per column.
URL-addressable (P1-§4.2.4 convention fix applies here from day one).

### 8.6 Integration touches (small, high-leverage)

Signal detail gains the priority drawer section; rejection page gains near-miss sort +
rail-trend chip; graduation page gains dossier link + crossing timeline; sell-decision
rows gain acknowledge; journal gains the draft-accept flow (drafts land pre-linked with
signal/position ids — the P1-§2.2 write-only-chain fix made usable); cmdk palette
registers "insights for <symbol>", "compare selected", "open dossier <strategy>".
Mobile: Focus panel and feed use DataTable card-mode — the review flow (read → ack/
dismiss → maybe take) is deliberately phone-viable; PROPOSE order actions stay
desktop-default (config).

### 8.7 Presentation invariants

Never a bare number (always with its evidence chip); never an unexplained suppression
(muted counts visible); never a stale render without its `asOf`; loading/error/empty via
the existing QueryState convention; all colours through `--ay-*` tokens; axe + Playwright
role/name coverage like every other page.

---

## 9. Backend service requirements

### 9.1 Module + API surface (strategy-signal-service, `insights` module)

```
GET  /api/v1/insights                    {items} envelope; filters: type,severity,status,scope,day,limit/offset
GET  /api/v1/insights/{id}
GET  /api/v1/insights/summary            badge counts by severity/status (Focus header)
GET  /api/v1/insights/focus              ranked signal queue + attention queue (one call)
GET  /api/v1/insights/compare?signalIds=
GET  /api/v1/insights/strategy-dossier/{strategyId}
GET  /api/v1/insights/trust-summary
POST /api/v1/insights/{id}/ack | /dismiss | /feedback
POST /api/v1/insights/{id}/act           {action, params} — executes a PROPOSE via the target endpoint, records insight_actions
```

All typed records (SS ratchet at 28/28 — new Maps fail CI anyway); `{items}` envelope +
limit/offset; D8 error envelope; gateway route: `/api/v1/insights/**` added to the
strategy-signal `Path=` allowlist (P1's gateway-allowlist trap — spec recapture in the
same PR).

### 9.2 Engine internals

`InsightEngine` orchestrates registered generators per trigger; each generator is pure
(inputs → insight candidates) with IO at the edges — unit-testable against fixtures.
Market-data reads via a `ContextClient` (RestClient, same pattern/timeouts as
`MarketOiClient`; digest calls budgeted ≤ 2 s, failures → that family DEGRADED, never an
engine error). Writes are idempotent upserts keyed on `dedupe_key` (regeneration
refreshes). Engine identity: `engine_version` from build-info git SHA (the #617
mechanism), `config_hash` = SHA-256 of the resolved `artha.insights.*` subtree, both
stamped on every row.

### 9.3 Delivery

Redis pub `insights` channel (severity ≥ NOTICE frames: `{id,type,severity,scope,title}`)
→ WS bridge allowlist +1 → `/topic/insights`; FE merges into feed/Focus caches (same
bounded-ring pattern as signals). ntfy/Telegram delivery reuses `NotifierClient` +
`notification_events` audit with the §2.5 severity floors. No new Redis streams — pub-sub
transport + durable table is the platform's established shape (P1-§9.3).

### 9.4 Traceability requirements (hard)

Every insight row: non-empty evidence, engine_version, config_hash, data_trust. Every
PROPOSE execution: insight_actions row + target-endpoint audit. Every digest response:
asOf + trust per group. Log lines carry correlationId per existing convention. A
`GET /api/v1/insights/{id}` response is self-contained for audit: everything needed to
re-derive the insight by hand is in it or one evidence link away.

### 9.5 Performance/ops posture

Sweeps bounded (15-min sweep processes deltas since last run, not full scans); digest
folds are the same cost class as existing page folds; `insights` table growth ≈ low
hundreds/day (11 types, dedupe, single owner) — trivial next to `signal_rejections`.
Micrometer: `ay_insights_generated_total{type}`, `ay_insights_suppressed_total`,
`ay_insight_sweep_duration`, digest-call latency. Engine health = the existing actuator +
a `DATA_TRUST` self-insight if a sweep fails twice consecutively (the engine reports on
itself through its own channel).

### 9.6 Stack verdict (swap check, per constraints)

No swaps required: Java 21/Modulith fits the module, Timescale holds the two new tables
without hypertables (row counts small), Redis pub-sub suffices, React/TanStack renders
feeds it already knows how to render. Explicitly rejected: separate Python "insight
service" (splits ownership, new deploy unit, no numeric-library need — the math is
weighted sums), Kafka (scale mismatch), client-side insight computation (kills
traceability + history). Optional later: `persistQueryClient` for offline mobile review
(P1-§9.6 note stands).

---

## 10. Validation and trust model

### 10.1 Determinism + replay (the golden-vector culture, extended)

Generators are pure; templates are code. **Golden insight test**: a fixture day (frozen
digest responses + signal/rejection fixtures + config) → byte-identical insight set
(ids/timestamps normalized). Any diff = intentional (template/threshold change reviewed
in the PR) or a bug. `market_context_days` rows are the natural fixture source.

### 10.2 Usefulness measurement (are recommendations useful/consistent/explainable?)

Weekly quality report (EOD job → a NOTICE insight + doc row), per type and band:
- **Act rate**: A-band signals acted (taken or explicitly dismissed with feedback) — target ≥ 60%.
- **Dismiss rate**: NOT_USEFUL feedback share — a type > 40% two weeks running is a
  candidate for threshold change or retirement (owner decision; the report proposes,
  never self-tunes).
- **Suppression audit**: count + top dedupe_keys of suppressed insights (is muting hiding value?).
- **Priority calibration**: outcome deltas by band (A-band taken trades' realized R vs
  C-band takes — the honest check that ranking correlates with anything; small-N caveats
  stated per §6.5's honesty rule).
- **Consistency**: re-run of the day's generation off `market_context_days` must
  reproduce the day's insight set (drift = nondeterminism bug).

### 10.3 Staged rollout (trust before push)

Stage 0 (shadow): engine generates, feed page only, no WS/ntfy — 2 weeks, owner reviews
quality reports. Stage 1: WS + in-app toasts. Stage 2: ntfy/Telegram floors armed. Each
stage = a config flag flip (default OFF, `artha.insights.delivery.*`), never code.

### 10.4 Failure honesty

Engine failures produce visible `DATA_TRUST` insights, not silence; BLOCKED beats a
confident wrong number everywhere (§7.4); every quality report includes the "insights we
did NOT generate because of trust gates" count — the suppressed-by-caution ledger.

---

## 11. Example workflow — reviewing a signal and acting

09:47 IST. Scalper strategy `siva-vwap-breakout` fires on NIFTY 25200 CE.

1. `SignalEmitted` → priority generator: edge 0.92 (composite 0.81 vs 0.72), context 0.75
   (6/8 dots; options-digest PCR 0.87→1.14 since open agrees), track record 0.68 (PF 1.9,
   34 trades, TAKE_ELIGIBLE), risk 0.81 (heat 34%, no NIFTY exposure), freshness 1.0
   (12 s old). Trust OK (ticks live, dots alive, capture 40 s fresh). **Priority 82.4 (A)**.
2. Insight row written; WS frame → Focus panel reorders; ntfy pushes (A-band ≥ WARN floor
   config) with the title only.
3. Owner opens Focus → Review. Explain drawer: component table, evidence chips — clicks
   "PCR shift" → options-digest values with asOf 09:45; clicks "heat 34%" → margin-heat
   numbers. Notices a B-band signal on SENSEX PE from another scalper → selects both →
   Compare: matrix shows they differ mainly on context (SENSEX digest NEUTRAL) and cost
   (SENSEX leg margin higher); NIFTY column wins on 3 of 5 rows.
4. Take (PROPOSE): ticket opens prefilled — instrument = tradeable leg, qty = advised_lots
   × lot, book = scalper (selector visible per P1-Phase-2), SL/TP from the signal.
   Manual-checks checklist still gates the button (existing behavior). Confirm →
   `insight_actions{TAKE_SIGNAL}` → `POST /signals/{id}/taken` → position opens (risk
   governor now also checks manual path per P1-Phase-1 V1).
5. `paper.events OPENED` → RISK_HEAT recheck (heat 41%, below WARN threshold — no insight;
   the non-event is still visible in the sweep log). Journal draft created: linked
   signal_id + position_id, tagged `scalper`, ratings empty.
6. 10:32 exit at TP. `paper.events CLOSED` → journal draft updated with outcome tag;
   HYGIENE sweep will nudge if ratings stay empty. Signal insight → status ACTED,
   `expires_at` set.
7. 15:50 EOD digest insight: "1 A-band taken (+₹2,340), 2 B dismissed, 4 C/D expired
   unactioned; PCR round-trip 0.87→1.14→0.95; FII cash −₹1,850 Cr (3rd sell day);
   participant-OI ingest OK; 1 journal entry unrated." Every clause carries its evidence
   chip. Feedback buttons on the drawer close the loop.

Trace: insight row → action row → taken signal → paper order/position (opening_signal_id)
→ journal entry (linked) → notification_events — every hop queryable, per §9.4.

---

## 12. Phased implementation roadmap

Tiers per house rules (clean / HOLD / owner-gated). P1-Phase-n dependencies named; this
layer's phases are I1–I4. Nothing here changes engine/backtest behavior; HOLD applies
where live paper flow or push channels are touched.

**I1 — Foundations (after P1-Phase-1; ~1.5 weeks)**
`insights`/`insight_actions`/`insight_feedback` migrations + module skeleton + engine/
registry/TrustService (clean) · day-context + options-digest endpoints + `market_context_days`
EOD job registered in `ingest_runs` (clean) · SIGNAL_PRIORITY + DATA_TRUST + RISK_HEAT
generators (clean — display-only) · `/insights` feed page + explain drawer + Focus panel
in shadow mode (clean) · golden insight test + fixtures (clean).

**I2 — Context breadth + rejection intelligence (~1.5 weeks)**
futures/equity/fii digests + expiry-compare (clean) · CONTEXT_SHIFT/MARKET_STRUCTURE/
REJECTION_NEARMISS/RAIL_TREND/HYGIENE/EXPIRY_EVENT generators (clean) · ContextStrip on
the §6.3 pages + trust chips (clean) · fired-vs-rejected contrast endpoint + view (clean)
· quality-report job (clean).

**I3 — Actions + compare + strategy evidence (needs P1-Phase-2 events/UI; ~2 weeks)**
PROPOSE actions (`/act`, ticket prefill, dismiss-batch, watchlist add) — HOLD (touches
live paper flow via existing endpoints) · signal compare endpoint + view (clean) ·
STRATEGY_EVIDENCE + SELL_DECISION generators + dossier + graduation-page integration
(clean) · journal draft-accept flow (clean) · WS `insights` channel + Stage-1 delivery
flag (HOLD — new push surface) · saved views via user_prefs when P1-Phase-4 lands, else
localStorage interim (clean).

**I4 — Delivery arming + calibration (owner-gated)**
ntfy/Telegram floors ON (owner flips `artha.insights.delivery.*`) · priority-weight tuning
from 4+ weeks of quality reports (owner-reviewed config PRs) · type retirement/additions
per dismiss-rate evidence · mobile review-polish pass.

Explicitly deferred: ML/LLM anything (§0), broker-order automation (out of scope until
OpenAlgo arms + its own program), evolution-engine feed integration (its E-phases own it).

---

## 13. Dependencies from Prompt 1

Missing or in-flight inputs that gate implementation. **HARD** = the named feature cannot
ship without it; **DEGRADED** = ships with reduced behavior (stated); **AS-IS** =
consumed unchanged. P1 refs are to `docs/audits/2026-07-10-app-platform-audit.md`.

| # | Input | P1 ref | Status in P1 | Blocking | Without it |
|---|---|---|---|---|---|
| 1 | Signal schema + score_breakdown + scalper_detail | §11.1 | EXISTS | AS-IS | — |
| 2 | Rejection schema (margin, blocking_rail, diagnostic, rail-counts, shadow-summary, dot-health) | §11.2 | EXISTS | AS-IS | — |
| 3 | Paper/positions/margin-heat + graduation board + shadow endpoints | §11.4/9 | EXISTS | AS-IS | — |
| 4 | Analytics folds this layer's digests re-use (chain/spurt/trending/premium/active-strikes; futures; breadth/sector/delivery/contribution; FII reads) | §11.12–15 | EXISTS | AS-IS | digests are new endpoints over these folds — no new raw data |
| 5 | **`ingest_runs` ledger** (+ T+1 canary) | §11.16, P1-Phase-1 | ADD | **HARD (I1)** | TrustService has no batch-source oracle; FII/equity digests cannot be trust-gated — the layer would advise on silent holes, violating §7 |
| 6 | **Risk-governor enforcement on manual orders (V1) + idempotency (V2) + tick-freshness guards (V3)** | §8, P1-Phase-1 | ADD (HOLD) | **HARD for I3 PROPOSE order actions** | one-click take/ticket flows would amplify the ungoverned-manual-path defect; I1/I2 display-only parts unaffected |
| 7 | `signals.status` + `paper.events` push frames | §7.2.1–2, P1-Phase-2 | ADD | DEGRADED (I1–I2) | engine falls back to 15-min sweeps + existing in-process events; Focus reorders on sweep cadence, not instantly; I3 wants the frames |
| 8 | `sell_decisions` persistence | §7.2.4, P1-Phase-2 | ADD | HARD for SELL_DECISION insights + dossier follow-through | verdicts remain recompute-on-read with no history to insight over |
| 9 | Freshness envelope `{asOf, source, historyStart, staleSeconds, complete, provenance}` on analytics responses | §11.17, P1-Phase-3 | ADD | DEGRADED (I1) | digests compute their own staleness from timestamps they already read; per-page trust chips outside digest pages wait for the envelope |
| 10 | `user_prefs` server table | §11.18, P1-Phase-4 | ADD | DEGRADED | saved views/mutes ride localStorage interim (single-owner acceptable); server-side needed for §2.5 mutes to bind notification floors |
| 11 | Book selector + qty override on tickets (`book` already on the API) | §11.3, P1-Phase-2 | ADD (UI) | DEGRADED for I3 prefill | prefill sends `book` on the body anyway (API accepts it today); the UI just can't show/override it |
| 12 | **Manas `rs_rank` serialized in the screen API row** | §11.14 caveat | gap (DB-only today) | HARD for Manas context-alignment component | Manas swing priority runs with RS at neutral 0.5 + evidence note "RS unavailable on API" — wrong to silently fake it |
| 13 | Per-underlying DataHealth granularity | §9.1 | ADD | DEGRADED | trust states are per-family, not per-underlying; a single stalled underlying degrades the whole family (conservative, acceptable) |
| 14 | `strategies.enabled` exposure in FE type (+ write endpoint for ops view) | §2.7 | gap | DEGRADED for dossier "armed?" line | dossier reads `enabled` from the registry API directly (it IS served — P1 verified); the FE type fix is one line |
| 15 | Notification center = this layer's feed | §6.1 | ADD (this design provides it) | — | P1-Phase-2's separate notification-center item is SUPERSEDED by §8.2 here; `notification_events` gets its read endpoint as part of I1 |
| 16 | Jobs `error` display + unified job envelope | §5.6/§9.2, P1-Phase-1/4 | ADD | not blocking | DATA_TRUST insights link to Data-Ops; unified jobs console remains P1-Phase-4's item |
| 17 | Screener engine-version stamps (V10) + CA-adjustment fix (fidelity D1/P0-4) | §8 V10, fidelity | ADD / open defect | DEGRADED | equity-digest screener-derived fields carry the CA caveat in trust_reasons until fixed; never blocks non-screener breadth/sector fields |
| 18 | Gateway allowlist entry `/api/v1/insights/**` + spec recapture | §5.5 trap | ADD (I1 PR) | HARD (trivial) | without it the SPA fallback serves index.html for the new prefix — the P1-documented trap; ship in the same PR as the module |

Superseded/absorbed P1 items for the record: P1-§6.1 notification center → §8.2 here;
P1-§6.6 rejected-vs-fired view → §4.2; P1-§6.4/6.5 position-detail/trade-chain remain
P1-Phase-2 items that §8.6 links INTO, not duplicates.
