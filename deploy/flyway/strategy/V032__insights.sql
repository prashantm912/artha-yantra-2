-- Intelligence layer I1 (INT design §2.1/§2.4): the decision-support insight store. The insight
-- plane lives in strategy-signal-service (it already owns signals/rejections/paper/graduation and
-- reads market-data over REST) as the new Modulith module `insights`. This is I1 FOUNDATIONS —
-- shadow mode: rows + read APIs only, NO delivery (ntfy/Telegram/WS push wait for I3/I4).
--
-- `insights` is insert-mostly but NOT strictly append-only (unlike signal_rejections): status and
-- priority mutate in place (re-evaluation refreshes the OPEN row for a dedupe_key), a nightly
-- hygiene job EXPIREs signal-scoped rows and prunes suppressed-INFO rows > 90 days (§2.5). So the
-- read-only per-schema role gets SELECT/INSERT/UPDATE/DELETE; the service itself connects as `artha`
-- (D10 single-writer). Plain OLTP tables (§9.5: low hundreds of rows/day, same order as
-- signal_rejections) — NOT hypertables.
--
-- Traceability is a HARD constraint (§9.4): every row carries a non-empty `evidence` pointer, the
-- engine git SHA (`engine_version`, the H9 #617 mechanism), the resolved-config hash (`config_hash`),
-- and a `data_trust` verdict — so a GET /{id} is self-contained for audit and identical inputs
-- replay byte-identical (the golden-vector culture extended to the rule engine, §10.1).
CREATE TABLE insights (
    id              UUID PRIMARY KEY,
    generated_at    TIMESTAMPTZ NOT NULL,          -- timestamptz (in-container now() is UTC, never ::date)
    type            TEXT NOT NULL,                  -- SIGNAL_PRIORITY | DATA_TRUST | RISK_HEAT (I1); catalog grows additively
    severity        TEXT NOT NULL CHECK (severity IN ('INFO','NOTICE','WARN','CRITICAL')),
    scope           TEXT NOT NULL,                  -- 'signal:<id>' | 'strategy:<id>' | 'book:<book>' | 'underlying:<exch>:<name>' | 'market' | 'dataops'
    title           TEXT NOT NULL,                  -- one line, FE-renderable as-is (templated, no free text)
    explanation     TEXT NOT NULL,                  -- 2-4 sentences, numbers inline (templated)
    evidence        JSONB NOT NULL,                 -- [{label,value,source:{endpoint,params,asOf},ref}] — MANDATORY, non-empty
    priority        NUMERIC(5,2),                   -- 0-100, only for prioritizable types (§3); null otherwise / when BLOCKED
    priority_detail JSONB,                          -- component breakdown (§3.4 explain contract)
    data_trust      TEXT NOT NULL CHECK (data_trust IN ('OK','DEGRADED','BLOCKED')),
    trust_reasons   TEXT[],
    dedupe_key      TEXT NOT NULL,                  -- type + scope + semantic key (e.g. 'SIGNAL_PRIORITY:signal:42')
    cooldown_until  TIMESTAMPTZ,                    -- suppression window end for this dedupe_key
    suppressed      BOOLEAN NOT NULL DEFAULT FALSE, -- generated but muted (§2.5) — still STORED (auditable "why didn't it warn me")
    status          TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACKED','ACTED','DISMISSED','EXPIRED')),
    expires_at      TIMESTAMPTZ,                    -- signal-scoped insights expire with the signal
    engine_version  TEXT NOT NULL,                  -- git SHA of the generating jar (H9 #617); 'dev' when git.properties absent
    config_hash     TEXT NOT NULL                   -- SHA-256 of the resolved artha.insights.* subtree in force
);

-- The open-feed read pattern (Focus/feed newest-first, filtered by status+severity).
CREATE INDEX idx_insights_open ON insights (status, severity, generated_at DESC);
-- Dedupe: at most one OPEN insight per dedupe_key (regeneration UPSERTs the OPEN row, §2.5.1).
CREATE UNIQUE INDEX uq_insights_dedupe ON insights (dedupe_key) WHERE status = 'OPEN';
-- Signal-scope + type lookups (SIGNAL_PRIORITY re-eval, per-scope filters).
CREATE INDEX idx_insights_type_scope ON insights (type, scope);

-- One row per one-click action taken on an insight (§2.4). Append-only: the writer only INSERTs.
-- In I1 (shadow mode) only the display-side actions land (ACK/DISMISS); PROPOSE executions (TAKE_SIGNAL
-- etc.) arrive with the I3 /act path.
CREATE TABLE insight_actions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    insight_id  UUID NOT NULL REFERENCES insights(id),
    action      TEXT NOT NULL,                      -- 'ACK','DISMISS','TAKE_SIGNAL','OPEN_TICKET','ADD_WATCHLIST','JOURNAL_DRAFT_ACCEPT','MUTE_TYPE'
    target_ref  JSONB,                              -- what the click called, e.g. {signalId, orderId}
    acted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor       TEXT NOT NULL DEFAULT 'owner'
);
CREATE INDEX idx_insight_actions_insight ON insight_actions (insight_id, acted_at DESC);

-- Owner feedback closing the usefulness loop (§2.4 / §10.2). One row per insight (PK insight_id);
-- re-feedback UPSERTs the verdict.
CREATE TABLE insight_feedback (
    insight_id  UUID PRIMARY KEY REFERENCES insights(id),
    verdict     TEXT NOT NULL CHECK (verdict IN ('USEFUL','NOT_USEFUL')),
    note        TEXT,
    given_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The service connects as `artha` (D10 single-writer). The read-only per-schema role gets the
-- mutation surface each table actually uses: insights mutates+prunes (S/I/U/D); the audit + feedback
-- tables never DELETE (feedback UPSERTs → U).
GRANT SELECT, INSERT, UPDATE, DELETE ON insights TO ay_strategy;
GRANT SELECT, INSERT ON insight_actions TO ay_strategy;
GRANT SELECT, INSERT, UPDATE ON insight_feedback TO ay_strategy;
