# Midday live-session liveness check — 2026-07-23 (data date)

Analysis date: 2026-07-23, ran 12:35–13:42 IST (scheduled `midday-live-liveness-check`).
Analyst: Claude (scheduled midday gate). **Read-only run** — no restarts, no writes, no deploys.
Verdict: **PASS.**

Scope note: this covers the blind window between the 09:35 market-open gate
(`2026-07-23-open-gate.md`) and the 15:47 post-market forensics. Liveness check only, not a
forensic pass — the evening `session-analysis post` run owns `2026-07-23-session-findings.md` and
should fold these numbers in as the session's midpoint datapoint. The in-session data-health watch
(`2026-07-23-live-watch-findings.md`) is the sibling run.

---

## 0 Verdict

**PASS** — the live SignalEngine is evaluating. Σ `ay_signal_eval_outcome_total` advanced **+64
across two 3m bar boundaries** (12:36:04 → 12:42:03 IST) while bars were arriving;
`ay_signal_eval_failures_total` = 0; evaluations reach the *confluence* stage (not parked at the
chart gate). 896 rejection rows across **36 distinct slugs** by 12:34 IST — **36/38 loaded, the
best coverage ratio recorded on any gate run to date**. 0 fires by midday. No action needed.

## 1 Stack + Kite

| check | result |
|---|---|
| Trading day / in-session | Thu 2026-07-23, **12:35:52 IST** (python `zoneinfo`), inside 09:15–15:30. No July-2026 NSE holiday row. |
| Containers | 11/11 healthy, up ~4 h (booted ~08:38 IST — same boot as the open gate, no restart since). |
| Kite session | last status line = `kite session status -> CONNECTED` @ `04:08:05Z` (09:38:05 IST). |
| Transient | `kite session status -> ERROR` @ `04:03:05Z` (09:33 IST) self-recovered 5 min later on the next `monitor-sched` poll. The `-> TOKEN_EXPIRED` @ `03:08:01Z` is the pre-login boot line, not a live expiry. |
| Ticker storm | ≤6 ticker reconnect/disconnect lines in the last 4 h, none in-session. No storm. |

## 2 Feed freshness

`GET /api/v1/market/health/data` (in-container, market-data :8081):

```
{"status":"GREEN","marketOpen":true,"asOf":"2026-07-23T07:06:05.210974069Z","tickedTokens":25,"problems":[]}
```

(07:06:05Z = 12:36:05 IST.)

`marketdata.candles` 1m since 09:15 IST — **201 bars** each on `NIFTY26JULFUT`, `NIFTY 50` and
`SENSEX`, max bucket **12:35 IST**. 09:15→12:35 = 200 min, so the count tracks minutes-since-open
exactly. `NIFTY26JULFUT` is the scalper signal series (the dated front contract);
`NIFTY-FUT-CONT` is replay-only and stale by design — not checked, not flagged.

## 3 THE GATE — outcome counters (actuator :8082)

`docker exec ay-strategy-signal-service sh -c "wget -qO- http://127.0.0.1:8082/actuator/prometheus | grep -E 'ay_signal_eval_outcome|ay_signal_eval_failures'"`

| outcome | R1 12:36:04 | R3 12:42:03 | Δ R1→R3 |
|---|---|---|---|
| `chart-gate-failed` | 1100 | 1132 | +32 |
| `composite-below-threshold` | 44 | 44 | 0 |
| `confluence-blocked` | 896 | 928 | +32 |
| `confluence-gate-absent` | 0 | 0 | 0 |
| `discipline-paused` | 0 | 0 | 0 |
| `fired` | 0 | 0 | 0 |
| `unscoreable-indicators-warming` | 0 | 0 | 0 |
| **Σ** | **2040** | **2104** | **+64** |
| `ay_signal_eval_failures_total` | 0 | 0 | 0 |

R1→R3 is ~6 min, clearing two 3m bar boundaries (12:39, 12:42); +32 per boundary = ~32 strategies
evaluating per bar close. An intermediate read (R2) at 12:36:46 returned Σ 2040 unchanged —
**discarded, not a finding**: it was 42 s after R1 with no bar boundary in between, and this gate's
signal is the delta *across a boundary*. (Same discard shape as 07-22 §3.) Counters are in-memory
and reset on restart — only the delta is meaningful.

**Why counters and not `signal_rejections`:** `recordRejection`'s two call sites
(`SignalEngine:1228`, `:1406`) are both downstream of the `chart != Outcome.FIRED` early return at
`SignalEngine:1132-1134`, so only the `confluence-blocked` outcome writes a row. Chart-gate
failures, below-threshold composites and warming indicators leave no row and no log. An empty
rejections table is therefore *not* evidence of a dead engine — that premise produced the false
starvation alarm on 2026-07-17 and an unnecessary live-service restart on 2026-07-20.

