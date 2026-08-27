# H26 rate-capture log — the kill-criterion data

One row per trading session. **Append, never overwrite.**

⚠️ **Why this file exists.** `ay_upstox_rate_window_peak_used` is a **per-process HIGH-WATER MARK**. It never decays and
it **resets on deploy, with nothing in the metric revealing the reset**. A session's peak is therefore lost the moment
market-data is recreated — so it must be captured before any post-close deploy and written down here.

⚠️ **Two rules that apply to every row, and misreading either produces a wrong verdict:**

1. **Kite is primary today, so Upstox usage measures HEADROOM, not capacity.** The projection is
   *Upstox-today PLUS the migrating Kite share* — never Upstox's own number alone.
2. **These are REQUEST counts, not instrument demand.** Kite chunks at 250 instruments/request
   (`KITE_QUOTE_BATCH_SIZE`); the Upstox quote gateway sends ONE unchunked call. **Raw request transfer is the WRONG
   model** — the OI snapshot's ~71 batched `/quote` calls per ~2-min cycle map to ~6 `/v2/option/chain` calls under
   Upstox, roughly 12:1 on the dominant term.

## Rows

| session | coverage | Upstox 30m peak | 1m peak | 1s peak | Upstox batch / live | live refused | Kite QUOTE | Kite HIST | Kite DUMP |
|---|---|---|---|---|---|---|---|---|---|
| **2026-08-26** (Wed) | **100%** — process up 08:41 IST, before the 09:15 open; captured 16:23 pre-deploy | **632 / 1800** (35%) | 254 / 450 (56%) | 17 / 45 (38%) | 1,272 / 32 | **0** | **17,669** | 596 | 1 |
| **2026-08-27** (Thu) | **100%** — process up 08:40 IST (host downtime boot), before the 09:15 open; captured 19:22, post-close, NO deploy since boot so the high-water marks are the session's | **215 / 1800** (12%) | 194 / 450 (43%) | 9 / 45 (20%) | 210 / 50 | **0** | **16,886** | 661 | 1 |

## ⚠️ Reading of 2026-08-27 — THE BASELINE MOVED, AND THE TWO ROWS ARE NOT COMPARABLE

**`computed` 2026-08-27 19:22. Upstox batch calls fell 1,272 → 210 (6.1×) and the 30-min peak
632 → 215, while the Kite side barely moved (QUOTE 17,669 → 16,886, HISTORICAL 596 → 661).**

⚠️ **This is not a quieter session. It is a DIFFERENT SYSTEM, and the change is ours.** The H31
`day-context` precompute deployed on the evening of 2026-08-26 — *after* that day's 16:23 capture.
`overnightCues()` reaches `UpstoxGlobalInstrumentsClient.worldIndices()`, an **uncached** batched
Upstox `/v2/market-quote/quotes` call, on **every** `day-context` compute; the precompute collapsed
those from once-per-caller to **4 per hour on a dedicated schedule**. The Upstox term this log
exists to measure is therefore a **direct function of a fix we shipped for an unrelated reason**.

**Consequences, and they are load-bearing for the stop rule:**

1. **The 2026-08-26 row is a PRE-FIX observation and must not be averaged with post-fix ones.**
   A five-session projection mixing the two measures neither system. On the current count this
   leaves **one** post-fix session, not two.
2. **The direction is favourable and should not be over-read.** Upstox headroom is now 12% of the
   30-min window against 35% the day before, `live_refused` still **0**. But the migrating Kite
   share — the term that actually decides H26 — is **unchanged**, so the naive projection
   (215 + ~1,414 ≈ 1,629) still fails the 1440 stop rule. **The call-shape factor remains the
   whole question; nothing measured today changes that.**
3. **A general lesson worth carrying: an unrelated fix moved the metric a kill-criterion is keyed
   on, silently.** Nothing in the H31 work mentioned Upstox rate consumption, and nothing in this
   log would have flagged the discontinuity if the two numbers had happened to land closer
   together. **Before adding a session to this tally, ask what deployed since the last row.**

⚠️ **Capture provenance:** today's 15:25 `h26-daily-rate-capture` task **did not append a row** —
this one was taken by hand at 19:22 and survived only because no deploy had recreated the process.
That is exactly the silent loss the header warns about; it is recorded rather than quietly fixed.

