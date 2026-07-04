-- Phase-5 Track-B (Minervini SEPA): per-candidate VCP / base geometry (MV-5.4).
-- Sibling of minervini_screen_results keyed the same (screen_date, symbol), populated only for the
-- day's passers (the geometry scan is per-symbol and expensive, so it is not run for every scanned
-- name). Holds the Technical Footprint (§4.4), the buy pivot (§4.3), and the volume/shakeout flags
-- emitted by VcpDetector. Lives in the marketdata lineage per OD-5 (producer = MinerviniScheduler in
-- market-data-service). is_vcp=false rows keep reject_reason so a "why no base" is queryable.
CREATE TABLE minervini_setups (
    screen_date        DATE          NOT NULL,
    symbol             TEXT          NOT NULL,
    is_vcp             BOOLEAN       NOT NULL,
    footprint          TEXT,                     -- "[weeks]W [deepest%/tightest%] [count]T"
    pivot              NUMERIC(18,4),            -- buy trigger = high of the final tightest contraction
    deepest_pct        NUMERIC(8,2),             -- depth of the largest correction in the base
    tightest_pct       NUMERIC(8,2),             -- depth of the final, tightest contraction
    contraction_count  SMALLINT,
    base_weeks         SMALLINT,
    base_duration_days SMALLINT,
    volume_dry_up      BOOLEAN       NOT NULL DEFAULT FALSE,
    shakeout           BOOLEAN       NOT NULL DEFAULT FALSE,
    base_count         SMALLINT,
    reject_reason      TEXT,                     -- NULL when is_vcp; else why it is not a VCP
    computed_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (screen_date, symbol)
);

CREATE INDEX idx_minervini_setups_vcp
    ON minervini_setups (screen_date, is_vcp);
