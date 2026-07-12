-- App-platform audit 2026-07-10 §3.3 / §6.10 (Phase-3): the daily market-breadth materialization.
-- Breadth (advance/decline + above-MA counts) was single-date-only (folded per request from the EQ
-- bhavcopy), so no A/D history series existed despite bhavcopy being the ONE deep dataset (≥365d).
-- This table stores exactly one row per NSE trading day so the breadth history chart is a zero-read-
-- cost time series (§3.3 "nightly equity_breadth_daily row appended by the bhavcopy job").
--
-- Writer: EquityBreadthEodJob, a daily 19:55 IST cron + a boot one-shot (cron-only — the bhavcopy
-- module already depends on nse, so a reverse event dependency would form a Modulith cycle), recorded
-- in ingest_runs under source EQUITY_BREADTH (the batch-source trust ledger). Accumulating upsert
-- (trade_date PK), so a re-run — or a bhavcopy correction — is idempotent. Every value is folded from nse_eod_bhavcopy
-- 'EQ'-series rows; the above-MA counts use a self-contained SMA window over that same table (no
-- cross-source candle join). above_sma50/200 are counted only among names with ≥50/≥200 sessions of
-- history (the *_universe denominators), so the ratio is honest near the start of bhavcopy coverage.
--
-- Timestamp doctrine (CLAUDE.md): in-container now()/::date is UTC, never IST — trade_date is the NSE
-- session date carried by the bhavcopy row itself, never a ::date of a UTC now().
--
-- The marketdata schema is owned by `artha` (admin V001), so no explicit GRANT is needed here
-- (mirrors V030 backfill_jobs / V040 ingest_runs / V042 admin_audit / V043 market_context_days).
CREATE TABLE equity_breadth_daily (
    trade_date        DATE         PRIMARY KEY,          -- the NSE session day (from the bhavcopy row)
    advances          INTEGER      NOT NULL,             -- EQ names closing above their prior close
    declines          INTEGER      NOT NULL,             -- EQ names closing below their prior close
    unchanged         INTEGER      NOT NULL,             -- EQ names closing flat
    total             INTEGER      NOT NULL,             -- EQ names in the day's bhavcopy
    avg_delivery_pct  NUMERIC,                           -- mean delivery% across the EQ universe
    above_sma50       INTEGER,                           -- EQ names closing above their 50-session SMA
    sma50_universe    INTEGER,                           -- EQ names with >=50 sessions of history (denominator)
    above_sma200      INTEGER,                           -- EQ names closing above their 200-session SMA
    sma200_universe   INTEGER,                           -- EQ names with >=200 sessions of history (denominator)
    computed_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
