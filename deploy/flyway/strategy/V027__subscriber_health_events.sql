-- Durable, recreate-proof telemetry for the subscriber-health watchdog (SubscriberHealthCanary;
-- audit A13, RC-1). The canary previously left only a log line + a fire-and-forget ntfy push, so the
-- 2026-07-10 15:57 container re-create wiped the stall-window logs and the episode left NO durable
-- trace. One row lands here on: receive-stall detection, each re-subscribe attempt, eval-stall
-- detection, and recovery — so a post-mortem has the timeline even when the container logs are gone.
-- Write-only operator forensics (no read path / endpoint / DTO). Plain OLTP table (not a hypertable);
-- volume is a handful of rows per incident.
CREATE TABLE subscriber_health_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind        TEXT        NOT NULL, -- receive-stall | resubscribe | eval-stall | recovery
    detail      TEXT        NOT NULL
);

CREATE INDEX idx_subscriber_health_events_occurred ON subscriber_health_events (occurred_at DESC);

GRANT SELECT, INSERT ON subscriber_health_events TO ay_strategy;
