---
name: comprehensive-audit
description: Use to run a full 360° platform + codebase audit of ArthaYantra — Fable orchestrates a tiered, sharded Codex-Sol convergence loop (read-only), validates every finding against code + runtime, red-teams the result, and writes ONE dual-signed audit to docs/audits/. Owner-triggered; review + plan only, never a code change.
---

# comprehensive-audit

A full-platform audit run as a **tiered, sharded convergence loop** between the Architect
(Fable, orchestrator) and **Codex Sol** (the opposite-vendor analyst, read-only). Sol analyses
each shard; the Architect validates every finding against real code + real runtime evidence;
they iterate per shard until they agree; a fresh Sol thread red-teams the finished doc; the
output is ONE dual-signed audit in `docs/audits/`.

**This produces a PROPOSAL, not a gate.** `codex-ask`-class analysis is advisory (ROUTING.md) —
every accepted item still ships through the normal pipeline (its own review + Architect audit +
owner approval for money/arming/HOLD). The audit tells you *what* to consider, never *what to
trade*.

## Non-goals & safety rails (hard)

- **No product-code change, no commit, no deploy.** The run writes exactly two files in the repo
  working tree, both under `docs/audits/` (the audit doc + the perf-baselines file). Gitignored
  `state/` artifacts (shard threads, the context pack) and scratchpad screenshots are the only
  other writes.
- **Runtime access is read-only.** Read the DB, logs, and metrics; drive the UI/API to observe.
  **Never** place a trade, move money, mutate a live position / paper book, run a migration, or
  write to any live store.
- **Strategy scope = methodology rigor, not positions.** Critique walk-forward honesty, deflated-
  Sharpe correctness, overfit exposure, implementation fidelity. **Never** recommend what to buy,
  sell, or trade — that boundary is absolute (and matches the no-personalised-investment-advice
  rule).
- **Phase C is clock-guarded** (below) — the heavy build must not run during market hours on the
  live box.

## Owner steering (baked defaults — overridable per run)

- **Priority order** (P-level tie-break, highest first): **1. Reliability & live-trading readiness
  · 2. oipulse parity + features · 3. Performance & responsiveness · 4. UX / ease-of-use.**
- **Runtime access:** live DB + logs + metrics, read-only (`artha` / redis db0).
- **Functional testing:** BOTH — mock stack (`artha_mock` / db1) for deterministic e2e/backtest/
  routes, live stack for real-data spot-checks. Switch profiles only via `ay.ps1`.
- **Strategy metric:** forward-paper realism, not backtest CAGR; flag overfit.
- **oipulse:** feature gap-matrix from `docs/oipulse-study/` (53 pages) as the spine; emit a
  shortlist of built-but-may-diverge pages for an optional owner-run live check.
- **Horizon:** P0 = buildable now by owner + agents; P2/P3 = north-star.

## Shards & tiers (13 shards; ~32 Sol runs vs 60+ flat-deep)

Tiering sets the *starting* depth, never the ceiling — **promotion rule: any Light or Standard
shard that surfaces a validated Critical or Major is promoted to Deep and runs the full loop.**

| Tier | Sol turns (incl. the first pass) | Shards |
|---|---|---|
| **Deep** (unknown-unknowns) | up to 5 | `strategy-logic` (parity+money) · `security` · `data-quality` · `purpose-fit` |
| **Standard** | up to 2 | `architecture` · `ops-resilience` · `meta` (docs↔code drift + test-suite quality) |
| **Light** (prior audits swept these) | 1 pass + validate | `frontend` · `backend-java` · `optimizer-python` · `database` · `external-integrations` · `features-gaps` (+oipulse matrix) |

The two axes matter: the layer shards *tile the code*; the cross-cutting `security` /
`data-quality` / `purpose-fit` (Deep) + `meta` (Standard — promotion bumps it the moment doc-drift
proves Major) shards ask the questions **no single layer
owns** — is it secure, is the data correct (≠ schema correct), is it achieving its purpose, are
the docs even true. `purpose-fit` = is the platform doing what it exists for (options scalping +
oipulse replication; does the edge survive forward testing). `meta` exists because we have direct
evidence docs go quietly false — the engine-liveness detector doc was invalidated at its core
premise *after* owner approval.

---

## Phase A — recon + queue reconciliation → context pack

Read-only, light, runnable anytime. Produce a **context pack** at
`.claude/skills/comprehensive-audit/state/context-pack.md` — gitignored, in-repo (so Sol reads it
in-sandbox by path), and it survives across sessions (a scratchpad copy dies with the session; the
audit spans several). Every shard's `{{EXTRA_PROMPT}}` references it by path.

