# AI-orchestrated full E2E application test — findings (E2E-01)

**Run:** 2026-07-31, 16:21–18:00 IST (post-close, per the plan's timing table).
**Plan (authority):** [`2026-07-31-orchestrated-e2e-test-plan.md`](../superpowers/plans/2026-07-31-orchestrated-e2e-test-plan.md).
**Orchestration:** 18 agents (7 recon finders · 3 sequential browser route-walk batches · 1 gap-diff · 7 adversarial verifiers), 867 tool calls, ~2.23M subagent tokens. Every non-info finding got an independent refute pass before entering this report; 3 candidate findings were REFUTED and are recorded as such (§6).

## Tested SHAs

| surface | SHA | note |
|---|---|---|
| repo `main` | `65354e9c` | clean checkout, pulled at run start |
| ay-edge-gateway jar | `b57edddc` (2026-07-20) | oldest running jar; all misses verified spec/test/docs-only (§5) |
| ay-market-data-service jar | `4db9b1c4` (2026-07-29) | |
| ay-strategy-signal-service jar | `f6ba4dab` (2026-07-30) | |
| ay-backtest-service jar | `54f3bb4b` (2026-07-29) | |
| profile | `live` / db `artha` / redis-0 | confirmed via `docker inspect` env before any probe |

**Scope tonight:** T1a (look-only route walk of the LIVE SPA — 85 static routes + 3 param samples), T2 (live read-only integrity), T3 (strategy readout), T4 (docs truth). **T1b (mock-profile interactive mutations + API-surface probe) is a WEEKEND item** per the plan's single-stack reality — it did not run and stays open on the E2E-01 row.

## 0 Verdict counts (post-verification)

| severity | confirmed | refuted | notes |
|---|---|---|---|
| LIVE-defect | **2** | 2 | §1 |
| contract-lie | **0** | — | nullability sampler: zero disagreements across 14 endpoints (§4.1) |
| feature-gap | **7** | 1 | §2 (3 of the 7 verdict DEFER) |
| docs-drift | **12** | — | §3 (mechanical ones fixed in this PR) |
| info / observations | ~25 | — | §4.3 minor-UI list + operational notes |

## 1 LIVE defects (CONFIRMED)

### 1.1 Corporate-action rebuild FAILED for CHEVIOT + ULTRACEMCO — Timescale "tuple decompression limit exceeded"

`docker logs ay-market-data-service` 2026-07-30 11:30:47Z / 11:52:37Z: ERROR "corporate-action rebuild failed for CHEVIOT" (and ULTRACEMCO) — `CALL public.refresh_continuous_aggregate('candles_5m', '2015-01-04'..'2015-04-14')` aborts with `ERROR: tuple decompression limit exceeded by operation`. This is a **new** Timescale failure shape (not the known 2.18.2 sorted-merge planner bug): the full-history cagg refresh over compressed 2015 chunks trips the decompression-limit GUC. Risk: base `candles` rows may be CA-adjusted while the 5m/15m/1h/1d/1w caggs were NOT refreshed — split-brain between base and aggregates for both symbols (both swing-universe equities). Chip filed (fix = chunked refresh windows or `SET timescaledb.max_tuples_decompressed_per_dml_transaction = 0` around the rebuild, then re-run for both symbols and verify base-vs-cagg agreement).

### 1.2 `/multiframe-chart` 4th pane permanently broken for index symbols — hardcoded `30m` interval the candles API rejects

Default load (NSE:NIFTY 50): pane 4 issues `GET /api/v1/market/candles?...&interval=30m` → **400** (twice, console errors), renders "No candles.", and the interval select mis-displays `1m` (30m is not among its options). Source: `MultiframeChartPage.tsx:17` `INDEX_DEFAULT_INTERVALS = ['15m','5m','3m','30m']` vs `api/charts.ts:14` `CHART_INTERVALS` which has no 30m. Bonus: the "indices have no 1m candles" premise behind the 30m default is stale — the same page's 1m fetch returned 200 WITH data this session. Chip filed (fix the default; consider taking audit FG-03 single-symbol parity in the same touch — same file, see §2).

## 2 Feature gaps (CONFIRMED)

1. **Morning canaries never ran today; no boot catch-up exists.** Stack was down 02:29–08:56 IST; the 08:30 notifier-health + 08:45 ingest-coverage crons ticked while down and missed crons never replay (`IngestCoverageCanary.java:55` documents it). The owner's machine routinely boots ~08:56 — this recurs on every late boot. Chip: boot-time run-once-if-missed-today catch-up, mirroring the #1036 pattern.
2. **NFO+BFO option 1m capture breadth collapsed to 22 ATM contracts (7× worse cycle-over-cycle).** Post-weekly-expiry NFO contract counts: 07-15=312 → 07-22=181 → 07-29=26 → 07-31: NFO 22 and BFO 22 (BFO was 279 contracts on 07-30). The 22 survivors are exactly the 08-04 weekly ATM ±5-strike band, each with full 375 bars — breadth loss, not sparsity. `options_chain_snapshots` stayed dense (1.2M+ rows/day); only the WS tick-agg candle path narrowed. Owner decision: is ATM-band-only the intended post-roll subscription set, or widen? Chip filed.
3. **Cockpit Option-chain panel hard-errors every evening.** `chain-table?mode=live` 503s DATA_STALE post-market (`OptionsChainService.java:195` throws when no spot quote) instead of degrading to the last session's chain; header tiles all "—", straddle panel empty. Design call: cockpit falls back to `mode=history` when closed, or live mode serves last captured chain with a stale marker. Chip filed.
4. **Multi Leg Price page (oipulse `/app/strategies/multi-leg-price`) still has no route** — audit FG-05, the last true missing oipulse page. Verdict **DEFER** (P2 unchanged; straddle/strangle + strategy-builder cover most of its value; generalize `StraddleChartService` to N legs if pulled).
5. **Options Chain §20.7.6 fidelity tail never shipped** (2h/4h/custom intervals, IV-Chng + O=H/O=L optional columns, grouped name select, strike-click sub-view) while §20.7.6 claims "nothing dropped". Verdict **DEFER** — display-only tail, zero gate/money impact; the claim itself is corrected via the master-plan chip (§3).
6. **Multiframe chart shares ONE symbol across all four panes** (audit FG-03). Verdict **DEFER**, but it lives in the same file as defect §1.2 — consider same-touch.
7. **§18.6 sentiment% formula reconcile half-done** — exact oipulse level-based formula is display-only while the scalper gate consumes the ΔOI basis. Verdict **DEFER** (recalibrating an entry-gate basis is exactly the class the T1/T7/G13/G10 all-loosenings-lose prior warns against; stays on the owner live-check shortlist).

## 3 Docs drift (CONFIRMED — 12; the mechanical ones are fixed in this PR)

| # | drift | disposition |
|---|---|---|
| 1 | S1/S2 bug-queue mirror rows lead "NEW" though shipped in #1016 @ 28860acd (S3's own cell says S1+S2+S3 one PR; fix is in the running jar) | **fixed in this PR** |
| 2 | task_3a928626 status cites "DONE [#PR](pending)" — actual PR is #1022 @ 77fae804 | **fixed in this PR** |
| 3 | F10 "Part B (detection) — STILL TODO" is stale: detector BUILT dormant as the G2 StrategyCoverageWatchdog (#1035); the cited #868 canary was deleted (#1022) | **fixed in this PR** (folded into G2) |
| 4 | G2 status cell leads "DESIGN DONE" though the build shipped (#1035) and was live-verified dormant | **fixed in this PR** (re-led BUILT-HOLD) |
| 5 | CLAUDE.md still documents the wrong-Content-Type 500 with chip task_9ffe390d open — fixed by #1021, live in all three servlet services | **fixed in this PR** |
| 6 | §0 recipe's §4b awk is BLIND to open chips whose cells contain escaped pipes — task_f10a03 (OPEN) invisible; G15's in-cell pipes false-positive-prone | **fixed in this PR** (recipe note + anchor guidance) |
| 7 | T14 sign-aware margin-invariant carried session-to-session with no ledger row (promotion-rule violation) | **chip row added** (owner: promote or WONT) |
| 8 | 07-31 §3.29 table omits two armed exit paths its own README query returns (`trailing_stop\|atr_multiple`, `stop_loss\|atr_multiple`) | **chip row added** (next §3.29 run adds them + basis-indistinguishability note) |
| 9 | D3 row's edge-gateway "/v3/api-docs now enumerates SystemStatus{...}" is true only of the captured spec — live gateway (b57edddc) predates #1077 | noted here; resolves itself on next edge-gateway deploy (wire identical either way) |
| 10 | master-plan §18.4 "two remaining payload extras" both shipped (`ScalpAlertService.java:141-179`) | **chip row added** (one master-plan stale-status sweep covers 10–12) |
| 11 | master-plan §20 status note lists three "remaining open Phase-4" items all shipped (value-verify, /orders, ManualVerifyChecklist) | same chip |
| 12 | master-plan §20.3 "52 pages, all INCLUDED" overstates — Dashboard dropped, Event Days skipped, Update Logs never dispositioned (rec SKIP), Strangle merged, Multi-Leg open as FG-05 | same chip |

Adjudicated non-drift: the ledger-consistency-check REVIEW[C] (T18/T30 promotion) is a **false positive** — 07-30 doc explicitly says "not a T18 reopen", and T30's row EXISTS (G16, `remaining-items.md:590`); 07-31 findings §6.3 already dispositioned it.

## 4 What came back clean (evidence of health, not absence of testing)

### 4.1 T2 integrity
- **Nullability sampler: ZERO contract-vs-runtime disagreements** across 14 sampled endpoints (market-data, strategy-signal, backtest; checker red-proofed against a synthetic mismatch). Honest coverage: 13/200 GET spec paths tonight (the allowlist is deliberately narrow — `/api/v1/market/candles` is a cache-first WRITE); the rest belongs to T1b against mock. One allowlist correction for next run: the backtest list endpoint is `/api/v1/backtests/jobs`, not `/api/v1/backtests`.
- **Referential integrity: ZERO orphans** in both directions (paper rows ↔ signals, last 5 sessions), zero duplicate open `(book,exchange,tradingsymbol,side)` tuples, all 25 NULL-signal_id orders accounted for as closes, no stale intraday positions, `signal_eval_outcomes` advanced on all 5 sessions (07-28's zero-signal day = normal down-tape).
- **Front-future 1m candles AND options snapshots both dense** all 5 sessions; native-vs-cagg 1d divergence is provenance-expected only; `/health/ingest` GREEN-overall-with-RED-days is a documented policy choice (RED days 07-17/21/23/24 = the pre-#1023 INSTRUMENT_SYNC failures, fixed 2026-07-26, registry stays Sunday-fresh — refuted as a current gap).
- **Config truth:** `published-config-drift.py` 69/69 clean; `ledger-consistency-check.py` output fully adjudicated (§3).
- **Engine liveness:** 3,504 evals today, 11 fired, buckets advancing to 15:54 IST — alive by the counters doctrine.

### 4.2 T1a route walk
85 static routes + 3 param-route samples walked authenticated at 1280×800, 11 of them re-checked at 480×900: **83 OK, 2 ISSUE** (§1.2 multiframe, §2.3 cockpit chain panel), 0 permanent skeletons, no horizontal scroll on the mobile spot-checks, no error boundaries. Console errors were exactly the two defects' own 400/503s.

### 4.3 Minor UI observations (unverified info tier — small, real, none load-bearing)
- Cockpit risk chip renders "Daily loss ₹: 3" — likely a pct value under a ₹ label.
- Cockpit + /scalper "Live signals" widgets render swing EXIT signals with timestamp 00:00 (date-only field formatted as time); /signals shows real times.
- Signal detail meta "v? · checksum n/a"; strategy editor "Published Universe: 0 constituents".
- /scalper open-positions Mark/uP&L "—" on all 17 rows post-market (last-tick mark absent after close).
- /data-ops/data-quality coverage ratios exceed 100% and read OK (BANKEX 292/187 = 156.15%) — expected-baseline denominator stale; over-coverage, not under.
- Calendar-spread first paint ~11–19s (near+far leg fetch) with blank chart meanwhile; Big-OI-Movement "Latest top-10" degenerates on the 15:30 bucket; Active-Strikes-IV "Call IV —" chip while the chart's Call IV series is populated; FII/DII freshness badge "Live as of as on 2026-07-30".

### 4.4 Operational notes (info)
- **Two stack down-windows in 24h** (07-30 20:05–21:05 IST; overnight 02:29–08:56 IST — owner machine off). Swing heartbeat ping 20:15 was withheld → hc-ping should have alerted (dead-man's-switch working as designed; owner: confirm the alert arrived). First boots 08:56–08:57 crashed once on a DB-not-ready race and self-recovered by 08:59, before open.
- **Session-level external heartbeat (`ARTHA_HEARTBEAT_SESSION_URL`) still dormant** — today's live session had no off-box liveness monitor; machine demonstrably boots minutes before open. Owner arming decision.
- Kite REST circuit opened 3× (12:07–12:10, 12:53, 15:47 IST) — all paths fail-soft/cached; a few minutes of chain snapshots missing; SENSEX 07-30 expired-backfill had 8 leg-fetch cancels but kept retrying past 16:17.
- Two forever-RUNNING BHAVCOPY ledger rows (07-16, 07-20) — pre-#1141 deadlock artifacts, no operational impact; optional owner-gated cleanup.
- 88 legacy weekend TICK_AGG option bars from Sunday 07-26 persist in `candles` (forward writes blocked by #1064; the finder's "no session guard" claim was REFUTED). Owner-gated one-time delete; blast radius negligible (zero-volume, outside any session window).

## 5 Deploy-state truth (verified commit-by-commit)

DONE doctrine = MERGED (PR# + SHA); deployment is a separately-recorded state — the "market-data missing #1141" premise is **not a defect**: #1141's diff is test-code + javadoc only. Every missing commit across all four running jars was verified **spec/metadata/test/docs-only**; the money-path commits checked (#1084 sizing bounds, #1095 premium-level dedupe, #1021 415 handler) are all IN the relevant running jars (`git merge-base --is-ancestor` proven). Edge-gateway (11 days behind, all wire-identical retypes) is first in line to ride the next substantive deploy.

## 6 Refuted findings (the verification layer's yield)

1. ~~"Sunday TICK_AGG bars = live data-pollution defect, no session guard"~~ — guard EXISTS (#1064, deployed, predates the running jar); residue is 88 legacy rows, downgraded to owner-gated cleanup.
2. ~~"INSTRUMENT_SYNC missed 3 of last 10 trading days"~~ — data reproduces but is the pre-#1023 failure signature, fixed 2026-07-26 and green since 07-27; historical record, not an open gap.
3. ~~"T28 anchored to a DONE row = promotion violation"~~ — misread; G12's DONE cell explicitly folds the redefinition half into G13. Pointer inaccuracy only.

## 7 Strategy-performance readout (T3 — measurement, no tuning)

### 7.1 Per-book (inception-to-date, live DB)

| book | closed | realized P&L | wins | open | open exposure | last-5-days P&L |
|---|---|---|---|---|---|---|
| manas-arora (1 slug: manas-arora-vcp) | 8 | −₹5,357.39 | 1/8 | 6 | ₹95,462.72 | −₹1,276.99 |
| minervini | 8 | −₹5,639.09 | 0/8 | 11 | ₹104,490.08 | −₹1,627.16 |
| scalper (5 sub-accounts) | 9 | −₹2,366.37 | 3/9 | 0 | — | −₹2,366.37 |
| shadow champion (§3.24 deduped) | 286 raw / 131 events | −₹67,428.29 | — | — | — | −₹23,267.58 (48 events) |

Close-reason attribution: **scalper TIME_STOP is the only positive bucket platform-wide** (+₹388.43 on 5; every other reason bucket negative). 07-31 was the scalper book's **first green day** (+₹69.58, both winners TIME_STOP). `paper_account.cash` static 150,000 by design — not equity. "3 Manas books" in the task premise was wrong: one manas-arora book; manual/other accounts have never traded.

### 7.2 Verdict table (family × evidence)

| family | sample | edge sign | caveat |
|---|---|---|---|
| scalper (paper, 5 subs) | 9 closes | too-small; first green 07-31 | judge on forward paper only (derived-OI muted on history) |
| shadow champion | 131 deduped events | **negative** (−₹67.4k ITD) | the only sample big enough to carry a sign |
| shadow challengers | — | vol-off −₹26.5k · vol-12k5 −₹18.9k · composite-055 −₹10.0k | all negative, less so than champion |
| manas-arora-vcp | 8 closes | too-small (1W/8) | swing, open book −₹5.4k |
| minervini | 8 closes | too-small (0W/8) | swing, open book −₹5.6k |

### 7.3 Week aggregation (07-27..07-31)

- **§3.29 armed-vs-fired:** four armed exit paths never fired all week or ever since 07-01 — `take_profit premium_pct` (nearest miss ≈+12% vs +35% trigger; T21 bracket never paid in a month), `signal_exit` (always shadowed by time_stop/structural), `square_off` (btst never fired), `oi-confluence-exit` (0 CONFLUENCE_FLIP platform-wide).
- **§3.30 freeze:** 3 sessions zero paper entries; 07-29 = 3/5 frozen by 13:40; 07-31 = first FULL 5/5 freeze by 13:34, flag condition MET; README escalation condition (median freeze < 13:00 for a week) NOT met; the 07-31 freeze preserved the first green session.
- **§3.26 counterfactuals (all doc-computed):** the all-loosenings-lose prior reconfirmed twice more — T1 volume-floor now 6 consecutive no-pay sessions; G13 collapsed to a 6-leg anecdote; G10 −₹590.95 after costs on 265 legs (sign carried by 5 legs). 07-31 was the first wf set positive under both exit models (+196.10 stop / +35.05 hold) — that feeds G11, not a loosening case.
- **G15 regime split:** 07-27 mixed 0.563 · 07-28 chop 0.163 (expiry, excluded) · 07-29 mixed 0.501 · 07-30 mixed 0.434 · **07-31 chop 0.171 — the first post-07-27 expiry-free chop day, exactly G11's missing datum.** The 07-31 headline claim verifies arithmetically: stop +196.10 vs hold +35.05 on 22 legs, 15W/7L, robust to top-2 removal.

### 7.4 What the week adds to the owner decision list

| row | this week's delta | standing rec |
|---|---|---|
| **G11** (30-min time_stop) | **UNBLOCKED — the chop day arrived.** Three-session ledger complete: trend-ish hold-wins-large / mixed stop-wins-small / chop stop-wins-large. A ~1% round-trip cost leaves the stop model ≈+152 and takes hold negative. | **KEEP the stop** (decidable now) |
| G13 (iv_pair) | nothing new — 10th zero session (0/835); stop-conditional measurement stays valid | stays OPEN (owner) |
| G10 arming | rec NO **strengthened** — mechanism reproduced 3rd time, but committed counterfactual is −₹590.95 after cost | NO |
| #1075 (budget ₹15k→₹20k) | evidence 14→25 fires, 7 unfunded, **first SENSEX-rooted unfunded fire** (the "every one NIFTY-rooted" claim is now stale); funded legs lost on 07-29 but made the first green on 07-31 — mixed | keep 2026-08-12 date, decide with G14 |

## 8 Chips filed from this run

| chip | what | severity |
|---|---|---|
| task_e2e01a | CA-rebuild decompression failure — fix + re-run CHEVIOT/ULTRACEMCO, verify base-vs-cagg | LIVE-defect |
| task_e2e01b | /multiframe-chart 30m index default → 400; fix default (+ optionally FG-03 same-touch) | LIVE-defect |
| task_e2e01c | Morning-canary boot catch-up (run-once-if-missed-today, #1036 pattern) | feature-gap |
| task_e2e01d | Option 1m capture breadth collapse — owner decides intended subscription set | feature-gap (owner) |
| task_e2e01e | Cockpit chain panel post-market 503 → degrade to last-session chain | feature-gap (design) |
| task_e2e01f | Master-plan §17–§20 stale-status sweep (§18.4, §20 note, §20.3 exclusions, §20.7.6 residue) | docs-drift |
| task_e2e01g | §3.29 routine: add the two atr_multiple armed rows + basis note | docs-drift |
| task_e2e01h | T14 sign-aware margin invariant — promote to a G row or mark WONT (owner) | docs-drift (owner) |
| task_e2e01i | Owner-gated cleanup: 88 Sunday-07-26 TICK_AGG rows + 2 forever-RUNNING bhavcopy ledger rows | cleanup (owner) |

## 9 Run-quality notes (for the next E2E run)

- The finder→verifier shape paid for itself: 3 refutations, including one the orchestrator itself had pre-filed (deploy-lag).
- Allowlist corrections: backtest list = `/api/v1/backtests/jobs`; `/actuator/health` has no OpenAPI schema (sample, don't diff).
- T1b (mock interactive flows + full API-surface probe over all 4 specs) remains the biggest untested surface — weekend run per the plan.
