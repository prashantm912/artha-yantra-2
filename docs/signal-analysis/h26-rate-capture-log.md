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
