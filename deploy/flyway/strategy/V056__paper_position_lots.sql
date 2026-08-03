-- Per-signal lot tagging: record WHICH signal caused each entry fill, so a pyramided position can
-- be decomposed per contributing strategy.
--
-- WHY. `uq_paper_positions_open` guards the ROW, never the qty, so a second openPosition on the
-- same (book, exchange, tradingsymbol, side) AVERAGES into the open position rather than rejecting.
-- That is deliberate pyramiding behaviour and is NOT changed here. What it costs is attribution:
-- `paper_positions.opening_signal_id` is set on INSERT only and deliberately kept across an
-- averaging add (audit H5), so a position built by two strategies credits exactly one of them.
--
-- MEASURED ON THE LIVE DATABASE, 2026-08-03 (read-only) — this is not hypothetical:
--
--   order  signal  slug                                    sym                 qty  fill      IST
--   72     128     scalp-golden-crossover-nifty            NIFTY2680424200CE    65  199.3000  07-31 11:16:21
--   73     129     scalp-connect-the-dots-nifty            NIFTY2680424200CE    65  199.3000  07-31 11:16:23
--   74     (exit)                                          NIFTY2680424200CE   130  202.8500  07-31 11:46:15
--
-- Both signals fire on the same bar (`generated_at` 11:15:00 for both) at the same price ~2s apart;
-- position 47 carries qty 130 and `opening_signal_id` 128. Every one of the 10 closed scalper
-- positions has this shape (SENSEX pairs are 20 + 20 -> 40). A `GROUP BY slug` over
-- `opening_signal_id` therefore reports `scalp-connect-the-dots-*` at n=0 while it contributed half
-- of every trade. More accrual does not fix this — the two strategies traded the identical
-- instrument at the identical price for the identical holding period, so no sample size separates
-- them from the position row alone.
--
-- ⚠️ A COMMENT IN THE CODE SAYS THIS CANNOT HAPPEN, AND IT IS WRONG. PaperService.upsertPosition
-- states the position "can never span two strategies (the per-book open-key means only one strategy
-- holds a given key open at a time)". The rows above disprove it: two DIFFERENT strategies in the
-- same book hold the same key, because the second open does not take the key, it averages into it.
-- That comment is corrected in this change.
--
-- WHAT THIS TABLE IS. One row per ENTRY fill, carrying the (signal, qty, price) triple and the
-- position the fill built. Exit fills are deliberately NOT recorded: an exit carries no signal_id
-- (doSettle passes null) and closes the whole position in one leg, so it has nothing to attribute.
-- Realized P&L is decomposed at READ time, pro-rata by each lot's qty share of the position — which
-- is not an approximation but the exact same fungibility the position itself already assumes, since
-- every unit exits against one `avg_entry_price`.
--
-- WHY A NEW TABLE RATHER THAN A COLUMN ON paper_orders. `paper_orders` already carries signal_id,
-- qty and fill_price per fill — the raw attribution genuinely already exists there — but it has NO
-- position_id, and the join cannot be reconstructed after the fact: on 2026-07-31 the contract
-- NIFTY2680424250CE was entered THREE separate times (positions 49, 50, 51), each closed before the
-- next opened, so six entry orders fan out across three positions on a (book, exchange, symbol,
-- side) join with nothing but timestamps to bracket them. Adding position_id to paper_orders would
-- also mean writing the money-path order row, then UPDATEing it once the position id exists;
-- an additive table lets the link be written once, correctly, with the id already in hand.
--
-- ⚠️ NO BACKFILL, AND NONE IS POSSIBLE HONESTLY. The 45 positions already in live `artha`
-- (28 CLOSED / 1716 qty, 17 OPEN / 488 qty, measured 2026-08-03) get ZERO lots and are reported as
-- UNTAGGED by the attribution read. The scalper pairs above could be reconstructed by hand from the
-- order timestamps, but the swing and manual books cannot, and a partial backfill that looked
-- complete would be worse than an empty one. Attribution starts from the first fill after deploy.

CREATE TABLE paper_position_lots (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  position_id   bigint NOT NULL REFERENCES paper_positions (id) ON DELETE CASCADE,
  -- BOTH parents cascade, and the order_id one is not merely convenience. A lot describes a FILL;
  -- delete the fill's order row and the lot is a record of nothing. Making it RESTRICT instead would
  -- make correctness depend on DELETE ORDERING: PaperService.reset() happens to delete positions
  -- before orders (positions.deleteAll then orders.deleteAll), so the position cascade would clear
  -- the lots first and the order delete would survive — but any future caller that dropped orders
  -- first would hit a foreign-key error on a purely observational table. Caught by the full-verify
  -- suite, where nine paper ITs tear down with `DELETE FROM paper_orders` BEFORE `DELETE FROM
  -- paper_positions` and every one of them failed on the FK.
  order_id      bigint NOT NULL REFERENCES paper_orders (id) ON DELETE CASCADE,
  signal_id     bigint REFERENCES signals (id) ON DELETE SET NULL,
  book          text NOT NULL,
  exchange      text NOT NULL,
  tradingsymbol text NOT NULL,
  side          text NOT NULL CHECK (side IN ('BUY', 'SELL')),
  qty           bigint NOT NULL CHECK (qty > 0),
  fill_price    numeric(18, 4) NOT NULL,
  filled_at     timestamptz NOT NULL DEFAULT now()
);

-- One lot per entry fill. insertFilled mints a fresh identity per call, so a duplicate is
-- structurally unreachable rather than merely unlikely — this asserts that rather than defends it.
CREATE UNIQUE INDEX uq_paper_position_lots_order ON paper_position_lots (order_id);

-- The decomposition read: every lot of a position, and every lot of a signal.
CREATE INDEX ix_paper_position_lots_position ON paper_position_lots (position_id);
CREATE INDEX ix_paper_position_lots_signal ON paper_position_lots (signal_id)
  WHERE signal_id IS NOT NULL;
CREATE INDEX ix_paper_position_lots_book_filled ON paper_position_lots (book, filled_at DESC);

COMMENT ON TABLE paper_position_lots IS
  'One row per paper ENTRY fill: which signal caused it, how much it contributed, and at what price. Exists because a second openPosition on an open (book, exchange, tradingsymbol, side) AVERAGES into the position (uq_paper_positions_open guards the row, never the qty) while opening_signal_id records only the first signal — so a position built by scalp-golden-crossover + scalp-connect-the-dots firing on the same bar credits one strategy and hides the other. Realized P&L is decomposed at read time pro-rata by qty share, which is exact rather than approximate because the position exits every unit against one avg_entry_price. Exit fills are not recorded (they carry no signal and close the whole position). Positions opened before this migration have no lots and are reported UNTAGGED; there is no backfill.';

COMMENT ON COLUMN paper_position_lots.signal_id IS
  'The ENTRY signal that caused this fill; NULL for a manual/unlinked fill. ON DELETE SET NULL matches paper_positions.opening_signal_id — losing the signal must not delete the money record of the fill.';

COMMENT ON COLUMN paper_position_lots.qty IS
  'This fill''s contribution, NOT the position total. sum(qty) over a position equals paper_positions.qty for any position opened entirely after this migration; it is SHORT for a pre-existing position that later takes a tagged add, which is why the attribution read divides by paper_positions.qty and reports the remainder as untagged rather than assuming full coverage.';
