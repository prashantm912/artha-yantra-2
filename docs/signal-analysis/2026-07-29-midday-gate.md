# Midday live-session liveness check — 2026-07-29 (data date)

Analysis date: 2026-07-29, ran 12:36–12:41 IST (scheduled `midday-live-liveness-check`).
Analyst: Claude (scheduled midday gate). **Read-only run** — no restarts, no writes, no deploys.
Verdict: **PASS.**

Scope note: this covers the blind window between the 09:35 market-open gate and the 15:47
post-market forensics. Liveness check only, not a forensic pass — the evening `session-analysis
post` run owns `2026-07-29-session-findings.md` and should fold these numbers in as the session's
midpoint datapoint. The sibling run is the market-open gate
([`2026-07-29-open-gate.md`](2026-07-29-open-gate.md), PASS, 09:36–09:42 IST — open in PR #1101 at
the time of writing); it carries its own 12:43 IST corroboration sample, so its midday numbers
(Σ 2100, 854 rejections, 6 fires) sit ~3 min *after* this run's and agree with them.

---

## 0 Verdict

**PASS** — the live SignalEngine is evaluating and **firing**. Σ `ay_signal_eval_outcome_total`
advanced **2004 → 2036 → 2045** across the 12:36 and 12:39 IST 3m bar boundaries (Δ **+41** over the
3.5-min window), `ay_signal_eval_failures_total` = 0, and the persisted
`strategy.signal_eval_outcomes` table corroborates independently with **69 buckets since 09:15 and
zero gaps**. **6 entry fires + 4 exits** by midday — the first non-zero midday fire count in the
recent run of gates. 832 rejection rows across **18 distinct slugs**. 0 ERRORs in either service in
the last 60 min. No action needed.

## 1 Stack + Kite

| check | result |
|---|---|
| Clock guard (B8) | host `12:36:34.70 IST` / `07:06:34.70 UTC` vs container `2026-07-29 07:06:35.47 UTC` — **0.8 s apart**, no drift, no ⚠ line needed. |
| Trading day / in-session | Wed 2026-07-29, **12:36:34 IST** (python `zoneinfo`), inside 09:15–15:30. |
| Containers | 11/11 healthy. `ay-strategy-signal-service` started `2026-07-28T20:24:41.98Z` (**01:54 IST**), `ay-market-data-service` `20:24:25.78Z` — both up ~11 h, no restart today. |
| Kite session | last status line = `kite session status -> CONNECTED` @ `2026-07-29T03:02:09.516Z` (**08:32 IST**), owner-triggered (`user: owner`, tomcat thread) after the overnight `TOKEN_EXPIRED`. Zero status transitions since. |
| Ticker storm | ticker `reconnect`/`disconnect` lines in the last 3 h: **0**. |

### 1.1 Overnight token expiry — expected, already resolved

```
2026-07-28T23:04:49.417Z INFO  kite session status -> TOKEN_EXPIRED
2026-07-29T02:59:58.895Z ERROR instrument sync failed: no live Kite session for dump sync
2026-07-29T03:02:09.516Z INFO  kite session status -> CONNECTED
```

The nightly Kite token expiry (23:04 IST equivalent) tripped the **08:29 IST morning
`InstrumentSyncScheduler.morningSync`**, which ran ~2 min *before* the owner's 08:32 IST login and
threw `no live Kite session for dump sync`. Self-corrected by the login; the instrument master is
served from the prior day's dump in the interim. Pre-existing ordering quirk (scheduler at 08:29 vs
login-by-08:32), **not raised as a new defect** — but it is the only ERROR of the session and the
evening run may want to confirm the instrument master refreshed after login.

## 2 Feed freshness

`GET /api/v1/market/health/data` (in-container, market-data :8081):

```
{"status":"GREEN","marketOpen":true,"asOf":"2026-07-29T07:06:48.708778003Z","tickedTokens":105,"problems":[]}
```

(07:06:48Z = 12:36:48 IST.)

`marketdata.candles` 1m since 09:15 IST — **201 bars** on `NIFTY26AUGFUT` (the scalper signal
series, the dated front contract; note the roll off `NIFTY26JULFUT` since 07-27), 201 on
`NIFTY26SEPFUT`, 201 on `NIFTY26OCTFUT`; max bucket **12:35 IST**. 09:15→12:35 = 200 min, so the
count tracks minutes-since-open. `NIFTY-FUT-CONT` is replay-only and stale by design — not checked,
not flagged.

**Divergence worth carrying:** `tickedTokens` = **105** but only **69 distinct symbols** carry a 1m
bar in the last 10 min. On 07-27 the two matched exactly (69 = 69). 105 ticking vs 69 barring is not
a liveness failure (the signal series is dense and GREEN, `problems: []`), but the gap is new
relative to the last documented gate — see §7.

## 3 THE GATE — outcome counters (actuator :8082)

