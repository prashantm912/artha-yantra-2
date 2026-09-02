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
| 5 | 2026-08-31 | 18:25 | 3,475 | 2,647 | 353 | 232 | yes (`31-Aug-2026`, sole distinct value) | **YES** | `computed` — `curl` HTTP 200, 394,397 bytes; `wc -l` 3,476 total / 3,475 data; file runs `1018GS2026` → `ZYDUSWELL`, final line carries all 15 fields (not truncated) |
| — | 2026-09-01 | — | **NOT MEASURED** | — | — | — | — | **UNKNOWN** | trading day (`computed`: `nse_eod_bhavcopy` stores 3,495 rows / EQ 2,646 for `trade_date = 2026-09-01`), but the host was down in-session 12:42→18:45 IST, so the 18:23 task never fired. Not a short reading — an absent one. |
| 6 | 2026-09-02 | 18:24 | 3,482 | 2,646 | 369 | 234 | yes (`02-Sep-2026`, sole distinct value) | **YES** | `computed` — `curl` HTTP 200, 394,524 bytes; `wc -l` 3,483 total / 3,482 data; series sum = 3,482 exactly; file runs `1018GS2026` → `ZYDUSWELL`, final line carries all 15 fields (not truncated) |

**Tally: 5 clean sessions cited (08-24, 08-27, 08-28, 08-31, 09-02) of ≥5 required — the bar is
MET.** Session 2 (08-25) still has no citable row and is still not counted; 08-26 and 09-01 were
trading days with no probe (08-26: no artifact anywhere; 09-01: host down in-session 12:42→18:45
IST). Those are ABSENT readings, not short ones — nothing in this file has ever read short at the
probe hour.

**VERDICT: the bar is met — the anchor CAN move, to 18:24 (not 18:15).** Five cited sessions, five
complete files, zero short readings. Recommendation to the owner:

1. **Disable the scheduled task** `measure-bhavcopy-anchor-earliness` — it has answered its question.
2. **Move `ARTHA_BHAVCOPY_EOD_CRON`** from `0 45 18 * * MON-FRI` earlier. What is PROVEN is
   *complete at 18:24*, so `0 24 18 * * MON-FRI` is the evidence-backed move and buys the chain a
   **21-minute** head start (chain would run 18:24→18:38). Anything earlier than 18:24 is NOT
   proven by this file — the 17:10 and 18:04 same-day corroborations are a different instrument
   (see below) and support a bolder move only as a prior, not as evidence.
3. **Moving the anchor moves the WHOLE chain** — the 18:45→18:59 jobs are each pinned by their own
   cron and must be shifted together, or the settles will run BEFORE the bhavcopy they depend on.
   Shifting the anchor alone is the failure mode to avoid. ⚠️ **Re-read every job's live cron from
   `docker inspect ay-market-data-service` / `ay-strategy-signal-service` before editing** — the
   CLAUDE.md list has gone stale twice.
4. ⚠️ The H37 premise that motivated this ("box shut down ~19:00, one-minute margin") was itself
   **refuted** — measured shutdowns run to ~02:00–03:00 and zero of 13 fell in the evening window.
   The anchor move is still a real robustness win (it removes the tail-risk of an early shutdown and
   gives the chain slack), but it is no longer urgent.

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

**Tally unchanged at 3 of ≥5 as of 2026-08-28 (⚠️ STALE — 4 as of Session 5; see the table). Verdict unchanged: do NOT move the anchor.** What this does change is
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

## Session 5 — 2026-08-31 (Monday)

- `computed` 18:25 IST probe: HTTP 200, 394,397 bytes, **3,475** data rows, EQ **2,647** · SM **353**
  · BE **232**, sole distinct `DATE1` = `31-Aug-2026` (3,475 of 3,475), tail reaches `ZYDUSWELL` with
  a full 15-field line. Inside the 3,296–3,506 bar; SM and BE inside their cross-check bands. EQ
  **2,647** is 8 above the 8-session max (2,639) — again membership drift UPWARD, which cannot be a
  truncation signature. Session reads **complete**.
- `sourced` anchor re-read at run time: `docker inspect ay-market-data-service` →
  `ARTHA_BHAVCOPY_EOD_CRON=0 45 18 * * MON-FRI`. Unchanged.
- `computed` from `marketdata.ingest_runs` (21 days): today's only `BHAVCOPY` run so far is
  **08:32:46**, `SUCCESS`, `rows_written=0`, 26.5 s — the morning boot catch-up, correctly writing
  nothing for a session that had not closed. The 18:45 anchor had not fired when this was taken, so
  there is **no same-day stored-row cross-check tonight** (unlike 08-28's accidental 17:10 deploy
  catch-up). The probe stands on the file itself.
- `computed` from `marketdata.nse_eod_bhavcopy`: last stored trade date is **2026-08-28** (3,460
  rows). No 08-29 row, and that is correct — 08-29 was a Saturday.
- **Tally after tonight: 4 clean cited sessions (08-24, 08-27, 08-28, 08-31) of ≥5. VERDICT
  UNCHANGED — do NOT move the anchor.** One more clean session (next: 2026-09-01) reaches the bar.

## Session 6 — 2026-09-01 (Tuesday) — ⚠️ **PROBE FIRED LATE, NOT COUNTABLE**

