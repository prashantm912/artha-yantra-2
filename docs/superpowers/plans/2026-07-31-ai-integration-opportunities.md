# AI / LLM / MCP / RAG / ML Integration Opportunities — Research Memo

**Date:** 2026-07-31 · **Type:** research memo (no production code; `research` skill, standard depth)
· **Question:** "Where in ArthaYantra could AI — LLMs, MCP, RAG, machine learning, agents — be
incorporated?" · **Method:** read-only recon (one Explore agent over the full tree + direct reads of
the INT/EVO design docs, PHASE_GATES, DEFERRED_BACKLOG); every load-bearing claim cites file:line.

> ⚠️ **OWNER-PULL ONLY (standing rule, 2026-07-31).** Nothing in this memo is queued work. The
> items here — including every BUILD verdict — are to be considered **only when the owner
> explicitly asks** for a specific item by name. Do NOT promote any row into the remaining-items
> ledger, a session pickup sheet, an autonomous run, or any agent's "what's next" enumeration.
> The BUILD/DEFER/SKIP verdicts rank *feasibility if asked*, not priority against the live board.

---

## 0. The standing constraint this memo must respect

The question is **not greenfield**: it was already asked and answered once. The intelligence-layer
design (`2026-07-10-intelligence-layer-design.md:70-77`) rejected LLM/ML for the insight plane:

> "**Deterministic, not ML.** … LLM-generated insights were considered and rejected:
> non-deterministic, unexplainable at the evidence-pointer level, new infra.
> Revisit only after ≥6 months of `insight_feedback` data exists."

and its closing scope line (`:800`) reads "Explicitly deferred: ML/LLM anything (§0)."

That rejection was scoped to **LLMs generating insights inside the decision loop**. It does *not*
cover the surfaces below, which are read-only, narrative, or tooling surfaces. Three hard boundaries
from the existing designs are treated as inviolable throughout this memo:

1. **Determinism is load-bearing on the scoring/parity path.** Golden-vector byte-identity
   (Stage D) and the frozen `ScoreBreakdown` mean nothing nondeterministic may touch signal
   scoring, gate evaluation, backtest replay, or anything money-adjacent. AI **explains** these
   outputs; it never **produces** them.
2. **Nothing self-arms.** All AI features live in the INT automation tiers AUTO (display only) or
   PROPOSE (one-click approve, `insight_actions`-style audit row); never MANUAL-tier actions
   (`2026-07-10-intelligence-layer-design.md:79-86`).
3. **No invented data.** The repo's deepest invariant (absent-dot withholding
   `ConnectTheDotsScorer.java:47-53`, SKIPPED gates `scoring.py:528`, `DataTrust.BLOCKED → no
   score` `PriorityModel.java:43`). Every AI-generated sentence must be grounded in an evidence
   pointer to an existing row/endpoint, exactly like the insights `Evidence` model — numbers are
   rendered from structured data; the LLM writes only the prose around them.

## 1. Current state (verified)

- **Zero runtime AI/LLM/ML in the codebase.** A case-insensitive grep for
  `anthropic|openai|llm|gpt-|langchain|ollama|mistral|cohere` across all source trees hits exactly
  one file: `.github/workflows/ci-review-verdict.yml:119-155` — a regex over PR bodies (process
  governance, not integration). Optimizer deps are `fastapi/uvicorn/optuna/psycopg/redis/httpx`
  only — no sklearn/numpy/pandas (`services/optimizer-service/requirements.txt`). *(computed)*
- **The only "learning" machinery is Optuna** (grid/random/TPE/NSGA-II,
  `optuna_runner.py:60-79`), wrapped in strong hand-rolled statistics (RobustScore + DOF penalty
  `scoring.py:115-157`, deflated-Sharpe gate `scoring.py:528-566`, IS-only ablation rejection
  `ablation.py:285-310`, hand-rolled Spearman tornado `insights.py:1-25` with a written
  justification for staying sklearn-free). *(sourced)*