1. **Load context:** `CLAUDE.md`, `README.md`, `PHASE_GATES.md` (parking list), `docs/design/`
   (frozen authority), the nine `docs/superpowers/plans/*.md` (master-plan §17/§18 first), and
   `docs/README.md`.
2. **Reconcile the queue (load-bearing).** The anti-re-flag defence rests on
   `docs/superpowers/plans/2026-07-02-remaining-items.md §0` being authoritative — and
   "almost authoritative" is exactly what produced 4 wrong chip descriptions last wave. Spawn
   **Opus subagents** (`Agent`, `model:"opus"`) to verify every non-`DONE` row against actual
   code / git / DB; audit their receipts yourself; write the corrected closure state into the
   context pack. (Costs a handful of subagent runs — worth it; it gates the whole audit's premise.)
3. **Bake the anti-re-flag + counterintuitive lists into the pack:**
   - **Five prior audits already closed for startables — do NOT re-raise (flag only proven
     regressions):** full-codebase (`docs/audits/2026-07-05-full-codebase-audit.md`, #407–435,
     #500–508), swing (#628; residual HIGHs H6/H8), app-platform
     (`docs/audits/2026-07-10-app-platform-audit.md`, #686–701), research-fidelity
     (`docs/audits/2026-07-10-research-fidelity-audit.md`), frontend live-audit (#476,
     `docs/audits/2026-07-06-ui-data-correctness/`), plus `docs/strategy-audit/`.
   - **Counterintuitive-by-design (NOT bugs):** muted derived-history OI (Dow+IV → NEUTRAL on
     history); `NIFTY-FUT-CONT` stale by design (continuous future = replay-only; live uses the
     dated front); `armed≈0` backtest trades + `0 signals` on the strict ~30-rail AND gate;
     `status='published'` lowercase; in-container `now()`/`::date` is UTC.
   - **Known-open, owner-gated (give a status row, don't re-propose as new):** #881 EXIT-takeable
     (MONEY); `paper_positions id=28` remediation; chip task_6f1372da (money); F9 paper-risk #576;
     F7 auto-promotion #577; SENSEX-PE drafts; DataTable adoption ×8; task_2560273c; remaining D3.
   - **Engine cold-start (F10):** Part A fixed+live+drill-proven #874; #886 first real detection;
     the detector design doc is INVALIDATED at §3.1 (REST/JDBC-sees-bar-flow premise proven false).
     Health signal is `unresolved==0`, never `loaded>0`. **Regime set FROZEN → revisit 2026-08-14.**
4. **Training repo (`StockMarketStrategyTraining`) is OUT of scope** — all strategies live in this
   repo. No shard wanders there.

---

## Phase B — sharded Codex-Sol analysis (`start.sh`)

Read-only, `gpt-5.6-sol` @ `xhigh` (the `codex-ask`-class default in `_common.sh` — **do not
override `CODEX_MODEL`**). One thread per shard; labels are **date-stamped**
(`audit-<YYYY-MM>-<shard>`) so the next audit never collides with — or silently resumes — this
one's months-old threads.

```bash
# STATE_DIR is a PER-COMMAND prefix on EVERY harness call in EVERY phase — never a one-time
# export. Bash-tool env does not persist across calls; an unprefixed call silently falls back to
# .claude/skills/codex/state and orphans the shard threads.
STATE_DIR=.claude/skills/comprehensive-audit/state \
bash .claude/skills/codex/scripts/start.sh \
    --prompt-file .claude/skills/comprehensive-audit/prompts/shard-brief.tpl \
    audit-<YYYY-MM>-<shard> "<shard scope + context-pack path + this shard's runtime evidence>"
```

- **Every shard runs via `run_in_background`** — each is sol@`xhigh` over platform scope; any can
  blow the Bash 5-min foreground cap. Cap concurrency at **2–3 shards** (mass-parallel xhigh
  invites the at-capacity fallback chain). Existing thread → `start.sh` exits 2 → resume, don't
  re-start.
- **Killed-wrapper salvage** (wrapper died after codex ran — review + events usually survived):
  `jq -r 'select(.type=="thread.started").thread_id' state/<key>.events.ndjson | head -1 > state/<key>.thread`,
  then resume normally.
- **Arg-length budget:** the whole prompt passes as ONE argv (Windows ~32K cap; the template is
  ~4K). Keep the inline `EXTRA_PROMPT` slice ≤ ~24K chars — big content (context pack, EXPLAIN
  dumps) goes into `state/` files referenced by path, never inline.

`shard-brief.tpl` bakes the invariants, the claim/evidence contract, and the root-cause rule into
every shard identically; `{{EXTRA_PROMPT}}` carries the per-shard scope + the context-pack path +
runtime evidence. Feed Sol the **runtime evidence up front** (Phase C numbers, or the read-only
DB/metric reads) so it reasons over real data, not source guesses.

---

## Phase C — Fable functional testing (clock-guarded) + perf baselines

Behavioural bugs are the highest-value output and Sol structurally cannot produce them (read-only
sandbox, no app). Fable drives the app per `run-artha-yantra`; **mock stack for the destructive/
deterministic work, live stack for read-only spot-checks only.**

**Clock guard — run this before the heavy work; it refuses market hours on the live box** (Timescale
has OOM'd twice; a full Maven reactor + e2e + mock Docker stack alongside the live armed engine can
degrade it):

```bash
# IST = UTC+5:30 computed ARITHMETICALLY — this box's Git Bash has no tzdata, so TZ=Asia/Kolkata
# silently returns UTC (same trap class as ::date=CURRENT_DATE in CLAUDE.md). Use python for IST elsewhere.
umin=$(( 10#$(date -u +%H)*60 + 10#$(date -u +%M) )); ist=$(( (umin+330)%1440 )); dow=$(date -u +%u)
if [ "$dow" -le 5 ] && [ "$ist" -ge 540 ] && [ "$ist" -le 945 ] && [ "${AUDIT_FORCE_PHASE_C:-0}" != 1 ]; then
  echo "REFUSE Phase C: $((ist/60)):$(printf %02d $((ist%60))) IST is inside 09:00–15:45 weekday. Override: AUDIT_FORCE_PHASE_C=1"; exit 1
fi
```

Even off-guard, pick the window: the live box also runs the 20:05 swing batch, 21:15 paper
reconcilers, and 08:30–08:45 canaries + ~08:40 roll re-resolve (all IST) — prefer **22:00–07:00
IST or a weekend** so the heavy build never contends with them. The hard guard stays market-hours
only.

Then: login; exercise every top-level route; verify the `{items:[...]}` list-envelope contracts;
run a **mock backtest end-to-end**; run the **e2e suite** + each service's tests; **axe + a 480px
mobile viewport** a11y sweep. Capture every bug with exact repro + screenshot.

**Perf baselines (required numbers, not adjectives)** — measure and **persist to
`docs/audits/baselines.md`** as a new dated ROW per audit (metrics as columns; the FIRST run
creates the file) so future audits trend it (regression is a finding class): tick→signal latency, query p95 (`EXPLAIN ANALYZE` the heavy candle/OI/signal
queries + chunk/compression/index-hit stats), page load + bundle/chunk sizes + web-vitals, backtest
throughput, and container memory headroom (`docker stats`; Timescale OOM'd at 1 GB — headroom is
real). Read-only runtime taps: `/actuator/health` + `/actuator/prometheus`, the health endpoints
(`/api/v1/market/health/data`, `/signal-rejections/dot-health`, `/api/v1/market/health/ingest`),
`docker logs ay-<svc>` for OOM/GC history, `docker exec ay-<svc> sh -c 'kill -3 1'` to thread-dump
a stall.

---

## Phase D — validate + converge per shard, then merge

1. **Validate (Fable, every finding).** Verify against code + runtime yourself (Explore/grep/read;
   reproduce; re-run the query). Tag: `CONFIRMED` (file:line/repro/query+result) · `REFUTED`
   (counter-evidence) · `STALE/ALREADY-DONE` (cite PR) · `DUPLICATE-OF-PLANNED` (cite doc) ·
   `NEEDS-VERIFICATION` (state EXACTLY what evidence would settle it). Add what Sol missed. Never
   accept a claim on Sol's word — stale/hallucinated file:line is the #1 failure mode.
2. **Converge (`resume.sh` on the same thread).** Push the per-point ledger back; Sol concedes or
   defends with evidence; re-validate any new claim. Deep = up to 5 rounds, Standard = 2, Light =
   single pass (Fable validation only, no loop). **Never concede merely to end the loop — Sol being
   right is a success.** Unresolvable points keep BOTH positions and go to the owner-decision list.

```bash
STATE_DIR=.claude/skills/comprehensive-audit/state \
bash .claude/skills/codex/scripts/resume.sh \
    --prompt-file .claude/skills/comprehensive-audit/prompts/converge.tpl \
    --notes "<your CONFIRMED/REFUTED/STALE evidence>" \
    audit-<YYYY-MM>-<shard> "<point-by-point verdicts + counter-proposals>"
```

3. **Cross-domain merge pass (before Phase E).** The 13 shards ran on separate threads, so a
   single-thread review's free consistency is NOT free here — reconcile the union: dedup findings
   that surfaced in two shards, resolve contradictory verdicts, and make severities consistent
   across the whole doc.

---

## Phase E — red-team, then stamp → one doc

1. **Red-team (mandatory).** A FRESH Sol thread attacks the finished doc's *conclusions* (not the
   code) — the only defence against consensus-by-fatigue a round-cap can't catch. Downgrade / pull /
   re-evidence whatever it lands.
   ```bash
   STATE_DIR=.claude/skills/comprehensive-audit/state \
   bash .claude/skills/codex/scripts/start.sh \
       --prompt-file .claude/skills/comprehensive-audit/prompts/redteam.tpl \
       audit-<YYYY-MM>-redteam "path: docs/audits/<date>-comprehensive-audit.md — attack the conclusions"
   ```
   For any **Deep money/parity Critical**, additionally run `adversarial-review` (Opus, lens-diverse,
   REFUTE-framed) on that finding before it reaches the owner-decision list — our proven routine,
   and a true cross-vendor attack on a Sol-co-authored claim.
2. **Consistency stamp.** `codex-plan-review` on the finished doc for a formal `APPROVED` (confirms
   the written doc matches what you converged on). That skill sets its OWN `STATE_DIR`; its plan
   template is deliberately repurposed here as a consistency stamp on a non-plan doc.

---

## The output doc — `docs/audits/<date>-comprehensive-audit.md`

Open with an **executive summary** (≤1 page — the handful of changes that matter most, in the owner
priority order) so the owner can act without reading the ledger. **Discipline:** every row names the
**root cause**, not the symptom; no generic advice untied to a concrete file:line/repro/query.

Each finding row: **id · shard · category · severity** (P0–P3, ties broken by the owner priority
order) · **evidence** (no evidence, no row) · **proposed change + effort (S/M/L) + horizon** ·
**status** (novel / already-planned `[doc]` / parked `[PHASE_GATES]` / owner-gated / regressed-from-
audit `[audit]`) · **dual-sign** `Fable ✓ + <model> ✓` (a row without both is not converged). Sign
with the model that ACTUALLY served the round — the harness echoes it; a capacity-fallback round
signs `luna ✓`, not `Sol ✓`. Light-tier rows carry Sol's sign as-authored **only if unmodified**;
a Fable-edited surviving row gets one ratify-resume on the shard thread, else ships marked
`Fable-only`.

Then: a **prioritised roadmap** (P0→P3, grouped by theme, each P0/P1 self-contained enough to become
a builder brief or chip); the **oipulse gap-matrix** + live-check shortlist; an **"already covered —
do not re-propose"** appendix; the updated **perf baselines** pointer; an **owner-decision list**
(money / arming / HOLD / any invariant change); a **needs-verification** section (each with the exact
missing evidence); and **unresolved disagreements** (both positions). Report back to the owner with
the path, counts by severity + status, top P0/P1 in priority order, and the owner-decision list.

## Harness / cost / shipping notes

- Model from `_common.sh` (sol/xhigh), read-only sandbox from `start.sh`'s default — the skill
  overrides neither. At-capacity / codex-down fallback: `.claude/skills/codex/ROUTING.md` (codex
  fully down → an Opus subagent is the second voice; record the lost cross-vendor property).
  Killed-wrapper salvage: the jq one-liner in Phase B.
- State (`state/*.thread|review.txt|events.ndjson` + the context pack) is gitignored and
  per-shard-label keyed — concurrent shards never collide. Reset a shard with
  `STATE_DIR=.claude/skills/comprehensive-audit/state bash .claude/skills/codex/scripts/reset.sh <label>`.
- Rough cost: ~32 Sol runs tiered vs 60+ flat-deep, savings taken entirely from re-audited ground.
  Phases A/B/D/E are light and runnable anytime; Phase C is the heavy, clock-guarded one.
- **Shipping:** the RUN never commits. After owner sign-off, the audit doc + `baselines.md` go out
  via a normal docs PR (usual trunk flow) — that PR sits outside the run's no-commit rail.
