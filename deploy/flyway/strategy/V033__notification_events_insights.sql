-- Intelligence layer I1 (INT design §2.5.3 / §13 row 20): make the notifier's delivery-audit ledger
-- carry insight-scoped pushes. `notification_events` was minted (V004) for per-signal strategy pushes,
-- so `strategy_id` is NOT NULL and there is no insight linkage. Market/dataops-scoped insights
-- (DATA_TRUST, MARKET_STRUCTURE, ...) have NO strategy, so an audited push of one needs a nullable
-- `strategy_id` and an `insight_id` link.
--
-- Additive + forward-only (never edit the checksum-locked V004): DROP NOT NULL + one nullable column +
-- an index. In I1 (shadow mode) NOTHING writes an insight-scoped row yet — delivery arms in I3/I4; this
-- migration + the read endpoint (§13 row 15) just make the audited-push shape exist so the delivery
-- adapter lands additively later. Grants on notification_events (SELECT, INSERT for ay_strategy) are
-- unchanged from V004.
ALTER TABLE notification_events ALTER COLUMN strategy_id DROP NOT NULL;
ALTER TABLE notification_events ADD COLUMN insight_id UUID REFERENCES insights(id);

-- Per-insight delivery lookup (the audit chain read: which pushes an insight generated).
CREATE INDEX idx_notification_events_insight ON notification_events (insight_id, created_at DESC);
