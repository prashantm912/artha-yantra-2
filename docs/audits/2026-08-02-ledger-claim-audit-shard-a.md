# Ledger claim audit — shard A: load-bearing factual claims in §0 / §0a / group G

**46 claims checked: 40 HOLD, 5 FALSE, 1 UNVERIFIABLE.** Scope was
`docs/superpowers/plans/2026-07-02-remaining-items.md` §0 (groups A–G), §0a, prioritised by blast
radius — safety/impossibility claims first, then deployed/live-verified claims, then sole-writer/
only-path claims, then cited measurements. Shard B (§4b chips) and shard C (§1–§9 prose) are
out of scope and were not touched.

**Method.** Every claim was verified against CODE, the LIVE `artha` DB, the running containers'
env/metrics/logs, jar fingerprints, or the cited PR — never against another doc. Doc-to-doc
agreement is how the G4 fabrication survived, so it was not accepted as evidence anywhere here.
All DB reads were bounded and `SET statement_timeout`-capped; nothing was written.

**The headline finding is G11.** Its status (DONE) and its *decision* (keep the stop) are both
sound, but the sentence that describes what the fleet actually runs is false: only **18 of 63**
scalper configs carry `max_bars: 10`, not all 63, and the fleet spans five different stop horizons
(30 / 36 / 48 / 60 / 90 minutes). That sentence is the premise under which four separate entry-gate
rejections (T1, T7, G13, G10) were declared *final rather than stop-conditional*, so it is
load-bearing in exactly the G4 way.

---

## 1. Verdict table

Line/column citations below are as they appear in the ledger. Verdicts are on the **claim**, not on
the row's status.