### Independent corroboration from the 15:28 scheduled capture (PR #1502, closed unmerged)

⚠️ **The 15:25 task DID run.** It captured at 15:28 IST and opened [#1502](https://github.com/prashantm912/artha-yantra-2/pull/1502);
the PR was never merged, so the row never reached `main`. An earlier note in this file said the capture
"did not append a row" — that was **wrong, and wrong in an instructive way**: the file on `main` was checked
and the open PRs were not. Absence from a file is not absence of work.

**Its numbers are kept here rather than as a second row, because this file allows ONE row per session.**
Captured 15:28 (pre-close) against the merged row's 19:22 (post-close), so the two are not directly
comparable — and the difference is itself informative:

| term | 15:28 capture | 19:22 capture (the row above) |
|---|---|---|
| Upstox 30m peak | 215 | 215 |
| Upstox `batch` | 210 | 210 |
| Kite `QUOTE` | 16,657 | 16,886 |
| Kite `HIST` | 403 | 661 |

**The Upstox terms are IDENTICAL at both times, and the Kite terms are not.** So the Upstox load had already
stopped growing by 15:28 while Kite kept working through the evening chain — consistent with the H31
precompute having capped the Upstox term at 4 calls/hour, and with the evening jobs being Kite-only.

⚠️ **The fact worth keeping, which the merged row does not carry:** normalised per 30-min window, the
migrating Kite term is **≈1,340 (08-27) vs ≈1,414 (08-26) — STABLE across the two sessions**, while the
Upstox term moved 6.1×. That is what makes the H31 precompute the credible cause of the Upstox shift rather
than a quieter market: a quiet session would have moved BOTH terms.

⚠️ #1502 also recorded the Upstox gap as **unexplained**, which was correct at 15:28. It is explained now
(the precompute deployed the previous evening) — recorded so the "unexplained" framing is not re-inherited.

## Reading of 2026-08-26 — `computed`, and it is ONE session

**The raw-transfer model FAILS the stop rule, exactly as the plan predicted.**

- Session = 375 min = **12.5** half-hour windows.
- Kite `QUOTE` 17,669 / 12.5 ≈ **1,414 requests per 30-min window** (mean; the peak window is higher).
- Naive projection: 632 + 1,414 = **≈2,046** against an 1800 ceiling — **over the ceiling entirely**, and far past the
  **1440 (80%) stop rule**.

⚠️ **This is NOT a verdict to stop.** It is the arithmetic the plan already flagged as the wrong model: raw request
transfer ignores that the dominant Kite consumer remaps ~12:1 onto Upstox's chain endpoint. **Only the remapped model can
pass, which is precisely why the call-shape factor is mandatory rather than a refinement.**

What this row genuinely establishes:
- **Upstox headroom today is comfortable** — 35% of the 30-min window, `live_refused` = **0**, so nothing is being
  throttled at current load.
- **The migrating Kite share is large in request terms** (17,669/session) and **dominated by one consumer**. `computed`
  from a call-site census: of 11 Kite `QUOTE` call sites only two are self-scheduled — `FuturesOiSnapshotService`
  (cron `0 * * * * *`, but **one batched call per pass** ≈ 375/session, so NOT the dominant term) and
  `PreOpenEquityScanService` (once daily). The remainder is request-driven, and the bulk is consistent with
  `OptionsSnapshotService` (~70 batched calls per 2-min cycle ≈ 35/min ≈ 13k/session).
- **So the whole projection turns on one job's call shape** — not on diffuse load. That is a much more tractable
  question than the raw number suggests, and it is the next thing to measure.

⚠️ **One session is not a rate.** The stop rule needs **five**. Do not act on this row alone.

## Next

`h26-daily-rate-capture` (weekday 15:25 IST, before close and before any deploy) appends a row per session. After five
sessions, compute `projected_upstox_30m = 30m peak + Σ(Kite 30m rate × call-shape factor)` and apply the stop rule.

⚠️ Under end state **(b)** (Upstox-only, Kite dormant — owner decision 2026-08-26) there is **no fallback to absorb a bad
projection**, so the stop rule is **more binding, not less**.
