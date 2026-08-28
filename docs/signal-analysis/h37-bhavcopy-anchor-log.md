# H37 — bhavcopy anchor earliness watch

Running record for the scheduled task `measure-bhavcopy-anchor-earliness` (weekdays 18:23 IST).
**One row per session. Read this file before measuring; append after.** The tally does not exist
anywhere else — a run that does not append leaves nothing behind, and the "5 clean sessions" bar
becomes unreachable by construction.

## What is being decided

`ARTHA_BHAVCOPY_EOD_CRON` is `0 45 18 * * MON-FRI` (`docker inspect ay-market-data-service`, never
the yml default). The whole evening chain 18:45 → 18:59 hangs off it, one job per minute, and the
box is routinely shut down ~19:00. The question is whether the anchor can move earlier — i.e.
whether NSE's `sec_bhavdata_full_DDMMYYYY.csv` is already COMPLETE at ~18:23 on **every** session.

**The bar is EVERY session, not most.** A single short reading is decisive against moving: a partial
bhavcopy silently feeds the screens, the swing settles and the whole downstream chain.

## How a reading is taken (read-only)

URL from `LiveBhavcopyFetcher.fetchForDate` — `https://nsearchives.nseindia.com/products/content/
sec_bhavdata_full_<ddMMyyyy>.csv`, with `NseHttpClient`'s browser UA + `Referer: https://www.nseindia.com/`.
Fetch to a temp file with `curl`, count non-header data rows, confirm every `DATE1` is today, and
check the file is not truncated (it is symbol-sorted, so a complete file ends at a `Z*` symbol).
**Never** through an ingest endpoint; nothing may write `marketdata.nse_eod_bhavcopy`.

## Completeness bar

A complete day **STORES 3,296–3,506 rows** (`marketdata.nse_eod_bhavcopy` grouped by `trade_date`).
⚠️ Do **not** use `ingest_runs.rows_written` — bhavcopy writes are `ON CONFLICT DO NOTHING`, so it
counts rows SUBMITTED across a whole catch-up loop, not rows stored. The original brief's
"~8,300–8,600" came from that column and was wrong by ~2.5×, which made the bar unreachable.

Per-series cross-check on recent complete days (`computed` 2026-08-27, last 8 sessions):
EQ 2,624–2,639 · SM 343–380 · BE 226–238.

## Sessions

| # | Date | Probe time IST | Data rows | EQ | SM | BE | All rows dated today | Complete? | Evidence |
|---|------|----------------|-----------|----|----|----|----------------------|-----------|----------|
| 1 | 2026-08-24 | 18:24 | 3,506 | 2,639 | — | — | yes | **YES** | `sourced` — H37 ledger row, `docs/superpowers/plans/2026-07-02-remaining-items.md:1771` ("3,506 rows, all dated 24-Aug-2026, EQ 2,639") |
| 2 | 2026-08-25 | ? | **NOT RECORDED** | — | — | — | ? | **UNCITED** | ledger says "2 of 5 sessions" (`14190d5c`, 2026-08-25) but no row count survives anywhere. Cannot be counted toward the bar. |
| — | 2026-08-26 | — | **NO RECORD** | — | — | — | — | **UNKNOWN** | weekday, task should have fired; no artifact found in repo or ledger. Treated as not measured. |
| 3 | 2026-08-27 | 18:24 | 3,457 | 2,623 | 358 | 242 | yes (`27-Aug-2026`, sole distinct value) | **YES** | `computed` — `curl` HTTP 200, 391,423 bytes; `wc -l` 3,458 total / 3,457 data; file runs `20MICRONS` → `ZYDUSWELL` (not truncated) |
| 4 | 2026-08-28 | 18:24 | 3,460 | 2,629 | 352 | 243 | yes (`28-Aug-2026`, sole distinct value) | **YES** | `computed` — `curl` HTTP 200, 391,621 bytes; `wc -l` 3,461 total / 3,460 data; file runs `1018GS2026` → `ZYDUSWELL` (not truncated) |

**Tally: 3 clean sessions cited (08-24, 08-27, 08-28) of ≥5 required.** Not 4 — session 2 has no
citable row, and per this file's own rule a tally with no row to cite is not a tally.

**VERDICT: not yet enough evidence. Do NOT move the anchor.**

## ⚠️ Same-day corroboration for 2026-08-28 — complete at 17:10, NOT a fourth session

**`computed`, and it arrived by accident rather than from the measurement built to find it.** A
post-close deploy at 17:10 IST triggered the boot catch-up, which ran the whole evening chain ~95
minutes early. `marketdata.ingest_runs`: `BHAVCOPY` at **17:10:35** — and
`marketdata.nse_eod_bhavcopy` holds **3,460** rows for `trade_date = 2026-08-28`, the SAME count
the 18:24 file probe measured, with every later `BHAVCOPY` run (17:11:31, 17:19:51, 17:22:52,
18:44:59) writing **0**.

