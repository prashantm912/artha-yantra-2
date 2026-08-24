-- The engine's blind windows had no durable record anywhere, and both watchdogs were correct to
-- leave it that way -- which is exactly how the hole survived.
--
-- Measured on the 2026-08-19 host-network outage (five destinations failed together: Kite REST,
-- Kite WS, Upstox, Telegram, the liveness heartbeat):
--   * market-data's FeedWatchdog restarted the feed and the reconnect gap-backfill repaired the
--     candles -- NIFTY 50 ended the session with 373 of 375 one-minute bars, 51 of them
--     source='BACKFILL'. The DATA hole closes by itself.
--   * strategy-signal's SubscriberHealthCanary stayed silent by design: its receive-gap branch
--     returns early when the feed heartbeat is itself stale, because remediating a dead producer
--     is market-data's ownership, not the subscriber's.
--   * strategy.subscriber_health_events therefore holds ZERO rows for that day, and the last
--     `receive-stall` row of any kind is 2026-08-14.
-- So the bars came back but the fact that the engine never SAW them, live, did not. A later reader
-- cannot distinguish "no signal at 11:12 because there was no setup" from "no signal at 11:12
-- because the engine was blind", and the same ambiguity covers every unevaluated exit.
--
-- This table is the missing half. It records the window ONLY -- it does not re-evaluate anything.
-- The owner's ruling (2026-08-24) is backfill the data, never re-decide the bars: a bar the engine
-- did not see when it was live is not a decision it gets to make later, at a price that has since
-- moved. So there is deliberately no replay path here, and never should be.
--
-- Scope note: this covers the FEED-OUTAGE cause only -- the one branch that writes nothing today.
-- The subscription-drop and eval-stall branches already write durable subscriber_health_events
-- rows carrying their own durations.
--
-- A window is closed BY ID, by the same process that opened it, never by a blanket
-- `WHERE ended_at IS NULL`. So a service that dies while blind leaves its row open forever, and
-- that is the honest reading: the end was never observed. A blanket close would instead stamp a
-- days-old window with a recovery it did not witness.
CREATE TABLE blind_windows (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at    timestamptz NOT NULL,
    ended_at      timestamptz,
    closed_reason text,
    detail        text        NOT NULL,
    CONSTRAINT blind_windows_closed_ck CHECK ((ended_at IS NULL) = (closed_reason IS NULL))
);

CREATE INDEX idx_blind_windows_started ON blind_windows (started_at DESC);

COMMENT ON TABLE blind_windows IS
    'Windows in which the live signal engine received no bars because the PRODUCER was blind. '
    'Written by SubscriberHealthCanary; a record, never a replay trigger.';
COMMENT ON COLUMN blind_windows.started_at IS
    'Receipt time of the last bar before the gap -- the last moment the engine is known to have '
    'had data. On a boot that never saw a bar this is the boot heartbeat, so the window reads '
    '"blind since boot", which is the truth.';
COMMENT ON COLUMN blind_windows.closed_reason IS
    'How the window ended -- bars-resumed / session-ended / strategies-idle. session-ended is NOT '
    'a recovery: the outage simply outlasted the session, and conflating the two would report a '
    'still-broken feed as healed.';

-- Read-only for the per-schema analyst role: the register writes as the single-writer service user,
-- and nothing outside it has any business closing a window (V053/V056 shape).
GRANT SELECT ON blind_windows TO ay_strategy;
