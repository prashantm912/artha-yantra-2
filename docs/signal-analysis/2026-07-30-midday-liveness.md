# Midday live-session liveness check — 2026-07-30 (data date)

Analysis date: 2026-07-30. Window **12:36–12:41 IST** (scheduled `midday-live-liveness-check`).
Analyst: Claude (scheduled midday check). **Read-only run** — no restarts, no writes, no deploys.
Verdict: **PASS.**

Scope note: this covers the blind window between the 09:35 open gate
(`2026-07-30-open-gate.md`) and the 15:47 post-market forensics. It is a *liveness* check, not a
forensic pass — the evening `session-analysis post` run owns `2026-07-30-session-findings.md` and
should fold this file's counter deltas in as the session's midpoint datapoint.

---

## 0 Verdict

**PASS** — Σ `ay_signal_eval_outcome_total` advanced **2040 → 2070 → 2100** across two consecutive
3m bar boundaries (+30 each), `ay_signal_eval_failures_total` = 0 throughout, and the engine FIRED
2 signals at 12:33 IST. Rejection rows are at their best coverage yet (**36 distinct slugs**), feed
is GREEN, error count 0/0 over the last 60 min.

## 1 Preconditions

| check | result |
|---|---|
| Host-clock guard (B8) | Host `datetime.now(UTC)` = **2026-07-30T07:06:25.193Z**, container `now()` = **07:06:25.581Z**. Drift **0.4 s** — no ⚠ CLOCK-DRIFT line; host time service healthy this run (0.1 s at the morning gate). |
| Trading day / in-session | Thu 2026-07-30, **12:36 IST** at start, inside 09:15–15:30. |
| Expiry context | ⚠️ **BSE MONTHLY expiry** (last Thursday of July) — per [[monthly-expiry-oi-suppression]] SENSEX-rooted scalpers run with an inert OI bloc **by design**; NIFTY-rooted unaffected (NSE monthly was 07-28). |
| Stack | 11/11 `ay-*` containers healthy, all `Up 5 hours`. strategy-signal boot **02:36:13Z** = 08:06 IST — same boot as the morning gate, **no restart during the session**. |

## 2 Check 1 — stack + Kite

`kite session status` transitions since boot, both from `SessionStatusPublisher`:

```
02:36:12Z (monitor-sched-1)  kite session status -> TOKEN_EXPIRED
02:37:37Z (tomcat-handler)   kite session status -> CONNECTED     user=owner
```

Last line is **CONNECTED**, not TOKEN_EXPIRED/ERROR ⇒ pass. Four
`kite ticker error: The status code of the opening handshake response is not '101 Switching Protocols'`
WARNs, **all inside 02:36:02–02:37:12Z** (pre-token-refresh boot window), closed by
`02:37:37Z kite ticker connected; replayed 1 mode groups`. Nothing since — **no reconnect storm**.

## 3 Check 2 — feed freshness

`GET /api/v1/market/health/data` @ 12:36:44 IST:

```json
{"status":"GREEN","marketOpen":true,"asOf":"2026-07-30T07:06:44Z","tickedTokens":69,"problems":[]}
```

`tickedTokens` = **69**, same as this morning (the 105 → 69 drop vs 07-29 is still the open-gate
carry item, unverified either way here).

Signal series = the **dated front future**, not `NIFTY-FUT-CONT` (replay-only, stale by design —
never flagged). `marketdata.candles` 1m since 09:15 IST:

| tradingsymbol | 1m bars | max bucket (IST) |
|---|---|---|
| `NIFTY26AUGFUT` | 201 | 12:35 |
| `NIFTY26SEPFUT` | 201 | 12:35 |
| `NIFTY26OCTFUT` | 201 | 12:35 |

09:15 → 12:35 inclusive = 201 minutes. Bar count tracks minutes-since-open **exactly**, zero gaps.

## 4 Check 3 — THE GATE: outcome counters advancing

`docker exec ay-strategy-signal-service sh -c "wget -qO- http://127.0.0.1:8082/actuator/prometheus | grep ay_signal_eval"`

| outcome | T1 12:36:25 | T2 12:37:10 | T3 12:39:24 | T4 12:41:28 |
|---|---|---|---|---|
| `chart-gate-failed` | 1164 | 1165 | 1180 | 1196 |
| `composite-below-threshold` | 238 | 238 | 238 | 238 |
| `confluence-blocked` | 636 | 636 | 650 | 664 |
| `confluence-gate-absent` | 0 | 0 | 0 | 0 |
| `discipline-paused` | 0 | 0 | 0 | 0 |
| `fired` | 2 | 2 | 2 | 2 |
| `unscoreable-indicators-warming` | 0 | 0 | 0 | 0 |
| **Σ** | **2040** | **2041** | **2070** | **2100** |
| `ay_signal_eval_failures_total` | 0 | 0 | 0 | 0 |

**Delta: +30 across the 12:36 boundary (T1→T3), +30 across the 12:39 boundary (T3→T4)** ⇒ PASS.
Counters are in-memory and reset on restart — only the delta is meaningful, and the boot is 4.5 h
old so the absolute values are a full-session tally.