`docker exec ay-strategy-signal-service sh -c "wget -qO- http://127.0.0.1:8082/actuator/prometheus | grep -E 'ay_signal_eval_outcome|ay_signal_eval_failures'"`

| outcome | R1 12:36:34 | R2 12:37:28 | R3 12:40:01 | Δ R1→R2 | Δ R1→R3 |
|---|---|---|---|---|---|
| `chart-gate-failed` | 1118 | 1140 | 1147 | +22 | +29 |
| `composite-below-threshold` | 54 | 58 | 60 | +4 | +6 |
| `confluence-blocked` | 826 | 832 | 832 | +6 | +6 |
| `confluence-gate-absent` | 0 | 0 | 0 | 0 | 0 |
| `discipline-paused` | 0 | 0 | 0 | 0 | 0 |
| `fired` | 6 | 6 | 6 | 0 | 0 |
| `unscoreable-indicators-warming` | 0 | 0 | 0 | 0 | 0 |
| **Σ** | **2004** | **2036** | **2045** | **+32** | **+41** |
| `ay_signal_eval_failures_total` | 0 | 0 | 0 | 0 | 0 |

R1→R2 clears the **12:36 IST 3m boundary**: +32 = the full steady-state cohort evaluating at the bar
close. R2→R3 (+9) is a partial sample — R3 was taken at 12:40:01, two seconds into the 12:39 bucket's
write, so the 12:39 bucket was still filling in memory; the persisted table (§3.1) shows it settled
at the same 32. Counters are in-memory and reset on restart — only the delta is meaningful; the
container has not restarted today (up 11 h), so the absolute Σ is the day's true total.

### 3.1 Independent corroboration — the persisted table

`strategy.signal_eval_outcomes` (V045), per 3m bucket:

| bucket (IST) | evals |
|---|---|
| 12:39 | 32 |
| 12:36 | 32 |
| 12:33 | 32 |
| 12:30 | 32 |
| 12:27 | 32 |
| 12:24 | 32 |
| 12:21 | 32 |
| 12:18 | 30 |
| 12:15 | 29 |
| 12:12 | 32 |

**69 distinct buckets since 09:15 IST**, which is exactly `(12:39−09:15)/3 + 1` — **no gap, no
missing bucket**. The two views reconcile exactly:

- cumulative `sum(eval_count)` **through bucket 12:36** = 2068 − 32 = **2036** = **R2's in-memory Σ
  byte-for-byte**;
- cumulative **through bucket 12:39** = **2068**; R3 (12:40:01) read **2045**, i.e. 9 of that
  bucket's 32 evals had landed in memory when the scrape ran — the 12:39 boundary was mid-flush.

No drift between the durable table and the gauge.

The 12:15 = 29 / 12:18 = 30 dip is **explained, not an anomaly**: 3 strategies entered positions at
12:12 and stopped entry-evaluating until their 12:18 exits. The identical shape appears around the
11:06 entry (36 → 33 for 11:09–11:36, back to 36 at 11:42 after the 11:36/11:39 exits).

**Why counters and not `signal_rejections`:** `recordRejection`'s two call sites
(`SignalEngine:1228`, `:1406`) are both downstream of the `chart != Outcome.FIRED` early return at
`SignalEngine:1132-1134`, so only the `confluence-blocked` outcome writes a row. Chart-gate
failures, below-threshold composites and warming indicators leave no row and no log. An empty
rejections table is therefore *not* evidence of a dead engine — that premise produced the false
starvation alarm on 2026-07-17 and an unnecessary live-service restart on 2026-07-20.

### 3.2 The 09:15–09:42 window is structural, not starvation

The first ten 3m buckets of every session carry **0 then 4 evals**, stepping to the 36-eval steady
state at exactly **09:45 IST**:

| bucket (IST) | 07-27 | 07-28 | 07-29 |
|---|---|---|---|
| 09:15 | 0 | 0 | 0 |
| 09:18 → 09:42 | 4 each | 4 each | 4 each |
| 09:45 onward | 36 | 36 | 36 |

Byte-identical across three consecutive sessions, so this is a **configured 09:45 entry-window
start on 32 of the 36 evaluating strategies**, not a warm-up stall — `unscoreable-indicators-warming`
is 0 throughout, meaning the 32 return *before* the eval counter rather than being counted as
warming. Recorded here so a future gate reading a 4-eval bucket at 09:30 does not raise it as
starvation.

## 4 Context, not a gate — rejection rows + coverage

`strategy.signal_rejections` since 00:00 IST today: **832 rows / 18 distinct `strategy_slug`** at
12:37 IST — matching the `confluence-blocked` counter **exactly** (832 at R2/R3), zero drift between
the two views.

