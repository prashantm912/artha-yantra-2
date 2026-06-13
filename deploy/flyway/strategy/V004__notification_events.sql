-- Phase 41 (Q6 / E-14): per-attempt delivery audit for the signal notifier. Append-only (D17) —
-- ay_strategy gets SELECT, INSERT only. Notification opt-in lives on the strategies table
-- (Phase 21: notifications_enabled, notification_channel), NEVER the versioned config, so toggling
-- alerts never mints a version or perturbs a D18 checksum (asserted by an IT). signal_id is
-- nullable so a manual test-send (no real signal) is still audited.

CREATE TABLE notification_events (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  signal_id    BIGINT REFERENCES signals(id),
  strategy_id  UUID NOT NULL REFERENCES strategies(id),
  channel      TEXT NOT NULL CHECK (channel IN ('NTFY', 'TELEGRAM')),
  status       TEXT NOT NULL CHECK (status IN ('SENT', 'FAILED', 'SUPPRESSED')),
  attempts     INT NOT NULL DEFAULT 1,
  detail       TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_events_signal ON notification_events (signal_id);
CREATE INDEX idx_notification_events_strategy ON notification_events (strategy_id, created_at DESC);

GRANT SELECT, INSERT ON notification_events TO ay_strategy;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA strategy TO ay_strategy;
