# Midday live-session liveness check — 2026-07-22 (data date)

Analysis date: 2026-07-22, ran 12:36–13:50 IST (scheduled `midday-live-liveness-check`).
Analyst: Claude (scheduled midday gate). **Read-only run** — no restarts, no writes, no deploys.
Verdict: **PASS.**

Scope note: this covers the blind window between the 09:35 market-open gate
(`2026-07-22-open-gate.md`) and the 15:47 post-market forensics. Liveness check only, not a
forensic pass — the evening `session-analysis post` run owns `2026-07-22-session-findings.md` and
should fold these numbers in as the session's midpoint datapoint. The in-session data-health watch
(`2026-07-22-live-watch-findings.md`) is the sibling run.

---

## 0 Verdict

**PASS** — the live SignalEngine is evaluating. Σ `ay_signal_eval_outcome_total` advanced **+64
across two 3m bar boundaries** (12:38→12:44 IST) while bars were arriving, confirmed by a later
**+576** to 13:49 IST; `ay_signal_eval_failures_total` = 0; evaluations reach the *confluence*
stage (not parked at the chart gate). 764 rejection rows across **34 distinct slugs** by 12:39 IST
— the best coverage ratio (34/38) recorded on any gate run to date. 0 fires. No action needed.

## 1 Stack + Kite

| check | result |
|---|---|
| Trading day / in-session | Wed 2026-07-22, **12:36 IST** (python `zoneinfo`), inside 09:15–15:30. No July-2026 NSE holiday row. |
| Containers | 11/11 healthy, up ~4 h (booted ~08:58 IST). |
| Kite session | last status line = `kite session status -> CONNECTED` @ `03:29:07Z` (08:59:07 IST). The preceding `-> TOKEN_EXPIRED` @ `03:28:22Z` is the pre-login boot line, not a live expiry. |
| Ticker storm | 6 `KiteTicker.reconnect` stack frames, **all pre-login boot** (WARN `kite ticker error` @ `03:28:12Z` / `03:28:14Z` / `03:28:19Z`), terminated by `kite ticker connected` @ `03:29:07.425Z`. `09:10 ticker auto-start` @ `03:39:59Z`. Zero reconnect activity in-session. |

## 2 Feed freshness

`GET /api/v1/market/health/data` (in-container, market-data :8081), both reads:

```
{"status":"GREEN","marketOpen":true,"asOf":"2026-07-22T07:08:28.689138164Z","tickedTokens":25,"problems":[]}
{"status":"GREEN","marketOpen":true,"asOf":"2026-07-22T08:19:49.255764120Z","tickedTokens":25,"problems":[]}
```

(07:08:28Z = 12:38:28 IST; 08:19:49Z = 13:49:49 IST.)

`marketdata.candles` 1m since 09:15 IST — **203 bars** each on `NIFTY26JULFUT` and `NIFTY 50`, max
bucket **12:37 IST**. 09:15→12:37 = 202 min, so the count tracks minutes-since-open.
`NIFTY26JULFUT` is the scalper signal series (the dated front contract); `NIFTY-FUT-CONT` is
replay-only and stale by design — not checked, not flagged.

## 3 THE GATE — outcome counters (actuator :8082)

`docker exec ay-strategy-signal-service sh -c "wget -qO- http://127.0.0.1:8082/actuator/prometheus | grep -E 'ay_signal_eval_outcome|ay_signal_eval_failures'"`

| outcome | R1 12:38:28 | R2 12:44:25 | Δ R1→R2 | R3 13:49:49 |
|---|---|---|---|---|
| `chart-gate-failed` | 1188 | 1220 | +32 | 1574 |
| `composite-below-threshold` | 120 | 120 | 0 | 170 |
| `confluence-blocked` | 764 | 796 | +32 | 968 |
| `confluence-gate-absent` | 0 | 0 | 0 | 0 |
| `discipline-paused` | 0 | 0 | 0 | 0 |
| `fired` | 0 | 0 | 0 | 0 |
| `unscoreable-indicators-warming` | 0 | 0 | 0 | 0 |
| **Σ** | **2072** | **2136** | **+64** | **2712** |
| `ay_signal_eval_failures_total` | 0 | 0 | 0 | 0 |

