-- Phase 3 (§12.9): the Track-2 scalper side-channel. A scalper signal is EVALUATED
-- on the index future (the row's exchange/tradingsymbol) but TRADED as the option the
-- confluence seam picked at entry — recorded here, NOT inside the frozen C-2.6
-- score_breakdown JSONB (a scalper key inside score_breakdown is a parity FAIL, same
-- rule as suggested_qty). tradeable_* is the option the order/paper layer buys;
-- scalper_detail carries the confluence aggregate, chosen-side, |delta|, IV, and the
-- per-dot breakdown for explainability. All three are NULL for every non-scalper signal.
ALTER TABLE signals ADD COLUMN tradeable_exchange       TEXT;
ALTER TABLE signals ADD COLUMN tradeable_tradingsymbol  TEXT;
ALTER TABLE signals ADD COLUMN scalper_detail           JSONB;
