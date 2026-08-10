-- The corporate-action rebuild DESTROYED a symbol's history before proving it could be replaced.
-- `CorporateActionJob.submitRemediation` ran `purgeSymbol` FIRST and only then re-fetched ~12 years
-- from Kite, because the re-fetch went through the gap-aware `ensureCoverage` — which fetches only
-- the buckets the cache MISSES, so the purge was what manufactured the gaps that forced a re-fetch.
-- A re-fetch that threw (rate limit, auth, network, OOM) therefore left the symbol GUTTED, and the
-- damage self-sealed: the sweep's own `hasNonBhavcopyDaily` pre-filter reads `source <> 'BHAVCOPY'`,
-- which a purged symbol fails forever once the daily bhavcopy job refills its 1d bars, so the sweep
-- skipped its own victims before it ever reached detection.
--
-- ⚠️ NO SYMBOL COUNT IS RECORDED HERE ON PURPOSE, and the reason is worth more than the number.
-- The affected population was measured three times on 2026-08-04 — 45, then 38, then 0 — under one
-- definition (latest event FAILED, no non-BHAVCOPY 1d bar, zero 1m bars). All three were correct
-- when taken: an out-of-band operator restore was refilling these symbols DURING the measurements
-- (91,405 `source='BACKFILL'` rows landed in a single 40-minute window against exactly this symbol
-- set). A migration comment is checksum-locked forever, so freezing any one reading of a
-- concurrently-mutating population would preserve a number that was never stable. The durable fact
-- is the failure CLASS and its definition; the census belongs in the PR and the ledger.
--
-- This table is the staging buffer that lets the order become fetch -> verify -> swap. The whole
-- re-fetch lands here first; only once it is proven to cover what it is about to overwrite does the
-- symbol's live range get deleted and refilled. A failed fetch now leaves `candles` untouched.
--
-- Deliberately a PLAIN table, not a hypertable:
--   * it is a scratch buffer holding one symbol's fetch at a time (the remediation executor is
--     single-threaded), cleared at the start and end of every attempt — chunking, compression and
--     retention policies would all be pure overhead on rows that live for minutes;
--   * it must NOT be a continuous-aggregate source. Staging inside `candles` under a shadow
--     tradingsymbol was the alternative considered and rejected: the five candle caggs would
--     materialise the shadow rows, and the compression policy would compress them, making the
--     cleanup delete far more expensive than the write it undoes.
-- LOGGED, not UNLOGGED: an UNLOGGED buffer is truncated by crash recovery, which would re-open a
-- (narrower) version of the very hole this change closes — the window between the delete and the
-- refill would have no surviving copy of either side.
--
-- The PK mirrors the `candles` PK so the swap is a key-for-key anti-join and DELETE/INSERT pair, and
-- so a re-fetched page can be re-staged idempotently (ON CONFLICT DO UPDATE) after a retry.
--
-- ⚠️ The CHECK constraints below are NOT decoration — they are the load-bearing half of "a staged
-- row that could not be inserted into `candles` must fail HERE, while the live series is still
-- intact". Mirroring only types and NULLability is NOT enough: `candles` also carries
-- `candles_interval_check` (V003) and `candles_source_check` (V020, eight allowed values), so
-- without these an out-of-enum `source` would stage cleanly and then fail at the swap's INSERT —
-- AFTER that window's DELETE had already committed, which is precisely the half-swapped state the
-- staging design exists to prevent. They are copied at their V003/V020 values rather than derived,
-- because a migration cannot follow a constraint that changes later; if `candles_source_check` ever
-- widens again, widen this alongside it in the same migration.
CREATE TABLE IF NOT EXISTS marketdata.candle_rebuild_staging (
  exchange      TEXT           NOT NULL,
  tradingsymbol TEXT           NOT NULL,
  "interval"    TEXT           NOT NULL,
  bucket        TIMESTAMPTZ    NOT NULL,
  open          NUMERIC(18, 4) NOT NULL,
  high          NUMERIC(18, 4) NOT NULL,
  low           NUMERIC(18, 4) NOT NULL,
  close         NUMERIC(18, 4) NOT NULL,
  volume        BIGINT         NOT NULL,
  oi            BIGINT,
  source        TEXT           NOT NULL,
  PRIMARY KEY (exchange, tradingsymbol, "interval", bucket),
  CONSTRAINT candle_rebuild_staging_interval_check
    CHECK ("interval" IN ('1m', '5m', '15m', '1h', '1d')),
  CONSTRAINT candle_rebuild_staging_source_check
    CHECK (source IN ('KITE', 'TICK_AGG', 'MOCK', 'BACKFILL', 'OPENALGO', 'EXPIRYTRACK',
                      'OPENCHART', 'BHAVCOPY'))
);

-- No explicit GRANT belongs here: the marketdata schema is owned by `ay_marketdata` (admin V001)
-- and an `ALTER DEFAULT PRIVILEGES ... GRANT SELECT ... TO ay_backtest` already covers newly
-- created tables. (V052's tail is the authority on this; it also records that V040/V044/V047
-- attribute the schema to `artha`, and that that attribution is wrong. Stating it correctly here
-- rather than repeating the error — those files are applied and checksum-locked, this one is not.)