| row | claim (verbatim) | how checked | verdict |
|---|---|---|---|
| **G11** | "`time_stop: { max_bars: 10 }` is on all 63 scalper YAMLs, armed fleet-wide by T21/#990" | Live DB `strategy.strategy_versions` published configs, `jsonb_array_elements(config->'exit_rules')` where `type='time_stop'`; independently re-counted from the 63 files in `services/strategy-signal-service/src/main/resources/scalper-strategies/`; `git show` of #990's merge commit `64f9caaa` against `scalp-connect-the-dots-nifty.yaml` | **FALSE** — see §2.1 |
| **G11** | "KEEP the 30-minute `time_stop` as armed. No code, no deploy — the stop is already armed fleet-wide, so the decision is a no-op on the running engine" | same queries | **partially FALSE** — a `time_stop` rule is on 63/63 (that half holds, so the decision *is* a no-op), but "the 30-minute stop" describes 18 of 63 configs / 12 of the 38 live ones |
| **G5** | "New counters `ay_futures_oi_snapshot_quote_retries_total` + `..._skips_total`" | `docker exec ay-market-data-service … /actuator/prometheus`; `FuturesOiSnapshotService.java:66-71` | **FALSE** — the skips meter is `ay_futures_oi_snapshot_skips_total`, with no `quote_` segment. The `...` elision names a meter that does not exist. See §2.2 |
| **F10** | "`reload()` calls `bankCache.clear()` unconditionally (`:317`)" | `SignalEngine.java` — no `bankCache.clear()` call exists; `:995` is `bankCache.keySet().removeIf(key -> !freshBankKeys.contains(key))`; superseded by #1226 @ `e5f465c0` (merged 2026-08-02) | **FALSE (now)** — true when written 2026-07-16, superseded today. See §2.3 |
| **F7** | "deep link dormant until owner sets `ARTHA_NOTIFIER_APP_BASE_URL` to the reachable app origin — a .env flip, no rebuild" | `.env` carries `ARTHA_NOTIFIER_APP_BASE_URL=http://127.0.0.1:8080`; confirmed live in-container; compose default is empty (`docker-compose.yml:538`), app default blank (`application.yml:125`), so the value is a real arm | **FALSE** — the flag is SET; the feature is no longer config-dormant. See §2.4 |
| **E3** | "Still owner: heartbeat URL (needs an owner-created healthchecks.io check) … `ARTHA_NOTIFIER_APP_BASE_URL` (scalp deep link, #743)" | `.env`: `ARTHA_HEARTBEAT_URL` SET (a `hc-ping.com` URL, 56 chars), `ARTHA_NOTIFIER_APP_BASE_URL` SET | **FALSE** — 2 of the listed "still owner" flags are already set. The other two checked (`ARTHA_OPENALGO_GLOBAL_QUOTES_ENABLED`, `ARTHA_EVO_SCHEDULER_ENABLED`) are genuinely absent/empty and DO still hold. See §2.5 |
| **G2** | "`ARTHA_SIGNALS_STRATEGY_COVERAGE_WATCHDOG_MODE=ARMED` appended to `.env` in place … boot log `strategy coverage watchdog mode=ARMED graceMs=180000`" | `docker exec ay-strategy-signal-service env` → var present with value `ARMED`; `docker logs` → boot line matches the quoted string **character for character** | HOLDS |
| **G3** | "it now registers `SPECULATIVE`, so under pressure the pins yield to the live engine rather than the reverse" | `SubscriptionRegistry.java:107-112` (2-arg `subscribe` hardcodes `SubscriptionPriority.SPECULATIVE`), `OptionAtmPinner.java:114`, `PinnedSubscriptionRegistrar.java:9` | HOLDS |
| **G3** | "**44 option pins** … 69 active subscriptions = **2.3% of the 3000 cap, 0 evictions**" | live `/actuator/prometheus`: `ay_options_atm_pinned_contracts 44.0`, `ay_subscriptions_active 69.0`, `ay_subscription_evictions_total 0.0`; 69/3000 = 2.30% | HOLDS — still exactly true 7 days later |
| **G5** | "`kite-quote` is already configured to exactly that [1 req/second]" / "**Rate budget is unchanged at 1/s**" | `market-data-service/application.yml:133-136` — `limit-for-period: 1`, `limit-refresh-period: 1s` | HOLDS |
| **G5** | "a dedicated single-thread `oiCaptureTaskScheduler` … NOT `monitorTaskScheduler`" | `MonitorSchedulingConfig.java` — `oiCaptureTaskScheduler()` with `setPoolSize(1)`; `FuturesOiSnapshotService.java:112` binds `scheduler = "oiCaptureTaskScheduler"` | HOLDS |
| **G5** | "**LIVE-VERIFIED on the first full forward session, 2026-07-27: cadence 372 of 375 minutes (99.2%)**, against 211 / 198 / 187 / 192 / 208 over the five preceding sessions" | Re-derived from `marketdata.futures_oi_snapshots`, distinct IST minutes per session: 07-27 = **372**; 07-20…07-24 = 208 / 192 / 187 / 198 / 211 | HOLDS — exact match on all six numbers |
| **G6** | "Armed since #605 on **all 63 scalper YAMLs — 38 of which are enabled+published**" | Live DB: 63 `scalp%` strategies with a published version; 38 `enabled AND published_version_id IS NOT NULL` | HOLDS |
| **G6** | "`TwoCandleGate:94` uses the same 2-arg overload, so the two-candle FORMATION still tests the static floor" | `TwoCandleGate.java:93-95` — `floorMet` calls `ScalperGates.volume(underlying, …)`, the 2-arg form | HOLDS |
| **G6** | "jar-verified (`volumeFloor` present in the running `ConnectTheDotsScorer`)" | `unzip -p /app/strategy-signal-service.jar BOOT-INF/classes/…/ConnectTheDotsScorer.class \| strings \| grep volumeFloor` → present | HOLDS |
| **G7** | "all three landed at ONE seam (**`PaperService.openOrder`, the sole writer**)" | Only `INSERT INTO paper_positions` in main source is `PaperPositionRepository.java:250`, reached solely via `insertOpen`; the only main-source caller of `insertOpen` is `PaperService.java:893`, inside `private upsertPosition` (865–899), whose only caller is `PaperService.java:829` inside `openOrder` (657–854). `openPair`/`openScalperOrder`/`openScalperPair` all delegate to `openOrder` | HOLDS — sole-writer chain confirmed end to end |
| **G7** | "Every 63 published configs now carry `max_lots: 5`" | Live DB regex over published scalper configs → 63/63 match `"max_lots": 5` | HOLDS |
| **G7** | "`min_premium_inr` ships as capability only (no live strategy sets it)" | Live DB: 0 published versions (any strategy, not just scalpers) contain `min_premium_inr` | HOLDS |
| **G7** | "#1075 stays OPEN as the built + reviewed artifact" | `gh pr view 1075` → `state: OPEN`, `mergedAt: null` | HOLDS |
| **G9** | "**`volume-tolerance-pct` STAYS 0.0**" / "`…volume-tolerance` is an ABSOLUTE 650" | `PartialBucketCanary.java:251-252` — `@Value("${…volume-tolerance:650}")`, `@Value("${…volume-tolerance-pct:0.0}")`; no override in `application.yml`; still true after #1168 merged 2026-08-01 (that PR ships the scaling knob **dormant**) | HOLDS |
| **G9** | "the instance runs `maxmemory-policy volatile-lru` (`deploy/docker-compose.yml:72-83`)" | `docker-compose.yml:82-83` → `--maxmemory-policy volatile-lru`; the cited 72–83 span is the comment-plus-flag block | HOLDS, citation exact |
| **G9** | "The canary also has its **own scheduler** (`partialBucketTaskScheduler`)" | `MonitorSchedulingConfig.java:86`; `PartialBucketCanary.java:285` binds it; `MonitorSchedulingConfigTest:93` pins it | HOLDS |
| **G10** | "window **20** bars, minBars **10**, multiplier **1.5** (`ScalperOiProps:102-104`)" | `ScalperOiProps.java:103-105` — `DEFAULT_RELATIVE_VOLUME_MULTIPLIER 1.5`, `_WINDOW 20`, `_MIN_BARS 10` | HOLDS (line citation drifted +1) |
| **G10** | "`priorVolumes` = bars `index-1…index-window` … **truncating at the series start** (`ScalperConfluenceGate:1213-1219`)" | `ScalperConfluenceGate.java:1364-1372` — `for (int j = 1; j <= window && index - j >= 0; j++)` | HOLDS (line citation drifted 1213→1364) |
| **G10** | "deployed + live-verified unarmed (**0 rows in `strategy.strategies` carry the tag**)" | Live DB: 0 published versions and 0 versions of ANY status contain `time-of-day-volume-floor` | HOLDS — stronger than claimed (no draft carries it either) |
| **G10** | "`ScalperGates.relativeVolumeFloor` … resolves the floor as `multiplier × MEDIAN(priorVolumes)`" | `ScalperGates.java:191-205` — median of the non-null sorted window × multiplier, with `absoluteFallback` below `minBars` | HOLDS (cited 188-202; method body at 191-205) |
| **G12** | "`iv_daily_summary`, a table with **one row per DAY** written once at **16:00 IST**" | Live DB, 07-20…07-31 window: rows = distinct `summary_date` for all six underlyings (10 = 10); `computed_at` in IST spans 15:59:59–16:00:21 | HOLDS |
| **G12** | "`atm_iv` is NULL on 07-28 and 07-21 while those sessions still read a value" + "gate values each equal the prior trading day's row" | Live DB `NIFTY 50`: `atm_iv` NULL on 07-21 and 07-28 with `iv_30d` present. Gate values quoted in the row (0.130859 / 0.135577 / 0.121736 / 0.118781 for 07-24 / 07-27 / 07-28 / 07-29) equal `iv_30d` of 07-23 / 07-24 / 07-27 / 07-28 respectively | HOLDS — 4 of 4 |
| **G12** | "`IvAnalyticsService:117` prefers `iv_30d` over `atm_iv`" | `IvAnalyticsService.java:130` — `s.iv30d() != null ? s.iv30d() : s.atmIv()` | HOLDS (line citation drifted 117→130) |
| **G12** | "`DotState.frozen` flags ≥`MIN_FROZEN_BARS`(8) bars" / "`FETCH_DEPTH` scans 200 RAW rows" | `DotHealthCanary.java:83` `FETCH_DEPTH = 200`, `:88` `MIN_FROZEN_BARS = 8` | HOLDS |
| **G12** | "the gate tests `ivAbsBandLow ≤ atmIv ≤ ivAbsBandHigh` (`ConnectTheDotsScorer.java:210-213`)" | `ConnectTheDotsScorer.java:367-370` — exactly that predicate, null-guarded | HOLDS (line citation drifted 210→367; the file gained the A3 `iv-rank-dot` overload 2026-08-01) |
| **G13** | "removing it lifts the composite cap from **0.9574 to 1.0000**" / "Σall = 19.6, −0.8 `iv_rank` = the 18.80 denominator" | Arithmetic: 19.6 − 0.8 = 18.80; (18.80 − 0.8)/18.80 = 0.957446…; corroborated by G16's independent "1.0 of an 18.80 denominator ⇒ ±5.3 pp" (1.0/18.80 = 5.319%) | HOLDS (computed) |
| **G14** | "(c) EXPIRED within 11 h: 'no live position carries a `subaccount_idx`' … **all 4 opens carried one**" | Live DB, IST-bounded `opened_at` on 2026-07-29: 4 rows opened, 4 with non-null `subaccount_idx` | HOLDS — 4/4 |
| **G14** | "The ₹9,641/lot charge is carried from this row, not re-derived" | The row flags its own provenance; not re-derived here | **UNVERIFIABLE** — see §3 |
| **G15** | "Regime table + derived classifier live in `rollup.md` §Session regime; all 17 logged sessions labelled" + "**5 chop days in 17 logged sessions ≈ 29%**" | `docs/signal-analysis/rollup.md:49` §Session regime. Counting rows through 2026-07-29 (the row's write date): 17 rows, of which 07-14 / 07-15 / 07-21 / 07-23 / 07-28 are `chop` = 5 | HOLDS exactly |
| **G15** | "four of the five sit on an expiry: 07-14 and 07-21 are NSE weekly Tuesdays, 07-23 is a BSE weekly Thursday, and 07-28 is the NSE MONTHLY … **2026-07-15 is the only expiry-free chop day**" | `EXTRACT(ISODOW)`: 07-14 = 2 (Tue), 07-21 = 2 (Tue), 07-23 = 4 (Thu), 07-28 = 2 (Tue, last Tuesday of July 2026), 07-15 = 3 (Wed) | HOLDS |
| **G15** | "`session-analysis` SKILL.md step 6 now stamps a row per session and says to re-read G11 when a `chop` lands" | `.claude/skills/session-analysis/SKILL.md:38,43` | HOLDS |
| **G15** | "**2026-07-29 was NOT a trend day** — it is `mixed`, efficiency **0.501**" (correcting G11's stated rationale) | `rollup.md` regime table row `2026-07-29 \| +0.30 \| 0.61 \| 0.501 \| mixed` | HOLDS — and this is itself a correction that was correctly applied |
| **G16** | "`signal_rejections` is a PLAIN OLTP table with the right index, so the Timescale 2.18.2 sorted-merge trap **structurally cannot apply**" | Live DB: `pg_class.relkind = 'r'` for `strategy.signal_rejections`; zero matching rows in `timescaledb_information.hypertables` | HOLDS — the impossibility claim is sound |
| **G16** | "near-miss probe … deployed 2026-08-01" | `DotHealthCanary.java:122-162` carries `NearMissSpec(32, 3, "advances/declines > 32", …)`; running jar's `DotHealthCanary.class` contains `nearMiss` (7 string hits) | HOLDS |
| **G17** | "the hand-maintained `RailMarginSigns` table is **DELETED**, and the drift artifact this row existed to eliminate no longer exists in any form" | Repo-wide grep: zero occurrences of `RailMarginSigns` (plural). The singular `RailMarginSign` enum exists and rides `GateOutcome`, as claimed. Running jar contains the `RailMarginSign` class | HOLDS |
| **B11** | "the audited lookahead was NOT reachable (`IndicatorBank.mappedIndex` end-gates every read — proven empirically)" | `libs/strategy-engine/.../IndicatorBank.java:136-148` — for a non-primary timeframe it returns `indexAtOrBefore(primaryEnd − interval)`, i.e. the last COMPLETED bar; `DailyContextLookaheadTest:33` pins the independence | HOLDS — the unreachability claim is sound |
| **B17** | "`contracts/metrics/trial-metrics-catalog.json` = one **20-metric** contract … in-container smoke: 20 metrics load" | `git show 8e5793bc:contracts/metrics/trial-metrics-catalog.json` → 20 metrics at the time of #712. Today's file has **23** | HOLDS as a dated claim; the number is now stale (informational, not a correction) |
| **F4** | "composite-070 is **UNSATISFIABLE by construction**: `accepts()` admits only pure composite-blocks (composite < 0.60) but its floor is 0.70" | `ShadowVariants.java:204-240` — `accepts` returns `composite.compareTo(floor) >= 0` against the variant floor; the arithmetic holds for a rejected-only book | HOLDS (cited `accepts:117-149`; body now at 204-240) |
| **F9** | "`composite-055` swapped in … live-verified ('shadow challenger variants active: vol-off, vol-12k5, composite-055')" | `docker logs ay-strategy-signal-service` → the quoted line, verbatim, on today's boot | HOLDS |
| **F10** | "`FuturesTermStructureService.staleFallback:182` serves an **IN-MEMORY** `lastGood` map (`:78`) … but **throws 503 when that cache is empty**" | `FuturesTermStructureService.java:79` (`ConcurrentHashMap lastGood`), `:183` `staleFallback`, `:186` throws `ApiException(503, DATA_STALE, …)` when the cached value is absent | HOLDS (citations off by 1) |
| **E3** | still-owner: `ARTHA_OPENALGO_GLOBAL_QUOTES_ENABLED`, and (C6) `ARTHA_EVO_SCHEDULER_ENABLED` default-OFF | `.env`: both absent or empty | HOLDS |
| **E4** | "**Live today the rail is immediately reachable: 6 open positions**" / "`max_open_paper_positions=7` … shared by BOTH strategies through one `Books.MANAS_ARORA` key" | Live DB: `manas-arora` book has exactly 6 `status='OPEN'` rows; `risk_settings` for `manas-arora` → `max_open_paper_positions {"value": 7, "enabled": true}`, one row for the shared book | HOLDS (re-queried today, not recalled) |
| **E5** | "2027 NSE/BSE calendar CSV refresh before ~2026-11-16" | `libs/market-calendar/src/main/resources/nse-trading-holidays.csv` covers 2024 / 2025 / 2026 only | HOLDS |
| **§0a B1** | "**root cause: 0/63 YAMLs carry the tag** … 63/63 tagged + guard; deployed, 38 republished, DB-verified" | 63 of 63 files in `scalper-strategies/` contain `relative-volume-floor`; live DB: 63 published scalper versions carry the tag in `config->'tags'` | HOLDS |
| **§0a D8** | "the 12 `index_points` YAMLs (connect-the-dots ×6, trending-oi ×6; **8 enabled+published live**)" | Live DB: 12 published scalper strategies whose config references `index_points`, of which 8 are `enabled` | HOLDS — 12 and 8, exactly |
| **§0a D2+D3** | "oi_spurt floors (50,8)→(15,3) + vwap dot ≥15 bps … Spring props, deploy-effective, NO republish" | Live container env: `ARTHA_SCALPER_OI_SPURT_OI_PCT=15`, `ARTHA_SCALPER_OI_SPURT_PRICE_PCT=3`, `ARTHA_SCALPER_OI_VWAP_MIN_DISTANCE_BPS=15` | HOLDS |
| **C8** | "Armed in `.env` … ntfy + WS at the NOTICE floor, Telegram off … jar fingerprint carries `findLatest`, engine 38 loaded / 0 dropped" | Live env: `…NTFY_ENABLED=true`, `…WS=true`, `…SEVERITY_FLOOR=NOTICE`, `…TELEGRAM_ENABLED=false`; jar `InsightRepository.class` contains `findLatest`; boot log `signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)` | HOLDS — all five sub-claims |

---

## 2. The FALSE claims, with corrections

### 2.1 G11 — "`time_stop: { max_bars: 10 }` is on all 63 scalper YAMLs, armed fleet-wide by T21/#990"

**Two separate errors in one sentence.**

**(a) The value is not uniform.** Every scalper runs a `3m` primary (`timeframes.primary`, 63/63), so
`max_bars` converts directly to wall-clock. Measured two independent ways — live DB published
configs, and the 63 YAML files on disk — with identical results:

| `max_bars` | wall-clock | published configs | of which enabled |
|---|---|---|---|
| 10 | **30 min** | 18 | 12 |
| 12 | 36 min | 12 | 8 |
| 16 | 48 min | 3 | 2 |
| 20 | 60 min | 18 | 12 |
| 30 | 90 min | 9 | 4 |
| *(none — `max_holding_days: 1`)* | BTST | 3 | 0 |
| | | **63** | **38** |

So **18 of 63 configs — and only 12 of the 38 live strategies — run the 30-minute stop.** The
heterogeneity is deliberate and documented in the YAMLs' own inline comments
("`~90 min cap`", "`~48 min cap into the 15:20 square-off`", "`a gap trade is a 30-60 min play`").

**(b) The attribution is wrong.** `git show` of #990's merge commit `64f9caaa` against
`scalp-connect-the-dots-nifty.yaml` touches `time_stop`/`max_bars` **zero** times — #990 is
"T21 — premium bands (TP +35% / SL −25%) on all 42 bracket-less scalper YAMLs". The `time_stop`
rules predate it by more than a month (`-S max_bars` on that file: #42 @ `6ae99274` 2026-06-20 and
#226 @ `b5a2cda9` 2026-06-26).

**What *is* true:** a `time_stop` exit rule is present on **63 of 63** published scalper configs
(zero strategies lack one), so the row's "the stop is already armed fleet-wide … the decision is a
no-op on the running engine" holds — but at *five different horizons*, not one.

**Why this is load-bearing.** The G11 decision cell records that T1, T7, G13 and G10's rejections
"were CONDITIONAL on the exit staying at 30 min" and are now "FINAL rather than stop-conditional".
G10's cell says the same in its own words: "All four were measured under the 30-minute `time_stop`."
Both counterfactuals (07-29's 41-leg and 07-31's 22-leg) model *one* 30-minute stop across the
would-have-fired set. If two-thirds of the live fleet exits at 36–90 minutes instead, the modelled
exit does not match the fleet it is being generalised to. **This does not overturn the decision** —
the chop-day evidence is directional and the row's own caveats about sample size stand — but the
next person to re-run those four measurements (which the row says a change in exit doctrine would
require) must know the population is heterogeneous.

### 2.2 G5 — "New counters `ay_futures_oi_snapshot_quote_retries_total` + `..._skips_total`"

The `...` elision implies `ay_futures_oi_snapshot_quote_skips_total`. That meter does not exist.
The real pair, in code (`FuturesOiSnapshotService.java:70-71`) and live on
`/actuator/prometheus`, is:

- `ay_futures_oi_snapshot_quote_retries_total`
- `ay_futures_oi_snapshot_skips_total`  ← **no `quote_` segment**

The row's operational instruction — "retries advancing with skips flat = the fix working; skips
advancing = contention outgrew the patience" — is correct, but an operator following it verbatim
greps for a name that returns nothing, and an absent meter reads as *flat*, i.e. as the fix working.
Failure in the safe-looking direction. (`MonitorSchedulingConfig.java:122` already spells the real
name correctly; only the ledger is wrong.)

### 2.3 F10 — "`reload()` calls `bankCache.clear()` unconditionally (`:317`)"

True when written (2026-07-16); **false as of 2026-08-02**. #1226 @ `e5f465c0`
("evict the indicator-bank cache selectively on reload") replaced it. There is no `bankCache.clear()`
call anywhere in `SignalEngine` now — `:941` is a comment *referring* to it, and the live behaviour is
`SignalEngine.java:995`: `bankCache.keySet().removeIf(key -> !freshBankKeys.contains(key))`.

This matters because the claim is the stated **reason** the row's original spec (an OR-clause on the
20 s reconcile) was rejected: "a 20s retry loop through a sustained partial outage would wipe every
working strategy's warm ta4j bank every 20s". That premise no longer holds. The built design (Redis
`kite.status` listener + bounded 3×~35 s retry) may still be the right one, but anyone revisiting
F10 would be reasoning from an obsolete constraint.

### 2.4 / 2.5 F7 and E3 — two flags recorded as un-armed are armed

`.env` carries both, and both are real arms (compose default `${…:-}` is empty at
`docker-compose.yml:538`; the app default is blank at `application.yml:125`, and
`ScalpAlertService.java:158` documents that a blank origin is what makes the link relative/untappable):

- `ARTHA_NOTIFIER_APP_BASE_URL=http://127.0.0.1:8080` — F7 says the deep link is "dormant until owner
  sets" it, and E3 lists it under "Still owner". Both stale.
- `ARTHA_HEARTBEAT_URL` = a `hc-ping.com` URL — E3 lists "heartbeat URL (needs an owner-created
  healthchecks.io check)" under "Still owner". Stale; the check exists. (Corroborated by the
  `batch-liveness-outage-gap` memory note, which records the #640 heartbeat as **armed**.)

