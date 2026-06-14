-- Phase 44A (F-44A / FP-66): the trade journal. Same-schema FKs to signals /
-- paper_positions (validated); backtest_run_id / backtest_trade_id are SOFT
-- cross-schema references (plain columns, NEVER FKs into the backtest schema —
-- the §6.2 no-cross-schema-FK rule, D10). All link columns are nullable, so a
-- FREE entry (no linked object) is first-class. Screenshots are out of v1.

CREATE TABLE journal_entries (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  signal_id         BIGINT REFERENCES signals(id),
  paper_position_id BIGINT REFERENCES paper_positions(id),
  backtest_run_id   UUID,        -- soft cross-schema reference (no FK, D10)
  backtest_trade_id BIGINT,      -- soft cross-schema reference (no FK, D10)
  note              TEXT NOT NULL DEFAULT '',
  tags              TEXT[] NOT NULL DEFAULT '{}',
  discipline_rating SMALLINT CHECK (discipline_rating BETWEEN 1 AND 5),
  emotion_rating    SMALLINT CHECK (emotion_rating BETWEEN 1 AND 5),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_journal_created ON journal_entries (created_at DESC);
CREATE INDEX idx_journal_tags ON journal_entries USING gin (tags);

GRANT SELECT, INSERT, UPDATE, DELETE ON journal_entries TO ay_strategy;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA strategy TO ay_strategy;
