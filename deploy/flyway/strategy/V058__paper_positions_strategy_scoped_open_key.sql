-- Option D (owner-approved 2026-08-03, spike docs/superpowers/plans/2026-08-03-scalper-per-strategy-
-- sub-books.md §3.4): make the OPEN-position key STRATEGY-SCOPED inside the ONE existing book, so two
-- strategies that enter the same contract on the same bar hold SEPARATE positions with their own
-- brackets and their own exits — WITHOUT forking capital, duplicating a per-book count governor, or
-- narrowing the kill switch's reach the way a second `book` would (§4.1 / §4.2, all rejected there).
--
-- THE MEASURED PROBLEM. Every scalper paper position on the live book is a merged pyramid of TWO
-- strategies. Re-measured read-only against `artha` on 2026-08-03, not recalled:
--
--   book    | status | n  | qty | pnl
--   scalper | CLOSED | 10 | 850 | -3109.70      (no OPEN scalper rows)
--
-- All 10 are built from two ENTRY orders whose fill prices are BYTE-IDENTICAL (482.05/482.05,
-- 199.30/199.30, ...), one from `scalp-golden-crossover-*` and one from `scalp-connect-the-dots-*`,
-- placed 0.1-2.2 s apart. Two twin PAIRS, not one: the SENSEX pair accounts for 5 of the 10. The
-- twins are discriminated by their EXIT, not their entry — 10/10 closes trace 1:1 to one twin
-- (connect-the-dots owns 5/5 TIME_STOP, golden-crossover 5/5 STRUCTURAL_STOP), which is exactly what
-- their YAMLs predict (10-bar vs 12-bar time stop). The merged row exits on the pointwise MINIMUM of
-- two doctrines and neither strategy's exit is ever fully expressed.
--
-- The mechanism is this index plus PaperPositionRepository.openForSignal's strategy-blind join: with
-- one row on (book, exchange, tradingsymbol, side), PaperService.upsertPosition AVERAGES the second
-- twin into the first, and either twin's exit settles the whole thing.
--
-- WHAT CHANGES. `strategy_id` joins the partial-unique open key. It is stamped ONLY for books listed
-- in `artha.paper.strategy-scoped-books` (DEFAULT EMPTY — this migration is behaviourally INERT until
-- the owner arms a book); every other book keeps writing NULL and behaves byte-identically.
--
-- ⚠️ `NULLS NOT DISTINCT` IS LOAD-BEARING, NOT DECORATION. Under PostgreSQL's DEFAULT (`NULLS
-- DISTINCT`) two NULL-strategy rows are DIFFERENT keys, so adding a nullable column to a unique index
-- SILENTLY DELETES the guarantee it is supposed to preserve: an unscoped book could double-open the
-- same (book, exchange, tradingsymbol, side) and nothing would complain. Measured on this exact
-- server (PostgreSQL 17.3, temp-table probe, 2026-08-03): with `NULLS NOT DISTINCT` the second
-- NULL-strategy insert raises `duplicate key value ... =(scalper, NFO, X, BUY, null) already exists`;
-- WITHOUT it, both rows land (`count = 2`). Two rows with DISTINCT strategy_ids land under either
-- spelling — which is why the clause's absence is invisible to any test that only exercises the new
-- behaviour. PaperBookIsolationIntegrationTest#twoBooksMayEachHoldTheSameSymbolOpenButOneBookCannot
-- DoubleOpen is the existing guard that goes RED if this clause is ever dropped.
--
-- EXISTING ROWS ARE NOT RETRO-SPLIT AND CANNOT BE. `paper_orders` has no `position_id` (that gap is
-- what PR #1259 closes, going forward only), and the obvious order→position reconstruction FANS OUT
-- (NIFTY2680424250CE was entered three times on 07-31, so six entry orders match three positions).
-- The realised P&L of a merged position is one number produced by ONE exit that only one twin's
-- doctrine chose; no arithmetic splits it. So every pre-existing row keeps `strategy_id = NULL`,
-- stays exactly as it is, and any before/after comparison across this change is NOT like-for-like:
-- pre-change rows are 2-strategy merges with min-of-two exits, post-change rows are single-strategy
-- with own-doctrine exits. There are 10 such scalper rows, all CLOSED, and 0 OPEN ones as of
-- 2026-08-03, so nothing in flight is affected.
--
-- CAPITAL GOVERNORS ARE UNTOUCHED — one book, one paper_account row (₹150,000), one heat_cap_pct,
-- one daily_loss_limit, one daily_profit_target, one max_deployment_pct, one kill switch, one
-- 5-wins/day cap, one set of five sub-accounts. Total exposure is unchanged (the same 2 x ₹15,000,
-- now in 2 rows), and a scoped sibling INHERITS the open key's existing sub-account rather than being
-- assigned a fresh one, so the per-sub-account allocation arithmetic and the first-loss freeze
-- topology are bit-for-bit what they are today (PaperService.openOrder / openSubAccountIdx).
--
-- ⚠️ WHAT DOES CHANGE, STATED PLAINLY: the ROW is now the strategy-scoped unit, so every governor
-- that counts ROWS counts two where it counted one for a co-firing pair — `max_open_paper_positions`
-- (20) and `ScalperAccountModel.MAX_WINS_PER_DAY` (5). Both bind SOONER, never later; neither value
-- nor row is modified here. This is the accepted cost of Option D (spike §3.4), not a governor edit.

ALTER TABLE paper_positions ADD COLUMN strategy_id UUID;

COMMENT ON COLUMN paper_positions.strategy_id IS
  'The strategies.id that opened this position, stamped ONLY when the position''s book is listed in artha.paper.strategy-scoped-books (default EMPTY ⇒ always NULL ⇒ behaviour identical to before V058). Part of uq_paper_positions_open, so two strategies entering the same (book, exchange, tradingsymbol, side) hold SEPARATE rows instead of averaging into one. NULL means "unattributed" and all NULLs collide as one key (NULLS NOT DISTINCT) — that is what preserves the pre-V058 one-open-row-per-key guarantee for every unscoped book. Legacy rows written before V058 are NULL and are never retro-split.';

DROP INDEX IF EXISTS uq_paper_positions_open;
CREATE UNIQUE INDEX uq_paper_positions_open
  ON paper_positions (book, exchange, tradingsymbol, side, strategy_id)
  NULLS NOT DISTINCT
  WHERE status = 'OPEN';