⚠️ **A precision the correction preserves:** `http://127.0.0.1:8080` is loopback-only. The deep link
is *emitted* (no longer dormant), but it is only tappable on the host — it is not "the reachable app
origin" the row asks for. So the arming action is partially, not fully, discharged, and that
distinction is now written into the row rather than left as a silent "dormant".

The other two E3 flags checked — `ARTHA_OPENALGO_GLOBAL_QUOTES_ENABLED` and (C6's)
`ARTHA_EVO_SCHEDULER_ENABLED` — are genuinely absent/empty. Those sub-claims hold.

---

## 3. UNVERIFIABLE

**G14's `₹9,641/lot` charge.** Every rupee figure in G14 (3 converging = ₹28,923 passes, 4 =
₹38,564 refused; 5/6/7 = ₹48,205 / ₹57,846 / ₹67,487) is derived from it, and the row itself flags
this: "The ₹9,641/lot charge is carried from this row, not re-derived; every ₹ figure moves with it
if it is wrong." Re-deriving it needs the sizer's rounded-down lot against a specific 2026-07-29
option leg and the then-current `budget_inr`, which is a measurement task rather than a claim check.

**What would settle it:** replay `PositionSizer` against the four 2026-07-29 scalper opens
(`strategy.paper_positions` rows with `opened_at` in that IST day, which do carry
`advised_lots` and `avg_entry_price`) and compare the computed per-position `usageFor` charge to
₹9,641. That is a bounded, read-only check; it was left out of scope here because it is arithmetic
re-derivation rather than claim verification.

