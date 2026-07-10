-- Research-fidelity audit P0-2 / finding R1: backtest run rows carried NO engine CODE identity.
-- `backtest_runs.engine_version` holds the STRATEGY version ("strategy-engine/<v>"), NOT the git SHA
-- of the engine jar — so two runs of the same strategy across a code deploy were indistinguishable,
-- poisoning longitudinal optimizer comparability (an EVO-E0 HARD gate).
--
-- Stamp the git SHA + build image identity (from the jar's baked git.properties / build-info.properties,
-- H9 #617) on BOTH the job row (the service that CREATED the job) and the run row (the worker that
-- EXECUTED it) — the two can differ across a deploy between submit and execution, which is exactly the
-- distinction we want durable. Additive + nullable: old rows stay NULL, and a mock/test jar built
-- without git.properties writes NULL (fail-soft) rather than failing. Existing table GRANTs cover all
-- columns, so no new grant is needed.
ALTER TABLE jobs          ADD COLUMN engine_sha   TEXT;
ALTER TABLE jobs          ADD COLUMN engine_image TEXT;
ALTER TABLE backtest_runs ADD COLUMN engine_sha   TEXT;
ALTER TABLE backtest_runs ADD COLUMN engine_image TEXT;
