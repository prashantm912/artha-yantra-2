# Live data-health watch — 2026-07-21 (in-session, 09:44–09:47 IST)

Scheduled task `live-data-health-check` (session-analysis `live` mode, README §4.1). Market open
(Tue), ~32 min into the session. **Read-only**: SELECTs, `docker logs`, GET endpoints only — no
restart, deploy, write or config change.

This is a LIVE snapshot, not the session forensics. The immutable
`2026-07-21-session-findings.md` is the 15:47 post-market task's file; it should fold §2/§4/§5 here
into its §4 (data health) and §6 (new data points).

Stack: all 11 containers healthy (up 53 min at read time).

---

## 1 Verdict

**GREEN.** Both machine canaries green, engine liveness confirmed by an advancing counter delta,
EXT-02 rate budget clean. Two carried-risk notes for the owner (§4), neither actionable
mid-session.

---

## 2 Machine canaries (STEP 0)

| canary | result |
|---|---|
| `GET /api/v1/market/health/data` | `status=GREEN`, `tickedTokens=25`, `problems:[]`, asOf `2026-07-21T04:14:32Z` (09:44 IST) |
| `GET /api/v1/signal-rejections/dot-health` | `session=true`, `rowsInspected=14`; dead = `iv_rank`, `dow`, `fii` (all `required=false`); alive = `breadth` (required), `oi_spurt_price`, `vix` |

**Dead-set diff vs the 2026-07-20 findings file:** no NEWLY dead dot. **`oi_spurt_price` is NEWLY
ALIVE** — 07-20 §4 recorded it dead. The three dead dots (`iv_rank`, `dow`, `fii`) are the carried
set (dead every session since 07-02 for `ivRank`/`fiiLongPct`; `dow` un-armed by design).

## 3 Engine liveness (check 1 — the counters, never `signal_rejections`)

Per [[engine-liveness-is-counters-not-rejections]] the signal is Σ `ay_signal_eval_outcome_total`
advancing, read from the actuator on port **8082**.

| reading (IST) | chart-gate-failed | composite-below-threshold | confluence-blocked | Σ |
|---|---|---|---|---|
| 09:44:30 | 18 | 4 | 14 | **36** |
| 09:46:09 | 18 | 4 | 14 | **36** |
| 09:47:04 | 38 | 4 | 30 | **72** |

**Σ 36 → 72 ⇒ engine ALIVE.** `ay_signal_eval_failures_total = 0.0` throughout. `fired = 0`,
`unscoreable-indicators-warming = 0`, `discipline-paused = 0`. The two flat readings are 55 s apart
inside one 3 m bar — the batch lands at the bar close, so only a reading pair spanning a bar
boundary is diagnostic. (Counters are in-memory; today's absolute values date from the 08:49 IST
boot.)

**Boot line (read the same day — a restart destroys it):**