---

## 4. Pattern notes (no correction applied)

**Line-number citations drift, substance does not.** Six cited `file:line` anchors had moved —
`ScalperOiProps:102-104` → 103-105, `ScalperConfluenceGate:1213-1219` → 1364-1372,
`ConnectTheDotsScorer:210-213` → 367-370, `IvAnalyticsService:117` → 130,
`ShadowVariants.accepts:117-149` → 204-240, `FuturesTermStructureService:78/182` → 79/183. **In
every one of the six the claim itself was correct** — the code had simply grown above it. Two
implications: a stale line number is weak evidence of a stale claim, and a claim citing only a line
number (no symbol name) would have been unverifiable. Every one of these six was recoverable
*because* the row also named the symbol. That is the habit worth keeping.

**Deployed/live-verified claims were the strongest category.** Every one checked reproduced exactly
— G2's boot log character-for-character, G3's 44/69/0 metrics unchanged seven days on, G5's
372-of-375 cadence and all five prior-session counts re-derived from the DB, G12's four-for-four
prior-day IV match, C8's five arming sub-claims. The two false arming claims (F7, E3) failed in the
opposite direction from the usual worry: they under-claimed. Something was armed and the ledger still
says it is not.

**§0a was clean.** Every §0a claim checked (B1, D8, D2+D3, S-row scheduler beans) held exactly,
including two precise counts (63/63 tags; 12 index_points of which 8 enabled). No corrections.

