# Live data-health watch — 2026-07-23 (in-session, 09:58–10:06 IST)

Scheduled task `live-data-health-check` (session-analysis `live` mode, README §4.1). Market open
(Thu, ~45 min into the session). **Read-only**: SELECTs, `docker logs`, GET endpoints only — no
restart, deploy, write or config change.

This is a LIVE snapshot, not the session forensics. The immutable
`2026-07-23-session-findings.md` (15:47 post-market task) should fold §4/§5/§6 here into its §4
(data health) and §6 (new data points).

Stack: all 11 containers healthy (booted ~08:37 IST).

**Clock note:** the Windows host wall clock read **09:42 IST while the DB / containers read
09:59 IST** — a ~17-minute host lag. All timestamps in this file are container/DB time (the DB and
both JVM services agree with each other). Flagged because a host-side scheduled task computing its
own IST bounds would mis-window by 17 minutes.

---

## 1 Verdict

**GREEN.** Both machine canaries green, engine liveness confirmed by an advancing counter delta
across a bar boundary, EXT-02 rate budget clean, shadow book opening normally. One transient
anomaly for the owner (§4.1), self-recovered, not actionable mid-session.

---

## 2 Machine canaries (STEP 0)

| canary | result |
|---|---|
| `GET /api/v1/market/health/data` | `status=GREEN`, `marketOpen=true`, `tickedTokens=25`, `problems:[]`, asOf `2026-07-23T04:28:08Z` (09:58 IST) |
| `GET /api/v1/signal-rejections/dot-health` | `session=true`, `rowsInspected=40`; dead = `iv_rank`, `dow`, `fii` (all `required=false`); alive = `breadth` (required), `oi_spurt_price`, `vix` |

**Dead-set diff vs 2026-07-22 findings:** identical — no NEWLY dead, no NEWLY alive dot. Carried set
(`ivRank`/`fiiLongPct` dead since 07-02; `dow` un-armed by design).

## 3 Engine liveness (check 1 — the counters, never `signal_rejections`)

Per [[engine-liveness-is-counters-not-rejections]] the signal is Σ `ay_signal_eval_outcome_total`
advancing, read from the actuator on port **8082**.

| reading (IST) | chart-gate-failed | confluence-blocked | composite-below-threshold | Σ |
|---|---|---|---|---|
| 09:59:42 | 120 | 84 | 12 | **216** |
| 10:02:28 | 138 | 102 | 12 | **252** |

**Σ 216 → 252 (+36 across one 3 m bar boundary) ⇒ engine ALIVE.**
`ay_signal_eval_failures_total = 0.0`. `fired = 0`, `unscoreable-indicators-warming = 0`,
`discipline-paused = 0`, `confluence-gate-absent = 0`.
`ay_signal_eval_duration_seconds`: count 47, sum 165.11 s, **max 21.68 s** — a single eval taking
21.7 s is worth a post-market look (mean ~3.5 s); no stall followed it.

**Boot line (read same day — a restart destroys it):**

