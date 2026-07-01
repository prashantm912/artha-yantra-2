# ArthaYantra Full Codebase Audit — 2026-07-02

**Method:** two-phase multi-agent audit. Phase 1: 10 parallel architecture readers. Phase 2: 8 deep-dimension
finders + 23 adversarial verifications (16 CONFIRMED, 6 PARTIAL with corrections, 1 REFUTED). Plus read-only
live-stack checks (docker inspect, psql SELECTs, backups tree). 41 agents total; every top finding re-verified
against the cited lines by an independent skeptic agent instructed to refute it.

**Status legend:** CONFIRMED = adversarially verified against the code. PARTIAL = core claim stands, details/severity
corrected (correction noted). Empirical = verified against the running live stack on 2026-07-02.

**Complete inventory:** this report ranks and compresses. The full register — all 90 Phase-2 findings (every
severity, verbatim evidence/failure/fix, per-finding verdicts), ~25 Phase-1-only additional risks, and the
cleared-leads list — is in
[2026-07-02-full-codebase-audit-findings-register.md](2026-07-02-full-codebase-audit-findings-register.md).

---

## 1. Executive summary

**Bottom line: the platform's deterministic core (engine, golden/parity, schema lineages, gateway auth) is
genuinely strong — but the paper-trading pipeline that the entire "tune on live" methodology depends on is broken
three independent ways, and it is still latent only because the confluence gate has never passed (live DB on
2026-07-02: 0 signals ever, 0 paper positions, 6 rejections).** The day the gate first passes, every recorded
trade will be wrong-instrument, exit-orphaned, and the 15:45 close sweep will crash. Fix the paper pipeline
**before** loosening the gate, or the ~1-month E9 dataset is garbage from trade #1.

Second-order urgent: **backup posture is currently one recovery point deep.** Both retained nightlies are
pre-#395 per-schema dumps (25MB — missing all 224M hypertable rows); the only valid whole-db dump is the manual
one from 07-01; the 07-02 nightly silently skipped (stack down at 00:30, no dead-man alert); and rotation
(KEEP=3, DB-agnostic) can evict that one live dump after 3 mock-mode nights.

Third: **alerting is dead end-to-end today.** Live notifier defaults to the WireMock stub (catch-all 200, audited
"SENT" — even the built-in test-send falsely passes), the ops ntfy topic is never passed to market-data (all 3
contract canaries + corporate-action alerts no-op), the `.env` topic is blank anyway, there is no feed-liveness
watchdog, and the `obs` profile contains zero services.

What's genuinely good: byte-frozen golden/parity regime, single-engine live/replay parity by construction,
4-lineage Flyway discipline with the two-step CI drift check, loopback-only compose with file-mounted secrets,
correct AES-GCM token crypto, D8 error envelope, exact-decimal money math end to end, fail-closed scalper gating
with the #404 rejection observability.

One Phase-2 finding was refuted on external evidence: Upstox rate limits are per-API, not per-token — the
backfill cannot starve live OI capture at Upstox's limiter. Two CLAUDE.md claims are stale (§10).

---

## 2. Phase 1: architecture review

**Architecture map.** Maven reactor (Java 21, Boot 3.5.15): 5 libs (`strategy-engine` = deterministic signal core
shared by live+replay; `strategy-schema` frozen v1 + canonical JSON/SHA-256; `black76-math`; `market-calendar`;
`common-web` core/servlet) + 4 Java services (`edge-gateway` WebFlux ingress :8080 loopback-only;
`market-data-service` sole marketdata-schema writer, ingestion hub; `strategy-signal-service` registry + live
SignalEngine + paper ledger; `backtest-service` replay + jobs spine) + 2 out-of-reactor Python FastAPI services
(`optimizer-service` Optuna ask/tell; `margin-service` dormant SPAN appliance) + React 19/Vite SPA (~80 pages,
23 API modules, 161 TanStack hooks) + Playwright e2e ×2 suites. Deploy: one compose stack, TimescaleDB
2.17.2-pg17 + Redis 7.4, 4 Flyway lineages (admin/marketdata/strategy/backtest), nightly backup sidecar,
`ay.ps1` operator CLI.

**Boundaries.** Honored well: strategy-signal holds no marketdata DB grant (all reads over REST/Redis); backtest
reads marketdata via the single CD-1 read-only grant (SET ROLE asserted in an IT); optimizer never evaluates
(dual-writer exception to D10 documented); parity firewall = ScalperConfluenceGate/EmissionGuard as `Optional<>`
seams the replay never constructs.

**Data flow.** Kite WS → IngressQueue (10k, drop-oldest) → single normalizer thread → CandleBuilder →
`candles`@1m upsert + Redis `candles.1m.*` → SignalEngine (FIFO queue, single eval thread) → gate →
`strategy.signals` + Redis `signals` → gateway STOMP → SPA. Backtest: Postgres-authoritative jobs + Redis Streams
transport, resultRef = run id. OI: 60s full-chain REST poll → `options_chain_snapshots` (irreplaceable forward
capture); history is read-time virtual via `CandleDerivedChainReader`.

**Mock/live separation.** Design: one Postgres instance, `artha` vs `artha_mock`, Redis db0 vs db1, derived solely
by `ay.ps1` from `SPRING_PROFILES_ACTIVE`. **Weakness: compose interpolation defaults are the LIVE names while the
default profile is mock — fail-open.** Three unguarded entry points bypass ay.ps1 (raw compose, stale `ay.sh`,
`e2e/global-setup.ts`). No service asserts profile↔DB pairing at boot; no canary would detect MOCK-source rows in
live. Redis pub/sub channels are profile-unqualified (logical-DB isolation does not cover them).

**External dependencies + failure points.** Kite (WS + REST — REST clients have **no timeouts**), Upstox (per-API
limits; expired-history backfill), NSE/BSE public endpoints (self-healing catch-up), OpenAlgo appliance (opt-in),
ntfy (alert channel — currently dead), GitHub Actions 2-core runners, Docker Desktop/WSL2 single host.

**Top architectural risks** (all file-verified in Phase 2): fail-open mock→live compose defaults;
single-eval-thread + timeout-less HTTP; paper/live instrument divergence; backup rotation blind to DB identity;
calendar coverage cliff; alert channel wiring gaps; job-spine kind-blindness between backtest and optimizer.

**Phase-1 unknowns → resolved in Phase 2:** JDK HttpClient factory confirmed by classpath elimination; MonteCarlo
seed IS deterministic (cleared); OiAttribution IST-safe (cleared); folds run the same replay path (cleared);
JobPruner guard genuinely missing; `candles_3m` policy ACTIVE on live (psql check — job 1010, 42,816 rows);
Black76 callers do guard degenerate inputs (largely cleared); `libs/strategy-engine` actually has a 70% BRANCH
JaCoCo gate — stricter than services (Phase-1 lead wrong).