⚠️ Method note: T2 was taken **45 s** after T1 and showed only +1 — that is *not* a stall, it is a
read taken inside a bar, before the next 3m boundary. **A gate read must span a 3m boundary**; T2 is
recorded here only so the shape is not mistaken for starvation in a future run.

Corroborator — `strategy.signal_eval_outcomes` (V045 rollup), latest buckets only:

| bucket (IST) | 12:21 | 12:24 | 12:27 | 12:30 | 12:33 | 12:36 |
|---|---|---|---|---|---|---|
| evals | 32 | 32 | 32 | 32 | 32 | 30 |

Landing every 3 min as `SignalEvalOutcomeRollupJob` should, and the persisted 12:36 bucket (30)
matches the T1→T3 counter delta (+30) exactly. The 12:36 row appeared in the table at ~12:40, i.e.
the rollup lags its bucket by a few minutes — expected, not a gap.

## 5 Check 4 — rejection rows + coverage (CONTEXT, not a gate)

```sql
SELECT count(*), count(DISTINCT strategy_slug) FROM strategy.signal_rejections
 WHERE generated_at >= timestamptz '2026-07-30T00:00:00+05:30';
```

**636 rows / 36 distinct slugs** @ 12:37 IST (of 38 live-loaded scalpers). Row count matches the
`confluence-blocked` counter (636) exactly at the same instant — the two agree, as they must, since
that is the only outcome that writes a row.

Coverage vs history: **36/38 today** — best on record (07-10 = 35/39, 07-15 = 33/63, 07-17 = 17/63).

First-rail histogram at 636 rows: `volume-floor` **376**, `time-window` 92, `rsi-band` 76,
`option-side-constraint` 18, `volume-pump` 12.

`volume-floor` at 376/636 = **59 %** of first-rail blocks, the largest single rail — same shape as
this morning (12/32) and 07-29 midday. Standing prior before anything is proposed off it: **all four
measured loosenings of the scalper entry gate lost money (T1, T7, G13, G10)**, and all four are
conditional on the 30-min `time_stop`.

## 6 Check 5 — engine load state

```
02:36:13Z (main)        signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)
02:37:38Z (signal-eval) signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

`unresolved == 0` ⇒ **nothing to flag**. Same F10 cold-start shape the morning gate recorded
(self-healed in 85 s at boot); no re-load since, so this is the same state carried forward, not a
new event. Health signal is `unresolved == 0`, never `loaded > 0` ([[live-mode-findings]]).

## 7 Check 6 — error scan (last 60 min)

| service | `"level":"ERROR"` count |
|---|---|
| `ay-market-data-service` | **0** |
| `ay-strategy-signal-service` | **0** |

Nothing to quote. The known-open TimescaleDB `non-Var pathkey` errors from the options chain-table
page did **not** appear in this window either.

## 8 Fires and execution

`fired` counter **2**, `strategy.signals` rows since 00:00 IST **2** — the two agree.

| generated_at (IST) | tradingsymbol | side | interval |
|---|---|---|---|
| 12:33 | `NIFTY26AUGFUT` | BUY | 3m |
| 12:33 | `NIFTY26AUGFUT` | BUY | 3m |

First fires of the session (the open gate had 0 at 09:58). Both on the NIFTY front future 3m
primary, both at the same 12:33 bar.

## 9 Data-integrity probes

`GET /api/v1/signal-rejections/dot-health` @ 12:37:56 IST (200 rows scanned, 40 context-bearing):

| state | dots |
|---|---|
| alive | `breadth`\*, `oi_spurt_price`, `vix`, `futures_oi`\*, `underlying_oi`\* |
| alive, **frozen BY DESIGN** (EOD daily operand) | `fii` (9.62 across 14 bars), `iv_abs_band` (0.112743 across 14 bars) |
| dead (`required: false`) | `iv_rank`, `dow` |

`* = required`

**No change vs the 09:37 open-gate read or vs 07-29.** Dead set is exactly `{iv_rank, dow}` — the
standing state, both optional. ⚠️ Support rates are NOT reported here: a mid-session dot read is
PROVISIONAL (§3.21); the EOD run owns them.

`GET /api/v1/market/health/ingest` (07-16 → 07-29, 10 trading days): `NSE_FII_DII` **GREEN**
(0 missing days), `NSE_PARTICIPANT_OI` **GREEN** (0 missing days).

## 10 Carry into the evening `post` run

1. Fold the midday PASS + the +30/+30 counter deltas in as the session's midpoint datapoint.
2. `volume-floor` 376/636 (59 %) — classify STRUCTURAL vs REGIME. Do **not** propose a loosening
   without converting the pass-rate delta to LEGS then P&L; the standing prior is 4-for-4 losses.
3. Two fires at 12:33 on `NIFTY26AUGFUT` — check whether they reached a paper entry and how they
   exited, and whether both are the same strategy or two.
4. `tickedTokens` = 69 both at open and midday. The 07-29 → 07-30 drop from 105 is still **assumed**
   to be the BSE monthly-expiry contract set rolling off — confirm at EOD before it is carried.
5. Re-check `iv_rank` / `dow` at EOD — dead at midday is not yet an EOD finding.
6. Re-check the host-clock guard at EOD (0.1 s at open, 0.4 s at midday; B8 stays a watch item).
7. Confirm the 201/201 front-future bar cadence held through the close.
