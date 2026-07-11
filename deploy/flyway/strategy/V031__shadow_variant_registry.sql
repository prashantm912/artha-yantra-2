-- EVO E3 §11 (strategy-signal) + §3.3.1/§3.3.3: runtime-registrable shadow challenger variants.
--
-- Until now challenger knob-sets were fixed at boot by the env JSON
-- (artha.scalper.shadow-book.variants-json). The evolution engine's LIVE_FIRST loop needs to
-- register and retire challenger variants PER CAMPAIGN without a redeploy, so a campaign can spin
-- shadow books up and down as its evidence accrues. This table is the DURABLE store: runtime
-- registrations survive restarts. The engine's active challenger set = these ENABLED rows MERGED
-- with the env-JSON fallback (DB wins on a name collision), rebuilt at boot and hot-reloaded on
-- every register/retire.
--
-- `spec` is the EXISTING ShadowVariants vocabulary as JSON, NOT a new grammar:
--   {"rails":[{"rail":"volume-floor","disable":true}
--             |{"rail":"volume-floor","threshold":12500,"passWhen":"GTE"}],
--    "compositeThreshold":0.55}
-- (the variant NAME is the separate `name` column; the spec is the body only). Knob-sets outside
-- this vocabulary (e.g. the ENV-plane relative-vol k/N, §2.3) are rejected at registration — they
-- need a vocabulary extension or the counterfactual-replay path, not this table.
--
-- Names are IMMUTABLE once used: shadow_positions.variant references a variant by NAME, so a retire
-- is a SOFT-disable (enabled=false + disabled_at) that keeps the historical book rows interpretable;
-- the UNIQUE(name) forbids ever reusing a name for a different spec. Plain OLTP table (a handful of
-- rows, cap-bounded), NOT a hypertable.
--
-- SCOPE: a registered variant applies GLOBALLY — it re-scores EVERY scalper's rejection stream, not
-- one campaign's strategy. campaign_id is PROVENANCE (which campaign registered it), never an
-- application filter. Campaign isolation = distinct variant names + the per-strategy league read
-- (GET /api/v1/signal-rejections/shadow-summary?strategySlug=...).
CREATE TABLE shadow_variant_registry (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name         TEXT         UNIQUE NOT NULL,             -- the variant tag written to shadow_positions.variant
    campaign_id  UUID,                                     -- SOFT ref to backtest.evo_campaigns (cross-schema, no FK per D10)
    spec         JSONB        NOT NULL,                    -- the ShadowVariants vocabulary body {rails, compositeThreshold}
    enabled      BOOLEAN      NOT NULL DEFAULT true,       -- a retire soft-disables (history stays); DELETE never happens
    created_by   TEXT,                                     -- machine-writer actor (e.g. 'evo:{campaignId}'); null = owner
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),      -- timestamptz — in-container now() is UTC, never ::date
    disabled_at  TIMESTAMPTZ                               -- set when enabled flips false
);

-- The boot / hot-reload read is "all enabled" — a partial index keeps it a tiny scan as retired
-- rows accrue. Newest-first listing (the API GET) is a small full scan (cap-bounded), no index.
CREATE INDEX ix_shadow_variant_registry_enabled ON shadow_variant_registry (enabled) WHERE enabled;

-- SELECT (boot/reload merge + list), INSERT (register), UPDATE (soft-disable). No DELETE grant —
-- history stays. The service connects as `artha` (D10 single-writer); the per-schema role is
-- read/append/update-only, mirroring V016 shadow_positions.
GRANT SELECT, INSERT, UPDATE ON shadow_variant_registry TO ay_strategy;
