# 2026-08-02 — M40 correction: the reachable gap is FRESH entries, not the pyramid-add path

Cross-vendor review finding on PR #1214 (`fix/e4-m40-governor-and-vcp-tripwire`). Recorded here per
the ledger's own convention (each E4 sub-item gets a citable doc); the E4 row itself stays OWNER —
**this does not close M40, and nothing here is built or enforced.** Every material claim is tagged
**[computed]** (derived from a file/line read on this checkout) or **[sourced]** (quoted/paraphrased
from a cited doc).

## What PR #1214 actually fixed vs. what M40 asked for

**[sourced]** The decision sheet's own premise (`2026-08-01-e4-e8-decision-sheet.md:405-412`) is
narrower than either of us initially scoped from: *"Manas swing has no live circuit breaker… A real
Manas circuit breaker would need a different metric entirely — e.g. % of book capital deployed, or a
daily-loss-limit analog to the scalper's 3% — which is a small design-plus-build task, not a config
change."* It is about the missing EQUITY-appropriate circuit-breaker mechanism in general (the
existing `heat_cap_pct` rail is SPAN-margin-only and inert for cash-equity books) — it does **not**
say anything about the pyramid-add path specifically, and it does **not** say the gap is an
audit/ntfy coverage hole.

**[computed]** PR #1214's narrower framing — "the Manas §3.4.3 portfolio-open-risk-cap check on a
pyramid ADD doesn't get the same `risk_audit`/ntfy treatment three of `RiskService`'s four audited
rails get (daily-loss/profit-target/heat-cap; deployment audits only, no alert —
`RiskService.java:188`)" — was **my own scoping choice** when translating that decision-sheet
premise into a buildable, clean-tier task, not a claim the decision sheet itself makes. Under that
(self-chosen) framing, PR #1214 does fix a real, previously-uncovered **observability gap on the
pyramid-add path**
(`EmissionGuard.recordPyramidRiskCapBreach` → `RiskService.recordPyramidRiskCapBreach`), unreachable
in production today because `artha.manas-arora.pyramid.enabled=false` (default).

**What the decision sheet and PR #1214 both missed: that pyramid-add path is not where the doctrine's
aggregate-risk cap is actually exposed.** The doctrine's cap governs **all** open positions, not just
pyramided ones, and the code's own comment says so out loud —
`SwingBatchEngine.java:495-496` (pre-existing, unchanged by this PR until the correction below):

> `// §3.4.3: an ADD only goes on if the book's aggregate open risk stays within the portfolio cap. A
> fresh (first) entry is unbounded here — the ordinary book governor already bounds it.`

**[computed]** That second sentence is false as a safety claim. The "ordinary book governor"
(`max_deployment_pct`, `daily_loss_limit` — seeded in `V021__paper_books.sql:60-61,66-67`, both
`enabled: true` for `manas-arora`) bounds **capital deployed** or **day P&L**, never **aggregate open
risk** (Σ qty × stop-distance). Those are different quantities: deploying 80% of capital into
low-stop-distance positions can carry far less than 80% risk, and nothing in `RiskService.entryVeto`
computes or caps Σ risk across concurrent **fresh** entries — only `ManasPyramidPolicy.wouldBreachRiskCap`
computes that quantity, and it is only ever consulted for an **ADD** (`SwingBatchEngine.java:497`,
guarded by `isAdd`).

## The gap is reachable today, at current config, with pyramiding OFF

**[sourced]** Doctrine (`strategy-documents/manas-arora-operative/MomentumTradingManasArora_Consolidated_Strategy.md`):
- §2.2 (line 73): *"Max open (portfolio) risk at any time = 5–6%. Never expose more than 5–6% of the
  portfolio to risk across all open positions combined."* — an aggregate cap across **all** positions,
  not just pyramided ones.
- §3.2 rule 4 (line 139), the breakout setup's entry rule: *"If multiple qualifying names trigger the
  same day, bid on all high-probability ones and take them in the order they trigger **until open-risk
  cap (5–6%) is reached**; cancel the rest."* — the doctrine explicitly expects entry admission itself
  to stop at the cap, not just pyramid adds.