---

## 5. Claim labels

- **computed:** G13's composite-cap arithmetic (0.9574, 18.80 denominator) and G16's 5.3 pp;
  69/3000 = 2.3%; the `max_bars` → wall-clock conversion in §2.1 (`3m` primary × bars).
- **sourced:** every code citation (file:line read in this worktree at `bf93ebf7` + #1226), every
  live-DB count and every container env/metric/log line quoted above; #990's merge diff and #1075's
  PR state via `gh`.
- **recalled:** nothing load-bearing. The G4 correction was read from the ledger and used only as the
  format template, not as evidence.
- **assumed:** that `slug LIKE 'scalp%'` enumerates exactly the scalper fleet. Cross-checked — it
  returns 63, matching the 63 files in `scalper-strategies/`, so the assumption is corroborated
  rather than bare.

---

## 6. Open doubts

1. **The G11 correction does not re-run the analysis.** I established that the fleet's stop is
   heterogeneous; I did **not** determine whether the 07-29 and 07-31 counterfactuals sampled legs
   from all five horizons or predominantly from the 12 `max_bars: 10` strategies. If the
   would-have-fired sets happen to be drawn mostly from the 30-minute cohort, the modelled stop
   matches its population and the practical impact is small. Settling this needs the two findings
   docs' per-leg slug attribution joined to the `max_bars` table in §2.1 — a bounded, read-only
   follow-up I deliberately did not attempt inside a claim-audit shard.
2. **I did not verify that the *engine* honours `max_bars` per strategy** rather than applying a
   single global horizon. The YAMLs and DB configs differ; whether `ExitEvaluator`/`SignalEngine`
   actually reads each strategy's own `time_stop.params.max_bars` at runtime was not traced. If it
   somehow did not, the correction's direction would change (the ledger's uniform reading would be
   accidentally right about behaviour while wrong about config). I consider this unlikely — the rule
   is per-strategy config in a per-strategy exit-rule list — but it is untested here and it is the
   one thing that could invalidate §2.1's significance.
