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
-- number.
--
-- ⚠️ BOTH TABLES ARE ALTERED, AND THAT IS LOAD-BEARING RATHER THAN TIDINESS.
-- `instruments_staging` was created at V002 as `LIKE instruments INCLUDING DEFAULTS`, which is a
-- ONE-TIME SNAPSHOT of the column set — later ALTERs to `instruments` do not propagate to it. The
-- two tables sat at 17 columns each before this migration and must stay in step, because the sync
-- swap joins them.
--
-- Verified rather than assumed: `InstrumentRepository.stageDump` and the swap both use EXPLICIT
-- column lists, not `SELECT *`, so a column-set divergence could not silently shift values between
-- columns. The `SELECT *` reads in that class map by NAME.

-- ⚠️ Fail fast rather than queueing. The ALTER below takes ACCESS EXCLUSIVE on the live identity
-- table, and Flyway holds it for the whole transaction. Without a timeout this migration would wait
-- behind any long-running reader and hold every later reader behind ITSELF. 15 s is far above the
-- measured cost of this migration and far below anything an operator would tolerate silently.
SET LOCAL lock_timeout = '15s';

ALTER TABLE instruments
    ADD COLUMN upstox_instrument_key TEXT,
    ADD COLUMN kite_last_seen_at TIMESTAMPTZ,
    ADD COLUMN upstox_last_seen_at TIMESTAMPTZ;

ALTER TABLE instruments_staging
    ADD COLUMN upstox_instrument_key TEXT,
    ADD COLUMN kite_last_seen_at TIMESTAMPTZ,
    ADD COLUMN upstox_last_seen_at TIMESTAMPTZ;

-- Per-source provenance. `last_seen_at` cannot serve this: it records that SOME writer asserted the
-- row, and once two sources write the table it can no longer say which. Per-source tombstone
-- scoping in U-A2 depends on the distinction — a source may only deactivate rows IT previously
-- asserted, or the first Upstox sync silently deactivates every Kite-only row, including all the
-- `-BE` twins, reigniting H29/H36 at scale.
COMMENT ON COLUMN instruments.kite_last_seen_at IS
    'When the Kite instrument dump last asserted this row. Backfilled at V060 from last_seen_at for '
    'Kite-origin rows ONLY (see the migration for the predicate and why it is not unconditional); '
    'FROZEN until U-A2 wires the write into the sync upsert. A frozen value is still a TRUE '
    'statement about the past — but NULL here means "no Kite assertion on record", which is a real '
    'state, not an unknown. Do not read it as a liveness signal before U-A2 lands.';

COMMENT ON COLUMN instruments.upstox_last_seen_at IS
    'When the Upstox instrument master last asserted this row. Deliberately left NULL by V060: '
    'Upstox has never asserted anything, so NULL is the honest value and the correct starting '
    'point for per-source tombstone scoping.';

COMMENT ON COLUMN instruments.upstox_instrument_key IS
    'Upstox instrument_key (e.g. NSE_EQ|<ISIN>, or the opaque numeric F&O token). Source-local '
    'handle, NOT identity — identity remains (exchange, tradingsymbol) in Kite grammar. Populated '
    'by U-A2. ⚠️ NOT UNIQUE, deliberately: an exchange rename tombstones the old row and admits the '
    'successor as a new PK, and Upstox equity keys are ISIN-addressed, so a same-ISIN rename '
    'legitimately leaves TWO rows sharing one key. See the migration tail.';

-- ⚠️ SCOPED, NOT UNCONDITIONAL — and the first cut of this migration got it wrong.
--
-- That cut asserted "instruments is written only by the Kite instrument-dump sync" and backfilled
-- every row. **That premise is FALSE**, and cross-vendor review caught it. There are two other
-- writers, neither of which involves Kite:
--   * `tools/historical-import/ingest.py` inserts inactive placeholders carrying no token, no name
--     and no segment;
--   * `InstrumentRepository.upsertSyntheticCont` writes `SYN-CONT` continuous-futures rows whose
--     own javadoc says they can "never reach a Kite port".
--
-- `computed` live before writing this predicate: the placeholders are **182,487 rows — 58% of the
-- table** — against 134,435 Kite-dump rows and 6 SYN-CONT. An unconditional backfill would have
-- stamped "Kite asserted this" on the majority of the table, corrupting the exact provenance U-A2
-- uses to decide tombstone ownership. That is not a cosmetic error: it is the input to a rule whose
-- failure mode is deactivating every Kite-only row.
--
-- The predicate keeps rows that carry master metadata and drops the two non-Kite writers. Verified
-- against the H29/H36 population specifically, because those are the rows the plan warns must not be
-- mis-scoped: DIACABS, MENONBE and SABEVENTS all carry token + name + segment and are correctly
-- INCLUDED. `computed`: zero rows are tokenless-but-named, so the metadata test does not strand a
-- tokenless Kite row.
UPDATE instruments
   SET kite_last_seen_at = last_seen_at
 WHERE segment IS DISTINCT FROM 'SYN-CONT'
   AND (instrument_token IS NOT NULL OR name IS NOT NULL OR segment IS NOT NULL);

-- ⚠️ NO UNIQUE INDEX ON `upstox_instrument_key`, AND THE REASON IS A LANDMINE AVOIDED.
--
-- The first cut created a partial UNIQUE index (NULLs excluded), justified as making U-A2's named
-- worst case — a wrong synthesised symbol producing "a duplicate row, not an error" — fail loudly.
-- Cross-vendor review struck it on two independent grounds, both confirmed against the docs:
--
--   1. It would make a SOURCE HANDLE into a permanent IDENTITY. `docs/symbol-normalization.md`
--      records that NSE renames ~59 tickers a year, and on the day it happens `instruments`
--      tombstones the old row and admits the successor as a BRAND-NEW PK. Upstox equity keys are
--      ISIN-addressed (`NSE_EQ|<ISIN>`), and a same-ISIN rename is documented explicitly
--      (TATAMOTORS→TMPV keeps its ISIN). So after any such rename the retired row and its successor
--      legitimately hold the SAME Upstox key — and a unique index would fail the authoritative sync,
--      roughly 59 times a year, for correct data.
--   2. It could not have protected the phase it was justified by anyway: U-A2's shadow soak writes
--      NOTHING authoritative into this table, so the index would never see shadow output.
--
-- The check it was reaching for is real and belongs in U-A2's shadow diff, as a detector for
-- one-key-to-many-CANONICAL mappings — which can distinguish a rename lineage from a synthesis bug.
-- A unique constraint cannot.
