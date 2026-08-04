-- The corporate-action rebuild DESTROYED a symbol's history before proving it could be replaced.
-- `CorporateActionJob.submitRemediation` ran `purgeSymbol` FIRST and only then re-fetched ~12 years
-- from Kite, because the re-fetch went through the gap-aware `ensureCoverage` — which fetches only
-- the buckets the cache MISSES, so the purge was what manufactured the gaps that forced a re-fetch.
-- A re-fetch that threw (rate limit, auth, network, OOM) therefore left the symbol GUTTED, and the
-- damage self-sealed: the sweep's own `hasNonBhavcopyDaily` pre-filter reads `source <> 'BHAVCOPY'`,
-- which a purged symbol fails forever once the daily bhavcopy job refills its 1d bars, so the sweep
-- skipped its own victims before it ever reached detection. 45 symbols sat in that state (measured
-- 2026-08-04), each holding only BHAVCOPY 1d bars with zero 1m base rows.
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
-- so a re-fetched page can be re-staged idempotently (ON CONFLICT DO UPDATE) after a retry. Column
-- types and NULLability mirror `candles` exactly (including `fetched_at`, which the swap stamps
-- rather than copies): a staged row that could not be inserted into `candles` must fail HERE, while
-- the live series is still intact, not halfway through the swap that has already deleted it.
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
  PRIMARY KEY (exchange, tradingsymbol, "interval", bucket)
);

-- The marketdata schema is owned by `artha` and V052 established that
-- `ALTER DEFAULT PRIVILEGES FOR ROLE artha IN SCHEMA marketdata GRANT SELECT ... TO ay_backtest`
-- already covers newly created tables, so no explicit GRANT belongs here.
