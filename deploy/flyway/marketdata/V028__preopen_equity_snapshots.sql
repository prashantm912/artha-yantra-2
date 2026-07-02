-- Wave-5 audit §9.4: the equity pre-open FULL scanner (oipulse §equity/pre-open-market). One row per
-- F&O stock per session, captured once at ~09:09:30 IST from a single batched quote call, so the
-- pre-open read stays reviewable all day AND accrues a history (the old page pivoted to live LTP at
-- 09:15 and kept nothing). Plain table (~200 rows/day). prev_* fields come from the EQ bhavcopy at
-- capture time; prev_day_break is 'H' (above prev high) / 'L' (below prev low) / NULL.
CREATE TABLE preopen_equity_snapshots (
    trade_date     DATE          NOT NULL,
    symbol         TEXT          NOT NULL,
    preopen_price  NUMERIC(12,4),
    prev_close     NUMERIC(12,4),
    change         NUMERIC(12,4),
    change_pct     NUMERIC(8,2),
    prev_day_break TEXT,
    captured_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (trade_date, symbol)
);
