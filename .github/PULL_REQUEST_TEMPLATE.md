# Summary

<!-- What does this PR deliver? Link the phase / stage section it implements. -->

## Self-review checklist (A.12 / plan §9.12)

The CI tiers are the mechanical reviewer — a red tier blocks merge. This
checklist covers what the machine can't:

- [ ] **BigDecimal for all prices** (no float/double anywhere money-typed)?
- [ ] **IST-normalized times** (`TIMESTAMPTZ` / `Asia/Kolkata`, `+05:30` serialization)?
- [ ] **Stable `exchange + tradingsymbol` keys** (no numeric Kite tokens as identity)?
- [ ] **Works under `SPRING_PROFILES_ACTIVE=mock` with zero credentials**?
- [ ] **Flyway migration** for any DB change (never an edit to an applied `V###` file)?
- [ ] **Golden-vector tests updated** if engine behavior changed?
- [ ] **Error envelope (D8)** `{ code, message, details }` on new endpoints?
- [ ] **`mem_limit` impact considered** (new/changed containers stay within the ADR RAM table)?

<!-- Changes touching libs/strategy-engine/ additionally pass the golden-vector
     parity gate (Stage C onward). -->
