# AI-orchestrated full E2E application test — plan (E2E-01)

**Owner decision (2026-07-31, ~00:30 IST):** run the orchestrated end-to-end tests **today,
2026-07-31, after market close** — do not wait for more items to be implemented. The split that
was agreed: mock-stack testing may be interactive and heavy; the LIVE stack is read-only during
market hours and gets its E2E pass only post-close; strategy performance is a post-market data
readout, never in-session test traffic.

**Ledger row:** `E2E-01` in `2026-07-02-remaining-items.md` (chips/queue table). Findings doc
lands at `docs/audits/2026-07-31-e2e-orchestrated-findings.md`.

**Orchestration authority:** the owner explicitly requested "AI orchestrated full end to end
application tests" — that is the standing opt-in for multi-agent orchestration for THIS run.
Keep the default size guideline (~15 agents); adversarially verify findings before reporting
(the review pattern that caught 6 real defects this week: every finding gets a refute pass
before it reaches the report).

---

## Timing (all IST, 2026-07-31)

| when | why |
|---|---|
| **NOT before 15:45** | market close; live stack must never see exploratory load in-session |
| **16:20 start** | after the 15:47 post-market analysis routine has run (its findings feed T3) |
| ~20:45 checkpoint | wrap or park T2/T3 SQL before the **21:15 paper reconcilers** window; resume after |
| deliverable same evening | findings doc + chips + ledger flips; owner reads the summary |

**Pre-req before starting:** merge the two open D3 PRs if green (#1138, #1139) so the tested
spec matches main — but do NOT gate the run on them; test what is live. Record the tested SHAs
(live jar fingerprints per `deploy-verify-by-jar-fingerprint`) in the findings doc header.

---

## Track T1 — application E2E (feature gaps + bugs)

⚠️ **SINGLE-STACK REALITY (plan revision, review round 1 + Architect check):** mock and live are
the SAME compose project (`name: arthayantra`, pinned container names) — they CANNOT run
side-by-side, and mock-vs-live is `SPRING_PROFILES_ACTIVE` in `.env`. On a trading evening the
stack IS live (EOD batches, 21:15 reconcilers) and switching it to mock would take live DOWN.
Therefore T1 splits:

- **T1a — TONIGHT, against the live stack, LOOK-ONLY.** Route walk and render checks only: no
  form submits, no action buttons, no publish/disable, no paper open/close, nothing under
  `/data-ops` actions. Page loads trigger the app's normal cache-first tail refresh — the same
  writes any owner browse causes — which is why this waits for post-close and why data-ops
  ACTION buttons stay untouched.
- **T1b — WEEKEND, mock profile, full interactive mutations** (flows in step 3 below). Requires
  the owner (or a weekend run) to switch the stack to mock via `ay`.
- **FAIL-CLOSED PREFLIGHT before ANY mutation** (review finding — the boundary was prose-only;
  round 2 then caught that the first preflight checked variables the container does not EXPOSE —
  `ARTHA_DB_NAME`/`ARTHA_REDIS_DB` are compose-file inputs translated away before the container —
  so it would have failed closed FOREVER, making T1b structurally unrunnable. The variables below
  are the ones `docker inspect` actually shows, verified against the running stack 2026-07-31):
  1. `docker inspect ay-edge-gateway` env shows `SPRING_PROFILES_ACTIVE=mock` AND
     `SPRING_DATA_REDIS_DATABASE=1` (live shows `live` / `0`);
  2. `docker inspect ay-strategy-signal-service` env shows `SPRING_DATASOURCE_URL` containing
     `/artha_mock?` (live shows `/artha?`).
  Any mismatch, or any doubt → the mutation flows DO NOT RUN. Passing this preflight is the ONLY
  thing that authorizes step 1 and step 3.

1. **Baseline (T1b only, after the preflight):** run the existing Playwright suite (`e2e/`, `E2E_OWNER_PASSWORD=...`) — it must
   be green before exploratory work, else fix-or-file first.
2. **Full route walk (T1a tonight, look-only; repeated interactively in T1b):** every SPA route (enumerate from `frontend-react` router config, not from
   memory). Per page: renders without console errors; primary data loads (no permanent skeleton);
   empty-state vs populated-state both reachable; mobile viewport (~480px, S24 target) has no
   horizontal scroll; obvious a11y (axe pass on the 10 highest-traffic pages).
3. **Form/flow probes (T1b only — gated by the preflight above):** strategy create→draft→publish→disable; backtest submit→job→results→
   export; watchlist/journal CRUD; paper manual open/close; data-ops console actions (mock).
   Each flow: happy path + one invalid-input path (expect 4xx surfaced in UI, not a silent fail).
4. **API surface probe (T1b only, mock):** for every path in the four `contracts/*.openapi.json`
   specs, one GET (or safe POST with `{}` where documented) against the MOCK gateway — assert status is in the
   spec, response parses, and REQUIRED keys are present. This is the cheap spec-vs-server sweep
   the ratchet work enabled; Map-return endpoints (the 6 assessed stops) are exempt from key
   assertions.
5. **Feature-gap diff:** master-plan §20 (oipulse replication map) + §17/§18 vs what the route
   walk actually found. Output = a gap list with a BUILD/DEFER verdict each, not silent absence.

## Track T2 — live stack read-only integrity (post-close only)

Environment: live (`artha` / redis-0). SELECTs, `docker logs`, in-container `wget` GETs only.
Hard rules: no restarts, no writes, no `.env` reads printed, no Kite/Upstox direct calls.

1. **Health sweep:** `/api/v1/market/health/data`, `/health/ingest`, `/signal-rejections/dot-health`,
   actuator per-service (8081/8082), heartbeat/dead-man state, canary logs for the day.
2. **Contract-vs-runtime nullability sampler** (deferred here from the 07-31 routine work, where
   it was judged too heavy for a daily routine): sample read endpoints via in-container wget and
   diff observed-null fields against the spec's nullable sets in `contracts/*.openapi.json`.
   ⚠️ **GET does NOT mean read-only on this platform** (review finding): cache-first candle GETs
   re-fetch from Kite and perform authoritative UPSERTS. The sampler uses ONLY this explicit
   side-effect-free allowlist — anything not on it belongs in T1b against mock:
   `/actuator/health|info|prometheus` (per-service ports) · `/api/v1/market/health/*` ·
   `/api/v1/signal-rejections*` (incl. `/dot-health`) · `/api/v1/signals*` (list/active/detail) ·
   `/api/v1/paper/positions|trades|pnl` · `/api/v1/strategies` (list/detail — GET only) ·
   `/api/v1/insights*` · `/api/v1/backtests/*/results|trades` (DB reads keyed by run id).
   **EXCLUDED by name:** `/api/v1/market/candles` (cache-first write-through), everything under
   `/data-ops`, `/api/v1/market/margin` (posts a basket to Upstox), any POST/PUT/DELETE. A field observed null but published
   non-nullable is exactly the OpeningSignal/WorldIndex defect class review caught 4× this week —
   this makes it mechanical. Report per-endpoint: keys seen, nulls seen, spec disagreements.
3. **Cross-source data integrity SQL:** candle coverage vs snapshots (per root, per day, last 5
   sessions); `candles`@1d dense vs `candles_1d` cagg sparse divergence spot-check; ingest_runs
   coverage vs the trading calendar; OI capture density (NFO option 1m sparse-after-07-06 watch);
   signals↔paper_positions↔paper_orders referential spot-joins (orphans in either direction).
4. **Config truth:** `tools/published-config-drift.py` (should be clean after the 07-28 wave;
   any drift is a finding), `ay verify-deploy` jar fingerprints vs merged main.

## Track T3 — strategy performance readout (measurement, not tuning)

Data source: live DB paper books + the week's findings docs. NO knob changes, NO republishing —
output is verdicts + owner decisions, per the four-loosenings prior (T1/T7/G13/G10 all lost money).

1. Per book (3 Manas ₹1.5L books, Minervini, scalper sub-accounts): inception-to-date and
   last-5-session P&L, open exposure, win rate, per-close_reason attribution — deduped by
   `(bar_time, tradingsymbol)` per §3.24.
2. §3.29 unexercised-path table (armed-vs-fired exits) + §3.30 freeze telemetry across the week —
   both now standing routine outputs; the E2E run aggregates the week.
3. Counterfactual pipeline (§3.26) on the week's rejection classes that the shadow book skips;
   convert every pass-rate delta to LEGS then P&L before quoting it.
4. G15 regime labels for the week's sessions; performance split by regime (the G11 question needs
   exactly one post-07-27 chop day — flag if this week produced one).
