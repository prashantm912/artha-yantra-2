# AYDB — cagg storage relief via RETENTION, not compression (design pass)

> **SUPERSEDED 2026-07-19 — NOT the path taken.** Adversarial review measured the real reclaim at
> **~6 GB, not ~17 GB** (the caggs are recent-skewed: `candles_5m`'s 15 GB is mostly recent data
> retention can't shed). With the owner's real **100 GB budget** and disk showing 277 GB host-free,
> retention was too low-yield to justify an irreversible policy. The owner chose the durable fix
> instead: **upgrade TimescaleDB 2.17.2 → 2.18.2-pg17 (done) → compress the caggs (V049)** — which
> squeezes the fat recent body (~10x, ~18 GB reclaim, no data dropped), impossible on 2.17.2 but safe
> from 2.18.1. See `V049__compress_candle_caggs.sql` + the upgrade runbook in the memory topic. This
> doc is kept for the read-path/reader analysis (auto-heal refuted, context-reader census), which
> remains accurate and informed the compression-window choice.

**Status:** SUPERSEDED (see banner). Original: design / owner-decision (windows). Superseded the AYDB-01 compression approach
([#936](https://github.com/prashantm912/artha-yantra-2/pull/936)) as the *no-harm* path on our
current TimescaleDB. Build is a small `add_retention_policy` migration once the windows are signed off.

## Verdict

**Reclaim the storage with a retention policy on the three fat fine-grain caggs
(`candles_5m` / `candles_15m` / `candles_1h`), keeping generous windows — not V049 compression.**
It sidesteps the entire TimescaleDB-2.17.2 hazard that blocks #936, needs no version upgrade and no
hot-path code, reclaims **~18 GB of the 46 GB DB (drops it to ~55 % of the 50 GB trigger)**, and the
dropped data is safely rebuildable from the fully-retained 1 m base in bounded chunks. The only cost is
fine-grain intraday history older than the window, which the live engine and 3 m scalper backtests never
read.

## The problem (measured live 2026-07-19, read-only)

- **DB = 46 GB, 92 % of the 50 GB review trigger.**
- The bulk is three **uncompressed** caggs: `candles_5m` **15 GB**, `candles_15m` **5.1 GB**,
  `candles_1h` **1.4 GB** — together **~21.5 GB, ~47 % of the whole DB**. (`candles_1d` 226 MB,
  `candles_1w` 38 MB are negligible.)
- The base `candles` hypertable (**22 GB**) is **already compressed** (policy id 1, `compress_after
  7 days`) and holds native **1 m (239 M rows, 2015-02→2026-07) + 1 d (5.4 M rows, 2006→2026)** — no
  retention policy, kept in full.
- **#936 (V049 compression on the caggs) is unsafe on 2.17.2**: pre-2.18.1 cannot *refresh* a compressed
  cagg region, and the manual historical refresh paths (`CandleRepository.refreshAll`,
  `CorporateActionJob` 4 400-day CA recovery, `CandleQueryService` gap backfills) reach back years, so a
  corporate-action re-adjust or gap backfill would error after compression runs.

## Why retention is no-harm where compression isn't

| | Compression (#936, V049) | **Retention (this doc)** |
|---|---|---|
| 2.17.2 manual-refresh conflict | **breaks** — refresh over a compressed region errors | **none** — retention *drops* old chunks; a later refresh over that window just re-creates them |
| Code in the OOM-prone CA path | yes (a decompress→refresh→recompress wrapper) | **none** — one `add_retention_policy` per cagg |
| Timescale upgrade required | to ≥2.18.1 for the safe version | **no** |
| Base `candles` (deep-history backtest source) | untouched | untouched |
| Reversible | drop policy (chunks stay compressed) | drop policy (dropped chunks re-materialize on demand) |

## Load-bearing findings (each drove the window sizing)

1. **5m/15m/1h reads are cagg-*direct* — no 1 m rollup fallback.** `CandleQueryService.java:105-109`:
   only `3m` routes to `repository.rangeRolledFromOneMinute(...)`; every other interval reads
   `candles_<iv>` directly. So a window that retention has dropped reads **empty**, it does not silently
   roll up from 1 m. → windows must cover every realistic fine-grain read.
2. **Who reads the fine-grain caggs — MEASURED across the 69 published versions
   (`config->'timeframes'`):**
   - **Primary timeframe:** `3m` × 63, `1d` × 6. **Zero 5m/15m/1h primaries.** `3m` rolls from the 1 m
     base (`rangeRolledFromOneMinute`), never a cagg → the live engine + all scalper-primary reads are
     cagg-free.
   - **BUT `additional` (context) timeframes DO hit these caggs:** `1h` × **63**, `5m` × **6**,
     `15m` × **6**, `1d` × 6. So a 3 m scalper *backtest* reads `candles_1h` (and, for 6 of them, `5m`/
     `15m`) as its higher-timeframe confluence series **over the backtest window** — this is the reader
     I first missed. The live engine reads the same context but only `warmupDays` back
     (`LiveSeriesStore.java:52,111`, bounded/recent → safe at any window). **The exposure is
     deep-history backtests of those strategies.**
   - **Charts / intraday overlays:** also read old 5m/15m/1h directly.
3. **The read path RE-MATERIALIZES on read — this is the linchpin.** `CandleQueryService.read()` calls
   `ensureCoverage(...)` for the base interval, whose contract is "coverage check + gap fetch … **refreshing
   derived aggregates**" (`CandleQueryService.java:~90,115`), and the refresh is the chunked ≤92-day
   `refreshWindows` (`CandleRepository.java:33,308-315`) — never the unbounded
   `CALL('candles_5m', 2014→2026)` that SIGKILLed the box 3× (`CandleRepository.java:29`). **If a read of
   a retention-dropped window re-materializes it via this path, retention is fully safe for backtests too**
   (the backtest auto-warm `MarketDataClient.warm(...)` GETs the context series "again before replay",
   `MarketDataClient.java:14-19`, which would trigger exactly this). **The one thing inspection did NOT
   settle:** whether `ensureCoverage` refreshes the derived caggs for a window where the **1 m base has no
   gap** (base present, only the cagg dropped) — if the refresh is gated on a base-gap fetch, an old window
   with intact 1 m would *not* auto-heal. **This is the single pre-build gate (a 20-min mock-stack test:
   drop a cagg chunk, read it, see if it repopulates).**

## Recommended windows (owner data-policy — these are my defaults)

Retention `drop_after` must vastly exceed each cagg's *refresh* `start_offset` (V004/V029: 5m=1d, 15m=2d,
1h=7d) — no conflict at these scales. Windows are sized for the two real readers: (a) old intraday charts,
(b) **deep-history backtests of the 6/6/63 strategies that use 5m/15m/1h as context**. Because scalper
backtests are only meaningful recently (live OI capture began 2026-06-15; derived-history OI is muted, so
deep scalper backtests are low-value by design — see CLAUDE.md), a **2-year floor covers realistic use**
even if the read-path does *not* auto-heal.

| cagg | size | recommended `drop_after` | reclaim (approx) | rationale |
|---|---|---|---|---|
| `candles_5m` | 15 GB | **2 years** | ~12 GB | 6 strategies read it as context; 2 y covers every realistic scalper backtest |
| `candles_15m` | 5.1 GB | **2 years** | ~4 GB | 6 strategies as context |
| `candles_1h` | 1.4 GB | **3 years** | ~0.9 GB | 63 strategies read it as context — the widest reader; keep it longest (cheap anyway) |
| `candles_1d` | 226 MB | **keep** (no policy) | — | 6 primary + 6 context; daily deep-history valued (screener, swing) |
| `candles_1w` | 38 MB | **keep** | — | negligible |

**Net reclaim ≈ 17 GB → DB ~29 GB (~58 % of trigger).** If the pre-build re-materialize test (below)
**confirms** a read auto-heals a dropped window, windows can safely tighten (5m=1 y → ~13.5 GB reclaim)
because any deep backtest self-warms its context caggs on submission. **If it does NOT auto-heal, keep the
2 y/2 y/3 y set** (or wider) so no realistic backtest hits a dropped window.

## The migration (once windows are signed off)

New `deploy/flyway/marketdata/V0xx__retain_fine_grain_caggs.sql` (+ `.conf` non-transactional, the
cagg-migration convention), e.g.:

```sql
SELECT public.add_retention_policy('candles_5m',  INTERVAL '1 year',  if_not_exists => true);
SELECT public.add_retention_policy('candles_15m', INTERVAL '2 years', if_not_exists => true);
SELECT public.add_retention_policy('candles_1h',  INTERVAL '3 years', if_not_exists => true);
```

- On a **fresh DB (CI / mock)** the caggs have no old chunks → the policy is a no-op registration
  (deterministic, safe in the market-data IT shard).
- On the **live DB** the first policy runs drop the aged chunks incrementally — pure *reduction*, the
  opposite of the compression-then-inflate footprint. Still: deploy attended, then DB-probe the reclaim.

## Harm analysis (honest)

- **What is lost:** fine-grain intraday bars older than the window. **NOT read by** the live engine
  (recent `warmupDays` only), scalper *primary* series (3 m, rolls from 1 m), or swing backtests
  (daily/1 d, kept). **IS read by** old intraday charts + **deep-history backtests of the 6/6/63 strategies
  that use 5m/15m/1h as *context*** — the 2 y/2 y/3 y windows keep those covered for every realistic window
  (scalper backtests are recent-only by design).
- **What is NOT lost:** the 1 m base (2015→now, 239 M rows) and 1 d base (2006→now) — the authoritative
  deep history — stay in full. Every dropped cagg bar is recomputable from them.
- **Rebuild caveat (must be in the runbook):** re-materialize a needed old window **only** via the
  existing ≤92-day chunked refresh, never an unbounded `refresh_continuous_aggregate` (OOM).

## Verify before build

1. **THE gate — read-path auto-heal (20-min mock-stack test).** On the mock stack: `add_retention_policy`
   a short window on `candles_5m`, force a drop of an old chunk, then GET
   `/api/v1/market/candles?interval=5m` for that old window and check whether it repopulates (via
   `ensureCoverage`→`refreshWindows`). **PASS → retention is fully safe at any window; windows can tighten
   to 5m=1 y. FAIL → keep the 2 y/2 y/3 y windows** (realistic backtests still never hit a dropped window).
   Either outcome ships; the test only sets how aggressive the windows can be.
2. ~~Confirm no 5m/15m/1h *primary* backtest~~ **RESOLVED (measured):** 0 fine-grain primaries across 69
   published (3 m × 63, 1 d × 6). The only fine-grain readers are the *context* series (5m×6/15m×6/1h×63)
   + charts — accounted for in the windows above.
3. **Chart default lookback** — confirm no normal chart view requests >2 y of 5 m by default (would
   re-materialize on every load if #1 passes, or read empty if not). Grep the chart endpoints' `from`
   defaults.
4. **Mock-stack determinism** — the market-data IT shard stays green (policy is a no-op on the fresh cagg);
   add a probe test that the policies register.
5. **Reversibility drill** — `remove_retention_policy` + a bounded refresh restores a window.

## Decision for the owner

- **Approve the approach + windows.** Default: **5m=2 y / 15m=2 y / 1h=3 y (~17 GB reclaim → DB ~58 % of
  trigger)** — safe regardless of the auto-heal test. If you want maximum reclaim, run the 20-min auto-heal
  test first; a PASS lets 5m drop to 1 y (~13.5 GB more headroom).
- Then it's a small reviewed migration (HOLD-tier: changes a live-data retention policy) → deploy attended
  → DB-probe the reclaim.
- **#936 stays open** as the *alternative* for the day you want full fine-grain history kept (compressed):
  that path needs the TimescaleDB ≥2.18.1 upgrade first.

## Bottom line

Retention on `candles_5m`/`15m`/`1h` reclaims ~17 GB with no 2.17.2 hazard, no upgrade, no hot-path code,
and no realistic-use breakage at the 2 y/2 y/3 y windows. It is strictly the lower-risk lever than #936's
compression, which cannot ship safely until TimescaleDB is upgraded.
