# Coverage Debt Ledger

Deliberately-deferred test coverage — one row per genuinely hard-to-cover path, so a gap is
**recorded, never silently hidden** (no coverage-ignore comments, no config exclusions, no lowering
the JaCoCo/coverage gate). Rows are added by the `write-tests` skill's mock-pain tripwire
(`.claude/skills/write-tests/SKILL.md`): if the mock setup grows longer than the test's assertions and
no seam applies, add a row here and move on instead of fighting the mock.

**Critical-path floor (overrides this ledger):** behaviour touching money/rounding, deletion,
persistence, parity, or an external request shape MUST keep at least one behavioural test or a manual
integration check — it may NEVER be deferred here. This ledger is for internal-path depth only.

Clear a row when the seam is found, the code is deleted, or the test is added.

| path | why hard | escape plan |
| --- | --- | --- |
| _(none yet)_ | | |