- ⚠️ **The run fired at 18:52 IST, not 18:23.** `computed`: `datetime.now(ZoneInfo('Asia/Kolkata'))`
  at the start of the run read `2026-09-01 18:52:29`. That is **7 minutes AFTER the 18:45 anchor**,
  so tonight's fetch measures the file at a moment the anchor has already passed. **It cannot
  answer the question this watch exists to ask** — "is the file complete 20+ minutes EARLY?" — and
  is therefore **NOT counted as a clean session**. ⚠️ **Why it fired late is now `computed`, not `assumed` — and it was neither
  scheduler drift nor a late morning boot.** The HOST WAS DOWN. Windows System log: `winlogon`
  event 1074 initiated power-off at **12:42:12 IST**, event 6006 at 12:42:17; boot at 18:45:33,
  then event 41 + 6008 (*“the previous system shutdown at 6:45:33 PM was unexpected”*) — a
  power flicker — and the successful boot at **18:47:43**. All 11 containers started 18:48:59.
  The 18:15 cron therefore never fired at all; the run happened when the box and Claude came back.
  **So this is not a probe defect and not scheduler drift — it is an environmental outage, and
  the correct disposition is exactly the one taken here: not countable.** Same conclusion, but the
  cause is now measured, so a future session should not re-derive it — see the outage register
  and `docs/signal-analysis/2026-09-01-session-findings.md`.
- `computed` file probe anyway, for the record: HTTP 200, 396,242 bytes, **3,495** data rows,
  EQ **2,646** · SM **374** · BE **233**, sole distinct `DATE1` = `01-Sep-2026` (3,495 of 3,495),
  head `1018GS2026`, tail `ZYDUSWELL` with a full 15-field line. Inside the 3,296–3,506 bar.
  Command: `curl -sS -o <tmp> -w 'HTTP=%{http_code} BYTES=%{size_download}'` with the
  `NseHttpClient` UA + `Referer: https://www.nseindia.com/` against
  `https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_01092026.csv`.
- ✅ `computed` cross-check, and it is exact: `marketdata.nse_eod_bhavcopy` stores **3,495** rows for
  `trade_date = 2026-09-01`, `fetched_at` 18:49:28–18:49:29 IST — **identical to the probe count**.
  The file the probe read is the file the anchor consumed. Confirms the probe is sound; it does not
  make it early.
- ⚠️ `computed` from `marketdata.ingest_runs`: tonight's anchor run started **18:49:21**, not 18:45
  (`SUCCESS`, `rows_written=8500`, 37.1 s). The 08-31 run started 18:44:58, so this is a ~4-minute
  slip on the anchor itself, same night the scheduled task slipped ~29 minutes. **Not investigated
  here** (this task is read-only and the question is the anchor's EARLINESS, not its jitter), but
  worth naming: if the 18:45 job can start at 18:49, the real margin to a ~19:00 shutdown is
  smaller than the cron string suggests — which strengthens, not weakens, the case for moving the
  anchor earlier once the evidence bar is met.
- `computed` earlier same-day run: **07:50:48**, `SUCCESS`, `rows_written=0` — the morning boot
  catch-up correctly writing nothing for an unclosed session.
- ⚠️ **Stale tally corrected:** the "Tally unchanged at 3 of ≥5" line in the 08-28 corroboration
  section above predates Session 5 and is superseded. The authoritative tally is the one under the
  sessions table.
- **Tally after tonight: 4 clean cited sessions (08-24, 08-27, 08-28, 08-31) of ≥5 — UNCHANGED,
  because tonight's probe was post-anchor and does not qualify. VERDICT UNCHANGED — do NOT move the
  anchor.** One more clean, ON-TIME session is still needed. **If the scheduled task keeps firing
  near 18:52 it can never produce one** — the fire time is now the blocker, not NSE. Next run should
  check its own clock FIRST and say so loudly if it is again past 18:45.


## Session 6 notes (2026-09-02)

- Probe fired **18:24 IST**, 21 minutes ahead of the 18:45 anchor. Same hour as every prior reading,
  so what is proven remains *complete by 18:24*, never 18:15.
- EQ 2,646 is 7 above the old 8-session band ceiling (2,639) and matches 09-01's stored EQ exactly
  (`computed`: `nse_eod_bhavcopy` 09-01 = 3,495 rows / EQ 2,646). Band drifts with listings; the
  truncation test is the alphabetical tail, and this file ends at `ZYDUSWELL` with all 15 fields.
- Series sum equals the data-row count exactly (2646 EQ + 369 SM + 234 BE + 94 ST + 45 GS + 44 GB +
  28 BZ + 13 IV + 6 RR + 1 SZ + 1 N1 + 1 E1 = 3,482), so no row was dropped by the parse.
- **No same-day cross-check is possible from this run** — the 18:45 ingest had not happened when the
  probe fired. A later interactive session can confirm `nse_eod_bhavcopy` stores 3,482 / EQ 2,646
  for `trade_date = 2026-09-02`; a mismatch would flip session 6 to NO and un-meet the bar.
- ⚠️ **This run appended only. Nothing was ingested, restarted, deployed or re-crontabbed, and the
  edit is left UNCOMMITTED** for the next interactive session to carry.
