-- Audit V2 (app-platform audit 2026-07-10 §8): idempotency key on POST /api/v1/paper/orders. A client
-- retry (network timeout, double-click) on the MANUAL order path currently opens a SECOND fill that
-- doubles qty into the averaged position. An optional client_order_id lets a duplicate submission
-- return the ORIGINAL position instead of re-filling. Additive / forward-only; only the manual path
-- stamps it (engine/taken fills leave it NULL).

ALTER TABLE paper_orders ADD COLUMN client_order_id TEXT;

-- Unique PER BOOK over the rows that carry a key (audit V2: "clientOrderId unique per book"). A partial
-- index over NON-NULL values only, so every engine/taken/exit fill (client_order_id NULL) is
-- unconstrained. This index is ALSO the concurrency arbiter: two simultaneous submits carrying the same
-- (book, client_order_id) can never both INSERT a fill — the loser's INSERT hits a 23505, its whole fill
-- transaction rolls back, and the service reads back the winner's position (see PaperService.openManualOrder).
CREATE UNIQUE INDEX uq_paper_orders_client_order_id
  ON paper_orders (book, client_order_id) WHERE client_order_id IS NOT NULL;

-- No new GRANT: the V005 table-level GRANT (SELECT, INSERT, UPDATE, DELETE ON paper_orders TO ay_strategy)
-- already covers every column, including ones added later.
