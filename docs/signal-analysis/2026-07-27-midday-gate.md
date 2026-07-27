# Midday live-session liveness check — 2026-07-27 (data date)

Analysis date: 2026-07-27, ran 13:00–13:08 IST (scheduled `midday-live-liveness-check`).
Analyst: Claude (scheduled midday gate). **Read-only run** — no restarts, no writes, no deploys.
Verdict: **PASS.**

Scope note: this covers the blind window between the 09:35 market-open gate
([`2026-07-27-open-gate.md`](2026-07-27-open-gate.md), PASS) and the 15:47 post-market forensics.
Liveness check only, not a forensic pass — the evening `session-analysis post` run owns
`2026-07-27-session-findings.md` and should fold these numbers in as the session's midpoint
datapoint. The in-session data-health watch
([`2026-07-27-live-health.md`](2026-07-27-live-health.md), GREEN, 09:43–09:47 IST) is the sibling
run.

---

## 0 Verdict

**PASS** — the live SignalEngine is evaluating. Σ `ay_signal_eval_outcome_total` advanced **+32
across the 13:00 IST 3m bar boundary** (2260 → 2292) and **+96 over the full 7.5 min run window**,
`ay_signal_eval_failures_total` = 0, and evaluations reach the *confluence* stage (not parked at the
chart gate). The persisted `strategy.signal_eval_outcomes` table corroborates independently: an
unbroken **32 evals per 3m bucket** with no gaps. 796 rejection rows across **36 distinct slugs**
(36/38 loaded) by 13:07 IST. 0 fires by midday. One transient Kite network blip at 11:08–11:16 IST,
fully self-recovered. No action needed.

## 1 Stack + Kite

| check | result |
|---|---|
| Clock guard (B8) | host `13:00:24.06 IST` vs container `2026-07-27 07:30:24.51 UTC` — **0.5 s apart**, no drift, no ⚠ line needed. |
| Trading day / in-session | Mon 2026-07-27, **13:00:24 IST** (python `zoneinfo`), inside 09:15–15:30. |
| Containers | 11/11 healthy. `ay-strategy-signal-service` up **14 h**, `ay-market-data-service` up 23 h — no restart today. |
| Kite session | last status line = `kite session status -> CONNECTED` @ `05:46:04.458Z` (**11:16:04 IST**). Zero status transitions in the last 60 min. |
| Transient | `kite session status -> ERROR` @ `05:41:04.426Z` (11:11 IST), recovered on the next `monitor-sched` poll 5 min later. See §1.1. |
| Ticker storm | last ticker line `05:38:58.473Z` (11:08:58 IST). Nothing in-session since. **No storm.** |

### 1.1 The 11:08–11:16 IST network blip (recovered, no action)

A single ~8-minute upstream-connectivity episode, entirely in the past by the time this gate ran:

```
05:38:17.433Z WARN  kite ticker error: Failed to connect to 'ws.kite.trade:443': ws.kite.trade
05:38:22.433Z WARN  kite ticker disconnected
05:38:58.473Z WARN  kite ticker error: Failed to connect to 'ws.kite.trade:443': Connection refused
05:41:04.425Z WARN  kite session probe errored: I/O error on GET request for "https://api.kite.trade/user/profile": HTTP connect timed out
05:41:04.426Z INFO  kite session status -> ERROR
05:46:04.458Z INFO  kite session status -> CONNECTED
```

The REST circuit opened alongside it — `chain broadcast failed for <index>: kite-rest circuit open;
serving cached data` (WARN, all six chain indices) plus one ERROR from the OI-capture scheduler
(`FuturesOiSnapshotService.scheduledSnapshot` → `KiteCallExecutor.execute:167`). Both the WS ticker
and the REST session recovered on their own; the circuit-breaker + cached-data fallback behaved as
designed. Nothing to act on, but the evening `post` run should check whether the 11:08–11:16 window
left an OI-snapshot or 1m-bar hole (the counters show the *engine* kept evaluating through it).

## 2 Feed freshness

