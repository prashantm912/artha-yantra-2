-- H8 + M38 identity-row rename (PR #1208, docs/superpowers/plans/2026-07-02-remaining-items.md
-- E4 row). Owner-executed against the LIVE `artha` database, single authorized transaction.
--
-- Why this exists: MinerviniStrategySeeder.create() only ever writes name/description on a
-- strategy's FIRST seed; RegistryService.update() (the resyncConfig re-seed path every already-
-- existing strategy takes) re-syncs ONLY tags to the identity row, never name/description; and
-- publish() only moves the version pointer. So the corrected YAML text in #1208
-- (services/strategy-signal-service/src/main/resources/minervini-strategies/minervini-cheat-3c.yaml
-- and .../minervini-primary-base.yaml) never reaches the already-seeded live `strategy.strategies`
-- rows on its own. RegistryService.java:437 (list/detail) and SwingBatchEngine.java:1074
-- (strategy.name() on every emitted SwingStrategy) read that identity row directly.
--
-- Every `name`/`description` literal below is copied verbatim from the two YAML files above (the
-- YAML is authoritative; diff before executing). Both slugs update in ONE transaction — a partial
-- apply (one strategy renamed, the other not) is impossible. Re-running this script after a
-- successful run is a detectable no-op: each UPDATE's WHERE clause pins the OLD name, so a second
-- run reports "UPDATE 0" for both statements instead of silently re-applying.
--
-- `strategy.strategies.name` carries a UNIQUE constraint (V002__strategies_versions_audit.sql) — if
-- either target name below already belongs to a different row, the UPDATE will 23505/abort and the
-- whole transaction rolls back (safe failure, nothing partially applied). Not expected: both target
-- names are new strings not used elsewhere in the registry as of #1208.
--
-- Validated pre-merge against a disposable Postgres 16 container seeded with the two OLD rows: both
-- UPDATEs apply exactly once; the post-commit name/description match the YAMLs byte-for-byte
-- (scripted diff); a second run of this same script reports UPDATE 0 / UPDATE 0.

BEGIN;

-- 1. PRECHECK — current state before touching anything. Expected (per the live read already taken):
--      minervini-cheat-3c     | Minervini Cheat (3-C)
--      minervini-primary-base | Minervini Primary Base
--    If either row is already renamed, the guarded UPDATE below for that slug will affect 0 rows.
SELECT slug, name, description, updated_at
FROM strategy.strategies
WHERE slug IN ('minervini-cheat-3c', 'minervini-primary-base')
ORDER BY slug;

-- 2a. H8 — minervini-cheat-3c: name + description, verbatim from the YAML.
UPDATE strategy.strategies
SET name = 'Minervini VCP Early-Pivot (3-C)',
    description = $cheat_desc$SEPA swing long: buy the breakout above a synthetic early-pivot proxy (an interpolated point inside the final VCP contraction, not a detected level) on expanding volume, within a base whose funnel snapshot required close > 50-day MA — NOT the doctrine's below-50-day-MA "Low Cheat". Protect with an 8% stop then trail the 50-day MA. Universe = the daily SEPA funnel.$cheat_desc$,
    updated_at = now()
WHERE slug = 'minervini-cheat-3c'
  AND name = 'Minervini Cheat (3-C)'
RETURNING slug, name, description, updated_at;

-- 2b. M38 — minervini-primary-base: name + description, verbatim from the YAML.
UPDATE strategy.strategies
SET name = 'Minervini 52-Week Breakout (Primary Base proxy)',
    description = $pb_desc$SEPA swing long: buy a fresh 52-week-high breakout on expanding volume — a generic breakout, not gated on IPO/young-leader status (no IPO-date data available); protect with an 8% stop then trail the 50-day MA. Universe = the daily SEPA funnel.$pb_desc$,
    updated_at = now()
WHERE slug = 'minervini-primary-base'
  AND name = 'Minervini Primary Base'
RETURNING slug, name, description, updated_at;

COMMIT;

-- 5. POST-VERIFY — re-read both rows AFTER commit (durable, not just the RETURNING echo above).
--    Diff this output's name/description against the two YAML files' `name:`/`description:` fields
--    byte-for-byte before marking the ledger row DONE.
SELECT slug, name, description, updated_at
FROM strategy.strategies
WHERE slug IN ('minervini-cheat-3c', 'minervini-primary-base')
ORDER BY slug;