5. Verdict table per strategy family: sample size, edge sign, data-fidelity caveats (derived-OI
   MUTED on history; judge OI-led strategies on forward paper only).

## Track T4 — gap hunt vs plans (docs truth)

1. `tools/ledger-consistency-check.py` (should be quiet; anything it flags gets resolved in the
   findings PR).
2. Diff the shipped surface against `2026-07-02-remaining-items.md` open rows: any row whose
   feature the T1 walk shows ALREADY exists (the §9-03 class, but for features) → close with
   evidence; any T1 gap with no ledger row → new row or chip.

---

## Orchestration shape

- One orchestrator session (post-close), fanning out per track; T1 and T2 are independent, T3
  depends on the 15:47 routine's findings doc, T4 depends on T1's route walk.
- Finder→verifier pattern per track: every candidate finding gets an independent refute pass
  before it reaches the report (a finding that survives refutation carries its evidence trail).
- Findings severity: LIVE-defect > contract-lie > feature-gap > docs-drift. LIVE defects follow
  the hotfix decision tree (snapshot evidence first); nothing gets fixed mid-run except by that
  tree — the run REPORTS, the fixes are separate PRs.
- Budget: default size guideline (~15 agents). If the route walk alone exceeds it, sample by
  page-traffic priority rather than raising the cap without the owner.

## Abort / park conditions

- Live stack unhealthy at start (canaries red, heartbeat stale) → park T2, run T1/T3/T4, report
  the health failure as finding #1.
- Any T2 probe that turns out to be non-read-only → stop that probe, record it, continue. The
  allowlist above is the authority; discovering side effects mid-probe means the ALLOWLIST was
  wrong, and that itself is a finding.
- 21:15–21:35 IST: pause T2/T3 SQL against live (reconciler window).

## Deliverables

1. `docs/audits/2026-07-31-e2e-orchestrated-findings.md` — findings with evidence + severity +
   verified-by-refutation status; tested SHAs in the header.
2. Chips for every actionable finding; ledger rows closed/opened per T4.
3. `E2E-01` ledger row flipped with the findings-doc link and the one-paragraph verdict.
4. Owner summary: counts by severity, the strategy-performance verdict table, and the top-3
   decisions the data now supports.
