-- Research-fidelity audit 2026-07-10 §10 T1 (persist `book` on signals) + P1-7 (flag-snapshot ledger).
--
-- T1 — persist the paper BOOK on the signal row. Until now a signal's book was DERIVED at read time by
-- joining the originating strategy's mutable `strategies.tags` (SignalRepository/BookResolver) — so a
-- later tag edit SILENTLY rewrote which book historical signals belonged to (audit T1: "history silently
-- rewrites if tags change"). Freeze it: stamp the book from the strategy's family tags at INSERT and read
-- the column thereafter. The value is derived by a BEFORE-INSERT trigger (not a plain column DEFAULT,
-- because the derivation is a join, not a constant) so EVERY insert path — the live engine, the swing
-- batch, and the direct-SQL test seeders — freezes the book identically with no writer change. Precedence
-- mirrors Books.fromTags exactly: scalper > minervini > manas-arora > other.
CREATE FUNCTION strategy_book_for_version(v_version_id UUID) RETURNS TEXT AS $$
  SELECT COALESCE((
    SELECT CASE
      WHEN 'scalper'     = ANY(st.tags) THEN 'scalper'
      WHEN 'minervini'   = ANY(st.tags) THEN 'minervini'
      WHEN 'manas-arora' = ANY(st.tags) THEN 'manas-arora'
      ELSE 'other' END
    FROM strategy_versions sv JOIN strategies st ON st.id = sv.strategy_id
    WHERE sv.id = v_version_id
  ), 'other');
$$ LANGUAGE sql STABLE;

ALTER TABLE signals ADD COLUMN book TEXT;

-- Backfill every existing row from its strategy's CURRENT tags (a one-time snapshot; drift is frozen from
-- here on). NULL tags / a missing strategy resolve to 'other' inside the function.
UPDATE signals SET book = strategy_book_for_version(strategy_version_id);

-- BEFORE-INSERT stamp: fill book from the family tags when the insert did not supply one. An explicit
-- book on the insert (should one ever be passed) wins; the trigger only fills a NULL.
CREATE FUNCTION signals_stamp_book() RETURNS trigger AS $$
BEGIN
  IF NEW.book IS NULL THEN
    NEW.book := strategy_book_for_version(NEW.strategy_version_id);
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_signals_stamp_book
  BEFORE INSERT ON signals
  FOR EACH ROW EXECUTE FUNCTION signals_stamp_book();

-- The book-filtered signals history read (SignalRepository.list) now filters this column instead of the
-- tags EXISTS-join; index it alongside the recency ordering it pages by.
CREATE INDEX ix_signals_book ON signals (book, generated_at DESC);

-- P1-7 — flag-snapshot ledger. Behaviour-affecting flags that live OUTSIDE the version-pinned strategy
-- YAML (the paper risk governor + env knobs at order open; the pyramid env flags at swing-batch run) left
-- no per-order/per-run record, so forward-paper evidence could not be stratified by flag regime after the
-- fact (audit T4/P1-7). Each paper-order open and each swing-batch run appends one row here snapshotting
-- the live values, keyed to the order/run — so later analysis knows WHICH flag regime produced which
-- trades. flags_hash is the sha-256 of the canonical flags json (a stable REGIME id: identical regimes
-- share a hash), so "did the flag regime change between run A and run B" is a hash comparison.
CREATE TABLE flag_snapshots (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    context_kind TEXT        NOT NULL,   -- 'PAPER_ORDER' | 'SWING_BATCH'
    context_ref  TEXT        NOT NULL,   -- paper_positions.id (as text) | '<batch>:<run_date>'
    book         TEXT,                   -- the paper book / swing family this regime governed (nullable)
    flags        JSONB       NOT NULL,   -- name -> live value of the behaviour-affecting flag regime
    flags_hash   TEXT        NOT NULL,   -- sha-256 of the canonical flags json (the flag REGIME id)
    captured_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_flag_snapshots_context ON flag_snapshots (context_kind, context_ref);
CREATE INDEX ix_flag_snapshots_hash ON flag_snapshots (flags_hash);

-- Append-only for the read-only per-schema role (mirrors V038/V039): SELECT + INSERT only.
GRANT SELECT, INSERT ON flag_snapshots TO ay_strategy;