- **Agentic AI is already the platform's biggest AI consumer — outside the codebase.** The
  session-forensics loop (`.claude/skills/session-analysis/` + `docs/signal-analysis/README.md`,
  1,120 lines, 57 findings files since 07-02) is a Claude-agent-driven process that reads live
  Postgres/logs/Prometheus and writes the findings docs and the rollup league table. It found the
  B0–B11 bug queue, the T-namespace tunes, and the G-row verdicts. This is the strongest evidence
  in the repo that agentic analysis pays here. *(sourced)*

## 2. Opportunity map (ranked by value × fit × friction)

Verdicts: **BUILD** (feasible, worth it, respects §0) / **DEFER** (owner-gated or blocked — what
unblocks it is named) / **SKIP** (not worth it or violates §0).

### 2.1 BUILD — Natural-language → SQL in the Data-Ops Query Console

**What exists:** `QueryConsolePage.tsx` is a read-only SQL console with preset queries (`:20-28`),
a free-text editor (`:91-94`), row-limit guard, result grid, CSV export, backed by
`api/dataops.ts:208 runQuery(sql, rowLimit)`.
**What AI adds:** an "Ask" box that translates a question ("show yesterday's rejections by rail for
NIFTY scalpers, IST bounds") into SQL, pre-filled into the *existing* editor for the owner to review
and run. The execution/safety path already exists; the LLM never executes anything.
**Why it's first:** failure mode is a wrong query the owner sees before running — not a bad trade.
Grounding = the schema DDL (`deploy/flyway/*`) + the session-analysis README §6 SQL cookbook as
few-shot context. The IST-offset trap (CLAUDE.md) goes in the system prompt verbatim.
**Shape:** one endpoint in edge-gateway (Java, Anthropic Java SDK, structured output = the SQL
string + a one-line explanation), or a proxy in the optimizer's FastAPI to avoid a new Java dep.

### 2.2 BUILD — "Explain this" verbalizer over ScoreBreakdown / rejection diagnostics

**What exists:** the frozen `ScoreBreakdown` (per-indicator score/weight/contribution/rawValue +
recursive `GateResult` tree, `libs/strategy-engine/.../ScoreBreakdown.java:16-57`) already rendered
by `ReasoningBreakdown.tsx`; rejection rows carry `blocking_rail/operand/threshold/margin` + full
`diagnostic` JSONB (`V015__signal_rejections.sql:10-30`, `V044__composite_rejections.sql:33-48`).
**What AI adds:** a plain-English paragraph per signal/rejection ("fired because VWAP+supertrend+OI
aligned long with 7.2/9 dot support; the IV-rank optional activated at 0.71…"), generated from the
structured object, with every claim traceable to a field. Same generator upgrades notifier bodies
(`NotifierService.java:88`, `ScalpAlertService.java:110-180`) from concatenated fields to a
readable one-liner — alert prose is ephemeral display, never persisted into parity paths.
**Containment:** output stored (if at all) in a new side table or generated on-demand; never in
`signals`/`score_breakdown`.

### 2.3 BUILD — Read-only MCP server over the platform ("artha-mcp")

**What exists:** the session-analysis agent today reaches data via `docker exec … psql` and raw
gateway GETs; every Claude session re-learns the access patterns from the 1,120-line README.
**What AI adds:** a small MCP server exposing typed read-only tools — `query_signals`,
`query_rejections`, `rail_counts`, `dot_health`, `backtest_run(runId)`, `insights_feed`,
`run_preset_sql(name, params)` — wrapping the existing gateway endpoints
(`SignalRejectionsController.java:24-92`, insights/backtest APIs) and the Query-Console preset lane.
Claude Code / Claude Desktop sessions then attach to live data uniformly; the forensics skill gets
shorter and less error-prone (no IST-bounds hand-rolling, no psql quoting).
**Why it ranks high:** it multiplies the *already-proven* agent workflow rather than adding a new
one, and it is pure read-only infrastructure — zero contact with §0 boundaries. Loopback-only
gateway is preserved (MCP server binds 127.0.0.1, authenticates like the e2e helper).

### 2.4 BUILD — RAG / retrieval over the ~1M-word docs corpus

**What exists:** ~392 markdown files (~994k words in `docs/` alone): 57 session-findings, the
653-line rollup, 67 oipulse-study files, 44 strategy-audit files, design set A–G, and
`strategy-documents/` — the human-authored Siva/Minervini/Manas doctrine the rails were transcribed
from. Traceability questions ("which rail implements Siva §12.3?", "has this tune been tried?",
"what did we conclude about relative-volume-floor?") are answered today by grep across all of it.
**What AI adds:** retrieval-grounded answers with file citations. **Phasing matters:** start with
zero new infra — a `docs-navigator` agent skill (structured grep + the rollup as an index) or the
artha-mcp `search_docs` tool; add embeddings/vector store only if grep-recall provably fails.
A vector DB on day one would be over-engineering for a single-owner corpus this greppable.

### 2.5 BUILD (small) — Narrative generation for backtests, campaigns, and the journal

- **Backtest runs:** `backtest_runs` carries metrics + `fold_metrics` + `montecarlo_summary` +
  benchmark attribution + per-trade `contributions` JSONB (`V003__runs_trades.sql:9-69`) with zero
  prose anywhere. "Summarize this run / compare these two runs in words" over
  `BacktestResultsPage`/`BacktestComparePage` is additive and verifiable against the numbers.
- **Evolution campaigns:** `reports.py:75-110` already assembles a structured `CampaignReport`
  incl. the graveyard ("why each candidate was retired") — an LLM rendering of it gives the
  Proposals inbox / Leaderboard a "why is this ranked here" paragraph.
- **Journal:** `AutoJournalListener.java:37` already auto-drafts an entry on `PaperPositionClosed`
  — a template stub where a generated trade narrative (linked signal, dots, exit reason, session
  context) slots in directly as a **draft** (AUTO tier; owner edits/accepts). `journal_entries`
  free-text + tags (`V008:7-21`) also feed 2.4's corpus.
- **News/announcements:** `EquityNewsService.java:34-42` + `AnnouncementService.java:36-45` are the
  only unstructured text already flowing in, currently display-only — summarization/eventtagging is
  a natural, low-stakes add.

### 2.6 BUILD (guarded) — Semi-automate the session-forensics loop

**What exists:** the post-market forensics is agent-run per skill; the deterministic 80% (SQL
passes, counterfactual resolution, rollup-row maintenance) is re-derived by the agent every run.
**What AI adds (and subtracts):** move the deterministic passes into scheduled code (the repo
already has the canary/scheduler pattern), keep the agent for what it is actually good at —
anomaly narrative, cross-session synthesis, proposal ranking. A scheduled agent run (Claude Code
Routine, or Managed Agents later) consumes the pre-computed pass outputs via artha-mcp (2.3) and
writes the findings doc. The skill's epistemics (INCONCLUSIVE first-class, zero-rejections ≠ FAIL,
propose-never-arm) become the agent's system prompt — they already exist as text.

### 2.7 DEFER — LLM hypothesis-generation front end for the evolution engine

`suggesters.py:1-24` (E5) is three hardcoded heuristics that emit `REVIEW_GATE` proposals into the
owner inbox and never invent indicator math. An LLM suggester reading rejection forensics + session
findings and emitting *the same proposal shape* into *the same inbox* is architecturally clean
(emit-only, owner-approved, graveyard-suppressed) and probably the highest-ceiling item here — but
it sits closest to the §0 line and the R2b generator plan
(`2026-07-12-minute-research-system-design.md` P2b) already claims this ground with a deterministic
beam/mutation design whose arming is an owner call. **Unblock:** owner decision on R2b sequencing;
build the LLM variant as one more suggester behind the same inbox, or not at all.

### 2.8 DEFER — Natural-language Telegram interface

`telegram/TelegramCommandBot.java` + the command audit table (`V019__bot_commands_audit.sql`) is an
existing bidirectional conversational channel — an NL layer ("how did the books do today?") would
attach with no new channel infra. Deferred because it is a remote command surface on a
trading system: needs its own threat-model pass (prompt injection via message content → command
execution) and adds token spend on a phone-convenience feature. **Unblock:** owner demand + a
read-only-tools-only design (queries yes, `/act` no).

### 2.9 DEFER — Machine learning proper (learned scoring, near-miss ranking, regime models)

The rejection/outcome telemetry is quietly building a labeled dataset: `signal_rejections` (with
signed `blocking_margin`), `composite_rejections` (~192 rows/session with full breakdowns),
`signal_eval_outcomes`, paper-trade outcomes, and `insight_feedback` (Useful/Not-useful,
`V032:61`). Candidate models: near-miss ranking ("which rejections would have been winners"),
rail-interaction analysis, chop-day/regime classification (G15 shipped a deterministic regime
label — a labeled target). All of it stays **DEFER per §0's own revisit clause**: ≥6 months of
`insight_feedback` (~2027-01), and the entry-gate verdict of 2026-07-30 ("every measured loosening
lost money") is a warning that this data currently supports *tightening*, not clever new scoring.
Related micro-item: Optuna fANOVA importance needs sklearn and was **deliberately declined**
(`insights.py:1-25`) — respect that unless the owner reopens it.

### 2.10 SKIP

- **LLM inside the optimizer search loop** — Optuna + the statistical gates are strictly better
  and explainable; an LLM adds nothing to the ask/tell cycle.
- **Any AI in the live entry/exit/money path** (scoring, gates, brackets, sizing, order routing) —
  violates boundary 1; the exit doctrine (#694) and capital governors (#1086) are exactly the
  places where "creative" behavior is catastrophic.
- **NL→YAML strategy authoring as auto-publish** — an NL→draft-config assistant on
  `StrategyBuilderPage` validated against `strategy-schema-v1.json` is fine *as a PROPOSE-tier
  draft generator* (fold into 2.5-class work if wanted), but anything that publishes is out:
  publish/arm is MANUAL-tier by the INT design.

## 3. Cross-cutting implementation notes

- **Model/API:** Anthropic API; default `claude-opus-5` (adaptive thinking, structured outputs for
  SQL/JSON shapes; Java SDK in services, Python SDK in optimizer-side tooling). Narrative surfaces
  can drop to a cheaper tier later — measure first.
- **Where the code lives:** prefer thin additions inside existing services (edge-gateway endpoint,
  optimizer FastAPI route) over a new `ai-service` — a new service needs a CI matrix shard
  (CLAUDE.md) and compose wiring; don't pay that until ≥2 features share real infra. The API key
  is a new secret in `.env` (live profile only; mock stack returns canned prose or 501, same
  pattern as `unpriced` margin).
- **Egress:** the platform is loopback-only by design; calls to `api.anthropic.com` are its first
  outbound AI dependency. Fail-soft everywhere (timeouts → feature degrades to "no narrative",
  never blocks the page or the engine) — same doctrine as tick-freshness: display can degrade,
  decisions cannot.
- **Grounding contract (applies to every BUILD item):** the LLM receives structured rows and may
  only narrate them; every output carries the evidence pointers of its inputs; numeric values are
  interpolated from the data, not generated. This is the repo's `Evidence` pattern extended to
  prose, and it is the direct answer to the INT design's "unexplainable at the evidence-pointer
  level" objection.

## 4. Verdict

**BUILD** (suggested order): 2.1 NL→SQL console → 2.3 artha-mcp → 2.2 explain-this →
2.5 narratives (backtest/journal/news) → 2.4 RAG-lite → 2.6 forensics semi-automation.
**DEFER:** 2.7 (R2b sequencing, owner), 2.8 (threat model + owner demand), 2.9 (per §0's own
revisit clause, ~2027-01).
**SKIP:** anything touching scoring/parity/money paths, LLM-in-the-search-loop.

Every BUILD item is display-or-draft tier, off the parity path, grounded in existing structured
data, and individually small (1–3 PRs). None requires reopening the INT §0 decision — they operate
in the space that decision explicitly did not close.

**Claims labeling:** file:line citations above are *computed/sourced* from this session's recon;
the ranking and phasing are *judgment*; nothing here is recalled-without-verification.
