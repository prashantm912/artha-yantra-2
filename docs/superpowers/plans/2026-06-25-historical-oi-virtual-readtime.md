# Historical OI for backtests — virtual read-time derivation (not snapshot backfill)

**Status:** DESIGN / proposed · **Date:** 2026-06-25 · **Author:** Claude (workflow: 8-agent recon → 3 designs → 3 adversarial lenses → synthesis + completeness critic)

## Problem

OI-using strategy backtests (and the OI-attribution surface shipped in #201) return `NO_DATA`
for any session before ~2026-06-15, because the confluence engine reads
`marketdata.options_chain_snapshots` (per-strike chain cross-section: oi, oi_change, iv,
greeks) — which is only **live-captured forward** since 2026-06-15 (+ a 4-day `OiBackfillService`
run). Meanwhile we DO have a full year of per-contract option **OI** — but in a *different* table,
`marketdata.candles` (72.7M rows, 2025-03-07→2026-06-24, 10,541 expired NIFTY option contracts,
every row has `oi`), loaded by the Upstox expired-instruments backfill.

The original ask: *"when we backfill old candle data, also write the OI snapshot rows."*

## Verdict: do NOT materialize snapshots — derive OI at read time from candles

All three adversarial review lenses (data-engineering, live-safety, backtest-correctness)
independently chose **virtual read-time derivation** over both materializing approaches. The
reason is decisive and specific to this table.

### Why materializing is the wrong move (answers "will the backfill take too long / blow up?")

Writing `options_chain_snapshots` for the year is a **~1.12 billion-row** write (≈15.4× the
72.7M candle rows already loaded), ~30–50 GB compressed, ~6.5 h **per expiry** (≈79 snapshots/day
× 510 banded strikes × 2 types × ~480 days × 6 indices × ~4 expiries). Worse than the volume: it
writes into the **exact table family + memory profile that OOM-crashed the live 1 GB Timescale
twice** (the prior incident). Both materializing variants — inline (extend the candle ingester) and
decoupled (a post-hoc job from candles) — share the single worst hazard: **write-amplification into
old, already-compressed 1-day chunks** (decompress → modify → recompress per chunk). That's the
failure class we already proved fatal.

And it buys nothing: the data we'd materialize is **fidelity-identical** to what we can compute for
free from candles (same `oi`, same null `iv`/`greeks`/`bid`/`ask`), because the source of truth for
historical OI is the candle `oi` column either way. Materialization only earns its cost if some
consumer must read **raw per-strike snapshot rows directly without the candle join** — and none does
today (`SnapshotPremiumReader` is built-but-unwired; OI-attribution reads *aggregated* factors over
HTTP).

### Why virtual is safe and free

- **Zero new rows, zero storage, zero backfill time, no migration.** Converts the 72.7M candle set
  into OI factors at query time, completely sidestepping the OOM (a *write*-amplification incident;
  this path writes nothing).
- **The join bridge already exists + is indexed:** `expired_contracts` maps
  `(exchange, tradingsymbol) → (strike, instrument_type, expiry, underlying)`.
- **The only OI factors any consumer actually reads are losslessly derivable from candles:**
  active-strike OI sentiment = `100·(Σpe ΔOI − Σce ΔOI) / Σ(ceOi+peOi)` — needs `oi` + `oi_change`
  only, no IV; the active-strike OI series needs `oi` only.

## Answers to the explicit questions

### 1. Will it affect live test / live trades?

- **Live trades: zero.** No shared write path; the frozen premium-replay golden path reads candles
  via `CandlePremiumReader`, **never** snapshots — so this cannot drift `GoldenSignalsJson` /
  `BacktestParityTest`. The only output that changes is OI-attribution (`NO_DATA` → real factors),
  which is the intended effect, not a regression.
- **Live capture: near-zero, *conditional on one gate*.** `ConnectingDotsService` runs in market-data
  in LIVE mode and serves the live oipulse pages on a 5-min refresh. The virtual reader adds an
  `EXISTS` probe (and a candles-JOIN fallback) to the snapshot query. **This must be gated to
  `mode=history` only** (the param already exists in `MarketDataClient.connectingDots`) so the LIVE
  refresh never enters the fallback. With that gate, live is untouched; without it, every live
  refresh pays the probe.

### 2. Backfill time / memory?

- **None** — the virtual approach runs no job, writes no rows, adds no storage, consumes no Upstox
  quota, and cannot OOM. The expensive asset (per-contract OI) is already on disk in `candles`.
- The materialize alternative would be ~1.12B rows / 30–50 GB / ~6.5 h-per-expiry into the
  twice-OOM'd table — which is exactly why it's rejected.

### 3. IV / greeks?

- **Full-chain greeks: deferred (null).** Verified safe: `ConnectingDotsService.ivFactor` returns
  NEUTRAL when `iv==null`, and `atmIvByBucket` filters nulls — so a missing IV degrades **1 of 11**
  factors gracefully, never crashes.
- **BUT do not blanket-defer IV.** Leaving IV null makes every historical OI-confluence backtest run
  on a structurally-different **10-factor** trend vs live's **11-factor** trend — train/live skew
  that can invalidate a scalping edge. `IvSolver.solve()` is deterministic, parity-pinned, ~1–3 µs/call.
  **Recommendation:** lazily recompute IV in the virtual reader for the **ATM-band strikes only**
  (the only strikes `atmIvByBucket` reads) — option premium = candle `close`, spot from the index
  candle, rate pinned from config. Trivial cost (O(ATM strikes × buckets)); keeps the 11th factor
  faithful. Full-chain greeks stay deferred.

## The five corrections baked in (from the completeness critic)

1. **`oi_change` ≠ 1-minute lag.** Live `oi_change` is a diff between consecutive **5-min** chain-pass
   snapshots; a `lag()` over raw 1-min candle OI is a 1-min delta (~5× smaller) → it systematically
   compresses the sentiment % toward zero. The virtual reader **must bucket to the consumer's
   `OiInterval` (5m default)** with `last(oi, ts)` per `time_bucket(...,'Asia/Kolkata')`, then `lag()`
   over the **bucketed** series, and null the first bucket of each session — matching
   `OptionsSnapshotReader.strikeSeries` exactly.
2. **A 4th consumer exists: `IvDailySummaryRepository` / `iv_daily_summary`,** which reads `iv`,
   `forward_price`, `risk_free_rate` (solver inputs candles can't supply). The virtual reader leaves
   the IV-daily *history* rollup empty. **Decision needed:** is historical IV-daily in scope? (If yes,
   the ATM-band IV recompute from #3 partially covers it; the full forward/rate persistence does not.)
3. **IV-defer is a cost choice, not a limitation** → recompute ATM-band IV (above).
4. **Live read-path collision** → gate fallback to `mode=history` (above), verify probe latency on a
   covered LIVE session.
5. **Pin the scale number + consider the narrow middle path.** Before calling materialize "rejected,"
   confirm row count via `SELECT count(DISTINCT …) FROM expired_contracts × 79`. And if read-path probe
   latency ever proves non-negligible, the cheapest *materialized* fallback is a **narrow fresh table**
   (`ts, underlying, expiry, strike, type, oi, oi_change, spot` for ATM±N only) — a NEW table (new
   migration), never an `ALTER` on the compressed `options_chain_snapshots` (which V023 shows is the
   real decompress-dance hazard). Virtual wins unless probe latency disproves it.

## Phased plan

| Phase | Work | Verify |
|---|---|---|
| **P0** | Add a candles→OI-factor derivation: bucketed `last(oi)` per (expiry,strike,type) at `OiInterval`, `oi_change` = lag over buckets (first-bucket null), JOIN `expired_contracts` for strike/type. Spot from index 1m, fallback 1d close. | Bucket-OHLC **and** `oi_change` parity vs real snapshots on the 06-15→06-24 LIVE overlap window (not just OHLC). |
| **P1** | Wire the fallback into `OptionsSnapshotReader.series` / `ActiveStrikeService` / ConnectingDots, **gated to `mode=history`**; coverage gate (skip strikes whose `expired_contracts` row is NONE/PARTIAL) + a "derived" provenance tag. ATM-band IV lazy recompute via `IvSolver`. | Unit + RTL green; probe latency sub-ms on a covered LIVE session; IV factor non-neutral on history. |
| **P2** | OI-attribution + oipulse OI pages on full history; re-run the June + an April backtest; confirm `NO_DATA` → real factors. | OI-attribution buckets populate for April; golden/parity tests **byte-identical** (premium path untouched). |

## Migrations

- **None** for pure virtual (`oi_change` derived in SQL).
- Optional: a covering index for the empty-probe `EXISTS` if profiling shows it; and *only if* the
  narrow-table fallback (correction #5) is chosen, one new lineage table (never an ALTER on the
  compressed snapshot hypertable).

## Test plan

- Bucketed-OHLC parity (candles-derived vs snapshot) on the LIVE-overlap window.
- **`oi_change` parity** on the same window (the load-bearing one — guards the sentiment factor).
- Active-strike sentiment % derived vs captured.
- Coverage gate drops NONE/PARTIAL contracts with no silent strike loss.
- Golden/`BacktestParityTest` **unchanged** (premium replay reads candles, not snapshots).
- Discontinuity test: a backtest straddling the 06-15 live cutover sees a continuous factor series.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Empty-probe taxes the live 5-min refresh | Gate fallback to `mode=history`; keep probe a cheap indexed `EXISTS`. |
| Spot null where index 1m is sparse (history) | Coarse 1d-close fallback for spot (OI sentiment needs no spot; only ATM-pick + IV degrade). |
| Incomplete `expired_contracts` coverage silently drops strikes | Coverage gate + a "partial history" badge; never silently skew active-strike selection. |
| `oi_change` semantics drift from live | Bucket to `OiInterval`, null first bucket, parity-test on overlap. |
| 10-vs-11-factor train/live skew | Recompute ATM-band IV at read time (keep factor 11 live). |

## Open questions for the owner

1. **IV-daily history (`iv_daily_summary`) in scope?** If yes, we need ATM-band IV recompute at minimum
   (forward/rate persistence is out of reach from candles).
2. **Show a "derived / partial-history" badge** on OI pages + attribution, or fill silently?
3. **Is a 10-factor historical trend acceptable** if we *don't* recompute IV, or do we want the ATM-IV
   recompute (recommended) to keep all 11?
4. **Scope:** NIFTY only first, or NIFTY + SENSEX together?
5. **Pure virtual now, or pre-decide the narrow-table fallback** if probe latency disappoints?
