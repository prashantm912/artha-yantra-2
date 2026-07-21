# Midday live-session liveness check — 2026-07-21 (data date)

Analysis date: 2026-07-21, ran ~13:12–13:17 IST (scheduled `midday-live-liveness-check`).
Analyst: Claude (scheduled midday gate). **Read-only run** — no restarts, no writes, no deploys.
Verdict: **PASS.**

Scope note: this covers the blind window between the 09:35 market-open gate
(`2026-07-21-open-gate.md`) and the 15:47 post-market forensics. It is a liveness check only, not
a forensic pass — the evening `session-analysis post` run owns
`2026-07-21-session-findings.md` and should fold these numbers in as the session's midpoint
datapoint.

---

## 0 Verdict

**PASS** — the live SignalEngine is evaluating. Σ `ay_signal_eval_outcome_total` advanced **+32
across a 3m bar boundary** while bars were arriving, `ay_signal_eval_failures_total` = 0, and
evaluations are reaching the *confluence* stage (not parked at the chart gate). 1026 rejection rows
across 20 distinct slugs by 13:17 IST, 0 fires. No action needed.

## 1 Stack + Kite

| check | result |
|---|---|
| Trading day / in-session | Tue 2026-07-21, **13:12 IST** (python `zoneinfo`), inside 09:15–15:30. No July-2026 NSE holiday row. |
| Containers | 11/11 healthy, up ~4 h (booted ~08:50 IST). |
| Kite session | last status line = `kite session status -> CONNECTED` @ `03:20:26Z` (08:50:26 IST). The preceding `-> TOKEN_EXPIRED` @ `03:19:22Z` is the pre-login boot line, not a live expiry. |
| Ticker storm | **0** `KiteTicker.reconnect` frames in the last 60 min. |

## 2 Feed freshness

`GET /api/v1/market/health/data` (in-container, market-data :8081):

```
{"status":"GREEN","marketOpen":true,"asOf":"2026-07-21T07:42:35.751039109Z","tickedTokens":25,"problems":[]}
```

(07:42:35Z = 13:12:35 IST.)

`marketdata.candles` NFO 1m since 09:15 IST — **237 bars** each on `NIFTY26JULFUT` /
`NIFTY26AUGFUT` / `NIFTY26SEPFUT`, max bucket **13:11 IST**. 09:15→13:11 = 236 min, so the count
tracks minutes-since-open. `NIFTY26JULFUT` is the scalper signal series (the dated front contract);
`NIFTY-FUT-CONT` is replay-only and stale by design — not checked, not flagged.

## 3 THE GATE — outcome counters (actuator :8082)

`docker exec ay-strategy-signal-service sh -c "wget -qO- http://127.0.0.1:8082/actuator/prometheus | grep -E 'ay_signal_eval_outcome|ay_signal_eval_failures'"`

| outcome | R1 ≈13:12:40 IST | R2 ≈13:16:50 IST | Δ |
|---|---|---|---|
| `chart-gate-failed` | 1330 | 1346 | +16 |
| `composite-below-threshold` | 68 | 68 | 0 |
| `confluence-blocked` | 1026 | 1042 | +16 |
| `confluence-gate-absent` | 0 | 0 | 0 |
| `discipline-paused` | 0 | 0 | 0 |
| `fired` | 0 | 0 | 0 |
| `unscoreable-indicators-warming` | 0 | 0 | 0 |
| **Σ** | **2424** | **2456** | **+32** |
| `ay_signal_eval_failures_total` | 0 | 0 | 0 |

Reading wall-clocks are approximate (±30 s); R1 is anchored to the concurrent health `asOf` of
07:42:35Z. The two reads are ~4 min apart, clearing at least one 3m bar boundary. Counters are
in-memory and reset on restart — only the **delta** is meaningful.

**Why counters and not `signal_rejections`:** `recordRejection`'s two call sites
(`SignalEngine:1228`, `:1406`) are both downstream of the `chart != Outcome.FIRED` early return at
`SignalEngine:1132-1134`, so only the `confluence-blocked` outcome writes a row. Chart-gate
failures, below-threshold composites and warming indicators leave no row and no log. An empty
rejections table is therefore *not* evidence of a dead engine — that premise produced the false
starvation alarm on 2026-07-17 and an unnecessary live-service restart on 2026-07-20.

## 4 Context, not a gate — rejection rows + coverage

`strategy.signal_rejections` since 00:00 IST today: **1026 rows / 20 distinct `strategy_slug`**.

The row count matches the `confluence-blocked` counter exactly (1026 at R1) — the two views agree.
Coverage 20/38 loaded strategies, in-band against the historical reference ratios (07-10 = 35/39,
07-15 = 33/63, 07-17 = 17/63).

## 5 Engine load state

Boot lines, verbatim:

```
03:19:23Z  signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)
03:21:05Z  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

Current state `unresolved == 0` → **not flagged**. The 03:19:23Z line is the known F10 cold-start
shape (engine loads before the Kite login resolves the universe); the retry self-recovered 102 s
later on the `signal-eval` thread. Health signal is `unresolved == 0`, never `loaded > 0`.

## 6 Error scan (last 60 min)

| service | `"level":"ERROR"` count |
|---|---|
| `ay-strategy-signal-service` | **0** |
| `ay-market-data-service` | **3** |

All 3 market-data errors are one canary on one contract — shortest decisive line:

```
data canary RED: NFO:FINNIFTY26SEPFUT — ticks flowing but no 1m bar closed for 908s
```

(also 910 s @ 07:26:29Z and 372 s @ 07:41:31Z.) `FINNIFTY26SEPFUT` is a thin far-month leg, is not
a scalper signal series, and does not appear in the health endpoint's `problems` list — a thin-tape
artifact, non-blocking. No new observation, no action proposed here.

No TimescaleDB `non-Var pathkey` lines in the window (the known-open chain-table page issue,
mitigation scheduled 15:40 IST).

## 7 Carry into the evening `post` run

1. Fold this midpoint (Σ counters 2456 @ ~13:17 IST, 1026 rejections / 20 slugs, 0 fires) into
   `2026-07-21-session-findings.md`.
2. Session is at 0 fires by midday — confirm the full-session blocking-rail mix and whether the
   opening-window rails (`time-window`, `morning-opening-formation`) still dominate at close.
3. `FINNIFTY26SEPFUT` bar-close canary fired 3× on a thin contract — decide whether that contract
   belongs in the canary's watch set at all, or whether the threshold should be liquidity-scaled.
