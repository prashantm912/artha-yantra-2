-- H44: a DURABLE record of fills struck on a contract that has never ticked.
--
-- Why a table and not the counter. The evidence for the owner's arming decision was
-- `ay_paper_fill_no_tick_total`, a Micrometer counter -- process-lifetime, reset on every restart.
-- Measured 2026-08-29 on the weekly report's FIRST run: the container had restarted 11 minutes
-- earlier, so a "weekly" figure covered 11 minutes of a Saturday. This stack restarted three times
-- in 24 h. A weekly rate keyed on that counter can only ever mean "since the last restart", which is
-- indistinguishable from a genuinely quiet week -- it fails in the reassuring direction.
--
-- Why NOT paper_order_rejections, which was the first plan. That table is described by its own
-- repository as "the append-only ledger of REFUSED paper-order..." and is served through a filtered
-- SELECT read surface. These rows are the opposite: the fill SUCCEEDED. Writing them there would
-- make a successful trade appear in an operator's refusals listing, and would inflate any count that
-- does not happen to filter on `reason`. A distinct fact deserves a distinct table.
--
-- Relationship to the rejection rows, because the two are easy to confuse:
--   paper_fill_observations   the gate was DISARMED, the fill HAPPENED, and it may be unsettleable
--   DATA_GAP_NEVER_TICKED     the gate was ARMED and REFUSED the entry (no position exists)
-- Together they answer "how often would arming have fired?" across both flag states.
CREATE TABLE IF NOT EXISTS strategy.paper_fill_observations (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  position_id   BIGINT      NOT NULL,
  book          TEXT,
  exchange      TEXT        NOT NULL,
  tradingsymbol TEXT        NOT NULL,
  qty           BIGINT      NOT NULL,
  observed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The weekly report's only query shape: "rows in the last 7 days". Nothing reads by symbol yet, so
-- one time index is all that is earned -- an index nothing uses is a write cost with no reader.
CREATE INDEX IF NOT EXISTS idx_paper_fill_obs_observed_at
  ON strategy.paper_fill_observations (observed_at DESC);

COMMENT ON TABLE strategy.paper_fill_observations IS
  'H44: paper fills struck on a contract that has NEVER ticked, recorded while the closability gate '
  '(artha.paper.refuse-no-tick-entries) is DISARMED. The fill succeeded; every AUTOMATIC exit refuses '
  'without a real tick (#694), so such a position may be unsettleable until one arrives. Durable on '
  'purpose: the ay_paper_fill_no_tick_total counter resets on restart and cannot carry a weekly rate. '
  'NOT a refusal -- refusals live in paper_order_rejections with reason DATA_GAP_NEVER_TICKED.';
