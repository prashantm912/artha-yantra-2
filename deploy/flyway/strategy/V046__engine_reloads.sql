-- T15 (signal-analysis rollup / 2026-07-25 bug queue B7): the engine boot line
-- ("signal engine loaded N published strategies (U dropped, E failed)") existed only in container
-- logs, and a post-close deploy destroys those — the 2026-07-17 and 07-20 session forensics both
-- lost their headline finding's root cause to that race, and the week of 07-21..24 kept the line
-- only because nothing happened to restart the container (RestartCount=0 — luck, not design).
-- One narrow append-only row per RELOAD OUTCOME makes the evidence durable: boots, the 08:40 cron,
-- kite-CONNECTED retries (#874), reconcile-triggered drifts and post-publish hot-swaps all land
-- here, so "what did the engine hold on date X, and when did it change?" survives any deploy.
--
-- Volume: a handful of rows per day (boot + publishes + drift), plain OLTP, no retention needed at
-- this size. OBSERVABILITY ONLY — nothing reads it on any trading path; the write is a single
-- swallowed-on-failure INSERT off the reload path (a ledger failure must never break a reload).
--
-- Reading it (IST bounds per the V045 conventions — +05:30 bounds, Asia/Kolkata render):
--   installed=true rows are installs (bank rebuild + resubscribe); installed=false rows are
--   "reload unchanged" confirmations. Health is unresolved = 0, NEVER loaded > 0 (a cold boot
--   produces a PARTIAL load — the 2026-07-16 F10 lesson).

CREATE TABLE engine_reloads (
  id          BIGSERIAL PRIMARY KEY,
  reload_at   TIMESTAMPTZ NOT NULL,
  loaded      INT NOT NULL,          -- strategies the engine holds after this reload
  unresolved  INT NOT NULL,          -- dropped on an unresolved universe (0 = healthy)
  load_errors INT NOT NULL,          -- failed to load
  installed   BOOLEAN NOT NULL       -- true = installed (banks cleared + resubscribed);
                                     -- false = structurally unchanged, nothing swapped
);

CREATE INDEX idx_engine_reloads_at ON engine_reloads (reload_at DESC);

-- ay_strategy is the READ-ONLY per-schema role; only the artha owner writes.
GRANT SELECT ON engine_reloads TO ay_strategy;
