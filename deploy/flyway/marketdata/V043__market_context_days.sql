-- Intelligence-layer INT increment I1 (design 2026-07-10 §6.6) — the ONE new persisted table the
-- decision-support layer adds on the market-data side. Everything else in the layer stays
-- compute-on-read (§6.6 / P1-§3.3 line honored); this table exists so the daily EOD day-context is
-- (a) a stored prior-day baseline the digests can read instead of re-folding yesterday, (b) queryable
-- context history for the dossier + future evolution-engine regime work, and (c) the replay fixture for
-- the §10 determinism tests.
--
-- Writer: MarketContextEodJob (the day-context EOD job), which runs after the 19:00/19:30 NSE ingests
-- and records a row in ingest_runs under source MARKET_CONTEXT_DAY (the batch-source trust ledger).
-- Exactly one row per IST SESSION day (trade_date PK, accumulating upsert = idempotent re-run).
--
-- Timestamp doctrine (CLAUDE.md): in-container now()/::date is UTC, never IST — so trade_date is the
-- IST session date computed in Java from the day's captured snapshots, never a ::date of a UTC now().
-- day_context is the full serialized DayContext response; the scalar baseline columns duplicate that
-- day's primary-index EOD headline metrics so a "vs prior day" read is a single scalar lookup.
--
-- The marketdata schema is owned by `artha` (admin V001), so no explicit GRANT is needed here
-- (mirrors V030 backfill_jobs / V040 ingest_runs / V042 admin_audit).
CREATE TABLE market_context_days (
    trade_date       DATE         PRIMARY KEY,          -- the IST session day (never a UTC ::date)
    options_name     TEXT,                              -- the primary options root the baselines are for (e.g. NIFTY)
    expiry           DATE,                              -- the primary index expiry the options baselines used
    pcr_eod          NUMERIC,                           -- primary-index PCR at the day's last captured bucket
    max_pain_eod     NUMERIC,                           -- primary-index max-pain at the day's last captured bucket
    atm_straddle_eod NUMERIC,                           -- primary-index ATM straddle premium at EOD
    atm_iv_eod       NUMERIC,                           -- primary-index ATM/30d IV at EOD (from the 16:00 IV rollup)
    day_context      JSONB        NOT NULL,             -- the full serialized DayContext (the replay fixture)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
