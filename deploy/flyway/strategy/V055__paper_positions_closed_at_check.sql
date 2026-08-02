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
-- SYMMETRIC, BY OWNER DECISION 2026-08-02. The first cut asserted only the forward direction
-- (CLOSED ⇒ closed_at present) and deliberately left out the converse (an OPEN row carrying a
-- closed_at). The owner chose the biconditional instead, and the reasoning is worth recording:
-- the converse is unreachable today for the SAME reason the forward direction is — close() is the
-- only writer of closed_at, it stamps status in the same statement, and no reopen() exists — so
-- both halves rest on exactly one convention. If that convention is worth pinning in one
-- direction it is worth pinning in both, and it costs nothing to enforce here while it needs a
-- second migration later.
--
-- ⚠️ THE CONVERSE IS WEAKER THAN THE FORWARD DIRECTION, AND THIS COMMENT ORIGINALLY OVERSTATED IT.
-- An earlier draft claimed a reopened row with a stale stamp "would keep contributing realized P&L
-- it no longer has". That is FALSE and the cross-vendor review caught it: every realized-P&L reader
-- goes through listClosed(), which filters `WHERE status='CLOSED'` (PaperPositionRepository:327),
-- so an OPEN row simply drops out of pnl() and trades() entirely — including equity()'s per-IST-day
-- bucketing, whose rows come from that same filter. A reopened row corrupts no money figure.
--
-- What the converse actually buys is LIFECYCLE COHERENCE: status and closed_at are two encodings of
-- one fact, and readers use them together — listClosed filters on the status and windows on the
-- timestamp. Letting them disagree makes closed_at unusable as "when did this end", and silently
-- mis-serves any future reader that keys on the timestamp's PRESENCE rather than on the status.
-- That is a coherence guarantee, not a demonstrated bug — stated at its real strength rather than
-- dressed up, because the forward direction is the one with measured downstream damage (see above).
--
-- NO-OP ON EXISTING DATA, MEASURED ON THE LIVE DATABASE, NOT ASSUMED. Both directions checked
-- against live `artha` (read-only) immediately before this was widened:
--
--   status | total | with_closed_at        violations of the converse
--   CLOSED |    27 |             27        (OPEN with closed_at set) = 0
--   OPEN   |    17 |              0
--
-- so every one of the 44 rows already satisfies the biconditional and ADD CONSTRAINT validates
-- without rewriting or rejecting anything. The table is a plain (non-hypertable) ledger of a few
-- dozen rows, so the brief ACCESS EXCLUSIVE lock that validation takes is milliseconds — but it
-- IS a lock on the money path, so this ships during a closed market.

ALTER TABLE paper_positions
  ADD CONSTRAINT ck_paper_positions_closed_at_matches_status
  CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL));

COMMENT ON CONSTRAINT ck_paper_positions_closed_at_matches_status ON paper_positions IS
  'closed_at is present if and only if status = ''CLOSED''. PaperPositionRepository.close() sets both in one atomic UPDATE; this makes that the DATABASE''s promise rather than a convention. The FORWARD direction is the one with measured downstream damage: listClosed / PortfolioReader / GraduationService / equity() all window or bucket on closed_at, so a CLOSED row with a null stamp is silently dropped from every bounded window or misdated to today. The CONVERSE is a lifecycle-coherence guarantee, NOT a money bug — every realized-P&L reader filters WHERE status=''CLOSED'', so an OPEN row simply drops out and corrupts no figure; what it would break is closed_at''s meaning as "when did this end", and any future reader keying on the timestamp''s presence rather than the status. Backs the non-nullable PaperService.TradeDto.closedAt in the published OpenAPI contract.';
