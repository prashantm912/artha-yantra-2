# Session findings — 2026-08-10 (data date)

Analysis date: 2026-08-10 (scheduled post-market agent, ran ~18:45–19:10 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **0**, eval-outcome buckets **0**, signals **0**, paper positions
opened/closed **0/0**, shadow positions **0**, `futures_oi_snapshots` **0**,
`options_chain_snapshots` **0**.

**⚠️ THIS WAS NOT A QUIET SESSION — IT WAS A FULL-SESSION PLATFORM OUTAGE.** 2026-08-10 is a
Monday, not an NSE holiday (no August rows in `nse-trading-holidays.csv`), and the market traded
normally: today's EOD bhavcopy landed with **3,361 rows dated 2026-08-10**, and the post-boot
Kite REST backfill retrieved a **complete 375-minute session** for `NIFTY26AUGFUT`. The stack was
simply down for all of it.

---

## 0 The outage — evidence and timeline

| time (IST) | event | evidence |
|---|---|---|
| 07:54 | morning pre-open jobs running normally | `ingest_runs`: NSE_FII_DERIVATIVE SUCCESS 07:54:25 |
| 08:29:59 | strategy-signal's **last log line** — the scheduled notifier-health canary ("0/0 attempts failed") | `docker logs` (retained across restart) |
| 08:30:14 | INSTRUMENT_SYNC SUCCESS (60,642 rows) | `ingest_runs` |
| 08:30:17 | market-data's **last log line** — the option ATM pin pass (`resolved=2/2, pinned=44`) | `docker logs` |
| 08:30 → 18:47 | **HOST DOWN. Zero log lines on any service; zero DB writes** | log gap on both services; every session table empty |
| 18:47:34 | entire stack starts together (`restarts=2` on the compose restart policy — host-boot shape, containers NOT recreated, logs intact) | `docker inspect` StartedAt on all containers within ~40 s |
| 18:47:45–18:50:47 | boot catch-up recovers the EOD lane (§2) | `ingest_runs`, `canary_runs` |
| 18:48:14 | Kite session validates — the MORNING token was still live (login had been done pre-08:30) | `marketdata.kite_session.last_validated_at` |
| 18:49:07 | engine reload healthy: **38 loaded / 0 unresolved / 0 errors** (install `t` then reconcile `f` — the normal pair) | `strategy.engine_reloads` 180/181 |

The blackout was **total and symmetric**: this is not the eval-stall class (07-07/07-10), not
chart-gate silence (§4.3's INCONCLUSIVE class), not a feed outage with the engine alive (08-07's
Kite blips). Both services' logs stop within 18 seconds of each other at 08:30 IST and every
session-scoped table — rejections, eval buckets, signals, paper events, shadow, OI snapshots,
chain snapshots, 1m tick-agg candles — has zero rows for the session window. Nothing in-stack
alarmed because the alarms were down too (the known third-layer gap: an in-stack canary cannot
catch a down stack; the 08:45 INGEST_COVERAGE canary itself only ran at 18:47 via BOOT_CATCHUP).

Precedent: 2026-07-08/07-09 (rollup row "OUTAGE — zero rejection rows both days"). This is the
third full-session outage of the series, and the first since the shadow book, the G16/G12
probes, CAS, and the freeze telemetry existed — all of which simply have no rows today.

### 0.1 The two forensic artifacts a naive read would misinterpret

- **`NIFTY26AUGFUT` shows 375/375 1m bars for the session — none captured live.** 374 are
  `source='KITE'` with `fetched_at` 18:47:45 (the cache-first REST backfill on boot), plus one
  `TICK_AGG` bar at 15:29 written 18:47:46. A capture-liveness read that only counts bars would
  call this session healthy — provenance (`source`, `fetched_at`) is what tells the truth.
- **Dozens of instruments carry exactly ONE `TICK_AGG` 1m bar, bucket 15:29, written 18:47:45.**
  That is the post-boot WS connect: Kite sends a snapshot tick on subscribe after hours, and the
  tick-agg buckets it at the instrument's last-trade minute. One bar per symbol at the session's
  final minute, all stamped hours after the close — a connect artifact, not capture.

## 1 What was lost vs what recovered

**LOST (unrecoverable as captured data):**
- `options_chain_snapshots` + `futures_oi_snapshots` — the whole session. Forward OI capture
  (live since 2026-06-15) has a one-day hole; per the derived-history doctrine the day's OI can
  only ever be candle-derived (muted: Dow/IV → NEUTRAL) once the contracts expire.
- The entire entry-gate evidence base for the day: rejections, dot support rates, composite
  distribution, shadow-book rows, §3.5 sole-blocker sets, T23/G9 canary telemetry, G16/G12
  probe reads — all structurally absent, not zero-valued.
- **The §2.2 strike-pick discriminator for Mon 08-10 (NIFTY cluster predicted) is UNOBSERVED**
  — the chain-proximity hypothesis gets no data point today. Next discriminators: Tue 08-11
  (NSE weekly day-of, saturation expected), Wed 08-12 (clean expected).

**RECOVERED (boot catch-up, all by 18:51 IST — the catch-up doctrine worked):**
- Today's **BHAVCOPY** (ingest SUCCESS 18:48:38; 3,361 rows dated 08-10 present), NSE_FII_DII,
  NSE_PARTICIPANT_OI, NSE_FII_DERIVATIVE.
- **MANAS_SCREEN** (2,269 rows, 18:49:23) + **MINERVINI_SCREEN** (1,783 rows, 18:50:12) +
  MINERVINI_PLANE_DIVERGENCE canary (18:50:47) — **the swing lane is fully fed for today**
  despite the intraday blackout (it is EOD-data-driven by design).
- `NIFTY26AUGFUT` 1m series via REST (usable for ground-truth history, with the provenance
  caveat above).

**Position risk during the blackout: none on the intraday book.** Zero scalper positions were
open (the book has been flat since 08-06's close), so nothing sat unmanaged past a dead
15:12 square-off. The 18 OPEN swing positions (6 manas-arora + 12 minervini) are EOD-managed;
the evening batch runs tonight on a live stack. T10 moves **17 → 18**: manas-arora opened
SKYGOLD Friday 20:05 IST (after the 08-07 file was written).

## 2 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **10 REVIEW lines — the identical standing
  false-positive set as 08-06/08-07** (5×[A] snapshot/self-referential chips, 5×[B] keyword
  reference-not-claim). No ledger edits required; **ledger consistent in substance**.
- `tools/published-config-drift.py`: **69 published — 69 matched (67 clean, 2 STALE-PUBLISH),
  0 DB-only, 0 YAML-only.** Same 2 as 08-03…08-07: `minervini-cheat-3c` /
  `minervini-primary-base` (1.0.2 drafts, name+description only). Republish proposal carried —
  **not republished by this run**.

## 3 §3.29 unexercised-path audit + §3.30 freeze telemetry

Zero paper closes today — fired vocabulary since 07-01 **unchanged** (TRAILING_STOP 13,
STRUCTURAL_STOP 6, STOP_LOSS 5, TIME_STOP 5, MANUAL 2). Armed set re-queried, identical to
08-07: never-fired stands — `take_profit premium_pct` (36), `signal_exit` (38), `square_off`
(2), `stop_loss percent` (4), tag `oi-confluence-exit` (8); INDETERMINATE pair
(`trailing_stop atr_multiple` 2, `stop_loss atr_multiple` 2) stands. No new reachability
evidence (outage day, not a regime read).

§3.30: zero entries, **0/5 sub-accounts frozen** — no-data. §3.34 heat-gate: no funded fire,
grep proves nothing (and the engine was down anyway).

## 4 Session regime (§3.25/§3.33) — NO STAMP; front-future proxy only

The `NIFTY 50` index has **no intraday data** for today (one snapshot-tick 1m bar at 15:29) and
no 1d bar at analysis time, so the doctrine metric (index daily o→c efficiency) is
**unstampable**, and the CAS continuous-vs-official split is unmeasurable. Proxy from the
REST-backfilled `NIFTY26AUGFUT` series: open 24,660.00 → close 24,662.00 (**+0.01%**), range
98.80 pts (**0.40%**), efficiency **0.020 → chop-shaped** — recorded as PROXY, never as a G15
stamp (G11's chop-day ledger must not count it).

## 5 New data points / anomalies

### 5.1 → README §3.35 (new dimension): discriminate NO-SESSION from NO-CAPTURE

Zero rejections is triply ambiguous (holiday / chart-gate silence / outage). Today's
distinguishing chain, now recorded as the standing method: calendar says trading day →
bhavcopy dated today proves the market traded → the post-hoc REST backfill proves a full
session existed → log-gap + empty session tables on BOTH services prove the stack (not the
engine) was the absence. Classify such a day **OUTAGE / NO-DATA** in the rollup — never
"quiet", never a G15 regime row, and never evidence in any tuning ledger row.

### 5.2 Ops note for the owner (known gap, not a new proposal)

The blackout was invisible until the host came back: every detector lives in the stack that
went down. The `batch-liveness` design already names this third layer (an off-stack/external
heartbeat) as the unbuilt piece — today is the first full-session demonstration since that
design was written. Left as an owner-priority note; no in-stack fix can close it.

## 6 Tuning candidates

**No-data day: every row carries forward UNCHANGED from 08-07.** Nothing here is applied.
Ledger §0 group G is the authoritative status.

| # | knob | status | note |
|---|---|---|---|
| NEW-1 (08-05) | paper heat-cap margin call timeout | **PROPOSED — carried** | no evidence possible today |
| T30 | `breadth` dot `>32` | **OPEN** | G16 probe had no rows to read |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | — |
| T28 | `atmIv` frozen daily stamp | **OPEN** | — |
| T3 | `iv_pair` | **OPEN (owner)** | — |
| T23 | partial-bucket tolerance | **OPEN — watch** | opening-bucket class: no canary ran today |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | — |
| T7 | composite threshold | **REJECTED — carried** | — |
| watch | `strike-pick` chain-proximity | **WATCH** | 08-10 discriminator LOST; next: 08-11 day-of, 08-12 clean-Wednesday control |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | moot today (nothing ran) |
| NEW (08-03) | minervini republish | **PROPOSED — carried** | §2 (6th session) |
| T10 | stale OPEN paper positions | **OWNER — chronic** | **18 OPEN (+1: SKYGOLD opened 08-07 20:05)** |
| T8/T26 | latency | OPEN (data) | n=0 |
| T2 | `iv_rank` | carried, not open | unmeasurable today |
| T29 | scalper `time_stop` | **CLOSED** | G11 chop-observation count unchanged (no stamp today) |

## 7 Honesty caveats

- Every "zero" in this file is an ABSENCE-of-process reading, not a measurement of the market
  or the gate. The one measured market fact is the proxy OHLC off a post-hoc REST backfill.
- The outage window's bounds (08:30:17 → 18:47:34 IST) are from container logs and Docker
  state; the host-level cause (shutdown vs sleep vs crash) is not observable from inside the
  stack and is left to the owner.
- This run was **read-only against the live stack**: SELECTs, `docker logs`, `docker inspect`
  only. No restart, deploy, write, or config change; nothing republished. Docs edits in this
  PR: this file + README §3.35 + rollup rows.