## 4 Context, not a gate — rejection rows + coverage

`strategy.signal_rejections` since 00:00 IST today: **896 rows / 36 distinct `strategy_slug`**
(read 12:34 IST). The row count matches the `confluence-blocked` counter exactly (896 at R1) — the
two views agree. Re-read at 13:41 IST: **1238 rows / 36 slugs**, last row 13:40 IST — still
flowing, coverage flat at 36.

Coverage **36/38 loaded** is the highest of any gate run so far (07-22 midday = 34/38, 07-10 =
35/39, 07-15 = 33/63, 07-17 = 17/63, 07-21 midday = 20/38). Blocking-rail mix (read 13:26 IST,
Σ 1168 rows at that moment):

| blocking_rail | rows |
|---|---|
| `volume-floor` | 946 |
| `time-window` | 150 |
| `option-side-constraint` | 14 |
| `vwap-distance` | 10 |
| `time-of-day-preference` | 8 |
| `pct-price-move` | 5 |
| `two-candle` | 5 |
| `supertrend-15m` | 5 |
| `divergence-vol-gate` | 5 |
| `directional-change-gate` | 5 |
| `volume-pump` | 5 |
| `confluence-composite` | 5 |
| `oi-cross-required` | 3 |
| `call-put-delta-filter` | 2 |

`strategy.signals` since 09:15 IST: **0 rows** — 0 fires by midday. `volume-floor` at 81% of all
blocks is again the dominant midday rail (the relative volume floor armed in #605: k=1.5 / N=20 /
minBars=10), matching 07-22's 83%. The tail is materially wider than 07-22 (14 distinct rails vs 5)
— consistent with the higher slug coverage.

## 5 Engine load state

Boot lines, verbatim:

```
03:08:03.019Z  signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)   [main]
03:09:06.657Z  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)   [signal-eval]
```

Current state `unresolved == 0` → **not flagged**. The 03:08:03Z line is the known F10 cold-start
shape (engine loads before the Kite login resolves the universe); the retry self-recovered 63 s
later on the `signal-eval` thread. Health signal is `unresolved == 0`, never `loaded > 0`. Same
pair as the 09:35 open gate — same boot, no restart since.

## 6 Error scan (last 60 min)

| service | `"level":"ERROR"` count |
|---|---|
| `ay-strategy-signal-service` | **0** |
| `ay-market-data-service` | **4** |

All four market-data errors are one canary class on FINNIFTY futures — shortest decisive line:

```
data canary RED: NFO:FINNIFTY26SEPFUT — ticks flowing but no 1m bar closed for 892s
```

3× on `FINNIFTY26SEPFUT` (832 s / 892 s / 354 s) + 1× on `FINNIFTY26AUGFUT` (412 s). `SEP` is the
established thin-tape artifact: **25** 1m bars for the whole session (max bucket 13:25 IST) against
201 on the front future, not a scalper signal series, absent from the health endpoint's `problems`
list (GREEN, empty). **New this session: `FINNIFTY26AUGFUT` joined the canary despite 218 bars**
(max 13:26 IST) — a near-full series, so its 412 s gap is a genuine mid-session bar-close hole, not
thin-tape. Third consecutive session for the SEP canary (07-21, 07-22, 07-23).

No TimescaleDB `non-Var pathkey` lines in the window (the known-open chain-table page issue,
mitigation scheduled 15:40 IST).

## 7 Carry into the evening `post` run

1. Fold this midpoint (Σ counters 2104 @ 12:42 IST, 896 rejections / 36 slugs @ 12:34 rising to
   1238 @ 13:41, 0 fires) into `2026-07-23-session-findings.md`.
2. Coverage 36/38 is a new high on the same boot — identify the 2 slugs of 38 that never emit, and
   whether they are the same 2 as 07-22's missing 4.
3. `volume-floor` = 81% of blocks by midday on a second consecutive 0-fire-by-midday session.
   Confirm the full-session share; the k=1.5 / N=20 floor is now a two-session repeat as the
   effective binding constraint (carry of 07-22 §7.3).
4. **`FINNIFTY26AUGFUT` bar-close canary is a new signal, not the known SEP artifact** — 218 bars
   yet a 412 s no-close gap. Locate the gap window in `marketdata.candles` and decide whether it is
   a tick-lull or a capture hole. The SEP canary remains the liquidity-scaling question raised on
   07-21 §7.4 / 07-22 §7.4, now three sessions old.
5. Re-read the eval counters BEFORE any post-close deploy — a recreate resets them (carry of the
   live-watch §7.5).
