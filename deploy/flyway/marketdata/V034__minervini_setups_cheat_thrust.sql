-- Phase-9 Track-B (Minervini SEPA): two geometry columns the live swing engine seeds per-symbol.
-- cheat_pivot = the cheat-area pause high (an earlier/lower breakout trigger than the final VCP
-- pivot — the C of the A-B-C-D turn, §6.3); it drives the `cheat_3c` setup's CHEAT_PIVOT context.
-- thrust = the power-play precondition (§6.4/§6.5): a prior ≈+100% move in <8 weeks before the base,
-- gating the `power_play` setup's THRUST flag. Both are NULL/false on non-VCP rows (like pivot).
ALTER TABLE minervini_setups
    ADD COLUMN cheat_pivot NUMERIC(18,4),                       -- earlier cheat-pause high (§6.3)
    ADD COLUMN thrust      BOOLEAN NOT NULL DEFAULT FALSE;      -- prior +100%/<8wk thrust flag (§6.4)
