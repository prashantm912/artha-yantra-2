# 2026-08-27 — what H31's own telemetry says, and one correction to the session analysis

`computed` 2026-08-27 19:20–19:45 IST from the running `ay-market-data-service`, post-close, no
deploy since the 08:40 boot. Read-only: log greps, `/actuator/prometheus`, SELECTs, `docker inspect`.

## Verdict first

**The H31 precompute works — 28 snapshot hits, 30 refreshes, and the only inline compute all day was
the 18:48 EOD job deliberately asking for an uncached read.** Two things sit underneath it that were
not known this morning:

1. **The fix's safety margin is 120 seconds and nobody wrote that down.** It is a scheduling
   coupling, not a property of the code.
2. **Five morning refreshes ran 15–34 s instead of ~300 ms, and the cause is now identified** — it is
   the co-cause an earlier task recorded as *unsized*.

And one correction that belongs to the session analysis rather than to H31:

3. **`futures_oi`'s ~20 missing minutes were NOT "post-boot warm-up".** 19 of 20 coincide with the
   `kite-rest` circuit breaker being open.

## 1. The margin is a coupling, not a property

`docker inspect ay-market-data-service` (never the yml):

```
ARTHA_CONTEXT_DAY_CONTEXT_REFRESH_CRON=0 13,28,43,58 8-15 * * MON-FRI
ARTHA_CONTEXT_DAY_CONTEXT_SNAPSHOT_MAX_AGE_SECONDS=300
```

The refresh runs **every 15 minutes** and the snapshot is honoured for **5 minutes**
(`DayContextService.heavy()` falls through to `inlineHeavy` once `age > snapshotMaxAge`). Read
naively that is the [[H31]] arithmetic trap again — a TTL shorter than its caller's interval — and
for 10 of every 15 minutes the snapshot IS past max-age.

It works because the cron fires at **:13/:28/:43/:58**, two minutes ahead of the insight sweep at
:15/:30/:45/:00. The sweep therefore reads a snapshot ~2 minutes old, inside the 5-minute window.

⚠️ **So the real margin is `120 s − refresh duration`, and it is invisible in both values.** Nothing
in the cron, the max-age, or their names says the two are coupled. Today's worst refresh consumed
**34 s of the 120 s** — 28%. Consequences worth stating before anyone edits either knob:

- **Moving either schedule independently breaks the fix silently.** The failure mode is not an error;
  it is callers quietly paying the inline compute again, which is exactly the H31 defect returning.
- A refresh that overruns 120 s hands the sweep an expired snapshot. **A failed refresh is worse than
  a slow one**: it leaves the previous snapshot in place to age out, so the miss arrives later and
  detached from its cause.

## 2. The morning slowness is the Upstox global-quote call, and it is now sized

Refresh durations, IST, `docker logs` (all 30):

| slot | duration |
|---|---|
| 08:43 | 395 ms |
| **08:58** | **5,180 ms** |
| **09:13** | **34,046 ms** |
| **09:28** | **15,327 ms** |
| **09:43** | **15,250 ms** |
| **09:58** | **15,271 ms** |
| 10:13 → 15:58 | 207–437 ms, with four 2.2–2.7 s outliers |

⚠️ **Three near-identical values (15,327 / 15,250 / 15,271) are a TIMEOUT, not variable work.** That
is the tell; a genuinely variable computation does not land within 77 ms of itself three times.

The cause maps one-to-one in time. `Upstox global quote batch failed (10 keys) — rows render
price-less: … ResourceAccessException: I/O error on GET` appears **exactly five times**, at
**08:58:00, 09:13:30, 09:28:15, 09:43:15, 09:58:15** — the same five slots. That is
`overnightCues()` → `UpstoxGlobalInstrumentsClient.worldIndices()`, the **uncached** batched Upstox
call, which the 2026-08-26 measurement task named as the leading unsized co-cause. **It is no longer
unsized: ~15 s per failing refresh, five refreshes, self-resolving after 09:58.**

⚠️ **It reported success throughout.** `ay_day_context_snapshot_refresh_failed_total` never
incremented — the call fails soft and the snapshot still publishes, so the only visible trace is the
duration. [[success-shaped-nothing-catalogue]]: the counter watches the step, the duration watches
the work.

Counters at 19:22: `hit_total` **28**, `refresh_total` **30**, `uncached_total` **1**.

## 3. Correction — the missing `futures_oi` minutes are the circuit breaker

The 2026-08-27 session findings report **"zero in-session outage lines"** and attribute ~20 missing
`futures_oi` capture minutes to **"post-boot warm-up + scattered early-session gaps"**. Both are
wrong, and the evidence was in the same logs.

`docker logs ay-market-data-service --since 12h | grep -c "circuit open"` → **564**, spanning
08:40:30 → 15:45:01, clustered at **09:13–09:58**, **10:17–10:18** and **15:45**.

The missing minutes (`marketdata.futures_oi_snapshots`, 09:15–15:29, generate_series left-join):

```
09:15 09:16 09:17 09:18 09:21 09:22 09:23 09:27 09:28 09:37 09:38
09:42 09:43 09:47 09:48 09:56 09:57 10:16 10:17 11:38
```

**19 of 20 fall in or immediately adjacent to a circuit-open minute.** Only 11:38 is unexplained.

⚠️ **Warm-up cannot be the explanation: 09:56 and 10:16 are ~76 and ~96 minutes after the 08:40
boot.** The attribution reads plausibly because the gaps *start* near boot, and the two late ones are
the disproof — the same shape as reading a leading `OPEN` and not checking the tail.

**What is NOT established, and is deliberately not asserted:** *why* the breaker opens. The obvious
candidate does not survive: every Kite `403 Forbidden` and `no live Kite session` burst is confined
to **08:40:20–08:40:39**, i.e. boot, while the breaker clusters begin at 09:13. I checked that
specifically because a 403-at-open would have been a tidy argument for arming the TOTP auto-login,
and the timestamps refuse it. `ay_kite_session_valid` reads **1.0** now. **Unsized, open.**

## What follows

- **Do not treat the 15-minute cron and the 300 s max-age as independent knobs.** If either moves,
  re-derive `120 s − refresh duration`. A guard asserting the cron lands inside max-age of the sweep
  is the right shape, and **is now BUILT** — `DayContextRefreshPhaseTest`
  ([#1508](https://github.com/prashantm912/artha-yantra-2/pull/1508) @ `fbe1e2d3`). It parses BOTH
  crons out of the files that declare them rather than copying the consumer's schedule, because a
  copy would pin market-data's BELIEF about a collaborator and keep passing after the collaborator
  moved. It asserts two things, since max-age alone is not the invariant: every sweep reads a
  snapshot inside max-age, AND the refresh fires at least 60 s ahead of it — a refresh moved to
  `:14:59` would score an age of 1 s and pass while leaving no time to COMPLETE. Four red-proofs,
  including one that moves the CONSUMER cron in strategy-signal and reddens this market-data test.
- **Judge a refresh on DURATION, not on the failed counter** — the counter cannot see a soft-failing
  upstream.
- The Upstox `worldIndices()` call is uncached on a path that now runs 4×/hour rather than
  per-caller, which is *why* [[H26]]'s Upstox baseline moved 6.1× — see `h26-rate-capture-log.md`.
- The breaker cause is the open thread, and it is worth one session's attention: it degraded 564 log
  lines' worth of chain broadcasts to cached data and cost 19 capture minutes, silently.