3. **F7's arming may be deliberate-but-partial.** `http://127.0.0.1:8080` is a plausible placeholder
   someone set to unblock local testing rather than a considered production arm. I corrected the
   row to say the flag is SET and loopback-only; I did **not** decide whether the owner intends it as
   the final value. If it was a test value, the row's underlying *intent* ("owner still needs to pick
   a reachable origin") survives, which is why the correction preserves that half.
4. **E3's remaining "still owner" items were only half-checked.** I verified four flags. The row also
   lists `source.optionanalytics` PCR flip and the Minervini/Manas low-cap gate ("needs a compose
   passthrough"); those were not checked and may be stale in the same way.
5. **B17's "20-metric contract" is now 23.** I confirmed it was 20 at #712, so the dated claim is
   sound and I applied no correction. But a reader today who greps the catalog will find 23 and may
   reasonably wonder which number is authoritative. Left alone deliberately — shard B owns
   DONE-cell currency.
6. **Group G rows are enormous** (G6 is 8.7 KB, G9 9.5 KB, G10 8.4 KB in one table cell). I checked
   the claims I judged load-bearing by the brief's four priority classes; I did **not** exhaustively
   enumerate every assertion in every cell. A claim I skipped is not a claim that holds.
7. **The five FALSE findings are all in the "stale, not fabricated" family** — unlike G4, which
   invented a job that never existed. Four were true when written and were overtaken by later work
   (#1226) or by an arming action nobody came back to record. That is a different failure mode from
   G4's, and it suggests a different remedy: G4 needed verification-before-writing; these needed
   a re-read trigger when the thing they describe changes.