`GET /api/v1/market/health/data` (in-container, market-data :8081):

```
{"status": "GREEN", "marketOpen": true, "asOf": "2026-07-27T07:30:41.826780002Z", "tickedTokens": 69, "problems": []}
```

(07:30:41Z = 13:00:41 IST.)

`marketdata.candles` 1m since 09:15 IST — **224 bars** on `NIFTY26JULFUT` (the scalper signal
series, the dated front contract), 225 on `NIFTY26AUGFUT`, 224 on `NIFTY26SEPFUT`; max bucket
advanced 12:59 → **13:01 IST** during the run. 09:15→13:01 = 226 min, so the count tracks
minutes-since-open. **69 distinct symbols** carry a 1m bar in the last 10 min — exactly the
endpoint's `tickedTokens: 69`, so ticks and bars agree. `NIFTY-FUT-CONT` is replay-only and stale by
design — not checked, not flagged.

## 3 THE GATE — outcome counters (actuator :8082)

`docker exec ay-strategy-signal-service sh -c "wget -qO- http://127.0.0.1:8082/actuator/prometheus | grep -E 'ay_signal_eval_outcome|ay_signal_eval_failures'"`

| outcome | R1 13:00:24 | R2 13:02:39 | R3 13:07:56 | Δ R1→R2 | Δ R1→R3 |
|---|---|---|---|---|---|
| `chart-gate-failed` | 1308 | 1332 | 1368 | +24 | +60 |
| `composite-below-threshold` | 188 | 192 | 192 | +4 | +4 |
| `confluence-blocked` | 764 | 768 | 796 | +4 | +32 |
| `confluence-gate-absent` | 0 | 0 | 0 | 0 | 0 |
| `discipline-paused` | 0 | 0 | 0 | 0 | 0 |
| `fired` | 0 | 0 | 0 | 0 | 0 |
| `unscoreable-indicators-warming` | 0 | 0 | 0 | 0 | 0 |
| **Σ** | **2260** | **2292** | **2356** | **+32** | **+96** |
| `ay_signal_eval_failures_total` | 0 | 0 | 0 | 0 | 0 |

R1→R2 clears the **13:00 IST 3m boundary**: +32 = ~32 strategies evaluating per bar close. R1→R3
spans three boundaries (13:00 / 13:03 / 13:06) at exactly +32 each. Counters are in-memory and reset
on restart — only the delta is meaningful; the container has not restarted (up 14 h), so the
absolute Σ is the day's true total.

Two intermediate reads at 13:01:08 (Σ 2266) and 13:02:59 / 13:03:23 (Σ 2292 unchanged) are
**discarded, not findings** — sub-boundary samples, same discard shape as 07-23 §3 and the
methodological note in [`2026-07-27-live-health.md`](2026-07-27-live-health.md) §2 (a flat read
inside a bucket is the window, not a stall).

### 3.1 Independent corroboration — the persisted table

`strategy.signal_eval_outcomes` (V045) is the durable mirror of the in-memory counters, so it is
immune to the "restart zeroed the gauge" ambiguity. Per-bucket, last 30 min of the run window:

| bucket (IST) | evals |
|---|---|
| 13:00 | 32 |
| 12:57 | 32 |
| 12:54 | 32 |
| 12:51 | 32 |
| 12:48 | 32 |
| 12:45 | 32 |
| 12:42 | 32 |
| 12:39 | 32 |

Metronomic — **no gap, no partial bucket**, including across the 11:08–11:16 IST network blip window
further back. Cumulative `sum(eval_count)` for the day = **2292** at bucket 13:00, byte-matching the
R2 in-memory Σ. Two independent views agree.

**Why counters and not `signal_rejections`:** `recordRejection`'s two call sites
(`SignalEngine:1228`, `:1406`) are both downstream of the `chart != Outcome.FIRED` early return at
`SignalEngine:1132-1134`, so only the `confluence-blocked` outcome writes a row. Chart-gate
failures, below-threshold composites and warming indicators leave no row and no log. An empty
rejections table is therefore *not* evidence of a dead engine — that premise produced the false
starvation alarm on 2026-07-17 and an unnecessary live-service restart on 2026-07-20.

