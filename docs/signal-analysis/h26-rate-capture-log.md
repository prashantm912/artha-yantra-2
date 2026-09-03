# H26 rate-capture log — the kill-criterion data

One row per trading session. **Append, never overwrite.**

⚠️ **Why this file exists.** `ay_upstox_rate_window_peak_used` is a **per-process HIGH-WATER MARK**. It never decays and
it **resets on deploy, with nothing in the metric revealing the reset**. A session's peak is therefore lost the moment
market-data is recreated — so it must be captured before any post-close deploy and written down here.

⚠️ **Two rules that apply to every row, and misreading either produces a wrong verdict:**

1. **Kite is primary today, so Upstox usage measures HEADROOM, not capacity.** The projection is
   *Upstox-today PLUS the migrating Kite share* — never Upstox's own number alone.
2. **These are REQUEST counts, not instrument demand.** Kite chunks at 250 instruments/request
   (`KITE_QUOTE_BATCH_SIZE`); the Upstox quote gateway sends ONE unchunked call. **Raw request transfer is the WRONG
   model** — the OI snapshot's ~71 batched `/quote` calls per ~2-min cycle map to ~6 `/v2/option/chain` calls under
   Upstox, roughly 12:1 on the dominant term.

## Rows

| session | coverage | Upstox 30m peak | 1m peak | 1s peak | Upstox batch / live | live refused | Kite QUOTE | Kite HIST | Kite DUMP |
|---|---|---|---|---|---|---|---|---|---|
| **2026-08-26** (Wed) | **100%** — process up 08:41 IST, before the 09:15 open; captured 16:23 pre-deploy | **632 / 1800** (35%) | 254 / 450 (56%) | 17 / 45 (38%) | 1,272 / 32 | **0** | **17,669** | 596 | 1 |
| **2026-08-27** (Thu) | **100%** — process up 08:40 IST (host downtime boot), before the 09:15 open; captured 19:22, post-close, NO deploy since boot so the high-water marks are the session's | **215 / 1800** (12%) | 194 / 450 (43%) | 9 / 45 (20%) | 210 / 50 | **0** | **16,886** | 661 | 1 |

## ⚠️ Reading of 2026-08-27 — THE BASELINE MOVED, AND THE TWO ROWS ARE NOT COMPARABLE

**`computed` 2026-08-27 19:22. Upstox batch calls fell 1,272 → 210 (6.1×) and the 30-min peak
632 → 215, while the Kite side barely moved (QUOTE 17,669 → 16,886, HISTORICAL 596 → 661).**

⚠️ **This is not a quieter session. It is a DIFFERENT SYSTEM, and the change is ours.** The H31
`day-context` precompute deployed on the evening of 2026-08-26 — *after* that day's 16:23 capture.
`overnightCues()` reaches `UpstoxGlobalInstrumentsClient.worldIndices()`, an **uncached** batched
Upstox `/v2/market-quote/quotes` call, on **every** `day-context` compute; the precompute collapsed
those from once-per-caller to **4 per hour on a dedicated schedule**. The Upstox term this log
exists to measure is therefore a **direct function of a fix we shipped for an unrelated reason**.

**Consequences, and they are load-bearing for the stop rule:**

1. **The 2026-08-26 row is a PRE-FIX observation and must not be averaged with post-fix ones.**
   A five-session projection mixing the two measures neither system. On the current count this
   leaves **one** post-fix session, not two.
2. **The direction is favourable and should not be over-read.** Upstox headroom is now 12% of the
   30-min window against 35% the day before, `live_refused` still **0**. But the migrating Kite
   share — the term that actually decides H26 — is **unchanged**, so the naive projection
   (215 + ~1,414 ≈ 1,629) still fails the 1440 stop rule. **The call-shape factor remains the
   whole question; nothing measured today changes that.**
3. **A general lesson worth carrying: an unrelated fix moved the metric a kill-criterion is keyed
   on, silently.** Nothing in the H31 work mentioned Upstox rate consumption, and nothing in this
   log would have flagged the discontinuity if the two numbers had happened to land closer
   together. **Before adding a session to this tally, ask what deployed since the last row.**

⚠️ **Capture provenance:** today's 15:25 `h26-daily-rate-capture` task **did not append a row** —
this one was taken by hand at 19:22 and survived only because no deploy had recreated the process.
That is exactly the silent loss the header warns about; it is recorded rather than quietly fixed.

