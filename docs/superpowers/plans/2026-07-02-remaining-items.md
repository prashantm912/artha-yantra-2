# Remaining items — the single forward ledger (2026-07-02)

**Status:** ACTIVE — the one place listing *everything still open* across the whole platform.
Supersedes [`archive/2026-06-30-remaining-build-inventory.md`](archive/2026-06-30-remaining-build-inventory.md)
(kept for provenance; its §4 false-flags, §5 descopes and §6 WON'T-DO lists are carried forward in §7 below
so nothing gets re-flagged). Cross-checked 2026-07-02 against `PHASE_GATES.md`, `docs/DEFERRED_BACKLOG.md`,
the two 2026-07-02 audits (both fix queues fully closed) and the open-PR/issue list (empty).

**Owner rule:** when an item here ships, mark it DONE in place (PR# + SHA) before starting the next one.

---

## 1. Net-new code

| id | item | authority | state |
|---|---|---|---|
| `phase5-minervini-trend-template` | **Phase-5 Minervini SEPA** — daily 8-gate Trend Template + cross-sectional RS-rank screener (master-plan §13) **plus** the full workflow (VCP/base geometry, the 6 §6 setups, swing paper/backtest/live, selling). Detailed trackable plan (audited): [`2026-07-04-minervini-sepa-implementation-plan.md`](2026-07-04-minervini-sepa-implementation-plan.md) — Track A (screener) = shippable 80/20; Track B = setups/entries/paper/live. Carries OD-1..OD-5 pending owner input (OD-5 = screen-results lineage vs §17.1). | master-plan §13/§9/§5/§14/§17.1/§17.7; the new plan doc; `DEFERRED_BACKLOG.md` Phase-5 row | **TRACK A SHIPPED + LIVE 2026-07-04** — screener [#524](https://github.com/prashantm912/artha-yantra-2/pull/524) (1,590 low-caps scanned → 210 pass 8 gates) + Upstox fundamentals/low-cap gate [#525](https://github.com/prashantm912/artha-yantra-2/pull/525) + React `/equity/minervini` [#526](https://github.com/prashantm912/artha-yantra-2/pull/526). **Track B STARTED — Phase 5 (VCP/base geometry + analyzer endpoint) SHIPPED+LIVE [#528](https://github.com/prashantm912/artha-yantra-2/pull/528)**: `ZigZag`+`VcpDetector` (canonical `40W 31/3 4T`), `V033__minervini_setups`, `GET /candidate/{symbol}` analyzer; 12-agent adversarial review (6 findings fixed); live 210 geom rows / 102 is_vcp. **Phase 6 RECON + build-spec** (`2026-07-04-minervini-phase6-build-spec.md`). **Phase 6 SUBSTANTIALLY SHIPPED+LIVE:** PR-E `session.style=swing` engine keystone [#530](https://github.com/prashantm912/artha-yantra-2/pull/530) (parity-safe, all goldens byte-identical); PR-F `vcp` breakout setup + `VCP_PIVOT` indicator [#531](https://github.com/prashantm912/artha-yantra-2/pull/531) (`VcpSetupTest` fires on breakout-with-volume); PR-G SEPA funnel 3-list [#532](https://github.com/prashantm912/artha-yantra-2/pull/532) + funnel FE view [#533](https://github.com/prashantm912/artha-yantra-2/pull/533) (LIVE: 62 buyable / 35 on-deck / 113 watch on 2026-07-03; `/equity/minervini` Screen\|Funnel toggle). **Phase 6/7/8 ALL SHIPPED+LIVE:** all 4 setups (`vcp`#531/`primary_base`+`WEEK52`#535/`cheat_3c`+`power_play`#536), regime gate #537, swing backtest #538, flat-8%-stop #539. **SECOND BATCH #540-#542 (2026-07-04):** MV-8.1 **hit-rate harness** [#540](https://github.com/prashantm912/artha-yantra-2/pull/540) (candles@1d re-screen, forward returns vs NIFTY — LIVE: mean excess +0.27→+2.92% at +5→+63 sessions, an asymmetric momentum edge; shared `MinerviniGates`; 4-critic review, 2 fixes); MV-4.4 **analyzer page** [#541](https://github.com/prashantm912/artha-yantra-2/pull/541) (`/equity/minervini/:symbol`, 50/150/200-MA chart + VCP pivot + 3 tabs — Track A UI complete); MV-7.4 **50-day-MA trail** [#542](https://github.com/prashantm912/artha-yantra-2/pull/542) (parity-safe, existing trailing/indicator basis) + the **partial-close executor BUILD-SPEC** (MV-7.3/7.4 scaled/staggered — `2026-07-04-minervini-partial-close-build-spec.md`; deliberately a supervised pass, parity-firewall + reliability bar not yet met). **THIRD BATCH #543-#546 (2026-07-04, "build all one by one"):** `minervini_detail` V020 side-channel [#543](https://github.com/prashantm912/artha-yantra-2/pull/543); **scaled/partial-close executor** [#544](https://github.com/prashantm912/artha-yantra-2/pull/544) (MV-7.3/7.4 — the parity-critical one BUILT to spec; `scaled_exit` tiers + side-channel `qtyFraction` + `applyExit` + fraction-aware `ReplayEngine`; 10-agent adversarial review found a REAL parity edge `BacktestParityTest` misses — a colliding close-fill-bar closing the wrong position — + 4 more, ALL fixed; goldens byte-identical); defensive selling [#545](https://github.com/prashantm912/artha-yantra-2/pull/545) (MV-9.2 `signal_exit crossunder(px,sma20)`); report-card [#546](https://github.com/prashantm912/artha-yantra-2/pull/546) (MV-10.1/10.2 `SwingReportCard` grade vs the reliability bar). MV-9.4 alerts = **DONE-BY-REUSE** (existing `NotifierService` per-strategy opt-in — a minervini listener would be a redundant duplicate). **BUILDABLE TRACK-B SURFACE COMPLETE** — full exit doctrine (8% stop → 50d-MA trail → scaled tiers → 20d-MA defensive) built + parity-verified. **PHASE-9 LIVE-OPERATION PASS SHIPPED + DEPLOYED 2026-07-04** (owner reviewed the hit-rate — LIVE mean-excess +0.27→+2.92% at +5→+63 sessions — and said "all 4 setups, full Phase-9 in one pass"): **6 PRs #548–#553** — P9-B geometry cheat/thrust ([#548](https://github.com/prashantm912/artha-yantra-2/pull/548)), P9-A seed 4 setups + `minervini_funnel` universe + `seeded`-indicator publish gate ([#549](https://github.com/prashantm912/artha-yantra-2/pull/549)), **P9-C daily swing engine keystone** ([#550](https://github.com/prashantm912/artha-yantra-2/pull/550) — reuses the FROZEN EntryEvaluator/ExitEvaluator over the daily bar since funnel equities don't tick; 2-reviewer adversarial pass fixed 4 issues incl. the auto-paper `suggested_qty` stamp + daily-close settle; goldens 9/9, suite 532/532), P9-I report-card endpoint ([#551](https://github.com/prashantm912/artha-yantra-2/pull/551)), P9-H buyable-transition push ([#552](https://github.com/prashantm912/artha-yantra-2/pull/552)), P9-F sell-decision triad ([#553](https://github.com/prashantm912/artha-yantra-2/pull/553)). P9-D/P9-E = done-by-reuse (global auto-paper + swing excluded from the 15:45 square-off; `minervini_detail` stamped on entry). **P9-G Stage-3/4 exit = DEFERRED BY DOCTRINE** (owner pinned 8%-stop + 50d-trail; it's an A/B variant after the base proves out). Go-live (P9-Z): compose flag-passthroughs + live `.env` seed+swing ON + 4 strategies published + smoke-tested; **the 20:00-IST batch fires for real Monday 2026-07-06 post-close, accruing the forward paper trades the §0.5 #12 reliability bar needs** (the paper book with the pinned exits is the real test). The full daily find→geometry→funnel(+regime)→setups→backtest→hit-rate→analyze→**live-paper→sell-decisions** workflow is COMPLETE. **RAN LIVE 2026-07-04 (Fri close, owner: daily-bar signals analysed post-close any session) → 8 entries fired → 8 swing paper positions open; `.env` seed+swing flags persist true.** **DEEP-HISTORY BACKTEST + LIVE CALIBRATION DONE 2026-07-04/05 (#556–#563, owner: "calibrate the live knobs on evidence"):** ~11y event-driven sim over `candles`@1d (~1,789 EQ, ~40k signals) + a 7-version A/B chain (`MinerviniSwingBacktest`/`SwingPortfolio`/`SwingRotationPortfolio`, V035, outside the parity firewall) — full write-up [`docs/strategies/minervini-swing-backtest-results.md`](../../strategies/minervini-swing-backtest-results.md), per-version log in the build-log. **Findings:** v1 expectancy +5.68%/tr (asymmetric momentum edge); v3 the portfolio view FLIPS the per-trade A/B — 8 slots hold ~750 of 39k signals so *selection* is everything → **RS-rank is the edge** (nearly doubles 8-slot CAGR, best Sharpe); v4 RS-priority allocation = a free +6–16pt lift (live funnel already RS-ranks); v6 the ₹37.5 L turnover floor was a local minimum at every book size; v7 **12 slots is the sweet spot** (best net CAGR + lower DD + higher Sharpe) and **RS-rotation is catastrophic** (+39%→−41%, confirms hold-to-natural-exit). **Live config tuned + verified (owner-confirmed money picks):** turnover floor `liquidity-multiple` 100→**25** (₹37.5 L→₹9.375 L, #561); `max_open_paper_positions` uncapped→**12**; `max_deployment_pct` 20→**80%**; per-name `position_sizing` 5→**6.5%** (12×6.5%≈78%, #563), 4 strategies re-published **v1.0.1** (verified live). Realistic net-of-cost expectation for the tuned book: **~25% CAGR rs-turnover (live-funnel equivalent) → ~39% rs-only (optimistic upper bound)**, budget 40–50% DD. **REMAINING = ONLY the supervised forward-paper watch + the owner's §0.5 #12 reliability sign-off** — backtest proves the mechanics have edge; the live paper book (pinned 8%-stop + 50d-trail) is the real test. |
| `10x-roadmap-p2-p5` | **The 10x-value roadmap's remaining phases.** SHIPPED: P1+F8+F4v2 (#483–#491), F6 telegram bot (#493, LIVE), **F7 graduation-measurement dashboard (#515)**, **F9 SPAN capability + advisory heat read (#510/#514)**, **F9 app-layer (advised_lots + heat cap + governor) [#576](https://github.com/prashantm912/artha-yantra-2/pull/576) DEPLOYED (advisory-dormant, arm via `ARTHA_PAPER_RISK_ENABLED`)**, **F7 auto-promotion logic (GRADUATED strategy stage) [#577](https://github.com/prashantm912/artha-yantra-2/pull/577) DEPLOYED (measurement-only, arm via `ARTHA_GRADUATION_PROMOTION_ENABLED`; dormant until a strategy hits 50 closed paper trades)**. REMAINING: F2 proposals pass (self-triggers at ≥5 rollup sessions), F3.2–.5 dot fixes (variant-evidence-gated), F5 always-on host (owner pick). **F7/F9 code is BUILT — remaining is only owner ACTIVATION (flip the flag after the advisory week/data accrual), not a build.** | [`2026-07-03-10x-value-roadmap.md`](2026-07-03-10x-value-roadmap.md) | ACTIVE — every remaining sub-item is data-gated or owner-gated; nothing more buildable without a gate opening. |
| `f9-app-layer` | **F9 paper risk APPLICATION layer** — advised_lots sizing + portfolio heat cap + governor ntfy, on top of the SPAN capability. **SHIPPED + DEPLOYED LIVE 2026-07-05 ([#576](https://github.com/prashantm912/artha-yantra-2/pull/576), `b266d151`).** Owner numbers: per-trade risk 1% / daily-loss 3% / heat cap 60%. V023: advised_lots/margin_snapshot/margin_pct columns + per-book heat_cap_pct (scalper 60% enabled, swing books inert) + scalper daily-loss 10%→3%. advised_lots = risk-based sizing stamped at open (advisory); margin_snapshot = PaperMarginAnnotator prices each open position's SPAN AFTER_COMMIT+async (fail-soft); heat-cap gate blocks new entries at ≥60% book SPAN **only when `artha.paper.risk.enabled` (ARTHA_PAPER_RISK_ENABLED) is ON** — default OFF (advisory-dormant, zero hot-path cost). 2-reviewer pass (adversarial + timescale-domain, both PASS) → gated heat pricing behind the flag + added a PaperMarginClient timeout. **Remaining = owner ACTIVATION only:** after a clean advisory week (watch `GET /paper/margin-heat` + per-position margin_snapshot), set `ARTHA_PAPER_RISK_ENABLED=true` in `.env` to arm the heat-cap enforcement. | roadmap F9 |
| `upstox-margin-route` | **F9 SPAN source via Upstox margin API** — `UpstoxMarginClient` + `POST /api/v1/market/margin` (typed record, fail-soft, gated on the analytics token) compute broker-real SPAN server-side, NO `.spn` file. Live-verified 2026-07-04 (1-lot short → span 337004.85 / final 188604.45). `GET /v2/user/get-funds-and-margin` = live capital (down 00:00–05:30 IST → 423); `GET /v2/charges/brokerage` = pre-trade charges (cross-checks F8 `FeeConstants`). marginism appliance #126 = offline/backtest fallback. **Gotcha:** `quantity` must be a lot multiple (UDAPI1104 else) — scalper qty already lot-aligned; ≤20 legs/basket. | roadmap F9; ADR-0002 | **SPAN CAPABILITY SHIPPED [#510](https://github.com/prashantm912/artha-yantra-2/pull/510) + advisory heat read [#514](https://github.com/prashantm912/artha-yantra-2/pull/514) (`GET /api/v1/paper/margin-heat`), deployed 2026-07-04.** Remaining F9 app layer = paper `advised_lots` sizing + daily-loss governor + heat cap — needs owner risk numbers + an advisory week. Supersedes `span-real-spn-broker-parity` (§2). |
| `signal-eval-redis-subscriber-watchdog` | **HIGH reliability — silent recurring Redis subscriber drop on the live signal engine.** RCA'd 2026-07-07 (`docs/signal-analysis/2026-07-07-session-findings.md` §8, correcting the automated report's "consumer hang" misdiagnosis): `SignalEngine`'s `RedisMessageListenerContainer` on `candles.1m.*` **silently drops its subscription intermittently** mid-session (two gaps on 2026-07-07: ~12:18–13:20 IST recovered, 14:22→close did not). The `signal-eval` executor is healthy (parked on `take()`, thread-dump confirmed) — it's STARVED, not hung; market-data feed is GREEN and NIFTY bars build fine (the FINNIFTY canary REDs are the illiquid-far-month false-positive). **No error/reconnect is logged and no canary covers consumer-side receipt** (market-data's `DataHealthCanary` watches bar CLOSES on the producer side only). A silent mid-session receive gap can miss a stop-loss **EXIT** eval, not just entries. **Fix:** (a) log `RedisMessageListenerContainer` drops/recoveries; (b) a subscriber-side receive-gap watchdog — when market-data is GREEN but no `candles.1m` bar has been received for N min during market hours, re-subscribe + ntfy (mirror `DataHealthCanary`, consumer-side). | §8 findings; `SignalEngine.java:64-131`, `DataHealthCanary` | **BUILT — [#634](https://github.com/prashantm912/artha-yantra-2/pull/634), HOLD for owner deploy sign-off.** `SubscriberHealthCanary` (per-minute in-session receive-gap check, feed-fresh cross-check via `ticks:last-at`, overlap-safe re-subscribe routed through the eval thread, ntfy) + `SignalEngine` receive-heartbeat + notifier listener. 2-reviewer adversarial pass: fixed the one HIGH (monitor-held-across-resubscribe-I/O → routed through `evalExecutor`); rebutted global-vs-per-channel with the single-connection all-or-nothing model + incident evidence (NIFTY+SENSEX eval both stopped at 14:22:45); 7 unit tests + ModularityTest green. **MERGED + DEPLOYED LIVE 2026-07-07 (#634, `064c259c`, owner "deploy 634").** CI caught a real context-load break first (the canary's `SignalEngine` dep failed the engine-disabled paper `*IntegrationTest` contexts → gated it on the same `@ConditionalOnProperty(artha.signals.engine-enabled)`; lesson: run a paper IT locally when adding a `@Component` to strategy-signal). Live-verified: running sha == HEAD, health UP, bean loaded (clean boot under matched condition), engine still subscribes + loads 39 strategies. Armed (default ON); first live opportunity = tomorrow's session. |
| `external-batch-liveness-watchdog` | **NEW (2026-07-09) — a whole-stack/host outage silently skips the 20:05 swing batches, and the in-process P0-4 did-not-run canary CANNOT catch it.** Surfaced while verifying the first live H4 Chandelier batches: Docker Desktop was found DOWN on 2026-07-09 (the stack stopped sometime after the 07-08 20:05 run), so **07-09's Manas + Minervini swing batch MISSED** — a one-day gap in the forward-paper reliability record that the reliability sign-off depends on. The P0-4 `swing_batch_runs` did-not-run canary runs INSIDE strategy-signal, so a full-stack/host outage kills the watchman together with the batch → zero alert (an in-process canary is structurally blind to its own host being down). **Fix (owner call):** (a) the **always-on host** (thread 2 / F5) makes outages rare — the real fix; (b) an **EXTERNAL** liveness check — a cheap off-box uptime pinger, OR a post-batch heartbeat the stack emits (ntfy/healthchecks.io "dead-man's-switch") that an external service alerts on the ABSENCE of. Until one exists, the owner must keep the box + Docker up at 20:05 IST daily. | this session's verify; `swing_batch_runs`, P0-4 canary | **OPEN — surfaced 2026-07-09; gated on the always-on-host decision (thread 2). 07-09 batch missed (stack was down); stack restarted + healthy, no code change yet. 07-06/07/08 batches verified fired clean (exit_skipped=0; 07-08 first live exit = SBCL STOP_LOSS).** |

## 2. Owner-gated — needs owner input/time, not code

| id | what's built | what's needed |
|---|---|---|
| `live-forward-paper-analysis` | Auto-paper ON (#367); every gate block persisted to `signal_rejections` (#404); analysis procedure = [`2026-06-30-live-signal-analysis-runbook.md`](2026-06-30-live-signal-analysis-runbook.md) | **~1 month of live-paper scalper trades**, then run the runbook: E9 target/trail band number + per-scalper keep/cut/tune via counterfactual replay on real captured premium. The biggest open track. |
| `span-real-spn-broker-parity` | ~~`.spn` loader + parity harness (#144)~~ **SUPERSEDED 2026-07-04** — the `.spn` file is no longer needed. See `upstox-margin-route` in §1: Upstox computes SPAN server-side (`POST /v2/charges/margin`) on the analytics token we already hold — no NSCCL file, no broker-number hunt. The marginism appliance (#126) stays the offline/backtest fallback; live sizing routes through Upstox. **No owner action** — moved to buildable. |
| `telegram-scalp-alert-optin` | notifier path built (#152) | owner sets the bot token |
| `per-strategy-notifications` | ntfy verified end-to-end (direct + service path, 2026-07-02) | only `scalp-connect-the-dots-nifty` has notifications ON — owner toggles the other 11 published strategies per taste |
| ~~`sensex-pe-publish`~~ **DONE 2026-07-05** | 18 SENSEX-PE drafts seeded (#382) | ~~owner publish decision~~ — owner said "publish"; all 18 PUT-side drafts published live via the internal registry endpoint (`docker exec … wget POST /strategies/{id}/publish`, bypasses gateway auth); engine reconciled 21→39 scalpers. (The publish also surfaced + fixed the reconcile-loop bug #579.) No PR (a live data action). |
| `value-verify-ratify` | data-foundation value-verify **PASSED** live-vs-live 2026-07-01 (captured OI == oipulse exact share) | owner ratifies the close; residual low nits in §5 |
| `soft-dot-arming` | FU2 dots + drasticFloor default built inert | arm only if live forward-paper data proves them (else stays a §7 WON'T) |

## 3. Verification only — next market session

Ran live 2026-07-03 (findings addendum A2 in `docs/signal-analysis/2026-07-03-session-findings.md`):

- [x] T2 aligned snapshot buckets row-for-row vs the oipulse barometer — **PASS** (labels identical;
  Call OI exact 3/3; put-row residuals = oipulse polling lag on BSE OI dissemination, our
  end-of-window values are the fresher ones).
- [x] Capture crons fire on the new boundaries: 09:16 futures / 09:18 options — **PASS**.
- [x] First pre-open equity scan lands at 09:09:30 (#470) + futures pre-open page renders live
  (#377) — **PASS**.
- [ ] NSE announcement field mapping on live data (#378) — not exercised 2026-07-03; check next
  session with fresh announcements.
- [ ] **sentimentPct formula reconcile (master-plan §18.6):** the level-based method is now BUILT
  (#512, 2026-07-04) — `ActiveStrikeService.sentimentLevelPct` (`100·(ΣputOI−ΣcallOI)/ΣputOI`) rides
  BESIDE the ΔOI-flow `sentimentPct` on the active-strikes response (`sentimentLevelPct` + per-bucket
  `levelPct`); the ΔOI number and the sentiment GATE are untouched. **Remaining (owner, live):** one
  live-session compare of both numbers vs the oipulse dashboard → decide which the gate should read.
- [ ] Stock-chain warm on a SECOND symbol during market hours (#472 verified on RELIANCE
  off-hours) — not exercised 2026-07-03.
- [ ] Owner hard-reload (Ctrl+Shift+R) after each FE redeploy — standing owner action.

**New verifies for 2026-07-06** (P1/F8 live acceptance, roadmap `2026-07-03-10x-value-roadmap.md`):
canary tile green through the session + zero false alerts; NIFTY26JULFUT TICK_AGG bars past 12:40
(#482 fix) + watch for "dropping future-stamped tick" WARNs; variant books (vol-off / vol-12k5 /
composite-070) populate with net-₹ labels; breadth dot scores intraday (advances/declines non-zero
in rejections' context). The 09:42 + 15:47 agents cover all of it.

### 3b. Audit-register fix queue — **CLOSED 2026-07-04**

All 22 surviving rows shipped in the weekend batch, PRs **#500–#507** (every one CI-green,
squash-merged; #506 replay-fallback re-verified against the goldens/parity suite before merge).
Per-row outcomes live in the addendum table at the bottom of
[`archive/2026-07-02-full-codebase-audit-findings-register.md`](archive/2026-07-02-full-codebase-audit-findings-register.md).
The whole 2026-07-02 audit is now fully closed: 90 findings → 54 fixed same-day (#407–#435) +
4 accepted-risk + 1 refuted + 22 fixed here + the rest resolved by intervening waves.

## 4. Scheduled maintenance

| when | item |
|---|---|
| **before ~2026-11-16** | **CD-2 yearly calendar CSV refresh** — add 2027 NSE/BSE holiday CSVs to `libs/market-calendar`. `CalendarHorizonCanaryTest` goes red ~45 days before year-end; unrefreshed, the monthly-expiry look-ahead cliff starts 2026-12-29 and OI capture silently halts 2027-01-01. |

## 5. Small optional nits — **CLOSED 2026-07-04** (with the fix-queue batch)

- Value-verify residuals: F3 heatmap UTC labels were already fixed (#402, stale entry); F5
  strike-series ΔOI is now the bucket-lag delta (#503 — full-chain `series()`/`latest()` reads
  deliberately keep the captured semantics so the live gate's dots stay byte-identical); the
  single per-strike Black-76 IV (CE==PE) is documented on the chain's IV column header (#503).
- FE revamp leftovers: "Columns" rename + chain density toggle had already shipped (stale
  entries). Options-chain Radix-Select migration: **decided SKIP** — the shared Select atom is a
  native `<select>` by design (a11y-strong, zero-dep); the migration is cosmetic with
  e2e-selector risk on the platform's most-used control. Never re-flag as pending.

## 6. Deferred-by-design — build only when a consumer appears

Provenance rows live in [`docs/DEFERRED_BACKLOG.md`](../../DEFERRED_BACKLOG.md) (cross-cutting + parked tables):

- ~~`candles_1h` IST re-anchor~~ **DONE (#513, 2026-07-04):** V029 drops+recreates `candles_1h` with
  `time_bucket('1 hour', bucket, 'Asia/Kolkata')` (was UTC-hour, unlike its IST 1d/1w siblings), `WITH
  NO DATA` + the same refresh policy so the live DB never does a heavy one-shot materialization.
- ~~Upstox `/pcr` … dormant `source.pcr` flag~~ **FLAG ALREADY BUILT as `source.optionanalytics=upstox|native`**
  (routes `/pcr-series` to Upstox `/pcr` vs native; mapping tested). Only a LIVE market-hours freshness
  check remains before flipping it (§4-adjacent; not an offline build). Do not re-flag as buildable.
- ~~optimizer `requirements.txt` hash-pinning~~ **ALREADY DONE (2026-07-04 audit) — remove, do not re-flag:**
  `requirements.lock` (799 SHA-256 hashes) + `requirements-dev.lock` (922) via `uv pip compile
  --generate-hashes`; Dockerfile installs `--require-hashes`, `ci-optimizer.yml` installs
  `--require-hashes -r requirements-dev.lock`. Supply-chain guard is live.
- Recorded real Kite binary-frame capture + B-9 frame-guard production wiring (needs a first-party WS client).
- Options-fidelity SNAPSHOT/SYNTHETIC_B76 live walk; walk-forward fold + MedianPruner live walk (multi-month data).
- ~~Second-order greeks (speed/zomma/color)~~ **DONE — vanna/charm/vomma were already shipped (§17.6);
  the third-order gamma-sensitivity trio speed/zomma/color added + FD-verified in `black76-math` and
  surfaced on the chain `Leg` (#511, 2026-07-04).** ~~§6.3 BSM-on-spot seam (stock options)~~ **DONE
  ([#582](https://github.com/prashantm912/artha-yantra-2/pull/582), 2026-07-05):** `BScholesMerton`
  in `black76-math` — spot+dividend-yield q pricing (price/IV reuse Black-76 on F=S·e^((r-q)T);
  spot greeks are the distinct BSM closed forms), FD-verified. DORMANT — index options keep Black-76,
  and a live equity-option wire-in additionally needs a dividend-yield (q) data source we don't have.
  **~~20-level depth flattening~~ SOURCE-BLOCKED (2026-07-05 recon) — do NOT build dead code:** the
  Kite/Upstox retail feeds only disseminate **5** levels (their wire DTOs hold what the feed sends);
  true 20-level needs NSE's separate paid depth feed we don't subscribe to. Plus depth is dropped at
  the domain `Quote` boundary with ZERO consumer and is live-only (mock can't test it). Nothing to
  flatten to 20, no reader — DEFER until the 20-depth feed is subscribed.
- ~~Native 3-min option-snapshot capture~~ **MOOT (2026-07-04 empirical check) — do not re-flag:** the
  backlog premise (5-min capture, want 3-min for Table-2 fidelity) is stale. Live capture already runs
  at ~1-minute granularity (verified in `options_chain_snapshots`), FINER than 3-min; any coarser
  interval (incl. 3-min) is a read-time rollup off the 1-min base. Fidelity goal already exceeded.
- Data-Ops parked: ~~`backfill_jobs` audit table~~ **DONE (#517)** — V030 run-audit table + `GET
  /market/admin/backfill-jobs` + Status-page history; ~~contract-type selector~~ **DONE (#517)** —
  Both/Options/Futures on the Collection wizard (default Both). ~~per-expiry BULK export~~ **DONE
  ([#584](https://github.com/prashantm912/artha-yantra-2/pull/584), 2026-07-05):** `POST
  /market/admin/export/bulk` zips one per-contract CSV/JSON of a whole (underlying, expiry) chain
  (`BackfillExportService.exportBulk`, ≤500 contracts sync) + a "Download whole expiry (ZIP)" button.
  **STOMP status push = NOT-WORTH-BUILDING (2026-07-05):** market-data has NO WebSocket infra; ~200
  lines of new plumbing to replace a working 2s poll for a single operator (net-negative). Deferred
  until a multi-dashboard consumer exists. Do not re-flag.
- Per-check server audit on signal Take; full-auto execution flag (semi-auto "Take" is the v1 safety boundary).
- AdvanceChart TV-binary extras: ~~OI-bar, trade-history, audio alerts~~ **+ horizontal price lines DONE
  (#516)** on the lightweight-charts v5 chart (all off/empty by default). ~~study-template save/load~~
  **chart-state persistence DONE ([#583](https://github.com/prashantm912/artha-yantra-2/pull/583),
  2026-07-05):** symbol/interval + the extras toolbar persist to localStorage (`core/chartPrefs`),
  restored on reload — the ADR-A13 chart-state v1. **Freehand drawing tools = DEFERRED (ADR A13):** a
  ~5-day custom lightweight-charts-primitive/canvas lift with a KLineCharts library-re-eval trigger,
  no consumer — not built as speculative dead code.

## 7. Decided WON'T DO — never re-flag as pending

Owner NOs, consolidated (full rationale in the archived inventory §6):

E8 ATR-stop arming · FU2 soft-dots as hard gates · E3 Dow dot arming · **W-U4 Upstox cutover (stay Kite,
split-by-capability end-state)** · SPAN short/sell premium legs (long-only) · live-broker order arming
(keep paper/read-only) · E12 ideal-window + OH-freshness + economic-event lockout/event anchors ·
Event Days page (static Budget slideshow, no API) · OiPulse ≥90% AI badge (proprietary; faithful
Table-1/2 HIGH tier is the equivalent) · E1 equity-screener OOM path (replaced by on-demand Upstox +
captured bank-radar) · SENSEX point-scale constant (dead — signals ride NIFTY-FUT-CONT).

---

## Net (refreshed 2026-07-04, post buildable-items sweep)

Everything currently buildable is BUILT: roadmap P1+F8+F4v2 (#483–#491) · the entire audit-register
fix queue (#500–#508) · the **F9 SPAN capability + advisory heat read** (#510/#514) · and the
**2026-07-04 buildable-items batch (#511–#517)** — third-order greeks, level-based sentiment,
candles_1h IST re-anchor, **F7 graduation framework**, Data-Ops backfill-audit + contract-type, and
the AdvanceChart feasible extras. Items that turned out already-done/moot were reconciled in the
docs (optimizer hash-pinning, the `source.optionanalytics` PCR flag, sub-3-min capture). What
remains is **owner-gated or data-gated only**: **the data month** (rollup → proposals pass at ≥5
sessions → the exit-band tuning session) · the **F9 app layer** (`advised_lots` sizing + governor —
owner risk numbers + an advisory week) · **F7 promotion numbers** + **F5 host pick** + **SENSEX-PE
publish** + **per-strategy notif toggles** + **value-verify ratify** (owner decisions) ·
**Minervini** (BUILT + LIVE + backtest-calibrated #556–#563; live now = 8 paper positions, tuned to a
12-slot / 80%-deployed / 6.5%-per-name / ₹9.4 L-floor book — remaining is ONLY the forward-paper watch
+ the owner's reliability sign-off) · a **Nov-2026 calendar refresh** (§4) · §3 next-session verifies.
Genuinely-deferred-with-no-consumer leftovers: Data-Ops bulk-export + STOMP-push, AdvanceChart
freehand drawing tools + study-template save/load. Machines watch the machines: two in-code canaries
+ two scheduled agents + the weekly backup round-trip CI.

---

## Manas Arora + per-strategy paper books (2026-07-05 autonomous batch — BUILT + DEPLOYED LIVE)

Owner batch ("implement autonomously while I sleep"), all merged + deployed to the LIVE stack same day:
- **Task 1** — Manas Arora strategy doc committed + merged (#565).
- **Tasks 2/3 — separate paper/signals BOOKS** (#566 backend + #568 frontend): the single global paper
  book split into 5 per-family books (scalper/minervini/manas-arora/manual/other), each **₹1.5 L**
  (owner "all 1.5L each"), own capital + risk config + auto-paper toggle; `book` = the strategy's first
  family tag; V021 migration (per-book paper_account + `(book,key)` risk_settings + `book` on
  positions/orders; existing paper positions WIPED per owner). `?book=` on the paper/risk/signals
  endpoints; per-book FE pages `/paper/:book` + `/signals/:book`. Minervini now sizes off its own ₹1.5 L.
- **Task 4 — Manas Arora family** (#567 screener / #570 live engine / #569 10yr backtest / #571 FE):
  a separate, non-interfering swing family (India-adapted Minervini). Screener live = 2,224 scanned →
  **97 pass all 6 gates**, funnel 41 buyable + 70 on-deck. Live engine (parity-safe, goldens
  byte-identical; cross-family isolation via `universe.mode`) auto-papers into the manas ₹1.5 L book;
  cron 20:05 IST. 10-yr backtest ran over ~11 yr candles@1d — **results + best setup:
  `docs/strategies/manas-arora-swing-backtest-results.md`**.
- **Deploy:** backup + `:62fb38f3` rollback images taken first; go-live flags in `.env` + compose
  passthroughs. Each PR got adversarial + domain review before merge (all PASS).
- **Doc-fidelity follow-ups SHIPPED 2026-07-05 (owner "build full §3.5 live" + "per-setup pivots"):**
  §3.5 ATR exit doctrine LIVE ([#573](https://github.com/prashantm912/artha-yantra-2/pull/573) —
  `atr_multiple` stop cap_pct 10 + armed trail arm_pct 9 + new `square_off` type, additive, goldens
  9/9 byte-identical); per-setup live pivots ([#574](https://github.com/prashantm912/artha-yantra-2/pull/574)
  — breakout→`MANAS_BREAKOUT_PIVOT`, vcp→`MANAS_VCP_PIVOT`, so vcp + breakout no longer fire off the same
  funnel pivot; live-proof: 2 real entries SENORES+SBCL where the same data gave 0 before). The v1 "possible
  v2" is now DONE — the live engine is doc-faithful.
- **Backtest review follow-up DONE 2026-07-05:** the 2 adversarial Manas-backtest reviewers ran (task
  `adaf…` PASS + `a6dd6a…` 1-bug/1-risk/1-question). Only real finding = missing `ORDER BY` in
  `eqSymbols()` (portfolio-stat reproducibility) — fixed in both the Manas fork (#569) and the Minervini
  original ([#575](https://github.com/prashantm912/artha-yantra-2/pull/575)). The `a6dd6a` reviewer's
  L545 volumeRatio "off-by-one" = FALSE POSITIVE (50 bars, verified); its stale-pivot + RS-sparse flags =
  verified non-issues (weekly-fixed pivot is intended; Pass 1/Pass 2 gate RS_LOOKBACK identically).
- **Backtest-improvement follow-ups SHIPPED + LIVE 2026-07-06/07** (from the 2026-07-06 swing-backtest
  comparison — two doctrine-faithful edges Manas lacked but Minervini already had):
  - **F1 — RS-rank gate + RS-priority funnel admission** ([#611](https://github.com/prashantm912/artha-yantra-2/pull/611),
    `329e9d71`): the screener computes weighted trailing-return RS (0.4/0.2/0.2/0.2, same math as
    Minervini) over the full scanned universe, percentile-ranks 0..100, and the funnel now gates + orders
    by it (`artha.manas-arora.funnel-rs-min`, default **70**). §4.10 "the single biggest edge a filter
    adds." Live-verified: 2,229 scanned, 0 nulls, **96 passers ≥70**.
  - **F2 — §3.4 multi-lot pyramiding (add-to-winner)** ([#612](https://github.com/prashantm912/artha-yantra-2/pull/612),
    `32b717c6`; **armed live 2026-07-07**): a held winner takes an **averaged** add-lot (owner-picked over
    subaccount separate-lots — cash-equivalent under §3.5.D close-together, no shared-surface migration) on
    a fresh **+6%**-since-last-lot pivot, up to 3 lots, within a ≤6% book open-risk cap; the pyramid closes
    all lots together off the oldest lot's governing stop. Flag `artha.manas-arora.pyramid.enabled`
    (default OFF; arm +6% aligned to the backtest `PYRAMID_ARM_PCT`, un-arm = `.env` flip + redeploy).
    2 adversarial reviewers clean; backtest evidence MIXED → built for doctrine faithfulness + forward
    paper, not a proven edge. Applied-findings record in `docs/strategies/swing-backtest-latest-2026-07-06.md`
    ([#613](https://github.com/prashantm912/artha-yantra-2/pull/613)).
- **H4 canonical Chandelier + pyramid-disarm — MERGED + DEPLOYED LIVE 2026-07-07** ([#628](https://github.com/prashantm912/artha-yantra-2/pull/628),
  `c0d36310`; owner "deploy chandelier + disarm pyramid on both live and back"): the canonical Chandelier
  (highest-high − 2×rolling-ATR(20), +9% arm, breakeven floor, close exit) is now the operative live-paper
  Manas exit in BOTH engines — doctrine-equivalent, not byte-identical (double[] vs BigDecimal). Pyramiding
  **DISARMED** — live `ARTHA_MANAS_ARORA_PYRAMID_ENABLED=false` (it degrades Sharpe 0.96→0.61 under the tight
  Chandelier trail), backtest headline flipped `rs-turnover-pyramid → rs-turnover-nopyramid` (pyramid kept as
  a labeled A/B probe). Live-verified (sha==HEAD, pyramid off in-container, Manas re-published). Doc:
  `docs/strategies/manas-h4-chandelier-backtest-2026-07-07.md`.
- **OPEN (owner "add to pending, take it later") — FIFO vs RS-priority slot admission for the RS-gated funnel.**
  The H4 compare's headline "45 vs 24" was FIFO-**gross** vs RS-priority-**net** (confounded). Like-for-like on
  `rs-turnover` (our live config, H4 run 2026-07-07): FIFO-gross **45.0** > RS-priority-gross **32.1** at equal
  DD (~50%) and Sharpe (~0.96), turnover ~equal (1270 vs 1322 trades) → inferred **FIFO-net ~34–37% >
  RS-priority-net 23.8%**. RS-priority IS a real edge (it crushes FIFO in the non-gated `technical`/`turnover`
  variants, 53 vs 30 gross) but is **redundant with the funnel's RS-rank gate** (F1) — re-sorting freed slots
  by RS just concentrates into the top-RS names for no extra selection edge. **To decide (both cheap,
  reversible, no live change):** (a) add a `portfolioFifoNet` accounting to `ManasAroraBacktestService` +
  re-run → a direct net-vs-net read (clean, analytics-only, outside the parity firewall; the FIFO-net figure
  above is inferred, not measured); (b) measure how often the live 20:05 batch actually exceeds the 7-slot
  cap (if rare, the whole question is low-impact live). If confirmed, move live admission to FIFO within the
  gated pool (or raise `max_positions`) — a HOLD-tier live-admission change. Caveat: literal "FIFO" doesn't
  map to a same-day batch (entries fire together at EOD), so the real live lever is "don't pile into top-RS on
  an over-subscribed day / raise the cap."
- **No buildable code left otherwise;** the one open perf follow-up is the audit LOW "serial/N+1 backtest
  reads" (`ManasAroraBacktestService.readSeries`).

---

## 8. Cross-doc remaining-items sweep (2026-07-09)

Full sweep of every active planning + audit doc (4 parallel readers). ~150 raw open items deduped into **6 threads**:
(1) **the live-paper data month** — Minervini + Manas §0.5-#12 reliability sign-off (30–50 fwd trades, +expectancy,
~2:1, 45–55% hit) + E9 scalper exit-band tune + F1 acceptance + relative-vol-floor k=1.5 judge (all need ~1 month of
UNBROKEN forward paper — see the §1 batch-liveness row); (2) **always-on host** (appears ×4: F5 / always-on-host-brief /
master §18.3 / DEFERRED systematic-scalp prereq — ONE owner call ~₹35–50k); (3) **owner flag-flips** (F9
`ARTHA_PAPER_RISK_ENABLED`, F7 `ARTHA_GRADUATION_PROMOTION_ENABLED`, Dow `ARTHA_OPENALGO_GLOBAL_QUOTES_ENABLED`,
per-strategy notif toggles, Minervini/Manas low-cap gate arm); (4) **swing exit-parity HOLD batch** (task #128 —
M2/M3/M4/M6/M7/M8/M9/M10/M11/M13/M14/M27, owner review); (5) **long-only / semi-auto boundary** (WON'T-DO until owner
reverses — live-order arming, SPAN sell-legs, OpenAlgo gateway arm, full-auto exec, Upstox cutover); (6) **the new
batch-liveness gap** (§1 row). Below = items NOT already enumerated elsewhere in this ledger.

### 8a. Open 2026-07-05 full-audit findings (`docs/audits/2026-07-05-full-codebase-audit.md` §12; fix log §13)
- **HIGH (2):** **H6** screener reads CA-UNADJUSTED bhavcopy (splits/bonuses poison SMA/52wk/RS/VCP ~1yr/event vs the
  adjusted engine plane; data-fidelity, owner call) · **H8** cheat-3c is a synthetic proxy mislabelled as the doc's true
  cheat setup (doctrine, owner call).
- **MEDIUM (27 open of 40):** HELD-unmerged pending owner sign-off (#591, screener pass-set changes): **M12** RS-tie
  determinism / **M35** liquidity depth 25×→50× / **M39** VCP base-depth+duration caps · setup-doctrine owner-call:
  **M36** 50d-trail armed day-1 / **M37** PowerPlay depth-duration caps / **M38** PrimaryBase 52wk-breakout mislabel /
  **M40** Manas open-risk cap · exit-parity HOLD batch (task #128): **M2 M3 M4 M6 M7 M8 M9 M10 M11 M13 M14 M27** · other:
  **M1** margin-heat basket-blind / **M16** book default 'manual' fail-open / **M17 M18 M20** FE surfaces / **M28** zero
  e2e for new pages / **M31** ~80% fork debt (10+ Minervini↔Manas file pairs).
- **LOW (~28, none started):** cosmetic/test/long-tail; the only one with teeth = serial/N+1 backtest reads
  (`ManasAroraBacktestService.readSeries`, ~1,800 round-trips, ~40 min under a concurrent pg_dump).

### 8b. Open 2026-07-06 UI/data-correctness audit (`docs/audits/2026-07-06-ui-data-correctness/`, 21 items)
- **MED:** **D1** participant-OI keeps the synthetic `TOTAL` row in the group list AND the %-denominator → every
  Long%/Short% HALVED · **D2** `/strategies` sends no limit → server caps 50 of 73, hides a published+enabled name
  (list 44 vs graduation 45) · **AC-1** dated futures (`NIFTY26JULFUT`) un-findable in broad instrument search (CONT +
  options flood past the result limit).
- **Cross-cutting root:** `latestMapped` cross-date staleness — `ROW_NUMBER … ORDER BY trade_date DESC` spans dates, so
  357 EQ names resolve to a pre-07-03 "latest" under one "as of 07-03" badge (SectorStats/Heatmap/IndexContribution/
  EquityReturns) — one `WHERE trade_date = max()` fix.
- **Data action:** re-fetch the partial 2026-07-02 bhavcopy (181 EQ/BE vs ~2670; poisons Equity-Returns r1d + delivery).
- Rest = cosmetic (D3 sector staleness, D4 signals-strategy-UUID, D5 mojibake, D6 CAGR-0.00%, D7 drawdown-downsample) +
  LOW visual.

### 8c. Register Phase-1 recon leads (`archive/2026-07-02-...-findings-register.md` §9, ~26)
Main fix queue CLOSED (#500–507). Survivors = unverified recon citations — FeeConstants drift-detection, optimizer
error-swallowing (blanket except → "failed"), dual symbol-grammar candle reads (#214 class), `ay reset-db -v` blast
radius, plaintext broker creds in `deploy/openalgo/.env`, etc. **VERIFY the cited lines before actioning** (frozen
2026-07-02; several may be incidentally fixed since).

### 8d. Autonomously-startable now (no owner/data gate) — BATCH STARTED 2026-07-09
The LOW N+1 backtest-reads perf fix · **D1** participant-OI TOTAL · **D2** strategies 50-cap · `latestMapped` staleness
SQL · re-fetch the 07-02 bhavcopy · the register Phase-1 verification pass. Everything HIGH/MED else = doctrine (owner
call) or the HOLD-tier parity batch (owner review). PRs land below as they merge.
