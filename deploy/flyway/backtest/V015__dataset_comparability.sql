-- Research-fidelity audit P1-1 / R2 (roadmap #22a/#22b/#30, queue row D4) — dataset comparability.
--
-- The reproducibility triple's data leg is `backtest_runs.data_hash` (V003): keys+interval+window+
-- barCount+max(fetched_at). It is a FRESHNESS proxy, not a content fingerprint — `fetched_at=now()` is
-- re-stamped on a value-IDENTICAL conflict write (CandleRepository), so a harmless tail re-fetch FLIPS
-- data_hash and a longitudinal optimizer sees "the data changed" when nothing did (audit R2). This slice
-- adds the two things §11.3 asks for WITHOUT bitemporalizing the 1m hypertable:
--   1. `content_hash` — a content-stable per-run fingerprint (excludes fetched_at; folds the actual
--      OHLCV/OI of the consumed series). Value-identical re-fetch ⇒ identical content_hash; a changed
--      bar ⇒ a changed content_hash. Written by the worker (DatasetContentHash); NULL on pre-V015 rows.
--   2. `dataset_epochs` — a small append-only ledger, one row per DATA-REWRITE mutation class
--      (CA re-adjust, authoritative re-fetch, backfill, bhavcopy long-tail fill). Each run stamps the
--      epoch HEAD (max id) at execution in `backtest_runs.dataset_epoch`. Comparability = same engine SHA
--      + same epoch scope untouched: a run whose stamped epoch < a later epoch row touching its scope is
--      STALE (the re-run policy surfaces this; it never auto-reruns). The ledger makes "what changed
--      between run A and run B" a QUERY instead of an inference.
-- Plus `evidence_policy` (roadmap #22b): the family evidence-policy the run's ranking obeys
--   (SIM_FIRST / LIVE_FIRST / SIM_BLOCKED, design §1.2) — so a mock/live/sim-smoke run is self-describing
--   about whether its numbers are a ranking plane at all.
--
-- All three run columns are additive + NULL-able (D17): old rows stay NULL, a deep-swing / mock / test
-- writer that omits them writes NULL rather than failing. Existing table GRANTs cover the new columns.

-- 1) The data-rewrite epoch ledger. Append-only (no UPDATE/DELETE grant): a mutation is a fact, never
--    edited. `symbols` NULL = a global (whole-store) rewrite; `window_*`/`interval` NULL = whole history /
--    all intervals. `job_link` optionally references the originating backfill/CA job (soft, no FK — the
--    writer may live in another schema/service). The monotonic identity id IS the epoch head.
CREATE TABLE dataset_epochs (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  reason       TEXT NOT NULL
               CHECK (reason IN ('CA_READJUST', 'AUTHORITATIVE_REFETCH', 'BACKFILL',
                                 'BHAVCOPY_FILL', 'MANUAL')),
  symbols      TEXT[],                         -- affected tradingsymbols; NULL = global scope
  exchange     TEXT,                           -- optional exchange narrowing; NULL = any exchange
  window_start TIMESTAMPTZ,                    -- affected window start; NULL = whole history
  window_end   TIMESTAMPTZ,                    -- affected window end (exclusive); NULL = whole history
  "interval"   TEXT,                           -- affected interval; NULL = all intervals
  job_link     TEXT,                           -- originating job / correlation id (soft, no FK)
  source       TEXT,                           -- who recorded it (service / actor)
  note         TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT ON dataset_epochs TO ay_backtest;  -- append-only ledger: no UPDATE / DELETE
GRANT USAGE ON ALL SEQUENCES IN SCHEMA backtest TO ay_backtest;  -- covers the new identity sequence

-- 2) The three self-describing provenance columns on the run row.
ALTER TABLE backtest_runs ADD COLUMN content_hash    TEXT;
ALTER TABLE backtest_runs ADD COLUMN dataset_epoch   BIGINT;
ALTER TABLE backtest_runs ADD COLUMN evidence_policy TEXT
  CHECK (evidence_policy IN ('SIM_FIRST', 'LIVE_FIRST', 'SIM_BLOCKED'));

-- 3) Re-create the experiment_runs view (V010) to surface the three new axes so the compare endpoint's
--    like-for-like verdict can refuse a cross-content-hash / cross-epoch ranking (roadmap #30). Postgres
--    CREATE OR REPLACE VIEW requires the SAME leading columns in the SAME order — the three new columns
--    are APPENDED at the end; the SELECT is otherwise byte-identical to V010. Grants are preserved by
--    CREATE OR REPLACE.
CREATE OR REPLACE VIEW experiment_runs AS
SELECT
  br.id                                   AS run_id,
  br.job_id                               AS job_id,
  j.kind                                  AS kind,
  j.parent_job_id                         AS parent_job_id,
  br.strategy_version_id                  AS strategy_version_id,
  j.request ->> 'strategyId'              AS strategy_id,
  j.request ->> 'strategyVersion'         AS strategy_version,
  j.request ->> 'purpose'                 AS purpose,
  br.exchange                             AS exchange,
  br.tradingsymbol                        AS tradingsymbol,
  br."interval"                           AS "interval",
  br.start_ts                             AS start_ts,
  br.end_ts                               AS end_ts,
  br.total_return                         AS total_return,
  br.metrics ->> 'cagr'                   AS cagr,
  br.sharpe                               AS sharpe,
  br.sortino                              AS sortino,
  br.max_drawdown                         AS max_drawdown,
  br.win_rate                             AS win_rate,
  br.profit_factor                        AS profit_factor,
  br.trade_count                          AS trade_count,
  br.alpha                                AS alpha,
  br.beta                                 AS beta,
  br.information_ratio                    AS information_ratio,
  br.data_hash                            AS data_hash,
  br.universe_checksum                    AS universe_checksum,
  br.premium_source                       AS premium_source,
  br.engine_sha                           AS engine_sha,
  br.created_by                           AS created_by,
  br.completed_at                         AS completed_at,
  br.content_hash                         AS content_hash,
  br.dataset_epoch                        AS dataset_epoch,
  br.evidence_policy                      AS evidence_policy
FROM backtest_runs br
JOIN jobs j ON j.id = br.job_id;