```
03:19:23Z (08:49 IST)  signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)
03:21:05Z (08:51 IST)  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

The cold boot hit the F10 class (0 loaded / 38 unresolved) and the #874 retry recovered it ~102 s
later, before the open. Health signal is `unresolved == 0`, which holds.

**Denominator:** 44 published+enabled strategies = 38 scalpers (9 families × 4 + `scalp-hero-zero`
× 2) + 6 swing (minervini/manas-arora, batch engine, not loaded by the live engine). So
38 loaded / 38 scalpers = full coverage — no repeat of the 07-17 shrinking-numerator problem.

**Rejection context (CONTEXT ONLY — not a liveness signal):** 30 rows at 09:47, latest bar 09:45.
Emitting slugs so far are the 4 `scalp-morning-trade-*` variants only, which matches Σ36 = 4
strategies × 9 bars at the 09:44 reading; the other families' entry windows had not opened.
First-blocks: `time-window` 8, `volume-floor` 3, `option-side-constraint` 2,
`morning-opening-formation` 1. Max composite **0.6915** vs threshold **0.600**.

## 4 Owner notes (no action taken — market hours)

### 4.1 ⚠ Paper swing brackets starved — all 15 open positions

`PaperStaleTickAlerter` WARNs once per open position at 04:16:15Z (09:46 IST):

```
paper bracket starved: position 25 SL/TP un-evaluated for ~2773s (tick absent) — stop may not fire
```

Fires for **every** open paper position — ids 13, 15, 16, 17, 19, 21, 22, 23, 24, 25, 26, 27, 30,
31, 33 (books `minervini` + `manas-arora`, all NSE cash equities: AUTOIND, ATHERENERG, DIACABS,
J&KBANK, INOXINDIA, THANGAMAYL, PRECOT, SOTL, CHENNPETRO, KRN, CARYSIL, SANSERA), opened
2026-07-07 … 2026-07-20. ~2773 s back from 09:46 IST ≈ 09:00 IST, i.e. no tick since before the
open.

`tickedTokens=25` covers indices/futures/option legs — the swing holdings appear not to be on the
live subscription, so their SL/TP are not evaluated intraday. **Chronic condition, not a
today-regression** (positions are days old and swing exits run in the EOD batch), but it means a
live intraday stop on the swing books would not fire. Owner call for post-close: either subscribe
the open swing holdings or accept EOD-only exit evaluation and downgrade the alert.

### 4.2 Transient data-canary REDs on far-month FINNIFTY futures

```
03:56:12Z (09:26 IST)  data canary RED: NFO:FINNIFTY26SEPFUT — ticks flowing but no 1m bar closed for 537s
04:11:15Z (09:41 IST)  data canary RED: NFO:FINNIFTY26AUGFUT — ticks flowing but no 1m bar closed for 361s
04:11:15Z (09:41 IST)  data canary RED: NFO:FINNIFTY26SEPFUT — ticks flowing but no 1m bar closed for 478s
```

Self-recovered — the canary read GREEN with `problems:[]` at 09:44 IST. Illiquid far-month
contracts (quote ticks without trades), not the 07-03 CandleBuilder-poison signature. Low severity;
if it recurs daily, consider excluding far-month FINNIFTY from the divergence probe.

## 5 Capture + OI liveness

| series | numbers (since 09:15 IST) |
|---|---|
| 1m candles (all instruments) | 651 bars, max bucket 09:43 |
| `options_chain_snapshots` | NIFTY 50 20,010 / SENSEX 35,306 / NIFTY BANK 14,550 / BANKEX 14,952 / NIFTY MID SELECT 12,930 / NIFTY FIN SERVICE 7,950; last 09:46 |
| `futures_oi_snapshots` | 1,104 rows, **16 distinct minutes** in ~33 elapsed, last 09:45 |

**OI quadrants LIVE again (§3.12).** Today's rows carry real interpretations —
`SHORT_COVERING/LONG_BUILDUP` 12, `LONG_BUILDUP/LONG_BUILDUP` 2, `SHORT_BUILDUP/SHORT_BUILDUP` 2 —
and **zero NEUTRAL**. This is a recovery from 07-20's top finding (748/748 NEUTRAL, composite cap
dropped to 0.7181). 14 of the 30 rows still carry a NULL quadrant pair, consistent with the early
session before OI history accrued.

⚠ **Watch:** `futures_oi_snapshots` at 16 distinct minutes in 33 is the same gappy 1-minute cadence
§3.12 blamed for the NEUTRAL fallback (a 3 m `latestPair` read with no prior bucket yields a null
interpretation). Quadrants are live today anyway — re-measure over the full session in the EOD file
before drawing a conclusion.

## 6 Shadow book (check 2)

Today: **1 champion OPEN, 0 closed** (too early for a close). No CLOSED row with a NULL `pnl_net`,
so the F8 lot-size lookup is not failing.

League (`GET /api/v1/signal-rejections/shadow-summary`, cumulative, judge on NET ₹):

| variant | open | closed | wins | losses | pnl points | **pnl NET ₹** | unpriced |
|---|---|---|---|---|---|---|---|
| champion | 1 | 94 | 32 | 62 | −758.05 | **−44,133.07** | 0 |
| vol-off | 0 | 17 | 4 | 13 | −142.60 | −6,114.70 | 0 |
| vol-12k5 | 0 | 10 | 2 | 8 | −42.90 | −614.23 | 0 |
| composite-055 | 0 | 8 | 2 | 6 | 2.50 | −478.98 | 0 |

Standing caveat: shadow exits replicate brackets / structural stop / square-off only — **no
indicator-driven exits** — and every entry is stamped ~79 s after `bar_time`.

## 7 EXT-02 Upstox rate budget — first live session on this code (check 5)

Deployed Sunday 2026-07-19 (#934): one token-scoped ~1800/30 min budget shared across all 10 Upstox
analytics clients, batch lane paused from open−30 min, armed F9 margin path does a bounded
`tryAcquire` and returns `unpriced` rather than parking.

`docker logs ay-market-data-service --since 45m` (60 lines total): **0 matches** for
`rate.budget|rateBudget|budget exhaust|tryAcquire|rate limit|rate-limit|permit` and **0 matches**
for `unpriced`. No rate-starvation of the live margin path. The pre-open pause is holding on its
first live session.

## 8 To fold into the EOD post-market file

1. §3.12 OI-quadrant recovery — confirm it holds over the full session and record the composite cap
   with the OI dots live (today's max reached 0.6915).
2. `futures_oi_snapshots` per-minute cadence over the full session (expect ~375; 16/33 so far).
3. §3.10 strategy-coverage — the morning-only emitting set at 09:47 is expected; re-measure the
   full-day distinct-slug count against the 38 loaded.
4. §4.1 paper-bracket starvation → a tuning/ops candidate row, not an entry-gate candidate.
5. Re-read the eval counters BEFORE any post-close deploy — a recreate resets them (07-20 lost the
   ability to check its two interior coverage holes).