---

## 3. Frontend audit

### Working well
- `QueryState.tsx` 4-way wrapper used by ~55 pages ("a 500 never renders the empty-state lie").
- Exact-decimal string/BigInt money math in `frontend-react/src/lib/decimal.ts`; `parseFloat` confined to chart
  coordinates with explicit comments at every crossing.
- Refcounted STOMP client (`lib/wsClient.ts`) with full-jitter backoff + broker resubscribe on reconnect.
- Query keys carry the full symbol context (killed the Angular store's 13 hand-rolled generation counters).
- Canvas charts re-theme via MutationObserver on `data-theme` across all 5 themes.
- Explicit +05:30 day bounds (SignalsPage/RejectionsPage) sidestep the UTC `::date` trap.
- A11y basics consistent (skip link, aria-live WS pill, role=img charts, keyboard row activation).
- Routes/pages inventory clean — zero orphan pages (scripted check).

### Findings
1. **[HIGH, CONFIRMED] Cockpit + Connecting Dots render backend failures as calm "no data".**
   `oiAnalytics.ts:86` passes `silenceToast: true` on EVERY request; `client.ts:76-82` silences ANY status (5xx
   included, not just the intended 422 DATA_GAP); `CockpitPage.tsx:147-214` + `ConnectingDotsPage.tsx:46-50`
   branch on `data == null && !isLoading` → errored queries render the empty copy. market-data crash mid-session
   (precedented: OOM ×2) reads as "quiet session" on the primary trading screen. Neither page uses QueryState.
   *Fix:* silence only `status===422 && code==='DATA_GAP'`; wrap both pages in QueryState.
2. **[MEDIUM after adversarial verify] MOCK mode indicator can never render.**
   Gateway `SystemStatusController.java:69-74` maps raw `MOCK` → `VALID` (its own IT asserts the mapping);
   `session.store.ts:82` checks `=== 'MOCK'` — unsatisfiable. Topbar tag, Settings notice, mockMode flag all dead;
   dashboard Kite tile shows VALID in bull green in mock mode. One incidental survivor: Settings "Ticker: MOCK"
   row. Data isolation itself intact — dead-indicator/UI-trust defect.
   *Fix:* expose raw session (or a `mode` key) in the status Map (Map return = no contract drift); point the store at it.
3. **[MEDIUM, CONFIRMED] `apiFetch` returns text/html as typed data** (`client.ts:85-88`) — a gateway allowlist
   miss (documented recurring incident, #404 class) renders as a permanent silent empty state.
   *Fix:* throw `ApiError(status,'BAD_CONTENT_TYPE')` when content-type is not JSON.
4. **[MEDIUM, CONFIRMED] No global 401 handling** — session expiry = endless "Authentication required" toast
   stream over frozen stale data; never redirects to /login. Gateway confirmed to return 401 JSON.
   *Fix:* on 401, flip session store to anonymous → RequireAuth navigates to /login; dedupe the toast.
5. **[MEDIUM, CONFIRMED] Daily/weekly charts label every bar the previous calendar date.**
   `CandleChart.tsx:114-117` applies the +19800s IST shift only when `intraday`; 1d/1w buckets are IST-midnight
   instants = 18:30Z previous day on the UTC axis. Monday session reads as Sunday.
   *Fix:* apply the shift for 1d/1w too (or use LWC BusinessDay time).
6. **[MEDIUM, verified] Live cockpit never auto-refreshes market panels, no as-of stamp** — spot/PCR/ATM/heatmap
   frozen at last Go press while WS signals stream (illusion of liveness). *Fix:* refetchInterval ~30-60s + render
   the chain `asOf`/stale flag like OptionsChainPage already does.
7. **[MEDIUM] Calendar Spread page dead-ends** — depends on shared expiry but mounts FilterBar with
   `showExpiry={false}`, so the expiry default/heal never runs (fresh profile or rolled-over expiry = unusable page).
8. **[LOW]** oiGet swallows all 422s by status not code; signals/rejections hard-cap 200 rows with no total count
   (backend caps 500, returns no total); WS reconnect invalidates the ENTIRE query cache (thundering herd);
   `formatDecimal` truncates instead of rounding; paper MTM overlay prefers a possibly-frozen WS tick over the
   fresher 5s server mark; `contracts/gen/*.d.ts` imported by ZERO frontend files — the tsc-strict contract gate
   does not constrain the app.

### Fix first
oiGet 422-narrowing + QueryState on Cockpit/ConnectingDots; raw mode in status payload; content-type guard;
401 redirect. All small.

---

## 4. Backend audit

### Working well
- FIFO no-conflation bar pipeline; hot-swap lands on bar boundaries (`SignalEngine.java:112-127`).
- Paper/backtest fill parity real: same `LtpSlippageV1` + `Fees.DEFAULTS`, verified constant-for-constant.
- Fail-closed scalper gating + #404 rejection persistence (full rail matrix per block).
- MarketOiClient never-falsely-confirm degradation (NEUTRAL/null defaults can lower confluence, never manufacture a buy).
- Deterministic `generated_at` = entry bar bucket instant; channel payload re-parses persisted breakdown bytes.
- Risk gate includes unrealized MTM in the daily-loss trip.
- MonteCarlo seed genuinely deterministic (persisted + reused, single Random in fixed order).
- Fold evaluation routes through the exact headline replay path (same premium/relax/band config).
- Strike-reference decoupling fails loud (422 DATA_GAP, never silently anchors SENSEX on NIFTY).
- Registry lifecycle disciplined (one published version, checksum-pinned signals, push+pull reconcile).

### Findings
1. **[CRITICAL, CONFIRMED] 15:45 mark-to-close crashes every day there is an open intraday position.**
   `PaperPositionRepository.java:174-188` — `intradayOpen()` SELECT lists 11 columns but maps via the shared
   13-column `map()` reading `stop_loss`/`take_profit` → PSQLException on the FIRST row, thrown out of
   `jdbc.query()` before the per-position try/catch. Regression from PR #110 (git-verified: COLUMNS/map()
   widened, inline SELECT not). Zero tests cover intradayOpen/markToCloseIntraday. Crashes exactly when the
   result set is non-empty — i.e. exactly when needed. Positions then carry overnight until expiry settlement.
   *Fix:* add `p.stop_loss, p.take_profit` to the SELECT (one line) + an IT.
2. **[HIGH after adversarial verify] TAKEN signals exit-orphaned.**
   `SignalRepository.activeEntry` (L128-140) filters `status='ACTIVE'` only; AutoPaperListener (auto) and
   SignalsController (manual take) transition to TAKEN synchronously → structural stop, confluence-flip,
   ExitEvaluator, intrabar level passes all permanently stop for that position. `PaperSignalListener.openSingle`
   (L67-72) passes null SL/TP (the signal's persisted stop/target discarded) → PaperBracketEvaluator skips the
   position. Exhaustive search: NO engine-EXIT→paper-close path exists. The 2026-06-30 runbook expects
   TAKE_PROFIT/trail/structural-stop/time_stop close_reason attribution — structurally impossible.
   Verifier corrections: re-entry averaging bounded (~0-2 adds) by the seeded 20% max_deployment cap; overnight
   carry only via finding #1. Every hands-off trade = hold-to-15:45.
   *Fix:* widen activeEntry to `IN ('ACTIVE','TAKEN')`; close linked paper position on engine EXIT; pass
   signal SL/TP into OrderRequest as bracket backstop.
3. **[HIGH, CONFIRMED] Paper trades the INDEX FUTURE, not the picked option.**
   `PaperService.openOrder` (L146-148) falls back to the signal's primary leg (the index future); nothing in
   paper/ reads `tradeable_*` (the live arm does — `LiveOrderService.java:65-67`: "the order routes the OPTION").
   PE variants are `direction: long` → paper opens a LONG future on a bearish read — **sign-inverted P&L**.
   Hero-zero qty sized off option premium applied to future notional (₹2.5k budget → ~450 units ≈ ₹1.1cr).
   Verifier bonus: ₹15k `premium_budget` sizing against the future price yields 0 lots → null suggestedQty →
   auto-take SKIPS most directional signals entirely. The ledger is empty-or-wrong either way.
   *Fix:* in openSingle, when scalper_detail present, open `tradeable_exchange/tradingsymbol` BUY at
   `scalper_detail.option_ltp` (mirror the existing straddle openLeg path).
4. **[HIGH, PARTIAL-confirmed] premium_pct exits dead live, honored in backtest.**
   `SignalEngine.java:699` sets entryPrice = index-future close; `levelFromRules` (L1147-1163) +
   `ExitEvaluator.levelDistance` (L237-238) apply the premium_pct percentage to that index price — a 50% SL
   sits ~12,500 NIFTY points away, unreachable. Backtest `OptionsPremiumReplay.exitRules` (L293-327) applies the
   SAME YAML to real option premium. `HeroZeroGate.java:57-58` documents the inert rule as "the primary live stop".
   Verifier corrections: affected positions ARE bounded live (hero-zero structural stop at opposite session
   extreme + 16-bar time stop + 15:20 square-off; btst 1-day time stop; gap/movers VWAP signal-exit + supertrend
   trail + 20-bar time stop) — defect = exit-SEMANTICS divergence poisoning E9 and backtest-vs-live comparison;
   the optimizer even tunes the live-inert take_profit knob ([20,55] band). Straddles excluded (StraddleExitMonitor
   is a real live combined-premium stop; YAML rule annotated as backtest proxy).
   *Fix:* resolve premium_pct against the picked option's premium live (option_ltp basis + LastTickReader), or
   reject premium_pct at publish for live scalpers and convert YAMLs to index_points. Document chosen semantics.
5. **[HIGH, CONFIRMED ×2] Zero HTTP timeouts.**
   (a) All strategy-signal RestClients (MarketOiClient, MarketDataCandlesClient, instrument-meta, notifier,
   margin) build from the bare builder → JDK HttpClient factory (verified by classpath elimination) → no
   connect/read timeout — all on the SINGLE eval thread; every recovery path (20s reconcile, 08:40 reload)
   queues onto the same dead executor. One established-but-stalled connection halts all entries AND all
   stop/exit evaluation until manual restart.
   (b) All 4 `kite/live` REST clients timeout-less (every Upstox/NSE/BSE client sets timeouts — omission, not
   style). One hung call permanently stalls the `fixedDelay` OI-snapshot loop (breaker only counts completed
   failures) → forward OI capture silently stops; container stays healthy.
   (c) edge-gateway has no upstream response-timeout; backtest MarketDataClient same gap.
   *Fix:* `spring.http.client.connect-timeout: 2s` / `read-timeout: 10s` in strategy-signal + backtest +
   market-data; `spring.cloud.gateway.httpclient.response-timeout` in gateway. Plus a snapshot-age watchdog.
6. **[HIGH, CONFIRMED] Backtest crash recovery hijacks optimizer jobs.**
   `JobRepository.requeueStaleRunning` (L148-151) + `findQueuedIds` (L154-158) have NO kind filter; a
   backtest-service restart mid-sweep requeues the running OPTIMIZATION parent, dispatches it onto
   `jobs.backtest`, and BacktestRunner replays it as a plain backtest (the sweep echo carries strategyId/from/to)
   — bogus `backtest_runs` row keyed to the sweep id, status flip-flop between two writers.
   *Fix:* `AND kind IN ('BACKTEST','TRIAL')` in both queries; optimizer boot marks its own orphaned running sweeps failed.
7. **[MEDIUM after verify] Optimizer concurrent sweeps steal + destroy each other's results.**
   One shared consumer `optimizer-1`/`cg-optuna`; `read_results` XACKs on read (`streams.py:39-55`); results not
   in this sweep's pending map are dropped after ack (`sweep.py:144-147`); `_reconcile_dead` never resolves
   completed-but-stolen trials → both sweeps hang at 'running'. A stuck sweep's zombie thread poisons ALL later
   sweeps until restart. Cancel checked only on the result path; observed cancel raises → blanket handler
   overwrites 'cancelled' with 'failed' (unguarded set_status); no restart recovery (in-memory daemon threads).
   *Fix:* serialize sweeps or route per-sweep (sweepId already on every entry); cancel-probe in the read loop;
   terminal-status guard on set_status; boot-time orphan cleanup.
8. **[MEDIUM, CONFIRMED] transition() has no state guard** — unconditional UPDATE; a signal can be taken twice
   (double fill/averaging), taken after expiry. *Fix:* `WHERE id=? AND status='ACTIVE'`, 409 on 0 rows.
9. **[MEDIUM, CONFIRMED] emitEntry = 3 autocommit statements** (insert / stampSuggestedQty / stampScalperDetail)
   — mid-sequence failure leaves an ENTRY without leg/qty; EXIT path can leave the entry ACTIVE and re-emit.
   *Fix:* TransactionTemplate around each emit path.
10. **[MEDIUM, CONFIRMED] resubscribe() stops the old Redis container before starting the new** — 1m bars
    published in the gap lost permanently (1m series never re-fetched post-warmup). *Fix:* start-before-stop
    (EngineSeries already rejects non-increasing buckets) or re-warm 1m after resubscribe.
11. **[MEDIUM, verified] OI fan-out uncached** — ~15-18 sequential GETs per gated entry PER STRATEGY (chain +
    active-strikes fetched twice within one evaluation); flip-exit re-runs the whole fan-out per bar per open
    position; 12 CE variants on one trending bar ≈ 180 sequential round-trips on the eval thread.
    *Fix:* bar-scoped memo in `MarketOiClient.get()` keyed (endpoint, underlying, barInstant).
12. **[MEDIUM, CONFIRMED] `dataHash` covers only the primary 1m tuple** — premium candles, context series,
    strikeRef, and the armed OI-gate's live REST inputs (which silently degrade to EMPTY on failure) are unhashed:
    "same triple ⇒ byte-identical trade list" is false for options runs. *Fix:* fold the other read inputs into
    the hash + persist an oiGateCoverage flag.
13. **[MEDIUM, CONFIRMED] ReplayEngine/OptionsPremiumReplay `getOrDefault(ts, 0)` silent index-0 fallback**
    (#214-class offset/missing-bar trap): a coarse bucket whose first 1m bar is missing maps the entry to bar 0 —
    phantom trade at window start, no error. *Fix:* fail loud or NavigableMap ceiling lookup.
14. **[MEDIUM, CONFIRMED] `kite:ticker:status=CONNECTED` written before the socket connects**, never corrected on
    connect failure (the stale-token 403 class reads CONNECTED all day). *Fix:* write from onConnected/onDisconnected.
15. **[MEDIUM, CONFIRMED] Candle provenance from PROFILE not gateway** (`CandleQueryService.java:65`) — an
    OpenAlgo candles cutover would persist rows as source='KITE'. *Fix:* gateway reports its own source label.
16. **[LOW]** bar-eval DB failure permanently consumes that bar's ENTRY decision (log-only); tick pipeline
    fan-out couples CandleBuilder to Redis publish success; BarWriter swallows closed-bar persist failures
    (heals at EOD); cancellation observed only at checkpoints 10/40/80 with a 30-min TTL flag; relax_session
    runs carry no run-level provenance; `intervalDuration` silently maps unknown primaries to 1m (live-only
    leniency; replay throws).

### REFUTED
**Upstox quota fragmentation** — official Upstox docs + staff statements: limits are **per-API**, not a per-token
aggregate. The 3-independent-limiter arithmetic (3×1800 vs 2000) described a constraint that does not exist;
expired-instruments endpoints are disjoint from live chain/quote endpoints; live capture rides Kite today anyway.
Residual low-severity hygiene only (uncoordinated pacing on /market-quote/quotes, nowhere near budget).

---

## 5. Data & migrations audit

### Working well
- Hypertable/compression design matches read shapes: candles 7d chunks, segmentby exchange/symbol/interval,
  orderby bucket DESC (V003); snapshots 1d chunks, segmentby underlying/expiry/type + the V007_1 index — exactly
  the chain-over-time pivot shape.
- Deliberate no-retention posture on all marketdata hypertables (≥5y floor documented per-file) + a manual
  operator prune function (V010) — no-silent-data-loss for the irreplaceable dataset.
- Idempotent per-source write semantics: bhavcopy `DO NOTHING` never clobbers a live bar; `insertBackfill`
  hard-codes source='BACKFILL'; risk-settings seeds never clobber hand-set rows.
- `R__seed_sample_strategy.sql` genuinely rerun-safe (early RETURN on existing slug; placeholderReplacement=false).
- Suffix-versioned corrections discipline honored (V002_1, V006_1/2, V007_1); the two CHECK swaps honest about
  the Timescale decompress-dance and run non-transactionally via .conf side-files.
- CD-1 grant future-proofed (ALTER DEFAULT PRIVILEGES for both creator roles) AND asserted by a real
  SET ROLE ay_backtest IT (SELECT works / INSERT fails).
- V015 signal_rejections indexes exactly match the three query shapes; V026 coverage columns fix the
  silent-short-fetch reconciliation hole.

### Findings
1. **[HIGH, CONFIRMED] Mock-pollution is permanent once it lands.**
   Candle UPSERT (`CandleRepository.java:24-31`) merges `high=GREATEST/low=LEAST/source=EXCLUDED.source` — a
   later authoritative Kite re-fetch can never lower a synthetic high, and provenance flips last-writer-wins.
   Verifier strengthened: the mock fixture uses REAL symbols with REAL Kite tokens (NSE:NIFTY 50 = the benchmark
   and scalper anchor), so mock bars land under production keys and roll into every cagg. Combined with the
   fail-open compose defaults (§9.5) this is the platform's #1 integrity trap. No boot guard, no MOCK-source
   canary would ever notice.
   *Fix:* separate replace-outright upsert for authoritative closed-bar fetches; compose `:?` defaults; boot
   profile↔DB assertion; optional live-boot canary `SELECT count(*) FROM candles WHERE source='MOCK'`.
2. **[MEDIUM — corrected by live check] `candles_3m` cagg.**
   V019 creates the cagg + a 3-minutely refresh policy; runtime bypasses it (read-time 1m rollup, #365);
   CLAUDE.md says "empty/unwired (refreshing OOMs)". **Live DB empirical: policy ACTIVE (job 1010, scheduled=t)
   and the cagg holds 42,816 rows** — lineage and live actually MATCH; the incremental 1-day-window refresh runs
   fine (only wide historical refresh OOMs). Real defect = CLAUDE.md stale + an active background job nothing
   reads (invalidation-tracking tax on every 1m insert).
   *Fix:* decide — V027 `remove_continuous_aggregate_policy` + drop the view (nothing reads it), or wire the
   read path to it. Fix the CLAUDE.md sentence either way.
3. **[MEDIUM, CONFIRMED] JobPruner FK abort.** DELETE has no NOT-EXISTS guard despite its own javadoc
   (`JobRepository.java:242-251`); `backtest_runs.job_id` FK (V003:11) aborts the whole statement — from
   ~Dec 2026 (first jobs + 180d) the monthly prune removes nothing, forever, and errors monthly.
   *Fix:* add the documented NOT-EXISTS guards (runs, trials, child jobs). No migration needed.
4. **[MEDIUM, CONFIRMED] `bucket::date <= ?` UTC trap in `EquityIndexContributionService.java:135-141`** — the
   lone bare-`::date` violator (every other site wraps AT TIME ZONE 'Asia/Kolkata'); today's in-progress daily
   bar masquerades as "on/before asOf" intraday — index-contribution mixes yesterday's constituent moves with
   today's index level. *Fix:* match the house pattern.
5. **[MEDIUM, CONFIRMED] Backup follows the active profile + DB-agnostic rotation** — see §9.1/§9.2 (primary
   treatment there; schema-side note: compose comment at line 141 still describes the pre-#395 per-schema design).
6. **[LOW, CONFIRMED] `backtest_runs` lacks UNIQUE(job_id)** — crash between run-insert and markCompleted + boot
   requeue = duplicate run + full duplicate trade set; resultRef tolerates it (latest completed_at) but
   `findReturnsByJobIds` is row-order-arbitrary and aggregates double-count.
   *Fix:* unique index + `ON CONFLICT (job_id)` upsert.
7. **[unverified] `signal_rejections` growth** — full-JSONB diagnostic per blocked chart-entry bar, no retention.
   Currently 6 rows (empirical); revisit after gate tuning loosens.

---

## 6. Security audit

Threat model accepted: single-owner loopback; host compromise = game over. Within it:

### Working well
- No secrets committed — `git check-ignore` verified the full chain incl. `deploy/openalgo/.env`.
- AES-256-GCM token-at-rest correct (`AesGcmTokenCipher`): fresh 96-bit SecureRandom nonce per encrypt, 128-bit
  tag, 32-byte key check, no home-grown KDF; plaintext token only in an AtomicReference.
- Strategy YAML: SnakeYAML SafeConstructor + alias cap 50 + no recursion/duplicates + 256KB cap — no gadget surface.
- Admin SQL console defense-in-depth (`AdminQueryService`): SELECT/WITH allowlist, single statement, blocked-keyword
  scan, `SET TRANSACTION READ ONLY` authoritative, 15s statement_timeout, row cap, pinned search_path. No
  string-concatenated user SQL anywhere (screener interpolates allowlisted view names only, parameterizes values).
- Session/auth hygiene: HttpOnly + SameSite=Strict, fixation rotation on login, CSRF on all mutating incl.
  logout, inbound `X-Artha-User`/`X-Request-Id` stripped at HIGHEST_PRECEDENCE then re-asserted from the
  authenticated principal; Argon2id off the event loop; fail-closed when no hash configured.
- Actuator exposure minimal + uniform (`health,info,prometheus`); OAuth popup postMessage origin-pinned;
  no tokens in localStorage; no `dangerouslySetInnerHTML`.

### Findings
1. **[MEDIUM, CONFIRMED] LoginRateLimiter non-atomic INCR/EXPIRE** (`LoginRateLimiter.java:41-56`) — TTL set only
   when attempts==1; crash in that window leaves a TTL-less counter that volatile-lru never evicts → the sole
   owner permanently locked out (all loopback/Tailscale traffic shares one IP bucket); manual redis-cli DEL to
   recover. *Fix:* Lua script or unconditional expire on every increment.
2. **[LOW] Owner password hash as plain env var** — the ONE credential not file-mounted, contradicting the
   compose file's own "no secret in docker inspect" invariant; enables offline cracking of a weak password.
   *Fix:* file secret like the rest.
3. **[LOW] Redis pub/sub channels profile-unqualified** (`signals`, `strategy.changed`, kite.status) — pub/sub
   ignores SELECT; latent cross-profile leak if mock+live stacks ever share one Redis. *Fix:* profile-suffix the
   channel names on both ends.
4. **[LOW, accepted-tradeoff] dev-tools socat bypass** — direct 8081/8082 = unauthenticated SQL console + admin
   surfaces; console runs as superuser `artha` (can `pg_read_file()` inside the RO txn). Document; never enable
   on a shared host; optional shared-secret header check in ArthaIdentityFilter.
5. **[LOW] `/api/v1/auth/session` pre-auth discloses live/mock profile** + materializes anonymous sessions.
   *Fix:* return profile only when authenticated.
6. **[unverified] Kite OAuth callback under SameSite=Strict** — the cross-site redirect may not carry the session
   cookie (functional 401 risk, not confidentiality). Needs one live login round-trip to confirm.

---

## 7. Performance & reliability audit

### Working well
- Bounded tick ingress: 10k ArrayBlockingQueue, drop-oldest + `ay_ticks_dropped_total` — correct backpressure for
  last-value ticks.
- Crash-safe job spine: Postgres authoritative, conditional claim, XACK-on-terminal, startup requeue+redispatch.
- NSE/BSE/Upstox clients uniformly time-bounded; Kite REST rides per-family rate limiters + breaker (but see
  timeout finding); optimizer httpx timeout=10.
- UTC-date trap systematically fixed (one violator, §5.4); no zoneless `LocalDate.now()` in main code.
- EOD jobs isolated on own executors with CAS running-guards; market-data on virtual threads.
- Snapshot inserts batched (500) + DO NOTHING; WS conflation correctly scoped; redis pubsub client-output-buffer
  limits set.
- OOM history acted on (timescale 1GB→4GB, shared_buffers 512MB; backtest -Xmx640m/896m).
- Ticker recovery fundamentals right: handle rebuild on stop (the 403-forever fix), registry replay on connect,
  >2min gap scan + full-window backfill, Redis-persisted SubscriptionRegistry.

### Findings
1. **[HIGH] No-timeout pair** — primary treatment §4.5.
2. **[MEDIUM, verified] IndicatorBank rebuilt per bar live** (`SignalEngine.java:418-422`) — the exact O(n²)
   cold-ta4j-cache pattern the golden runner fixed (D17, documented in its own comment); `IndicatorValueCache`
   seam wired into nothing; `EngineSeries` never trimmed → per-bar cost grows ~6×/month of uptime; heap creep in
   -Xmx448m. *Fix:* one long-lived bank per (strategy, instrument), invalidated on reload; session-boundary trim.
3. **[MEDIUM] OI fan-out uncached** — §4.11.
4. **[MEDIUM, CONFIRMED] Redis 48mb volatile-lru shared by both profiles**: Spring-Session keys ARE TTL'd
   (evictable — the compose comment's "sessions safe" claim is wrong); job streams never XTRIMmed (non-evictable
   growth); no volume → recreate loses sessions + owner chart subscriptions (2026-06-15 incident class recurs
   every `ay down`/`up` cycle). *Fix:* XADD MAXLEN ~10k on the 3 streams; small volume + appendonly; fix the comment.
5. **[MEDIUM, CONFIRMED] No feed-liveness watchdog**: gap scan fires only on onConnected (half-open freeze never
   triggers it); healthcheck HTTP-only; zero HealthIndicators repo-wide. All building blocks exist
   (`ticks:last-at`, FeedPipeline restart). *Fix:* 60s market-hours-gated watchdog → restartFeed + ntfy.
6. **[MEDIUM, CONFIRMED] PEL inline drain blocks backtest startup** — StreamBootstrap runs full replays serially
   on the ApplicationRunner thread before the pool starts; readiness REFUSING_TRAFFIC for the duration; optimizer
   gated behind the healthcheck. *Fix:* XACK-and-drop the PEL — requeue+redispatch already covers those jobs.
7. **[LOW]** `-XX:+UseSerialGC` on the two hot JVMs (STW pauses under the per-bar BigDecimal storm — switch to
   G1); WS passthrough queue unbounded per session (full-chain JSON every 30s vs 256m gateway heap — cap +
   drop-oldest for snapshots); job cancellation gaps (§4.16).

---

## 8. Testing audit

### Working well
- Golden/parity regime real: GoldenDeterminismTest (double-run + frozen fixtures) + BacktestParityTest (same
  fixtures through ReplayEngine) + OptionsPremiumGoldenTest; side-channel extension pattern repeatedly honored.
- `libs/strategy-engine` carries a **70% BRANCH** JaCoCo gate — stricter than the services' 60% line (Phase-1
  claim it lacked one was wrong).
- ITs migrate the REAL Flyway lineages into pinned-production-tag Testcontainers (admin first, currentSchema parity).
- ci-migrations two-step drift check (merge-base migrate → PR validate) + fresh-volume rebuild + grant assertion.
- margin-service parity harness honestly incomplete (real-.spn case an EXPLICIT pytest skip, visible every run).
- Optimizer tests assert convergence behavior on a synthetic Sharpe surface, not mock wiring; 75% cov gates on
  both Python services.
- 79 frontend vitest specs pin the OI math folds + payoff/risk engines; 41/75 pages have RTL specs.

### Findings (ranked by risk-reduction per effort)
1. **[HIGH, PARTIAL-confirmed] e2e/global-setup can hit the live DB.**
   `e2e/global-setup.ts:16` raw compose, no ARTHA_* vars (compose defaults = live); reuses ANY healthy stack with
   zero profile check; helpers document exporting the real owner password locally. Worse than originally claimed:
   `publishE2eStrategy` (signals/notifier/strategy-versions/sweep-explorer specs) creates AND PUBLISHES a
   fire-every-bar strategy — against a live stack with auto-paper ON, the live engine loads it and pumps garbage
   signals/paper trades every bar; sweep-explorer POSTs a real /optimizations/run. (Correction:
   backtest-results.spec is render-only.) Stack-down path boots MOCK services against live artha/db0.
   *Fix (~15 lines):* pass `ARTHA_DB_NAME=artha_mock, ARTHA_REDIS_DB=1` in the execSync env; hard-assert
   `SPRING_PROFILES_ACTIVE=mock` (docker inspect) before reusing a healthy stack.
2. **[HIGH, CONFIRMED] No live-vs-backtest exit-equivalence test.** The §4.4 divergence is invisible to CI: the
   only live-stop assertion anywhere is `isNotNull()` on a semantically wrong value
   (SignalEngineIntegrationTest:181-182); a backlog doc claims premium exits "work on BOTH paths".
   *Fix:* assert the stop VALUE; cross-suite equivalence test (one YAML through PremiumExitEvaluator AND the live
   emit→take→bracket chain); PaperSignalListener bracket-population test.
3. **[MEDIUM, CONFIRMED] frontend-react/e2e + ALL axe scans run in NO CI** (ci-react = lint/vitest/build; the
   "a11y gated by axe" invariant unenforced; root e2e covers a disjoint set). *Fix:* second job in ci-e2e reusing
   the already-booted mock stack.
4. **[MEDIUM, CONFIRMED] Gateway allowlist has no contract cross-check** despite the recorded #404 live incident.
   *Fix:* ~50-line pure-file test — every path in `contracts/<svc>.openapi.json` must match that service's
   `Path=` prefixes in gateway application.yml.
5. **[MEDIUM, CONFIRMED] Migration-only PRs skip ci-java** (path filter omits `deploy/flyway/**`) — the ITs that
   actually consume the schema never run pre-merge. *Fix:* one path-filter line.
6. **[MEDIUM, CONFIRMED] Calendar horizon canary missing** — the only calendar test asserts 2027 THROWS; nothing
   goes red before the rollover bricks live. *Fix:* assert coverage of today+~45d (red from mid-Nov 2026).
7. **[MEDIUM, CONFIRMED] Optimizer cancel + real Streams transport zero-covered** (FakeDispatcher replaces all
   consumer-group semantics; ack-before-process window untested). *Fix:* test_cancel.py + fakeredis dispatcher test.
8. **[MEDIUM, CONFIRMED] 69 of ~166 mapped endpoints return `Map<String,Object>`** — response-shape drift
   invisible to ci-contracts + TS for ~42% of the API (loophole codified in CLAUDE.md, exploited twice).
   *Fix:* ratchet — freeze the count in a ContractCaptureTest lint, fail on increase; convert high-churn surfaces
   as touched.
9. **[MEDIUM, CONFIRMED] Backup/restore has no automated round-trip** despite the #395 silent-loss incident in
   exactly that code. *Fix:* tiny Timescale round-trip (hypertable + compressed chunk + cagg) using the exact
   ay.ps1 commands, scheduled or post-backup.
10. **[LOW]** `*IT` silent-skip unguarded (no failsafe, no enforcer — 5-minute rule fixes it); rerun-retries on a
    dirty singleton DB both mask real races and convert flakes to hard failures (surface `flakyFailure` markers);
    ci-e2e lacks push-to-main trigger + readiness gate omits backtest/optimizer; frontend vitest has no coverage
    floor (34/75 pages spec-less, and those pages' only other coverage is the never-in-CI playwright suite).

---

## 9. DevOps & deployment audit

### Working well
- Post-#395 backup DESIGN sound and exceptionally documented (why whole-db; Timescale pre/post dance; incident
  history in the header where the next operator reads it).
- Compose hygiene uniform: pinned tags (openalgo digest-pinned), mem_limits, healthchecks, log caps, loopback-only
  everywhere ("never parameterized via env"), file-mounted secrets scoped per service.
- db-create (both DBs) / flyway-init (active DB only) separation clean; strict dependency gating, no sleep loops.
- ay.ps1 single-entrypoint env management with strong secret generation (correct encodings) + the #395 restore.
- #404 gate observability (rejection diagnostics) genuinely good.

### Findings
1. **[HIGH, CONFIRMED + empirical] Backup rotation + coverage — current state is ONE recovery point.**
   Sidecar dumps only the active profile's DB (`PGDATABASE: ${ARTHA_DB_NAME:-artha}`); `prune_global` KEEP=3 sorts
   stamp dirs regardless of which DB a dump contains → 3 mock-mode nightlies evict the sole live dump. On disk
   2026-07-02: exactly one valid whole-db dump (manual/20260701-011737/artha-full.dump, 2.9GB); the two retained
   nightlies (20260630, 20260701) are pre-#395 per-schema dumps (~25MB marketdata.dump — missing all 224M
   hypertable rows) occupying 2 of the 3 retention slots.
   *Fix:* always dump `artha` (mock is reproducible); per-DB retention (nest $MODE/$DB/$STAMP or key on
   ${DB}-full.dump); delete the two dead nightly dirs; take a fresh manual backup NOW.
2. **[HIGH, CONFIRMED + empirical] No dead-man on the nightly.**
   crond-in-container fires only if the stack is up at 00:30 IST — 2026-07-02 nightly already missed (no dir;
   cron entry verified fine; stack was down); no success ping; failure ping requires a running script AND a topic
   (owner's is blank). No host-side compensating task (Task Scheduler runs only span-fetch).
   *Fix:* ntfy on SUCCESS (silence by morning = alarm), or host-side schtasks freshness check / `ay backup` schedule.
3. **[HIGH, CONFIRMED] Live notifier → WireMock.**
   `ARTHA_NOTIFIER_NTFY_URL` defaults to `http://wiremock:8080` + topic `ay-signals-mock`; wiremock is NOT
   profile-gated (running in the live stack right now, strategy-signal depends_on it); stub catch-all 200s;
   `NotifierClient.configured()` checks only topic non-blank; all three senders audit "SENT" — **and
   `sendTest`, the owner's own verification path, falsely confirms delivery**. Keys absent from `.env.example`
   and the live `.env`.
   *Fix:* empty compose default; `configured()` requires non-blank URL; wiremock behind a profile or mock-only
   values; document both keys in .env.example.
4. **[MEDIUM after verify] Ops ntfy never reaches market-data.**
   Compose passes `ARTHA_NTFY_TOPIC` only to db-backup; market-data env block lacks it and its application.yml
   maps no artha.ntfy.* → NtfyClient resolves "" → all 3 contract canaries + CorporateActionJob alerts
   structurally dead (B-14 design invariant unenforced). Latent today — owner's topic is blank, so NOTHING is
   armed — but the single documented arming knob would silently arm only the backup sidecar. Drift stays
   partially observable (Redis key, counter, logs); push alerting dead.
   *Fix:* add the passthrough to market-data's env block; boot WARN when canary enabled + topic blank.
5. **[HIGH, CONFIRMED] `ay.sh` stale mirror breaks isolation.**
   Last functionally touched 2026-06-12 — predates DB isolation (#11) and the restore rewrite (#395). `up` never
   sets ARTHA_* (mock → live artha/db0 via compose fail-open defaults); `restore` = `pg_restore --clean` into
   hardcoded `artha`, no Timescale pre/post, no globals — the method #395 documents as silently broken. Still
   advertised in README:45 for Linux/WSL2, and CLAUDE.md notes the Bash tool is bash.
   *Fix:* delete (change README to pwsh-invoke ay.ps1) or port Set-ProfileEnv + #395 restore verbatim. Either
   way fix the root enabler: compose `${ARTHA_DB_NAME:?set via ay CLI}` fail-closed defaults.
6. **[MEDIUM, CONFIRMED] ay.ps1 profile detection = exact `'mock'` string** — `mock,debug` activates mock beans
   at the LIVE database. *Fix:* split on ',', mock if list CONTAINS mock; exit 1 on ambiguous.
7. **[MEDIUM, CONFIRMED] Restore verification is eyeball-only** — pg_restore + row-count query both run through
   Invoke-ComposeAllowFail; "[ay] restore complete" prints unconditionally; stack restarts on whatever resulted.
   *Fix:* 3-line guard — capture candle count, exit non-zero + skip restart when 0.
8. **[MEDIUM, CONFIRMED] `obs` profile is a phantom** — accepted by both CLIs, README advertises
   Prometheus/Grafana/Loki, ZERO services carry `profiles:[obs]`; micrometer/prometheus endpoints exposed but
   nothing scrapes; logs capped 10m×3; alert inventory = backup-fail + canaries (all dead per above). Owner
   cannot notice: stale feed, stalled engine, memory creep, disk filling.
   *Fix:* smallest honest change = delete the README row + CLI acceptance; or one prometheus container + 4 scrape
   targets + 2 alerts (feed staleness, container memory).
9. **[MEDIUM, CONFIRMED] No rollback path** — single mutable `:dev` tag, rebuild overwrites; Dockerfiles COPY
   host-built artifacts (stale dist/JAR ships silently — documented bitten gotcha). Bad deploy during market
   hours = ~10+ min full rebuild to roll back.
   *Fix:* additionally tag images with git short-SHA at deploy; bake the SHA into artifacts and compare to HEAD
   before compose build.
10. **[LOW]** Redis stateless across recreate (no volume/appendonly — §7.4); compose db-backup comment still
    describes pre-#395 per-schema behavior + "14 nightly + 8 weekly" retention that does not exist.

---

## 10. Maintainability audit

### Working well
- Cross-schema discipline honored (verified: backtest reads marketdata read-only via CD-1; strategy-signal only
  over HTTP; ModularityTest in market-data + strategy-signal).
- No orphan frontend pages (scripted routes-vs-pages check: zero unreferenced).
- Symbol-grammar reconcilers NOT duplicated (one `normalizeStrike`; per-edge expiry parsing intentional and
  documented in docs/symbol-normalization.md).
- Institutional memory written where the next maintainer reads it (backup.sh header, gate comments, migration
  rationale comments).

### Findings
1. **[MEDIUM, CONFIRMED] ScalperConfluenceGate monolith** — `evaluateWithDiagnostic` is ONE ~620-line method
   (L273-895), ~30 rails; fail-open vs fail-closed policy comment-only and deliberately mixed (L430 fail-closed,
   L447/454 fail-open, L581/664/679/684 fail-closed…); siblings ScalperGates 904 LOC, MarketOiClient 874 LOC.
   Nothing forces a new rail to declare its null-data policy — the next rail that picks wrong silently converts
   "no data" into "take the live trade" (or suppresses all entries).
   *Fix (parity-safe):* `FailPolicy` enum recorded per rail in the existing RailCheck machinery + a table test
   enumerating every rail's declared policy (build fails on an undeclared rail); mechanical extraction of rails
   into private methods after.
2. **Docs-vs-reality drift — fix CLAUDE.md:**
   - "market-calendar covers only the CURRENT year" — FALSE; CSV covers 2024-2026 (agents refuse valid backtest
     windows because of it).
   - "candles_3m exists but is empty/unwired (refreshing it OOMs)" — FALSE on live (policy active, 42,816 rows;
     only wide historical refresh OOMs).
   - compose comment "pg_dump per schema… 14 nightly + 8 weekly" — pre-#395.
3. **Dead seams:** `IndicatorValueCache` + impl unwired since Stage C (wire per §7.2 or delete); `candles_3m`
   cagg unread (§5.2); `backups/weekly/` obsolete tier; frontend `contracts/gen` unused (§3.8).
4. **Duplication:** two Playwright suites, disjoint coverage, only one in CI; ay.ps1/ay.sh divergence (§9.5);
   hand-written FE DTOs beside generated types.

---

## 11. Prioritized fixes

### P0 — before the gate ever passes a signal (this week; all small)
1. `intradayOpen()` — add `p.stop_loss, p.take_profit` to the SELECT (1 line) + IT for markToCloseIntraday.
2. `activeEntry` → `status IN ('ACTIVE','TAKEN')`; pass signal SL/TP into `OrderRequest`; close paper position
   on engine EXIT (small listener on paper_orders.signal_id).
3. `PaperSignalListener.openSingle` → open `tradeable_*` at `option_ltp` (mirror the straddle openLeg path).
4. `spring.http.client.connect-timeout/read-timeout` in strategy-signal + backtest + market-data (kite/live
   clients); gateway response-timeout.
5. Backup: always dump `artha`, per-DB prune, delete the 2 dead nightly dirs, success-ping dead-man.
   **Take a fresh manual backup today.**
6. Notifier: empty compose default + `configured()` requires URL; pass `ARTHA_NTFY_TOPIC` to market-data;
   set a real topic.

### P1 — this month
7. Compose `${ARTHA_DB_NAME:?set via ay}` fail-closed defaults + boot profile↔DB assertion + e2e global-setup
   mock pinning + delete/port ay.sh.
8. premium_pct live semantics decision (resolve against option premium via LastTickReader, or reject at publish +
   convert YAMLs to index_points) + the exit-equivalence test.
9. 2027 holiday CSV (cliff fires 2026-12-29 via look-ahead; OI capture silently halts 2027-01-01) + horizon
   canary test; SENSEX Thursday weekly-expiry calendar variant.
10. Job spine: kind filters in requeue/findQueuedIds; UNIQUE(job_id) on backtest_runs; JobPruner NOT-EXISTS
    guard; optimizer per-sweep result routing + cancel-in-loop + terminal-status guard + boot recovery.
11. UI trust cluster: oiGet 422-DATA_GAP-only + QueryState on Cockpit/ConnectingDots; raw mode in status payload;
    apiFetch content-type guard; 401 redirect; daily-chart date shift.
12. Feed-liveness watchdog + long-lived IndicatorBank + OI fan-out memo.

### P2 — opportunistic
Gateway allowlist contract test; frontend e2e/axe CI shard; `deploy/flyway/**` in ci-java paths; Redis stream
trimming + volume; Map-return ratchet; FailPolicy enum + rail extraction; CLAUDE.md corrections; image SHA tags;
LoginRateLimiter atomic expire; candles_3m keep-or-drop decision; transition() state guard; emitEntry
transaction; resubscribe start-before-stop; replay index-0 fail-loud; dataHash widening; SerialGC → G1;
WS passthrough cap; PEL XACK-and-drop; `bucket::date` fix; obs profile honesty.

---

## 12. Top 10 issues

| # | Issue | Severity | One-line failure |
|---|---|---|---|
| 1 | Paper pipeline broken 3 ways: 15:45 sweep crashes (PR #110 regression), TAKEN = exit-orphaned + null brackets, wrong instrument (PE sign-inverted, hero-zero ~1000× notional, most signals silently skipped) | CRITICAL (latent — 0 trades yet) | First gate-passing day produces an unmanaged, wrong-instrument ledger and a crashing EOD close; the entire E9 tune-on-live dataset is garbage from trade #1 |
| 2 | Backup posture = one recovery point: nightlies are dead per-schema dumps, rotation is DB-agnostic, missed nights are silent | HIGH (live NOW) | A third DB incident (two OOM crashes already) meets zero-or-one usable backups of the irreplaceable OI archive |
| 3 | Zero HTTP timeouts on the single eval thread + kite/live clients | HIGH | One stalled connection silently kills all live evaluation incl. stop management, or permanently halts forward OI capture |
| 4 | premium_pct exits dead live vs honored in backtest; optimizer tunes the inert knob | HIGH | Backtest/live exit policies irreconcilable; keep/cut decisions calibrated against exits live never executes |
| 5 | Mock-writes-to-live fail-open (compose defaults, ay.sh, e2e setup) + GREATEST/LEAST merge makes pollution permanent under real symbol keys | HIGH | One raw compose/e2e invocation permanently corrupts the live NIFTY candle archive with synthetic extremes, undetectably |
| 6 | Alerting dead end-to-end: notifier→wiremock (false "SENT", test-send lies), ops topic unwired to market-data, topic blank, no watchdog, phantom obs profile | HIGH | Live scalp alerts, canary drift, CA anomalies, backup failures, frozen feeds — all invisible |
| 7 | Calendar: 2027 cliff fires 2026-12-29 (look-ahead), OI capture silently halts 2027-01-01; SENSEX scalpers on NSE-Tuesday model | HIGH (dated) | Three days of silent signal outage, then irreplaceable capture gaps; SENSEX hero-zero armed the wrong weekday |
| 8 | Cockpit/ConnectingDots render failures as "no data"; MOCK tag can never render; text/html returned as data; daily charts off-by-one date | HIGH (UI trust) | Trading decisions made on "OI absent" when the source is down, on unmarked mock data, on wrong-dated bars |
| 9 | Job spine: restart hijacks running sweeps as plain backtests; concurrent sweeps steal results; cancel→failed; duplicate runs; pruner FK-dead | HIGH/MED | Research record silently corrupted; sweeps hang unrecoverable; jobs table grows forever |
| 10 | Test blind spots: e2e can write to live DB (publishes a fire-every-bar strategy), no exit-equivalence test, frontend e2e+axe in no CI, allowlist untested | HIGH/MED | The exact defect classes above ship green repeatedly; one local `npx playwright test` can pollute live |

---

## Appendix: explicitly unverified items

- Color-contrast AA ratios per theme (needs rendered axe run).
- Kite OAuth callback behavior under SameSite=Strict (needs one live login round-trip).
- Live `.env` values beyond keys inspected read-only (notifier/source flags).
- Whether any host scheduled task compensates the nightly dead-man (none found in repo; Task Scheduler runs
  only span-fetch).
- Actual runtime latencies / GC pauses / Redis occupancy (code-derived estimates only; prometheus endpoints
  exposed but unscraped).
- Whether the live flyway_schema_history checksums match the working tree in all 4 lineages (spot-verified
  candles_3m only).

## Appendix: empirical live-stack facts (2026-07-02)

- Stack up, profile `live`, DB `artha`, Redis db0; feed alive (114,847 candles in last 2 days).
- strategy.signals = 0 rows (ever); paper_positions = 0; signal_rejections = 6; strategies = 67.
- candles_3m cagg: 42,816 rows; refresh policy job 1010 ACTIVE (scheduled=t, 1-day start_offset).
- backups/: 1 valid whole-db dump (manual 20260701-011737, 2.9GB); nightlies 20260630 + 20260701 are per-schema
  format (pre-#395); no 20260702 nightly (stack down at 00:30); backup.sh bind-mounted and current (whole-db);
  crontab `30 0 * * *` present, crond running.
- wiremock container running in the live stack (notifier default target).