Coverage **18/38 loaded** is the low end of the recent range (07-27 = 36/38, 07-23 = 36/38,
07-22 = 34/38, 07-21 = 20/38, 07-10 = 35/39, 07-15 = 33/63, 07-17 = 17/63). Consistent with a
session where more strategies are exiting at the chart gate (`chart-gate-failed` 1147 vs
`confluence-blocked` 832 — a 58/42 split, versus 07-27's 1368/796 = 63/37 at a similar hour).

Blocking-rail mix (Σ 832 rows):

| blocking_rail | rows |
|---|---|
| `volume-floor` | 629 |
| `time-window` | 100 |
| `two-candle` | 12 |
| `volume-pump` | 12 |
| `rsi-band` | 12 |
| `pct-price-move` | 12 |
| `divergence-vol-gate` | 10 |
| `time-of-day-preference` | 10 |
| `oi-cross-required` | 8 |
| `directional-change-gate` | 8 |
| `max-oi-sr-gate` | 7 |
| `call-put-delta-filter` | 4 |
| `confluence-composite` | 4 |
| `option-side-constraint` | 4 |
| `constituent-gate` | 2 |
| `open-high-low` | 2 |
| `psar-durability` | 2 |

`volume-floor` at **76%** of all blocks (629/832) — the **fourth consecutive** session with it as the
dominant binding rail (83% / 81% / 77% / 76%). The relative volume floor armed in #605
(k=1.5 / N=20 / minBars=10) is a durable constraint, not a session artifact.

### 4.1 Fires — 6 entries, 4 exits

`strategy.signals` since 00:00 IST: **10 rows**, all on `NIFTY26AUGFUT` @ 3m:

| IST | type | side | composite | scalper_detail |
|---|---|---|---|---|
| 11:06 | ENTRY | BUY | 0.8569 | yes ×3 |
| 11:36 | EXIT | SELL | 0.8569 | no |
| 11:39 | EXIT | SELL | 0.8569 | no |
| 12:12 | ENTRY | BUY | 0.8429 | yes ×3 |
| 12:18 | EXIT | SELL | 0.8429 | no ×2 |

**6 ENTRY rows = the counter's `fired` = 6, exactly.** The 4 EXIT rows do not increment
`ay_signal_eval_outcome_total{outcome="fired"}` — that counter meters *entry* evaluations at a
primary bar close only. Two clean round trips (3 strategies each), both entries at a composite well
above threshold (0.86 / 0.84), average hold ~30 min and ~6 min respectively. First non-zero midday
fire count in the recent gate series (07-27 midday = 0 fires).

## 5 Engine load state

Boot line, verbatim — a **single** line, no cold-start retry pair:

```
2026-07-28T20:25:31.670Z  [main]  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

(20:25:31Z = **01:55 IST on 2026-07-29** — matches the container's ~11 h uptime.) `unresolved == 0`
on the first and only attempt → **not flagged**. Health signal is `unresolved == 0`, never
`loaded > 0`.

38 loaded vs 36 evaluating in steady state — the same 2-strategy gap seen on 07-23 and 07-27. Still
uncharacterized; carried forward (§7).

## 6 Error scan (last 60 min)

| service | `"level":"ERROR"` count |
|---|---|
| `ay-strategy-signal-service` | **0** |
| `ay-market-data-service` | **0** |

Clean window. Widening the scan to the **full session** (since 09:15 IST) also returns **0 ERRORs in
both services** — the only ERROR of the day is the 08:29 IST pre-login instrument sync in §1.1.

No TimescaleDB `non-Var pathkey` lines anywhere today (the known-open chain-table page issue,
mitigation scheduled 15:40 IST) — not re-raised.

## 7 Carry into the evening `post` run

1. Fold this midpoint (Σ counters 2045 @ 12:40 IST, persisted 2068 @ bucket 12:39, 832 rejections /
   18 slugs, **6 fires + 4 exits**, 201 front-future 1m bars) into `2026-07-29-session-findings.md`.
2. **`tickedTokens` 105 vs 69 symbols with a 1m bar in the last 10 min** (§2). Matched exactly on
   07-27. Confirm whether the 36 extra ticking tokens are option contracts that legitimately produce
   no bar in a 10-min window, or a bar-writer gap.
3. **Rejection coverage 18/38** is half the 07-27 figure (36/38) at a comparable hour, with the
   chart/confluence split shifting 63/37 → 58/42. Confirm from the full session whether the 18 is a
   tape artifact or a set of strategies that went silent.
4. `volume-floor` = 76% of blocks on a **fourth consecutive** session (83/81/77/76). Durable binding
   constraint — worth a full-session share and a tune proposal.
5. **38 loaded vs 36 evaluating** — the 2-strategy gap is now seen on 07-23, 07-27 and 07-29. Same
   open question as 07-23 §7.2 and 07-27 §7.4: *which* 2, and are they the same 2 across sessions.
6. §3.2 (the 09:15–09:42 four-eval window is a configured 09:45 entry-window start, verified
   identical across three sessions) is a **standing characterization** — future gates should not
   raise it.
7. Re-read the eval counters BEFORE any post-close deploy — a recreate resets them (standing carry).
