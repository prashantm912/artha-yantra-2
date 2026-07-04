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
| `phase5-minervini-trend-template` | **Phase-5 Minervini SEPA** — daily 8-gate Trend Template + cross-sectional RS-rank screener (master-plan §13) **plus** the full workflow (VCP/base geometry, the 6 §6 setups, swing paper/backtest/live, selling). Detailed trackable plan (audited): [`2026-07-04-minervini-sepa-implementation-plan.md`](2026-07-04-minervini-sepa-implementation-plan.md) — Track A (screener) = shippable 80/20; Track B = setups/entries/paper/live. Carries OD-1..OD-5 pending owner input (OD-5 = screen-results lineage vs §17.1). | master-plan §13/§9/§5/§14/§17.1/§17.7; the new plan doc; `DEFERRED_BACKLOG.md` Phase-5 row | **TRACK A SHIPPED + LIVE 2026-07-04** — screener [#524](https://github.com/prashantm912/artha-yantra-2/pull/524) (1,590 low-caps scanned → 210 pass 8 gates) + Upstox fundamentals/low-cap gate [#525](https://github.com/prashantm912/artha-yantra-2/pull/525) + React `/equity/minervini` [#526](https://github.com/prashantm912/artha-yantra-2/pull/526). **Track B STARTED — Phase 5 (VCP/base geometry + analyzer endpoint) SHIPPED+LIVE [#528](https://github.com/prashantm912/artha-yantra-2/pull/528)**: `ZigZag`+`VcpDetector` (canonical `40W 31/3 4T`), `V033__minervini_setups`, `GET /candidate/{symbol}` analyzer; 12-agent adversarial review (6 findings fixed); live 210 geom rows / 102 is_vcp. **Phase 6 RECON + build-spec** (`2026-07-04-minervini-phase6-build-spec.md`). **Phase 6 SUBSTANTIALLY SHIPPED+LIVE:** PR-E `session.style=swing` engine keystone [#530](https://github.com/prashantm912/artha-yantra-2/pull/530) (parity-safe, all goldens byte-identical); PR-F `vcp` breakout setup + `VCP_PIVOT` indicator [#531](https://github.com/prashantm912/artha-yantra-2/pull/531) (`VcpSetupTest` fires on breakout-with-volume); PR-G SEPA funnel 3-list [#532](https://github.com/prashantm912/artha-yantra-2/pull/532) + funnel FE view [#533](https://github.com/prashantm912/artha-yantra-2/pull/533) (LIVE: 62 buyable / 35 on-deck / 113 watch on 2026-07-03; `/equity/minervini` Screen\|Funnel toggle). **REMAINING** = Phase 6 engine setups `cheat_3c`/`power_play` (need Phase-5 geometry EXTENSION: cheat-pause/flag levels) + `primary_base` (needs `WEEK52_HIGH` breakout indicator) + regime gate MV-6.9 (reuses `BreadthService`) — each needs owner chart-verify of geometry thresholds; then Phase 7 (swing paper + flat-% stop + staggered exits) → 8 (backtest goldens) → 9 (live swing + `minervini_detail` V020 + context-seeding producer + selling) → 10 (analyzers); + MV-4.4 candidate analyzer page. |
| `10x-roadmap-p2-p5` | **The 10x-value roadmap's remaining phases.** SHIPPED: P1+F8+F4v2 (#483–#491), F6 telegram bot (#493, LIVE), **F7 graduation-measurement dashboard (#515)**, **F9 SPAN capability + advisory heat read (#510/#514)**. REMAINING: F2 proposals pass (self-triggers at ≥5 rollup sessions), F3.2–.5 dot fixes (variant-evidence-gated), F5 always-on host (owner pick), F7 promotion numbers + F9 app-layer sizing/governor (owner risk numbers + advisory week). | [`2026-07-03-10x-value-roadmap.md`](2026-07-03-10x-value-roadmap.md) | ACTIVE — every remaining sub-item is data-gated or owner-gated; nothing more buildable without a gate opening. |
| `upstox-margin-route` | **F9 SPAN source via Upstox margin API** — `UpstoxMarginClient` + `POST /api/v1/market/margin` (typed record, fail-soft, gated on the analytics token) compute broker-real SPAN server-side, NO `.spn` file. Live-verified 2026-07-04 (1-lot short → span 337004.85 / final 188604.45). `GET /v2/user/get-funds-and-margin` = live capital (down 00:00–05:30 IST → 423); `GET /v2/charges/brokerage` = pre-trade charges (cross-checks F8 `FeeConstants`). marginism appliance #126 = offline/backtest fallback. **Gotcha:** `quantity` must be a lot multiple (UDAPI1104 else) — scalper qty already lot-aligned; ≤20 legs/basket. | roadmap F9; ADR-0002 | **SPAN CAPABILITY SHIPPED [#510](https://github.com/prashantm912/artha-yantra-2/pull/510) + advisory heat read [#514](https://github.com/prashantm912/artha-yantra-2/pull/514) (`GET /api/v1/paper/margin-heat`), deployed 2026-07-04.** Remaining F9 app layer = paper `advised_lots` sizing + daily-loss governor + heat cap — needs owner risk numbers + an advisory week. Supersedes `span-real-spn-broker-parity` (§2). |

## 2. Owner-gated — needs owner input/time, not code

| id | what's built | what's needed |
|---|---|---|
| `live-forward-paper-analysis` | Auto-paper ON (#367); every gate block persisted to `signal_rejections` (#404); analysis procedure = [`2026-06-30-live-signal-analysis-runbook.md`](2026-06-30-live-signal-analysis-runbook.md) | **~1 month of live-paper scalper trades**, then run the runbook: E9 target/trail band number + per-scalper keep/cut/tune via counterfactual replay on real captured premium. The biggest open track. |
| `span-real-spn-broker-parity` | ~~`.spn` loader + parity harness (#144)~~ **SUPERSEDED 2026-07-04** — the `.spn` file is no longer needed. See `upstox-margin-route` in §1: Upstox computes SPAN server-side (`POST /v2/charges/margin`) on the analytics token we already hold — no NSCCL file, no broker-number hunt. The marginism appliance (#126) stays the offline/backtest fallback; live sizing routes through Upstox. **No owner action** — moved to buildable. |
| `telegram-scalp-alert-optin` | notifier path built (#152) | owner sets the bot token |
| `per-strategy-notifications` | ntfy verified end-to-end (direct + service path, 2026-07-02) | only `scalp-connect-the-dots-nifty` has notifications ON — owner toggles the other 11 published strategies per taste |
| `sensex-pe-publish` | 18 SENSEX-PE drafts seeded (#382) | owner publish decision (9 NIFTY-PE already live) |
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
  surfaced on the chain `Leg` (#511, 2026-07-04).** Still open: §6.3 BSM-on-spot seam (stock options);
  20-level depth flattening.
- ~~Native 3-min option-snapshot capture~~ **MOOT (2026-07-04 empirical check) — do not re-flag:** the
  backlog premise (5-min capture, want 3-min for Table-2 fidelity) is stale. Live capture already runs
  at ~1-minute granularity (verified in `options_chain_snapshots`), FINER than 3-min; any coarser
  interval (incl. 3-min) is a read-time rollup off the 1-min base. Fidelity goal already exceeded.
- Data-Ops parked: ~~`backfill_jobs` audit table~~ **DONE (#517)** — V030 run-audit table + `GET
  /market/admin/backfill-jobs` + Status-page history; ~~contract-type selector~~ **DONE (#517)** —
  Both/Options/Futures on the Collection wizard (default Both). Remaining (low-value, no consumer):
  per-expiry BULK export (single-contract export exists) + STOMP status push (the Status page polls
  today — works fine). Do not re-flag the two done sub-items.
- Per-check server audit on signal Take; full-auto execution flag (semi-auto "Take" is the v1 safety boundary).
- AdvanceChart TV-binary extras: ~~OI-bar, trade-history, audio alerts~~ **+ horizontal price lines DONE
  (#516)** on the lightweight-charts v5 chart (all off/empty by default). Remaining (large LWC-primitive
  lift, no consumer): interactive freehand drawing tools + user-configurable study-template save/load.

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
**Minervini** (owner-deferred) · a **Nov-2026 calendar refresh** (§4) · §3 next-session verifies.
Genuinely-deferred-with-no-consumer leftovers: Data-Ops bulk-export + STOMP-push, AdvanceChart
freehand drawing tools + study-template save/load. Machines watch the machines: two in-code canaries
+ two scheduled agents + the weekly backup round-trip CI.