**[computed]** Both live Manas strategies size at 1% risk per position:
- `manas-arora-breakout.yaml`: `position_sizing: { … risk_pct_equity: 1.0 }` (line 54), `max_positions: 7` (line 55).
- `manas-arora-vcp.yaml`: `position_sizing: { … risk_pct_equity: 1.0 }` (line 52), `max_positions: 7` (line 53).

Each YAML's own `max_positions: 7` is a per-strategy config field of uncertain enforcement (a
repo-wide grep for `max_positions` in `strategy-signal-service`'s Java found no consumer). The
concurrency ceiling that IS actually enforced sits one level up, book-wide, in
`RiskService`/`RiskService.MAX_OPEN`: `deploy/flyway/strategy/V021__paper_books.sql:65` seeds
**`('manas-arora', 'max_open_paper_positions', '{"enabled": true, "value": 7}')`** — a single cap
shared by BOTH strategies, since they share one `Books.MANAS_ARORA` book key
(`services/strategy-signal-service/.../signals/Books.java:16`), checked in `RiskService.entryVeto`
(`positions.openCount(book) >= 7` blocks a new entry). Either reading lands on the same number: **7
concurrent names × 1% risk each = 7% aggregate open risk**, above the doctrine's 5–6% cap, reachable
with **pyramiding disabled** — the exact live config today.

## The calculator already exists — it's just not wired to the fresh-entry path

**[computed]** `ManasPyramidPolicy.wouldBreachRiskCap` (`ManasPyramidPolicy.java:69-99`) and its pure
helper `breachesRiskCap` (`:137-157`) already compute exactly "would opening a new position at qty/stop
push Σ existing-risk + new-risk over `capPct`?" — using `guard.openRiskInr(book)` (current aggregate
risk across ALL open positions, not pyramid-specific — `PaperEmissionGuard.openRiskInr`,
`services/strategy-signal-service/.../paper/PaperEmissionGuard.java:72-92`) and `guard.bookEquity(book)`.
Nothing about this math is pyramid-specific; it is only ever **called** from the `isAdd` branch.
Extending it to gate the fresh-entry path (removing the `isAdd` guard, or adding an equivalent check
before `emitEntry` for a first-time entry) is a small, mechanically simple change.

## Why this is NOT built in PR #1214, and why it should not be

**Enforcing the aggregate cap on fresh entries would refuse entries the live engine currently accepts.**
That is a change to live P&L behaviour — the same class of decision as M36/M37 (per the decision
sheet, both explicitly deferred pending their own backtest A/B) — not a coverage/observability fix.
PR #1214's brief was explicit: no threshold or governor-limit changes, and "if the fix requires
changing a limit, stop and report instead." Gating fresh entries on the aggregate cap does not change
a *limit value*, but it does change *what gets admitted* — the same owner-decision bar M36/M37 sit
behind. It is correctly **HOLD-tier**, not clean, and is not attempted here.

## Verdict

- **M40 stays OWNER in the ledger.** PR #1214 closes a real but narrow add-path observability gap;
  it does **not** close M40's aggregate-risk-cap finding.
- **The residual, reachable gap:** fresh Manas entries are not bounded by the doctrine's 5–6%
  aggregate open-risk cap; reachable today (7 names × 1% each, pyramiding OFF or ON) with the
  calculator (`ManasPyramidPolicy.breachesRiskCap`) already available to reuse.
- **Recommendation:** commission a fresh-entry aggregate-risk-cap change as its own HOLD-tier item
  (same evidence-first discipline as M36/M37 — a deploy-free admission-count check first, then a
  backtest A/B if the answer isn't obviously safe), not bundled into this PR.

## Open doubts

1. I did not exhaustively confirm the YAML `max_positions: 7` field is UNUSED (only a targeted grep
   for the literal string in `strategy-signal-service`'s Java found no consumer) — it's possible it is
   read somewhere I didn't find (e.g. deserialized into a config object but never branched on, or
   consumed by a funnel/screener I didn't trace). Either way the book-wide `max_open_paper_positions=7`
   governor cap is independently confirmed and enforced (`RiskService.entryVeto`'s `MAX_OPEN` rail), so
   the 7-name/7%-risk reachability conclusion does not depend on resolving this.
2. I did not measure how often 5+ concurrent Manas positions are actually held in practice (live or
   paper) — this doc establishes the gap is *reachable by config*, not that it has *already fired*.
