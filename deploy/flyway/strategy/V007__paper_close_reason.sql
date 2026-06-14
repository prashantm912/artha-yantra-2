-- Phase 43B (F-43B / FP-2, amendment A11): the derivative paper lifecycle. An
-- additive close_reason (D17) records WHY a position closed - usable by every
-- closer (manual, the 15:45 intraday mark-to-close, and expiry settlement). The
-- partial-unique open key (§F.6) is already released on close, so the same
-- (exchange, tradingsymbol, side) can re-open on the next series after settlement.

ALTER TABLE paper_positions ADD COLUMN close_reason TEXT;