## 4 Context, not a gate — rejection rows + coverage

`strategy.signal_rejections` since 00:00 IST today: **764 rows / 36 distinct `strategy_slug`** at
13:00 IST, re-read at 13:07 IST = **796 rows / 36 slugs**, last row **13:07:18 IST** — still
flowing, coverage flat at 36. The row count matches the `confluence-blocked` counter *exactly* at
both reads (764 at R1, 796 at R3) — the two views agree with zero drift.

Coverage **36/38 loaded** ties 07-23's high (07-22 midday = 34/38, 07-10 = 35/39, 07-15 = 33/63,
07-17 = 17/63, 07-21 midday = 20/38). Blocking-rail mix (Σ 764 rows at 13:00 IST):

| blocking_rail | rows |
|---|---|
| `volume-floor` | 588 |
| `time-window` | 104 |
| `rsi-band` | 32 |
| `option-side-constraint` | 20 |
| `time-of-day-preference` | 8 |
| `oi-cross-required` | 2 |

`strategy.signals` since 00:00 IST: **0 rows** — 0 fires by midday, and `fired` = 0 on the counter
confirms it from the engine side. `volume-floor` at **77%** of all blocks is again the dominant
midday rail (the relative volume floor armed in #605: k=1.5 / N=20 / minBars=10) — 81% on 07-23,
83% on 07-22, so this is now a **three-session repeat** as the effective binding constraint. The
rail tail is narrower than 07-23 (6 distinct rails vs 14) at the same 36-slug coverage.

## 5 Engine load state

Boot line, verbatim — a **single** line, no cold-start retry pair:

```
2026-07-26T17:50:17.820Z  [main]  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

(17:50:17Z = **23:20:17 IST on 2026-07-26** — matches the container's 14 h uptime.) `unresolved == 0`
on the first and only attempt → **not flagged**. Unlike 07-23, there is no F10 cold-start
`0 published / 38 dropped` line to recover from, because the boot happened outside market hours with
the universe already resolvable. Health signal is `unresolved == 0`, never `loaded > 0`.

## 6 Error scan (last 60 min)

| service | `"level":"ERROR"` count |
|---|---|
| `ay-strategy-signal-service` | **0** |
| `ay-market-data-service` | **0** |

Clean window. The one ERROR of the session so far sits *outside* the 60-min window — the 11:11 IST
`kite-rest circuit open` from the OI-capture scheduler covered in §1.1.

No TimescaleDB `non-Var pathkey` lines in the window (the known-open chain-table page issue,
mitigation scheduled 15:40 IST) — not re-raised.

Also absent this session: the `FINNIFTY26SEPFUT` / `FINNIFTY26AUGFUT` bar-close canary that ran
three consecutive sessions 07-21 → 07-23. Zero canary REDs in the window.

## 7 Carry into the evening `post` run

1. Fold this midpoint (Σ counters 2356 @ 13:07 IST, 796 rejections / 36 slugs, 0 fires, 224 front-
   future 1m bars) into `2026-07-27-session-findings.md`.
2. **Check the 11:08–11:16 IST blip for a data hole** (§1.1) — the engine kept evaluating through
   it, but the REST circuit was open for ~5 min with cached-data fallback on all six chain indices
   and one failed OI snapshot. Confirm `options_chain_snapshots` / `candles` coverage over that
   window before treating it as fully benign.
3. `volume-floor` = 77% of blocks on a **third consecutive** 0-fire-by-midday session (83% / 81% /
   77%). The k=1.5 / N=20 / minBars=10 floor is now a durable binding constraint, not a
   session-specific artifact — worth a full-session share and a tune proposal if it holds.
4. Coverage 36/38 ties the record — the same open question as 07-23 §7.2 stands: **which 2 of the 38
   loaded strategies never emit**, and are they the same 2 across sessions.
5. Re-read the eval counters BEFORE any post-close deploy — a recreate resets them (standing carry).
