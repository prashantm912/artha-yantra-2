# Live data-health watch — 2026-07-24 (in-session, ~11:05–11:13 IST)

Scheduled task `live-data-health-check` (session-analysis `live` mode, README §4.1). Market open
(Fri, ~1h50m into the session). **Read-only**: SELECTs, `docker logs`, GET endpoints only — no
restart, deploy, write or config change.

This is a LIVE snapshot, not the session forensics. The immutable
`2026-07-24-session-findings.md` (15:47 post-market task) should fold §4/§5 here into its §4 (data
health) and §6 (new data points).

Stack: all 11 containers healthy, up ~26h (no reboot today — last boot 2026-07-23 08:37 IST).
Both canaries GREEN ⇒ deep-dive checks (3/4) skipped by design; ran canaries + checks 1, 2, 5.

---

## 1 Verdict

**GREEN.** Both machine canaries green, engine liveness confirmed by an advancing counter delta
across a bar boundary, EXT-02 rate budget clean, shadow book opening normally with priced closes.
No anomaly, no owner action.

---

## 2 Machine canaries (STEP 0)

| canary | result |
|---|---|
| `GET /api/v1/market/health/data` | `status=GREEN`, `marketOpen=true`, `tickedTokens=25`, `problems:[]`, asOf `2026-07-24T05:39:31Z` (11:09 IST) |
| `GET /api/v1/signal-rejections/dot-health` | `session=true`, `rowsInspected=40`; dead = `iv_rank`, `dow`, `fii` (all `required=false`); alive = `breadth` (required), `oi_spurt_price`, `vix` |

**Dead-set diff vs 2026-07-23 findings:** identical — no NEWLY dead, no NEWLY alive dot. Carried set
(`ivRank`/`fiiLongPct` dead since 07-02; `dow` un-armed by design).

## 3 Engine liveness (check 1 — the counters, never `signal_rejections`)

Per [[engine-liveness-is-counters-not-rejections]] the signal is Σ `ay_signal_eval_outcome_total`
advancing, read from the actuator on port **8082**. Counters are cumulative since the 07-23 08:37 boot.

| reading (IST) | chart-gate-failed | confluence-blocked | composite-below-threshold | Σ |
|---|---|---|---|---|
| ~11:09 | 2566 | 1870 | 178 | **4614** |
| ~11:12 | 2602 | 1906 | 178 | **4686** |

**Σ 4614 → 4686 (+72 across one 3 m bar boundary; chart-gate +36, confluence-blocked +36) ⇒ engine
ALIVE.** `ay_signal_eval_failures_total = 0.0`. `fired = 0`, `unscoreable-indicators-warming = 0`,
`discipline-paused = 0`, `confluence-gate-absent = 0`.

**Boot line (last boot 07-23; no restart today):**

```
signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)
signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

Cold boot hit the F10 class (0 loaded / 38 unresolved), #874 retry recovered. Health signal
`unresolved == 0` holds.

**Denominator:** 44 published+enabled = 38 `scalp-%` + 6 non-scalper (swing/batch, not loaded by the
live engine). Emitting today: **34 distinct slugs** (34/38 scalpers, consistent with prior sessions —
context only, not the liveness signal).

## 4 Shadow book (check 2)

Today's opens (`opened_at >= 09:15 +05:30`): champion **9 OPEN / 1 CLOSED** (−1,624.64 net), and both
challenger variants `vol-off` / `vol-12k5` trading (2 open / 1 closed each). Every CLOSED row carries
a non-NULL `pnl_net` ⇒ **no NFO leg-size lookup failure to flag** (F8 healthy). `composite-070` no
entries today (higher composite floor — expected).

League (`GET /api/v1/signal-rejections/shadow-summary`, cumulative, judge on NET ₹):

| variant | open | closed | wins | losses | pnl points | **pnl NET ₹** | unpriced |
|---|---|---|---|---|---|---|---|
| champion | 14 | 159 | 65 | 94 | −613.70 | **−33,592.90** | 0 |
| vol-12k5 | 2 | 23 | 7 | 16 | −282.85 | −8,431.99 | 0 |
| composite-055 | 0 | 9 | 2 | 7 | −58.15 | −4,498.59 | 0 |
| vol-off | 2 | 31 | 9 | 22 | −400.85 | −14,373.22 | 0 |

**Champion back cumulative-negative** (−₹33,592.90 over 159 closes) after the transient +₹980.61 the
07-23 file recorded over 130 closes — the sessions since gave the sign back, confirming that file's
"treat the positive league position as regime, not edge" caveat. Standing caveat: shadow exits
replicate brackets/structural stop/square-off only — no indicator-driven exits.

## 5 EXT-02 Upstox rate budget (check 5)

`docker logs ay-market-data-service` since 09:15 IST: **0** rate-budget acquire failures, **0**
`unpriced` reasons attributable to rate exhaustion. The open−30min batch-lane pause is holding; the
armed F9 margin path is un-starved.

## 6 To fold into the EOD post-market file

1. §4 — re-read the league at close; state whether 07-24 extends the champion drawdown or stabilises.
2. §3 — re-read the eval counters BEFORE any post-close deploy (a recreate resets them).
3. Confirm the 34/38 emitting-slug ratio holds across the full session (silent-slug watch, §3.10).
