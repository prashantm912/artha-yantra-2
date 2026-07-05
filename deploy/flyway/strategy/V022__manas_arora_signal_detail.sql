-- The Manas Arora swing side-channel (forks V020 minervini_detail). A fired manas-arora setup signal
-- records its setup detail (setup type, base footprint, pivot) here — OUTSIDE the frozen C-2.6
-- score_breakdown JSONB (a strategy-specific key inside score_breakdown is a parity FAIL, the same rule
-- as scalper_detail V009, suggested_qty, and minervini_detail V020). NULL for every non-manas signal.
ALTER TABLE signals ADD COLUMN manas_arora_detail JSONB;
