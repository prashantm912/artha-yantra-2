-- H26 unit U-A1 — the identity columns the Upstox-primary migration needs, shipped DARK.
--
-- Nothing reads or writes these yet. U-A2 brings the Upstox master sync, the grammar-synthesis
-- shadow diff, surrogate tokens and per-source tombstone scoping; this migration only makes the
-- columns exist so that unit is a code change rather than a code-plus-schema change on the live
-- identity table every join goes through.
--
-- ⚠️ THE PLAN CALLED THIS "V059" AND THAT NUMBER IS ALREADY TAKEN in this lineage
-- (V059__correct_prune_options_snapshots_comment.sql). Applied migrations are checksum-locked, so
-- a second V059 here would fail `flyway validate` and block EVERY future marketdata migration, not
-- just this one. Caught at STEP 0 by listing the lineage head rather than trusting the plan's
-- number. The plan text is stale, not wrong in substance — the columns really were unbuilt
-- (verified against information_schema before writing this).
--
-- ⚠️ BOTH TABLES ARE ALTERED, AND THAT IS LOAD-BEARING RATHER THAN TIDINESS.
-- `instruments_staging` was created at V002 as `LIKE instruments INCLUDING DEFAULTS`, which is a
-- ONE-TIME SNAPSHOT of the column set — later ALTERs to `instruments` do not propagate to it. The
-- two tables sat at 17 columns each before this migration and must stay in step, because the sync
-- swap joins them.
--
-- Note in mitigation, verified rather than assumed: `InstrumentRepository.stageDump` and the swap
-- both use EXPLICIT column lists, not `SELECT *`, so a column-set divergence would not silently
-- shift values between columns. The `SELECT *` reads elsewhere in that class map by NAME. So this
-- migration cannot corrupt the existing sync; keeping the tables aligned is about U-A2 being able
-- to stage the new columns at all.

ALTER TABLE instruments
    ADD COLUMN upstox_instrument_key TEXT,
    ADD COLUMN kite_last_seen_at TIMESTAMPTZ,
    ADD COLUMN upstox_last_seen_at TIMESTAMPTZ;

ALTER TABLE instruments_staging
    ADD COLUMN upstox_instrument_key TEXT,
    ADD COLUMN kite_last_seen_at TIMESTAMPTZ,
    ADD COLUMN upstox_last_seen_at TIMESTAMPTZ;

-- Per-source provenance. `last_seen_at` cannot serve this: it records that SOME sync asserted the
-- row, and once two sources write the table it can no longer say which. Per-source tombstone
-- scoping in U-A2 depends on the distinction — a source may only deactivate rows IT previously
-- asserted, or the first Upstox sync silently deactivates every Kite-only row, including all the
-- `-BE` twins, reigniting H29/H36 at scale.
COMMENT ON COLUMN instruments.kite_last_seen_at IS
    'When the Kite instrument dump last asserted this row. Backfilled from last_seen_at at V060 '
    'because every existing row was Kite-asserted; FROZEN until U-A2 wires the write into the sync '
    'upsert. A frozen value here is still a TRUE statement about the past, which is why the '
    'backfill is preferred to leaving it NULL — NULL would assert that Kite never saw these rows, '
    'which is false. Do not read it as a liveness signal before U-A2 lands.';

COMMENT ON COLUMN instruments.upstox_last_seen_at IS
    'When the Upstox instrument master last asserted this row. Deliberately left NULL by V060: '
    'Upstox has never asserted anything, so NULL is the honest value and the correct starting '
    'point for per-source tombstone scoping.';

COMMENT ON COLUMN instruments.upstox_instrument_key IS
    'Upstox instrument_key (e.g. NSE_EQ|<ISIN>, or the opaque numeric F&O token). Source-local '
    'handle, NOT identity — identity remains (exchange, tradingsymbol) in Kite grammar. Populated '
    'by U-A2.';

-- TRUE as of this migration: `instruments` is written only by the Kite instrument-dump sync, so
-- every existing row was asserted by Kite. Vanished rows correctly carry the moment they vanished,
-- which is what last_seen_at already holds for them.
--
-- ⚠️ **COST**, stated because it is not obvious from the statement: the three ADD COLUMNs are
-- metadata-only (no DEFAULT, so PG rewrites nothing and they are instant), but this UPDATE touches
-- EVERY row and is therefore a full table rewrite holding ACCESS EXCLUSIVE for its duration. The
-- live tick path resolves through this table, so this migration must land in a POST-CLOSE deploy
-- window like any other -- which the house 16:30 IST floor already enforces.
--
-- No row count is recorded here on purpose, following V057's precedent in this same lineage: a
-- migration comment is checksum-locked forever and the instruments population moves daily with the
-- expiry cycle, so freezing one reading would preserve a number that was never stable. Measure it
-- before deploying if the lock duration matters to you.
UPDATE instruments SET kite_last_seen_at = last_seen_at;

-- ⚠️ BEYOND THE PLAN'S LITERAL SPEC, and flagged as such so review can strike it.
--
-- The plan's named worst case for U-A2 is that a plausible-but-wrong synthesised tradingsymbol
-- produces "a DUPLICATE ROW, not an error" — silent identity corruption in the table everything
-- joins through. Two rows claiming the same Upstox key is exactly that failure, and an Upstox
-- instrument_key addresses one instrument, so uniqueness is a real invariant rather than a
-- convenience.
--
-- With the column NULL everywhere this index cannot fail on existing data, and during U-A2's
-- shadow phase a violation surfaces as a loud write failure while the sync is still writing
-- nothing authoritative — which is the cheapest possible moment to find it. Partial so the
-- overwhelming majority of rows (NULL key) cost nothing.
CREATE UNIQUE INDEX instruments_upstox_key_idx
    ON instruments (upstox_instrument_key)
    WHERE upstox_instrument_key IS NOT NULL;