### Independent corroboration from the 15:28 scheduled capture (PR #1502, closed unmerged)

⚠️ **The 15:25 task DID run.** It captured at 15:28 IST and opened [#1502](https://github.com/prashantm912/artha-yantra-2/pull/1502);
the PR was never merged, so the row never reached `main`. An earlier note in this file said the capture
"did not append a row" — that was **wrong, and wrong in an instructive way**: the file on `main` was checked
and the open PRs were not. Absence from a file is not absence of work.

**Its numbers are kept here rather than as a second row, because this file allows ONE row per session.**
Captured 15:28 (pre-close) against the merged row's 19:22 (post-close), so the two are not directly
comparable — and the difference is itself informative:

| term | 15:28 capture | 19:22 capture (the row above) |
|---|---|---|
| Upstox 30m peak | 215 | 215 |
| Upstox `batch` | 210 | 210 |
| Kite `QUOTE` | 16,657 | 16,886 |
| Kite `HIST` | 403 | 661 |

**The Upstox terms are IDENTICAL at both times, and the Kite terms are not.** So the Upstox load had already
stopped growing by 15:28 while Kite kept working through the evening chain — consistent with the H31
precompute having capped the Upstox term at 4 calls/hour, and with the evening jobs being Kite-only.

⚠️ **The fact worth keeping, which the merged row does not carry:** normalised per 30-min window, the
migrating Kite term is **≈1,340 (08-27) vs ≈1,414 (08-26) — STABLE across the two sessions**, while the
Upstox term moved 6.1×. That is what makes the H31 precompute the credible cause of the Upstox shift rather
than a quieter market: a quiet session would have moved BOTH terms.

⚠️ #1502 also recorded the Upstox gap as **unexplained**, which was correct at 15:28. It is explained now
(the precompute deployed the previous evening) — recorded so the "unexplained" framing is not re-inherited.

## Reading of 2026-08-26 — `computed`, and it is ONE session

**The raw-transfer model FAILS the stop rule, exactly as the plan predicted.**

- Session = 375 min = **12.5** half-hour windows.
- Kite `QUOTE` 17,669 / 12.5 ≈ **1,414 requests per 30-min window** (mean; the peak window is higher).
- Naive projection: 632 + 1,414 = **≈2,046** against an 1800 ceiling — **over the ceiling entirely**, and far past the
  **1440 (80%) stop rule**.

⚠️ **This is NOT a verdict to stop.** It is the arithmetic the plan already flagged as the wrong model: raw request
transfer ignores that the dominant Kite consumer remaps ~12:1 onto Upstox's chain endpoint. **Only the remapped model can
pass, which is precisely why the call-shape factor is mandatory rather than a refinement.**

What this row genuinely establishes:
- **Upstox headroom today is comfortable** — 35% of the 30-min window, `live_refused` = **0**, so nothing is being
  throttled at current load.
- **The migrating Kite share is large in request terms** (17,669/session) and **dominated by one consumer**. `computed`
  from a call-site census: of 11 Kite `QUOTE` call sites only two are self-scheduled — `FuturesOiSnapshotService`
  (cron `0 * * * * *`, but **one batched call per pass** ≈ 375/session, so NOT the dominant term) and
  `PreOpenEquityScanService` (once daily). The remainder is request-driven, and the bulk is consistent with
  `OptionsSnapshotService` (~70 batched calls per 2-min cycle ≈ 35/min ≈ 13k/session).
- **So the whole projection turns on one job's call shape** — not on diffuse load. That is a much more tractable
  question than the raw number suggests, and it is the next thing to measure.

⚠️ **One session is not a rate.** The stop rule needs **five**. Do not act on this row alone.

## Next

`h26-daily-rate-capture` (weekday 15:25 IST, before close and before any deploy) appends a row per session. After five
sessions, compute `projected_upstox_30m = 30m peak + Σ(Kite 30m rate × call-shape factor)` and apply the stop rule.

⚠️ Under end state **(b)** (Upstox-only, Kite dormant — owner decision 2026-08-26) there is **no fallback to absorb a bad
projection**, so the stop rule is **more binding, not less**.

---

## Row — 2026-08-28

| session | coverage | Upstox 30m peak | 1m peak | 1s peak | Upstox batch / live | live refused | Kite QUOTE | Kite HIST | Kite DUMP |
|---|---|---|---|---|---|---|---|---|---|
| **2026-08-28** (Fri) | **~99%** — process up **08:39:35 IST**, before the 09:15 open, **no restart**; captured **15:27**, 3 min before close. ⚠️ An expired-backfill self-heal resume was **still running** at capture, so the peak is a **lower bound** | **1291 / 1800** (**72%**) | 360 / 450 (**80%**) | 16 / 45 (36%) | 1,286 / 40 | **0** | **17,726** | 390 | 1 |

`computed` 2026-08-28 15:27 IST from `ay-market-data-service` `/actuator/prometheus`; container start `sourced` from `docker inspect` (`2026-08-28T03:09:35Z`).

## ⚠️ Reading of 2026-08-28 — THE 08-27 ATTRIBUTION IN THIS FILE IS WRONG, AND THIS ROW DISPROVES IT

The two sections above attribute the 08-27 collapse in Upstox load (batch 1,272 → 210, 30m peak 632 → 215)
to the **H31 `day-context` precompute** capping `worldIndices()` at 4 calls/hour. **That is not the cause.**

**`computed`, two independent measurements:**

1. **`ay_day_context_snapshot_refresh_total` = 27** at 15:27 today. `UpstoxGlobalInstrumentsClient.worldIndices()`
   is **one batched call** per refresh (`UpstoxGlobalInstrumentsClient:38`, `DayContextService:521-526`), so the
   whole H31 term is **~27 Upstox calls per session**. It is **arithmetically incapable** of moving a ~1,000-call
   swing in either direction.
2. **The real driver is `ExpiredBackfillAutoResume`'s per-session workload**, `sourced` from the boot logs:

   | session | boot resume ran | duration | outcome | Upstox `batch` |
   |---|---|---|---|---|
   | 2026-08-27 | 03:10:23–03:11:28Z | **65 s** | 104 expiries, **0 written**, 31,923 skipped, **0 rows** | **210** |
   | 2026-08-28 | 03:09:51–07:27:00Z | **4 h 17 m** | 105 expiries, **269 written**, 168 failed, **978,059 rows** | **1,286** |

   On 08-27 the backfill had **nothing to fetch**; today it fetched a year of contracts. The Upstox term tracks
   that, not the precompute. (08-27's own boot log survives at
   `/c/Trading/ArthaYantra/log-snapshots/2026-08-27/market-data-service.log`.)

⚠️ **How the wrong attribution passed.** H31 deployed on the evening of 08-26, the number moved on 08-27, and the
mechanism was plausible — nobody checked whether the mechanism was **big enough**. One metric read
(`refresh_total = 27`) falsifies it. **A correlated deploy is a hypothesis; the order of magnitude is the test.**
Same shape as [[a-diagnostic-inferred-from-one-case]]: a rule induced from one confirmed instance.

⚠️ **The claim that survives, and it matters more:** the 08-27 note observed the migrating **Kite** term was
**stable** (≈1,340 vs ≈1,414 per 30-min window) while Upstox moved 6.1×. That is still true today — Kite `QUOTE`
17,726 at 15:27 vs 16,657 at the same clock time on 08-27. **The Kite side, which is the term H26 actually turns
on, has been flat across all three sessions.** Only the Upstox baseline is noisy, and now we know why.

### What this row does to the stop rule

- **Upstox headroom is no longer comfortable.** 30m peak **1291/1800 = 72%**, 1m peak **360/450 = 80%** — both the
  highest recorded, and the 30-min figure is already **within 12% of the 1440 stop threshold before adding a single
  migrated Kite call.** `live_refused` is still **0**, so nothing was throttled.
- ⚠️ **The peak is ~97% of the session's entire Upstox call count** (1291 of batch+live = 1,326; corroborated by
  `http_client_requests_seconds_count` to `api.upstox.com` = 1,326). **The load is one concentrated burst, not a
  session rate.** Which sub-window carried it is `assumed` — the counter cannot be sliced by caller.
