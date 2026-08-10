-- A partial candle swap is NOT an ordinary failure, and recording it as one left live data mixed
-- for a week (cross-vendor review of #1297, 2026-08-10, caught before merge).
--
-- The staged rebuild swaps 1m FIRST and 1d LAST, deliberately: detection reads only the 1d series,
-- so if 1d landed first and 1m failed, the cache's 1d would now EQUAL Kite, the next sweep would
-- find no divergence, and the symbol would sit half-adjusted forever with nothing to re-trigger it.
-- The chosen order means a partial swap always leaves 1d unadjusted, so detection DOES re-fire.
--
-- Except it could not act on that. Both swaps are separate statements, so an ordinary catchable DB
-- error on the 1d swap (decompression cap, lock or statement timeout, disk) commits 1m and abandons
-- 1d. That was recorded as plain `FAILED` — and the rebuild cooldown skips any symbol whose latest
-- event is `FAILED` within `rebuild-retry-cooldown-days`. So the recovery the swap order was
-- designed to guarantee was suppressed for the whole cooldown, and for that week every consumer
-- read CORPORATE-ACTION-ADJUSTED 1m against UNADJUSTED 1d for the same symbol. An urgent page made
-- it visible; nothing made it stop.
--
-- `PARTIAL_SWAP` is that state named. It is deliberately NOT in the cooldown's match set, so the
-- next sweep re-detects and re-stages from scratch. It is equally deliberately NOT a resumable
-- checkpoint: the same review confirmed that reusing retained staging is unsafe here, because
-- `verifyStagedRebuild` validates COVERAGE ONLY and a later corporate action can make retained
-- values obsolete while coverage still passes. Fresh restage, promptly — not cheap reuse.
--
-- The cooldown still bounds the ORDINARY failure path (`FAILED`), which is what it was added for:
-- a symbol whose rebuild can never pass verification must not cost a nightly ~196-page Kite
-- re-fetch against the shared rate limiter plus a nightly page.

ALTER TABLE corporate_action_events DROP CONSTRAINT corporate_action_events_status_check;

ALTER TABLE corporate_action_events ADD CONSTRAINT corporate_action_events_status_check
    CHECK (status = ANY (ARRAY[
        'DETECTED',
        'REBACKFILL_RUNNING',
        'BASE_REBUILT',
        'RESOLVED',
        'FAILED',
        'PARTIAL_SWAP',
        'REFRESH_FAILED',
        'REFRESH_ABANDONED'
    ]));