```
signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)
signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

Cold boot hit the F10 class again (0 loaded / 38 unresolved), #874 retry recovered before the 09:15
open. Health signal `unresolved == 0` holds.

**Denominator:** 44 published+enabled = 38 `scalp-%` + 6 non-scalper (swing/batch, not loaded by the
live engine), verified by direct query today. 38 loaded / 38 scalpers = full coverage.

**Rejection context (CONTEXT ONLY):** 102 rows / **34 distinct slugs** by 09:58:20 IST; 28 of them
composite-passing (max composite 0.6383 vs threshold 0.6000), 21 of those carrying a resolved
`wouldBeLeg`. Distinct blocking rails on the composite-passing set: `call-put-delta-filter`,
`directional-change-gate`, `divergence-vol-gate`, `pct-price-move`, `supertrend-15m`, `two-candle`,
`volume-floor`, `volume-pump`.

## 4 Owner notes (no action taken — market hours)

### 4.1 kite-rest circuit-breaker burst 09:31–09:34 IST (93 warnings)

```
04:02:12Z (09:32 IST)  scheduled options snapshot failed for NIFTY 50 2026-09-29: kite-rest circuit open; serving cached data
04:04:33Z (09:34 IST)  scheduled options snapshot failed for BA…: kite-rest circuit open; serving cached data
```

93 occurrences, all `OptionsSnapshotService`, confined to **04:01:1x–04:04:33Z (09:31–09:34 IST)**,
plus 2 stragglers at 03:08Z during boot. None since. The breaker fell back to cached data as
designed, and chain capture is demonstrably intact:

| underlying | snaps since 09:15 | distinct minutes | first | last |
|---|---|---|---|---|
| NIFTY 50 | 27,090 | 22 | 09:17 | 10:02 |
| SENSEX | 49,400 | 26 | 09:18 | 10:02 |
| BANKEX | 22,032 | 21 | 09:19 | 10:03 |
| NIFTY BANK | 20,370 | 21 | 09:18 | 10:02 |
| NIFTY MID SELECT | 18,102 | 22 | 09:18 | 10:02 |
| NIFTY FIN SERVICE | 11,172 | 21 | 09:18 | 10:02 |

Post-market question: what tripped the kite-rest breaker ~16 min after the open, and whether the
3-minute chain cadence dropped any bucket inside the 09:31–09:34 window (the counts above are
session totals, not per-bucket).

### 4.2 Boot-time DB race on market-data (benign, carried)

At 03:07:34Z (08:37 IST) `market-data-service` failed its first boot with
`PSQLException: FATAL: the database system is starting up` (Hikari fail-fast through
`KiteSessionStore` → `LiveKiteConfig:102`). Compose restarted it and it came up healthy well before
the open. Same class as previously observed boot-ordering noise; noting it only because it shares
the 08:37 boot window with the F10 cold-start in §3.

### 4.3 futures-OI capture cadence still thin

`marketdata.futures_oi_snapshots`: **27 rows per symbol over 09:15–10:01** (46 elapsed minutes) —
consistent with the carried T12 cadence decline (187/375 on 07-22 after 192, 208). OI quadrants are
being served regardless; no new signal here, but the trend has not reversed.

## 5 Shadow book (check 2)

Today at 10:06 IST: **15 OPEN** (14 `champion` + 1 `composite-055`), **0 CLOSED** ⇒ no CLOSED row
with NULL `pnl_net` to flag. Challenger `vol-off` / `vol-12k5` had not opened yet (historically
~10:15–11:45 IST).

League (`GET /api/v1/signal-rejections/shadow-summary`, cumulative, judge on NET ₹):

| variant | open | closed | wins | losses | pnl points | **pnl NET ₹** | unpriced |
|---|---|---|---|---|---|---|---|
| champion | 14 | 130 | 57 | 73 | 1,132.15 | **+980.61** | 0 |
| vol-12k5 | 0 | 17 | 6 | 11 | 74.10 | +3,046.36 | 0 |
| composite-055 | 1 | 8 | 2 | 6 | 2.50 | −478.98 | 0 |
| vol-off | 0 | 25 | 8 | 17 | −43.90 | −2,894.87 | 0 |

**Champion flipped cumulative-positive on one session.** On 07-22 the watch file recorded champion
at **−₹41,260.30** over 103 closes; the 07-22 session alone then closed 27 positions for
**+₹42,240.91** (1,712.75 pts), taking the cumulative to +₹980.61 over 130. Verified directly:

```
2026-07-22 | champion | 27 | 1712.75 | 42240.91
2026-07-21 | champion |  9 |  177.45 |  2872.77
2026-07-20 | champion | 17 | -258.35 | -5881.86
```

One clean trend-down session carries the entire cumulative sign — treat the positive league position
as regime, not edge, until more sessions accrue.

Standing caveat: shadow exits replicate brackets/structural stop/square-off only — no
indicator-driven exits.

## 6 EXT-02 Upstox rate budget (check 5) — first live session on this code

`docker logs ay-market-data-service` since 09:15 IST: **0** rate-budget acquire failures, **0**
`unpriced` reasons attributable to rate exhaustion. Upstox batch-lane activity stops cleanly at
09:00 IST (`ExpiredBackfillService` last at 08:41, `UpstoxGlobalInstrumentsClient` at 09:00) and is
silent through the session so far — the open−30min pause is doing its job and the armed F9 margin
path is un-starved.

## 7 To fold into the EOD post-market file

1. §4.1 — root-cause the 09:31–09:34 kite-rest breaker burst; check per-bucket chain cadence inside
   that window rather than session totals.
2. §3 — the 21.68 s max eval duration; confirm it did not recur later in the session.
3. §4.3 — futures-OI cadence for the full session vs the 187/192/208 trend (T12).
4. §5 — re-read the league at close; state explicitly whether 07-23 confirms or reverses the 07-22
   champion swing.
5. Re-read the eval counters BEFORE any post-close deploy — a recreate resets them.
6. Host-vs-container clock lag (~17 min at 09:42 host time) — confirm whether it persists and
   whether any host-scheduled job windows are affected.