- **Consequence for the projection, and it changes the model:** the Upstox term is **dominated by a batch backfill
  whose size depends on how much history is outstanding**, not by steady session load. Averaging it across five
  sessions measures the backlog schedule, not capacity. **The five-session projection must separate the
  backfill burst from the steady term**, or it will report whatever the backfill happened to be doing that week.

⚠️ **Still no verdict.** Post-fix sessions: 08-27, 08-28 — **two of five**, and the two disagree by 6×. Do not act.

⚠️ **Unrelated observation, recorded not acted on:** the boot log carries
`kite session status -> TOKEN_EXPIRED` at 08:40 IST. PRs
[#1518](https://github.com/prashantm912/artha-yantra-2/pull/1518),
[#1519](https://github.com/prashantm912/artha-yantra-2/pull/1519) and
[#1520](https://github.com/prashantm912/artha-yantra-2/pull/1520) are open against exactly that. Out of scope for
this read-only capture.

---

## Row — 2026-08-31

| session | coverage | Upstox 30m peak | 1m peak | 1s peak | Upstox batch / live | live refused | Kite QUOTE | Kite HIST | Kite DUMP |
|---|---|---|---|---|---|---|---|---|---|
| **2026-08-31** (Mon) | **100%** — process up **08:32:30 IST**, before the 09:15 open; `RestartCount=0`, **no restart**; captured **15:28**, 2 min before close | **215 / 1800** (12%) | 213 / 450 (47%) | 20 / 45 (44%) | 210 / 31 | **0** | **17,403** | 789 | 1 |

`computed` 2026-08-31 15:28 IST from `ay-market-data-service` `/actuator/prometheus`; container start `sourced` from
`docker inspect` (`2026-08-31T03:02:30Z`, `RestartCount=0`).

## Reading of 2026-08-31 — the 08-28 attribution REPRODUCES, exactly

**The 08-28 row claimed the Upstox term is driven by `ExpiredBackfillAutoResume`'s per-session workload, not by the
H31 precompute. Today reproduces that prediction to the digit.**

`sourced` from today's boot log:

```
2026-08-31T03:03:24Z  expired-backfill … done: 104 expiries, 32081 contracts,
                      0 written, 32081 skipped, 0 failed, 0 rows   (ran 03:02:46→03:03:24, 38 s)
```

Nothing outstanding to fetch — the **same shape as 08-27**, and the Upstox terms land on the same numbers:

| session | backfill outcome | Upstox `batch` | Upstox 30m peak |
|---|---|---|---|
| 2026-08-27 | 0 written, 0 rows, 65 s | **210** | **215** |
| 2026-08-28 | 269 written, 978,059 rows, 4 h 17 m | 1,286 | 1,291 |
| **2026-08-31** | **0 written, 0 rows, 38 s** | **210** | **215** |

⚠️ **`batch` = 210 and the 30-min peak = 215 on both empty-backfill sessions, byte-identical.** That is not a
correlation any more — it is a **fixed steady-state cost**, and the backfill is the entire variable term. The
08-28 warning that "the five-session projection must separate the backfill burst from the steady term" is now
**measured rather than argued**: the steady term is **215 / 1800 = 12%** and it does not move.

`ay_day_context_snapshot_refresh_total` = **28** today (27 on 08-28), one batched Upstox call each — confirming
again that the H31 precompute is ~28 calls/session and arithmetically incapable of the swings this log recorded.

⚠️ **A stronger control than either previous session offers.** Roughly **twenty PRs merged and deployed between
the 08-28 row and this one** (`#1518`…`#1542`, including live-engine changes H20/H44/D3/N23-A/N26). The Upstox
steady term did not move by a single call. **Code churn is not a confound for this metric; backfill backlog is
the only one found so far.**

### The Kite side — still flat, which is the term H26 turns on

| session (same ~15:28 clock time) | Kite `QUOTE` | per 30-min window |
|---|---|---|
| 2026-08-27 | 16,657 | ≈1,340 |
| 2026-08-28 | 17,726 | ≈1,426 |
| **2026-08-31** | **17,403** | **≈1,400** |

`computed`: 09:15→15:28 = 373 min = 12.43 half-hour windows; 17,403 / 12.43 ≈ **1,400**. Spread across the three
comparable sessions is **±3%**. **Four sessions in, the migrating term is the stable one and the Upstox baseline
is the noisy one** — the opposite of what the first two rows suggested.

### Stop-rule arithmetic, on the steady term

- Naive raw-transfer projection: **215 + 1,400 = 1,615** against the 1440 stop threshold — **still FAILS**, and
  now on the *quietest* possible Upstox baseline. The naive model cannot pass on any session this log has seen.
- Remapped model (the only one that can pass): needs the **call-shape factor** for the dominant Kite consumer
  (`OptionsSnapshotService`, ~70 batched `/quote` calls per 2-min cycle → ~6 `/v2/option/chain` calls, ≈12:1).
  At 12:1 the migrating term is ≈117 and the projection is ≈332 — comfortably under. **That factor is still
  `assumed`, never measured, and it remains the whole question.**

⚠️ **Still no verdict — this is session four of five.** But the shape of the answer is now clear: **H26 is decided
by measuring one job's call shape, not by accumulating more sessions.** A fifth row will add a decimal place to
1,400; it will not decide anything. Recording that so the fifth capture is not mistaken for the deciding one.

---

## Row — 2026-09-01 ⚠️ NULL ROW, 0% COVERAGE — DO NOT COUNT IT AS SESSION FIVE

| session | coverage | Upstox 30m peak | 1m peak | 1s peak | Upstox batch / live | live refused | Kite QUOTE | Kite HIST | Kite DUMP |
|---|---|---|---|---|---|---|---|---|---|
| **2026-09-01** (Tue) | **0% of the session.** Host was DOWN 12:42–18:45 IST; the process holding the session's high-water marks died with it and is **unrecoverable**. Current process started **18:48:59 IST**, uptime **4.3 min** at capture (19:53 → see below), covering only the post-boot burst + the 18:45 evening chain | 213 / 1800 — **NOT a session peak** | 213 / 450 — **NOT a session peak** | 14 / 45 — **NOT a session peak** | 210 / 3 | **0** | **74** | 250 | 0 |

**Every number in that row is post-close.** Per this file's own rule, a truncated peak must never be quoted as a
session peak, so the four session-peak cells are struck out in prose rather than folded into any average.

### What actually happened, `computed` 2026-09-01

- **Windows System log** (`sourced`, events 6005/6006/1074/6008): boot **07:48:25**; winlogon-initiated shutdown
  **12:42:12**, log service stopped **12:42:17**; boot **18:45:33**, an **unexpected** shutdown at that same
  moment (6008), then boot **18:47:43**.
- **All 11 containers report `Up 4 minutes`** including `ay-timescaledb`, `ay-redis` and `ay-wiremock`, and
  `RestartCount=0`. The market-data image is dated **2026-08-29T14:47:37Z** — unchanged. **This was a host boot,
  not a deploy**, which matters because the usual suspect for a lost high-water mark here is a post-close deploy.
- **Live tick capture stopped at 11:56 IST**, ~46 min *before* the shutdown. Per-hour distinct minutes
  (`source='TICK_AGG'`): 09h **45**, 10h **60**, 11h **56**, then nothing. The single 15:29 bucket in the table
  (117 bars) carries `fetched_at = 18:49:22` — it is the **EOD bhavcopy ingest writing a close bar**, not live
  capture, and reading it as live coverage is exactly the trap the `TICK_AGG` filter exists to prevent.
- `marketdata.ingest_runs`: `OPTIONS_SNAPSHOT_CAPTURE` SUCCESS 09:18 (475,044 rows); the whole evening chain
  (BHAVCOPY / NSE_* / MANAS_SCREEN / DATA_QUALITY / MINERVINI_SCREEN / EQUITY_BREADTH) SUCCESS 18:49–18:51,
  post-boot. **The evening chain was not lost; only the intraday metrics were.**
- **The 15:25 capture slot could not fire** — the box was off from 12:42 to 18:45. This run executed at **18:53**.

### The one thing this null row DOES measure, and it is not nothing

`computed`, two scrapes 18:53 and 18:55, byte-identical: a process that saw **no trading session at all** logged
**210** Upstox `batch` calls and a **30-min peak of 213**, then went **flat** (Kite `QUOTE` likewise frozen at 74).

**210 batch / ~213 peak is precisely the figure the 2026-08-27 and 2026-08-31 rows report as a *whole-session*
value.** So the Upstox baseline term this log has been treating as a session measurement appears to be dominated
by a **boot-and-batch burst that is very nearly session-independent** — 08-31's process booted 08:32 and by 15:28
had accumulated the same 210, and today's booted 18:48 and reached 210 within four minutes with the market shut.

⚠️ **`assumed`, and deliberately not promoted further.** This is one observation, and the file already records
what happens when a rule is induced from one case. Two things it does *not* establish: which call sites make up
the 210 (never enumerated), and whether the 08-28 outlier (1,286 batch / peak 1,291) shares the same base — that
row was explained by an expired-backfill resume and remains the only session where the Upstox term moved at all.
**The distinguishing check is a call-site breakdown of `ay_upstox_calls_total{path="batch"}` across boot vs
intraday, not another day's total.**

If it holds, it *strengthens* rather than changes the standing conclusion: the Upstox baseline is a near-constant
~215 that carries almost no session load, so the stop rule turns entirely on the migrating Kite term and its
call-shape factor — which is what the 08-31 reading already said.

### Stop rule — unchanged, and still undecided

Four usable sessions (08-26 pre-fix, 08-27, 08-28, 08-31), not five; this row adds none. The arithmetic is
untouched: naive raw transfer **215 + 1,400 = 1,615 > 1,440 FAILS**; the remapped model at the `assumed` ≈12:1
call-shape factor gives ≈332 and passes. **The factor is still unmeasured and remains the whole question** —
consistent with the 08-31 note that H26 is decided by measuring one job's call shape, not by accumulating rows.

---

## Session 2026-09-02 (Wed) — the fifth row, and the open question is answered

| session | coverage | Upstox 30m peak | 1m peak | 1s peak | Upstox batch / live | live refused | Kite QUOTE | Kite HIST | Kite DUMP |
|---|---|---|---|---|---|---|---|---|---|
| **2026-09-02** (Wed) | **~99%** — process up **08:35:53 IST**, before the 09:15 open; `RestartCount=0`, **no restart**; image `2026-08-29T14:47:37Z`, **unchanged since the 08-31 row** so the two are the same system; captured **15:27:40**, 3 min before close | **215 / 1800** (12%) | 213 / 450 (47%) | 15 / 45 (33%) | 210 / 34 | **0** | **17,378** | 511 | 1 |

**What deployed since the last row** (the check the 08-27 reading added): **nothing reached the running
process.** `docker image inspect` returns `2026-08-29T14:47:37Z`, identical to the 08-31 and 09-01 rows.
One market-data commit merged to `main` since (`744ff92e`, #1558, `MarketContextEodJob` boot catch-up) but
post-dates the image and is **not deployed**. `sourced`. So 08-31 and 09-02 measure byte-identical code.

**Expired backfill:** `0 written, 32081 skipped, 0 failed, 0 rows`, finished **08:37:01 IST** — the same
quiet outcome as 08-27 and 08-31, not the 08-28 resume that produced this log's only Upstox outlier.
`computed` from the container log.

### The open question from the 08-31/09-01 rows is now ANSWERED, and not from one case

The previous row asked for **"a call-site breakdown of `ay_upstox_calls_total{path="batch"}` across boot vs
intraday"** and warned that another day's total would not settle it. A total did not settle it — an
**arithmetic identity already sitting in every row** did, and today supplies the fourth independent check.

⚠️ **The 1-minute peak is GREATER THAN the entire session's batch count.** `computed`:

| session | batch + live (all Upstox calls) | 1m peak | 30m peak |
|---|---|---|---|
| 2026-08-27 | 210 + 50 = 260 | 194 | 215 |
| 2026-08-31 | 210 + 31 = 241 | **213** | 215 |
| **2026-09-02** | 210 + 34 = **244** | **213** | **215** |

`peak_used{1m}` = 213 while the whole session's `batch` total is **210**. Every batch call the process ever
made therefore fits inside **one minute**, and the 30-minute peak (215) exceeds it by two. This is not an
inference about a burst — it is the burst, measured, with no room left for intraday spread.

Three further observations agree, so this rests on four legs, not one:

1. **09-01's null process reached 210 within four minutes of an 18:48 boot with the market shut.**
2. **Today's process ran a FULL session and finished at exactly 210** — a complete trading day added
   **zero** batch calls over a process that saw no session at all.
3. The burst lands with the expired-backfill sweep at **08:37 IST**, ~38 minutes **before** the 09:15 open.

**Conclusion, `computed`: the ~215 Upstox 30-minute peak is a pre-open boot burst. It carries no session
load whatsoever.** The 08-31 row's `assumed` hypothesis is upgraded.

⚠️ **One thing this weakens, and it is recorded rather than quietly patched.** The 08-27 reading attributed
the 1,272 → 210 collapse to the H31 `day-context` precompute calling `worldIndices()` **4× per hour on a
dedicated schedule**. If those calls carried `path="batch"`, today's ~6.9-hour uptime would have added ~27
to the total. It added **zero**. So either that call is not labelled `batch`, or the precompute does not
reach it. `assumed`, **not chased here** — it does not change any number in this log, and the 08-27 row's
load-bearing claim (the baseline moved, the two eras are not comparable) stands on the measurement, not on
the mechanism. Flagged so the next reader does not treat the mechanism as established.

### Stop rule — FIVE rows reached, computed, and the answer is STILL UNDECIDED

Five sessions now carry data (08-26, 08-27, 08-28, 08-31, 09-02); **four are post-H31-fix and comparable**
(08-26 is pre-fix and must not be averaged in). The Kite term is remarkably stable across all of them:

| session | Kite `QUOTE` | per 30-min window |
|---|---|---|
| 2026-08-27 | 16,657 | ≈1,340 |
| 2026-08-28 | 17,726 | ≈1,426 |
| 2026-08-31 | 17,403 | ≈1,400 |
| **2026-09-02** | **17,378** | **≈1,399** |

`projected_upstox_30m = current 30m peak + Σ(Kite 30m rate × call-shape factor)`, ceiling 1800, stop at 1440:

- **Naive raw transfer** (factor 1.0, the model the header calls WRONG): `215 + 1,400 = 1,615 > 1,440` — **FAILS**.
- **Non-overlap form.** The 215 term is now known to be a **pre-open** burst, so on a clean morning boot it
  does not co-occur with the Kite term in any 30-minute window: `max(215, 1,400) = 1,400 < 1,440` — passes,
  **by 2.8%**. Far too thin to decide on, and it does **not** hold after a mid-session restart (the 09-01
  shape), where the burst lands inside the session and the **sum** is the correct form.
- **Remapped call shape** (`assumed` ≈12:1 on the dominant `/quote` → `/v2/option/chain` term):
  `215 + ~117 ≈ 332` — passes comfortably.

⚠️ **Verdict: NOT STOP, NOT GO — undecided, and five rows was never going to decide it.** The three models
span 332 to 1,615 across a 1,440 threshold; **the spread is entirely the call-shape factor**, which is still
`assumed` and has never been measured.

**Recommendation: stop accumulating daily rows.** Four post-fix sessions agree to the *unit* on the Upstox
side (210 / 213 / 215, three times identically) and within **2%** on the Kite side. A fifth, sixth or tenth
row adds no information — the remaining uncertainty is not sampling noise. **What decides H26 is measuring
one job's call shape** (the OI snapshot's batched Kite `/quote` cycle against the Upstox
`/v2/option/chain` equivalent), a code-and-job measurement, not a session capture. That is the same
conclusion the 08-31 row reached; today's row is what makes it safe to act on.

---

## ✅ RETIRED 2026-09-02 (owner ruling) — the capture is disabled, not deleted

The recommendation above was accepted. `h26-daily-rate-capture` is **disabled** in the scheduler
rather than deleted, so the prompt survives and the decision is reversible if the baseline is ever
suspected of drifting.

**What retires and what does NOT.** The daily *capture* retires. **H26 itself stays open**, and its
verdict is unchanged: **NOT STOP, NOT GO.** The stop rule is undecided because the three projections
still span `≈332 → 1,615` against a 1,440 ceiling, and that spread is *entirely* the unmeasured
call-shape factor:

| projection | value | vs 1,440 |
|---|---|---|
| naive raw transfer | 215 + 1,400 = **1,615** | FAILS |
| non-overlap (burst is pre-open) | max(215, 1,400) = **1,400** | passes by 2.8% |
| remapped ≈12:1 (`assumed`) | **≈332** | passes comfortably |

⚠️ **Retiring the capture is not evidence that H26 is safe.** It is evidence that *this instrument*
has stopped producing information. Reading a retired measurement as a resolved question is exactly
the inversion this file has warned about elsewhere.

**What would actually decide it**, and the only thing that should be scheduled next on H26: measure
one job's call shape — the OI snapshot's batched Kite `/quote` cycle against the Upstox
`/v2/option/chain` equivalent — to get the real remap ratio. That is a code-and-job measurement, not
a session capture, and no number of further daily rows can substitute for it.
