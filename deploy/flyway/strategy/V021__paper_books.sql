-- Per-strategy paper BOOKS (2026-07-05, owner-approved): split the single global paper book into
-- three independent books — 'scalper', 'minervini', 'manas-arora' (+ 'manual' for hand orders) — each
-- with its OWN capital (₹1.5 L), risk config and auto-paper toggle. A position/order carries a `book`;
-- capital + risk_settings become per-book rows. Positions are WIPED (owner ruling: every book starts
-- flat). All additive/forward-only. The signals list filters by book via the strategies.tags join at
-- read time — no signals column is added here.

-- ── paper_positions / paper_orders: tag every row with its book ───────────────────────────────────
ALTER TABLE paper_positions ADD COLUMN book TEXT NOT NULL DEFAULT 'manual';
ALTER TABLE paper_orders    ADD COLUMN book TEXT NOT NULL DEFAULT 'manual';

-- The §F.6 open-position uniqueness must be PER-BOOK: two books may each hold the same (exchange,
-- symbol, side) long at once, so the old global partial-unique key would wrongly collide across books.
DROP INDEX IF EXISTS uq_paper_positions_open;
CREATE UNIQUE INDEX uq_paper_positions_open
  ON paper_positions (book, exchange, tradingsymbol, side) WHERE status = 'OPEN';
CREATE INDEX idx_paper_positions_book ON paper_positions (book);
CREATE INDEX idx_paper_orders_book    ON paper_orders (book);

-- Wipe existing paper positions + orders (owner: every book starts flat). DELETE (not TRUNCATE) so the
-- journal_entries FK (V013, ON DELETE SET NULL) is honoured — journal history is preserved, its
-- position_id is nulled. Orders reference signals only; positions are referenced by journal_entries.
DELETE FROM paper_orders;
DELETE FROM paper_positions;

-- ── paper_account: single row → one row per book, each ₹1.5 L ──────────────────────────────────────
ALTER TABLE paper_account DROP CONSTRAINT IF EXISTS paper_account_id_check; -- the CHECK (id = 1)
ALTER TABLE paper_account ADD COLUMN book TEXT;
-- repurpose the existing id=1 row as the scalper book at the new ₹1.5 L base
UPDATE paper_account SET book = 'scalper', starting_capital = 150000.00, cash = 150000.00 WHERE id = 1;
INSERT INTO paper_account (id, book, starting_capital, cash) VALUES
  (2, 'minervini',   150000.00, 150000.00),
  (3, 'manas-arora', 150000.00, 150000.00),
  (4, 'manual',      150000.00, 150000.00),
  (5, 'other',       150000.00, 150000.00);  -- catch-all for a strategy in no known family (governed, no auto-paper)
ALTER TABLE paper_account ALTER COLUMN book SET NOT NULL;
CREATE UNIQUE INDEX uq_paper_account_book ON paper_account (book);

-- ── risk_settings: global rows → composite (book, key) with deterministic per-book defaults ────────
-- Re-seeded deterministically so a fresh reset-db and the live DB converge to the same per-book config
-- (the pre-split global rows served the combined book and do not map cleanly). Swing books carry no
-- intraday daily_profit_target (a 1.5%/day target would halt multi-week entries after one good day).
ALTER TABLE risk_settings ADD COLUMN book TEXT;
ALTER TABLE risk_settings DROP CONSTRAINT risk_settings_pkey;
DELETE FROM risk_settings;
ALTER TABLE risk_settings ALTER COLUMN book SET NOT NULL;
ALTER TABLE risk_settings ADD PRIMARY KEY (book, key);

INSERT INTO risk_settings (book, key, value) VALUES
  -- scalper (intraday options; 5-sub-account model layered on top)
  ('scalper', 'kill_switch',              '{"enabled": false}'::jsonb),
  ('scalper', 'max_open_paper_positions', '{"enabled": true, "value": 20}'::jsonb),
  ('scalper', 'max_deployment_pct',       '{"enabled": true, "value": 80.0}'::jsonb),
  ('scalper', 'daily_loss_limit',         '{"enabled": true, "mode": "pct", "value": 10}'::jsonb),
  ('scalper', 'daily_profit_target',      '{"enabled": true, "mode": "pct", "value": 1.5}'::jsonb),
  ('scalper', 'auto_paper_trade',         '{"enabled": true}'::jsonb),
  -- minervini (daily EOD swing; 12-slot book, backtest-tuned)
  ('minervini', 'kill_switch',              '{"enabled": false}'::jsonb),
  ('minervini', 'max_open_paper_positions', '{"enabled": true, "value": 12}'::jsonb),
  ('minervini', 'max_deployment_pct',       '{"enabled": true, "value": 80.0}'::jsonb),
  ('minervini', 'daily_loss_limit',         '{"enabled": true, "mode": "pct", "value": 10}'::jsonb),
  ('minervini', 'auto_paper_trade',         '{"enabled": true}'::jsonb),
  -- manas-arora (daily EOD swing; 5-7 names, pyramiding averages into a name)
  ('manas-arora', 'kill_switch',              '{"enabled": false}'::jsonb),
  ('manas-arora', 'max_open_paper_positions', '{"enabled": true, "value": 7}'::jsonb),
  ('manas-arora', 'max_deployment_pct',       '{"enabled": true, "value": 80.0}'::jsonb),
  ('manas-arora', 'daily_loss_limit',         '{"enabled": true, "mode": "pct", "value": 10}'::jsonb),
  ('manas-arora', 'auto_paper_trade',         '{"enabled": true}'::jsonb),
  -- manual (hand orders only; never auto-papers)
  ('manual', 'kill_switch',      '{"enabled": false}'::jsonb),
  ('manual', 'auto_paper_trade', '{"enabled": false}'::jsonb),
  -- other (catch-all for an untagged strategy: governed by caps, but never auto-papers)
  ('other', 'kill_switch',              '{"enabled": false}'::jsonb),
  ('other', 'max_open_paper_positions', '{"enabled": true, "value": 10}'::jsonb),
  ('other', 'max_deployment_pct',       '{"enabled": true, "value": 80.0}'::jsonb),
  ('other', 'daily_loss_limit',         '{"enabled": true, "mode": "pct", "value": 10}'::jsonb),
  ('other', 'auto_paper_trade',         '{"enabled": false}'::jsonb);

-- audit rows carry the book they belong to (nullable for pre-split rows)
ALTER TABLE risk_audit ADD COLUMN book TEXT;