R1→R2 is ~6 min, clearing two 3m bar boundaries; +32 per boundary = ~32 strategies evaluating per
bar close. R3 is a same-run confirmation read 65 min later (+576 further). An intermediate read at
12:39:09 returned Σ 2072 unchanged — **discarded, not a finding**: it was 41 s after R1 with no bar
boundary in between, and this gate's signal is the delta *across a boundary*. Counters are
in-memory and reset on restart — only the delta is meaningful.

**Why counters and not `signal_rejections`:** `recordRejection`'s two call sites
(`SignalEngine:1228`, `:1406`) are both downstream of the `chart != Outcome.FIRED` early return at
`SignalEngine:1132-1134`, so only the `confluence-blocked` outcome writes a row. Chart-gate
failures, below-threshold composites and warming indicators leave no row and no log. An empty
rejections table is therefore *not* evidence of a dead engine — that premise produced the false
starvation alarm on 2026-07-17 and an unnecessary live-service restart on 2026-07-20.

## 4 Context, not a gate — rejection rows + coverage

`strategy.signal_rejections` since 00:00 IST today: **764 rows / 34 distinct `strategy_slug`**
(read 12:39 IST). The row count matches the `confluence-blocked` counter exactly (764 at R1) — the
two views agree.

Coverage **34/38 loaded** is the highest of any gate run so far (07-10 = 35/39, 07-15 = 33/63,
07-17 = 17/63, 07-21 midday = 20/38, 07-22 open = 16/38 by 09:50). Blocking-rail mix:

| blocking_rail | rows |
|---|---|
| `volume-floor` | 632 |
| `time-window` | 102 |
| `option-side-constraint` | 18 |
| `time-of-day-preference` | 10 |
| `rsi-band` | 2 |

`strategy.signals` since 00:00 IST: **0 rows** — 0 fires by midday. `volume-floor` at 83% of all
blocks is the dominant midday rail (the relative volume floor armed in #605: k=1.5 / N=20 /
minBars=10).

## 5 Engine load state

Boot lines, verbatim:

```
03:28:23.674Z  signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)   [main]
03:29:43.912Z  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)   [signal-eval]
```

Current state `unresolved == 0` → **not flagged**. The 03:28:23Z line is the known F10 cold-start
shape (engine loads before the Kite login resolves the universe); the retry self-recovered 80 s
later on the `signal-eval` thread. Health signal is `unresolved == 0`, never `loaded > 0`. Same
pair as the 09:35 open gate — this is the same boot, no restart since.

## 6 Error scan (last 60 min)

| service | `"level":"ERROR"` count |
|---|---|
| `ay-strategy-signal-service` | **0** |
| `ay-market-data-service` | **2** |

Both market-data errors are one canary on one contract — shortest decisive line:

```
data canary RED: NFO:FINNIFTY26SEPFUT — ticks flowing but no 1m bar closed for 909s
```

(909 s @ `06:26:24Z` = 11:56 IST, 907 s @ `06:56:26Z` = 12:26 IST.) Verified as a thin-tape
artifact, not a feed fault: `FINNIFTY26SEPFUT` has **17** 1m bars for the whole session (max bucket
12:25 IST) against 203 on the front future, it is not a scalper signal series, and it does not
appear in the health endpoint's `problems` list (GREEN, empty, at 13:49 IST). Same contract fired
this canary 3× on 07-21 — now a two-day repeat.

No TimescaleDB `non-Var pathkey` lines in the window (the known-open chain-table page issue,
mitigation scheduled 15:40 IST).

## 7 Carry into the evening `post` run

1. Fold this midpoint (Σ counters 2136 @ 12:44 IST / 2712 @ 13:49 IST, 764 rejections / 34 slugs,
   0 fires) into `2026-07-22-session-findings.md`.
2. Coverage jumped 16 slugs (09:50) → 34 slugs (12:39) on the same boot — check at close whether
   the remaining 4 of 38 ever emit, and which they are.
3. `volume-floor` blocks 632 of 764 by midday. Confirm the full-session share and whether the
   k=1.5 / N=20 floor is the effective binding constraint on a 0-fire day.
4. `FINNIFTY26SEPFUT` bar-close canary now fired on two consecutive sessions on a contract with
   ~8% of the front future's bar count — decide whether it belongs in the canary watch set at all,
   or whether the no-bar threshold should be liquidity-scaled. (Repeat of 07-21 §7.3.)
