-- Pin in the DATABASE the invariant TradeDto.closedAt already advertises on the wire.
--
-- WHY. V005 gave paper_positions a `status IN ('OPEN','CLOSED')` check and a nullable closed_at,
-- and nothing tying the two together. The invariant "a CLOSED row has a closed_at" has held since
-- V005 purely because ONE writer produces that status:
--
--   PaperPositionRepository.close()  UPDATE paper_positions SET status='CLOSED', realized_pnl=?,
--                                    closed_at=now(), close_reason=? WHERE id=? AND status='OPEN'
--
-- which sets both columns in the SAME atomic UPDATE. That is the only SQL in the tree that writes
-- status='CLOSED' to this table (ShadowPositionRepository.close writes shadow_positions, a
-- different table), and no migration ever backfilled a CLOSED row; V021 wiped the pre-book history.
--
-- So the invariant is true today by CONVENTION, and convention is exactly what a second closer
-- would break. A future settle path, a repair script, or an admin UPDATE that flips status without
-- stamping closed_at would violate it SILENTLY: nothing would error, and the damage surfaces far
-- downstream, where closed_at is not merely decorative —
--
--   * PaperPositionRepository.listClosed ORDERs BY closed_at DESC and windows on it
--     (`closed_at >= ?` / `<= ?`), so a null-closed_at row sorts unpredictably and vanishes from
--     every bounded window while still counting in unbounded reads.
--   * PortfolioReader / PaperReconciliationRepository / GraduationService all window CLOSED rows
--     on closed_at — a null row is silently excluded from P&L, reconciliation and graduation.
--   * PaperService.equity() buckets realized P&L by closed_at's IST date; its `closedAt() == null`
--     fallback attributes the row to LocalDate.now(IST), i.e. to TODAY, quietly misdating history.
--
-- This constraint converts all of that from a silent wrong answer into a loud write failure at the
-- one place that can still name the culprit.
--
-- WHAT THIS DOES NOT CLAIM. It does not assert the converse (an OPEN row with a closed_at set).
-- That shape is not reachable today either — close() is the only writer of closed_at and it sets
-- status in the same statement, and reopen() does not exist — but it is not what the downstream
-- readers above depend on, so it is left out rather than guessed at.
--
-- NO-OP ON EXISTING DATA, MEASURED, NOT ASSUMED. Verified against the dev/mock database before
-- shipping (see the PR receipt): zero rows in paper_positions have status='CLOSED' with a NULL
-- closed_at, so ADD CONSTRAINT validates without rewriting or rejecting anything. The table is a
-- plain (non-hypertable) ledger of a few thousand rows, so the brief ACCESS EXCLUSIVE lock that
-- validation takes is not a deployment concern.

ALTER TABLE paper_positions
  ADD CONSTRAINT ck_paper_positions_closed_at_present
  CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL);

COMMENT ON CONSTRAINT ck_paper_positions_closed_at_present ON paper_positions IS
  'A CLOSED position always carries its closed_at. PaperPositionRepository.close() sets both in one atomic UPDATE; this makes that the DATABASE''s promise rather than a convention, because listClosed / PortfolioReader / GraduationService / equity() all window or bucket on closed_at and would silently drop or misdate a null-stamped row. Backs the non-nullable PaperService.TradeDto.closedAt in the published OpenAPI contract.';
