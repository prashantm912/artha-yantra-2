-- E10 auto-journal follow-up: the AutoJournalListener now links a journal_entries row to EVERY
-- closed paper_positions row. The V008 FK journal_entries_paper_position_id_fkey defaults to
-- ON DELETE NO ACTION, so a paper-ledger WIPE (PaperService.reset -> positions.deleteAll, the
-- /paper/reset endpoint, and the shared-DB IT reset()s that DELETE FROM paper_positions) now
-- FK-fails on the linked auto-journal rows. Re-create the FK ON DELETE SET NULL: a position wipe
-- UNLINKS its journal entries (they survive as free entries, note + tags intact) instead of being
-- blocked. Positions are never hard-deleted in normal operation (closed, not deleted) — this only
-- affects the deliberate wipe path. signal_id is left as-is (auto-journal never links a signal, and
-- no delete path exercises that FK).
ALTER TABLE journal_entries DROP CONSTRAINT journal_entries_paper_position_id_fkey;
ALTER TABLE journal_entries
  ADD CONSTRAINT journal_entries_paper_position_id_fkey
  FOREIGN KEY (paper_position_id) REFERENCES paper_positions(id) ON DELETE SET NULL;
