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

**Tally: 2 clean sessions cited (08-24, 08-27) of ≥5 required.** Not 3 — session 2 has no citable
row, and per this file's own rule a tally with no row to cite is not a tally.

**VERDICT: not yet enough evidence. Do NOT move the anchor.**

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
