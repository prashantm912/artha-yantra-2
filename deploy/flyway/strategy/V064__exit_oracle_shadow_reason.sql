-- H40: give `exit_oracle_shadow` the discriminator its two sibling surfaces already have.
--
-- WHAT WAS WRONG. V056's shadow_* columns are NULLABLE on purpose and a NULL means "the
-- counterfactual could not be evaluated", never "it would not fire" — V056's own header says so.
-- What no column recorded is WHY it could not be evaluated. `SentimentLevelShadow.Reason` has FIVE
-- constants, and FOUR of them each explain a null: NO_OI_CONTEXT, MONTHLY_EXPIRY_SUPPRESSED,
-- LEVEL_UNAVAILABLE and SIDE_UNRESOLVED (the fifth, COMPUTED, is the populated case). The
-- distinction matters: one of the four is BY DESIGN and the other three are worth a look, and the
-- table renders them identically. `ExitOracleShadowWriter.record` was already HANDED that value — it takes a
-- SentimentLevelShadow, whose `reason` is non-null by construction — and dropped it on the floor
-- because there was nowhere to put it. `shadow_verdict_known` does not substitute: it records a
-- DIFFERENT fact (a counterfactual was/was not produced), so every one of the four causes can sit
-- behind a single `shadow_verdict_known = false`.
--
-- WHY IT MATTERS, MEASURED. On 2026-08-25 — an NSE monthly index expiry — the entry-side surface
-- wrote 1,478 rows carrying four nulls after 14 populated sessions, and two experienced readers
-- independently built a "self-contradicting row" theory out of it; one filed it as a live
-- regression. The behaviour was CORRECT and the record simply could not say so. The entry surfaces
-- gained `reason` that day. THIS table did not, so the same wall stands here: on a monthly expiry
-- every exit-oracle row for the whole session is four nulls with no discriminator.
--
-- ⚠️ A NULL shadow_reason IS A PRE-V064 ROW, NOT A FIFTH CAUSE. This mirrors the "absent key means
-- UNKNOWN/LEGACY" contract on the entry surfaces, and it carries the same obligation: EXCLUDE such
-- rows from a cause breakdown and say how many were excluded, never bucket them. Nothing can WRITE
-- the legacy state — the enum declares no UNKNOWN constant and the record's canonical constructor
-- rejects a null reason — so the absence cannot be forged by a new row. Rows written before this
-- migration deploys are genuinely unrecoverable: the cause was never persisted anywhere, so there
-- is nothing to backfill from and no backfill is attempted.
--
-- ⚠️ TEXT, NOT AN ENUM TYPE, and that is the same choice V056 made for every other categorical
-- column here (held_side, shadow_blocking_rail, ...). A Postgres enum would need its own migration
-- to gain a constant, which is exactly the friction that leaves a measurement surface behind.
--
-- MEASUREMENT ONLY, unchanged: nothing in this table is read by any trading decision, and adding a
-- column cannot change whether the oracle exits.
ALTER TABLE exit_oracle_shadow ADD COLUMN shadow_reason TEXT;

COMMENT ON COLUMN exit_oracle_shadow.shadow_reason IS
  'SentimentLevelShadow.Reason name() — WHY this row''s counterfactual is or is not computable (COMPUTED / NO_OI_CONTEXT / MONTHLY_EXPIRY_SUPPRESSED / LEVEL_UNAVAILABLE / SIDE_UNRESOLVED). ⚠️ NULL means the row predates V064, NOT a fifth cause: exclude such rows from a cause breakdown and report how many were excluded. Populated on every row written after V064 deploys.';