**So the file was already complete at 17:10:35** — **95 minutes before the 18:45 anchor** and 74
minutes before the probe designed to test this. That is the earliest completeness evidence in this
file by a wide margin, and it is consistent with the 18:04 observation already recorded in the H37
ledger row.

⚠️ **DELIBERATELY NOT COUNTED AS SESSION 5, and the distinction is the whole discipline of this
file.** It is not a probe of the NSE file; it is an inference from what our ingest stored plus the
absence of later writes. Sound, but a different instrument — and this file already refuses to count
session 2 for having no citable row. Promoting a different measurement to the tally because it
points the way we want would be the same error wearing better clothes.

**Tally unchanged at 3 of ≥5. Verdict unchanged: do NOT move the anchor.** What this does change is
the PRIOR: an anchor move looks considerably more likely to survive the remaining sessions than the
18:24-only evidence suggested. If a future run wants to test an earlier slot directly, 17:10 is now
the earliest hour with same-day support.


## Notes carried forward

- The scheduled task fires at **18:23**, not the 18:15 the brief's prose says. Both readings so far
  were taken at **18:24**, so what is actually proven is *complete by 18:24* — a **21-minute** margin
  ahead of 18:45, not 30. Any recommendation must say 18:24, not 18:15.
- Today's EQ count (2,623) is 1 below the 8-session minimum (2,624). Not read as partial: a truncated
  file would be missing a contiguous alphabetical tail, and this one runs to `ZYDUSWELL`. EQ
  membership moves with listings/suspensions.
- ✅ **Cross-check RUN 2026-08-27 19:21, and it PASSES — session 3 stands.** `computed` from
  `marketdata.nse_eod_bhavcopy` after the 18:44:58 ingest: `trade_date = 2026-08-27` stores
  **3,457** rows / EQ **2,623** — **identical to the 18:24 probe on both counts**. The 18:24 file
  was the same file the 18:45 anchor consumed; the session does not flip to NO.
- Corroborates this file's own `rows_written` correction: the same run's
  `ingest_runs.rows_written` reads **8,380** against 3,457 stored (2.4×), because the write is
  `ON CONFLICT DO NOTHING` across a catch-up loop. **Use the stored count, never the column.**
- Neighbouring stored counts for context (`computed` same query): 08-21 3,479 · 08-24 3,506 ·
  08-25 3,471 · 08-26 3,475 · 08-27 3,457. Today is the smallest of the five and still complete —
  further reason not to read a low count as truncation without checking the alphabetical tail.
- **Tally after tonight: 2 clean cited sessions (08-24, 08-27) of ≥5. VERDICT UNCHANGED — do NOT
  move the anchor.** Two more clean sessions are needed at minimum, and 08-25/08-26 cannot be
  recovered.

### Session 4 — 2026-08-28

- `computed` 18:24 IST probe: HTTP 200, 391,621 bytes, **3,460** data rows, EQ **2,629** · SM **352**
  · BE **243**, sole distinct `DATE1` = `28-Aug-2026`, tail reaches `ZYDUSWELL`. Inside the
  3,296–3,506 bar; EQ inside 2,624–2,639; SM inside 343–380. BE 243 is 5 above the 8-session max
  (238) — membership drift upward, not a truncation signature (a truncated file loses a contiguous
  alphabetical tail, and this one ends at `ZYDUSWELL`). Session reads **complete**.
- ✅ **Cross-check ALREADY SATISFIED, and it did not need the 18:45 run.** `computed` from
  `marketdata.ingest_runs`: today's only run with a non-zero `rows_written` was **17:10:35**
  (8,391 submitted, 26.1 s); the 17:19 / 17:22 runs submitted 0. `computed` from
  `marketdata.nse_eod_bhavcopy`: `trade_date = 2026-08-28` already stores **3,460** rows / EQ
  **2,629** — **byte-for-byte the same two counts as the 18:24 probe**. So the file the 18:24 probe
  read is the file already stored, and it was already complete at **17:10** today.
- ⚠️ **That 17:10 run is a BONUS observation, not a fourth measurement.** It is a catch-up, not the
  scheduled anchor, and it is `assumed` (not measured) why it fired then. It does not add a session
  to the tally — but it is the second independent instance (with 2026-08-21's 18:04 boot catch-up)
  of a complete file well before 18:45.
- First distinct value: today's file starts at `1018GS2026`, not `20MICRONS` — 48 `GS` + 43 `GB`
  government-security rows sort ahead numerically. Not a shape change; the completeness check is the
  tail, not the head.
- **Tally after tonight: 3 clean cited sessions (08-24, 08-27, 08-28) of ≥5. VERDICT UNCHANGED — do
  NOT move the anchor.** Two more clean sessions needed; 08-25 / 08-26 are unrecoverable.
