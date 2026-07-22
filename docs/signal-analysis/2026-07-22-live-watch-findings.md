# Live data-health watch — 2026-07-22 (in-session, 09:43–09:52 IST)

Scheduled task `live-data-health-check` (session-analysis `live` mode, README §4.1). Market open
(Wed, NIFTY weekly expiry Tue was 07-21), ~28 min into the session. **Read-only**: SELECTs,
`docker logs`, GET endpoints only — no restart, deploy, write or config change.

This is a LIVE snapshot, not the session forensics. The immutable
`2026-07-22-session-findings.md` (15:47 post-market task) should fold §2/§4/§5 here into its §4
(data health) and §6 (new data points).

Stack: all 11 containers healthy (up ~45 min at read time; booted ~08:58 IST).

---

## 1 Verdict

**GREEN.** Both machine canaries green, engine liveness confirmed by an advancing counter delta
across a bar boundary, EXT-02 rate budget clean. Two carried-risk notes for the owner (§4), neither
actionable mid-session.

---

## 2 Machine canaries (STEP 0)

| canary | result |
|---|---|
| `GET /api/v1/market/health/data` | `status=GREEN`, `tickedTokens=25`, `problems:[]`, asOf `2026-07-22T04:18:17Z` (09:48 IST) |
| `GET /api/v1/signal-rejections/dot-health` | `session=true`, `rowsInspected=18`; dead = `iv_rank`, `dow`, `fii` (all `required=false`); alive = `breadth` (required), `oi_spurt_price`, `vix` |

**Dead-set diff vs 2026-07-21 findings:** identical — no NEWLY dead, no NEWLY alive dot. The three
dead dots are the carried set (`ivRank`/`fiiLongPct` dead since 07-02; `dow` un-armed by design).

## 3 Engine liveness (check 1 — the counters, never `signal_rejections`)

Per [[engine-liveness-is-counters-not-rejections]] the signal is Σ `ay_signal_eval_outcome_total`
advancing, read from the actuator on port **8082**.

| reading (IST) | chart-gate-failed | confluence-blocked | composite-below-threshold | Σ |
|---|---|---|---|---|
| 09:45:18 (dot-health rowsInspected snapshot) | — | — | — | — |
| 09:47:51 | 38 | 34 | 0 | **72** |
| 09:48:25 | 38 | 34 | 0 | **72** |
| 09:48:46 | 38 | 34 | 0 | **72** |
| 09:49:23 | 58 | 50 | 0 | **108** |

**Σ 72 → 108 ⇒ engine ALIVE.** Three flat readings (09:47:51–09:48:46) sit inside one 3 m bar; the
09:49:23 reading crosses the bar close and jumps, same pattern as 07-21. `ay_signal_eval_failures_total
= 0.0` throughout. `fired = 0`, `unscoreable-indicators-warming = 0`, `discipline-paused = 0`,
`confluence-gate-absent = 0`.

**Boot line (read same day — a restart destroys it):**

```
03:28:23Z (08:58 IST)  signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)
03:29:43Z (08:59 IST)  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

Cold boot hit the F10 class again (0 loaded / 38 unresolved), #874 retry recovered ~80 s later,
well before the 09:15 open. Health signal `unresolved == 0` holds.

**Denominator:** 44 published+enabled strategies = 38 `scalp-%` + 6 non-scalper (swing/batch, not
loaded by the live engine) — verified by direct query today (`slug LIKE 'scalp-%'` → 38 true / 6
false). 38 loaded / 38 scalpers = full coverage.

**Rejection context (CONTEXT ONLY):** 50 rows / 16 distinct slugs by 09:49:21 IST.

## 4 Owner notes (no action taken — market hours)

### 4.1 Transient data-canary REDs on FINNIFTY futures (same pattern as 07-21)

```
03:56:12Z (09:26 IST)  data canary RED: NFO:FINNIFTY26SEPFUT — ticks flowing but no 1m bar closed for 606s
04:11:14Z (09:41 IST)  data canary RED: NFO:FINNIFTY26SEPFUT — ticks flowing but no 1m bar closed for 838s
```

Self-recovered — canary read GREEN with `problems:[]` at 09:48 IST. `FINNIFTY26SEPFUT` had only 3
one-minute bars in 33 elapsed minutes (far-month illiquid) vs `NIFTY26JULFUT`/`NIFTY26AUGFUT` at
33/33 fresh to 09:47. Same read as 07-21 §4.2: far-month-illiquidity false positive, not the 07-03
CandleBuilder-poison signature. Third session in a row this fires on far-month FINNIFTY — worth a
post-market look at whether the canary should exempt non-front contracts.

### 4.2 Kite ticker WebSocket 403 + TOKEN_EXPIRED at 03:28Z (08:58 IST boot)

```
03:28:12Z  kite ticker error: ... HTTP/1.1 403 Forbidden (repeated ~4x, Timer-0 retry thread)
03:28:22Z  kite session status -> TOKEN_EXPIRED
```

Pre-dates the daily login cycle at boot (container came up 08:58, before the day's Kite session was
refreshed) — self-resolved, `tickedTokens=25` GREEN by 09:43. Consistent with normal boot-before-login
sequencing, not flagged as an anomaly, but noting it since it coincides with the same boot window as
the F10 cold-start (§3).

## 5 Shadow book (check 2)

Today: **14 champion OPEN, 0 closed** — no CLOSED row with NULL `pnl_net`. No challenger-variant
positions opened yet (historically first-open ~10:15–11:45 IST).

League (`GET /api/v1/signal-rejections/shadow-summary`, cumulative, judge on NET ₹):

| variant | open | closed | wins | losses | pnl points | **pnl NET ₹** | unpriced |
|---|---|---|---|---|---|---|---|
| champion | 14 | 103 | 39 | 64 | −580.60 | **−41,260.30** | 0 |
| vol-off | 0 | 21 | 4 | 17 | −383.65 | −11,226.90 | 0 |
| vol-12k5 | 0 | 13 | 2 | 11 | −265.65 | −5,285.67 | 0 |
| composite-055 | 0 | 8 | 2 | 6 | 2.50 | −478.98 | 0 |

Standing caveat: shadow exits replicate brackets/structural stop/square-off only — no
indicator-driven exits.

## 6 EXT-02 Upstox rate budget (check 5)

`docker logs ay-market-data-service --since 09:15 IST`: **0 matches** for `rate budget exhausted` /
`unpriced` attributable to rate exhaustion. No rate-starvation of the live margin path this session.

## 7 To fold into the EOD post-market file

1. Confirm the FINNIFTY far-month canary-RED pattern (§4.1) recurs for a 3rd consecutive session —
   candidate to exclude non-front FINNIFTY from the divergence probe.
2. Re-measure Σ eval-outcome delta over the full session; today's boot was ~17 min later
   (08:58 vs 07-21's 08:49) — watch whether that shifts closer to the 09:15 open on a bad day.
3. Re-read the eval counters BEFORE any post-close deploy — a recreate resets them.
