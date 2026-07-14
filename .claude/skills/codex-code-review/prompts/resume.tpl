The code change for `{{TARGET}}` has been updated since your previous review. Re-run `git status -s`
and `git diff HEAD` (the same working-tree-vs-last-commit view) to see the current state, then
produce an INCREMENTAL review:

1. For each prior finding: quote it briefly, then state addressed / not addressed / partially
   addressed with the `file:line` that resolved (or didn't).
2. Flag any NEW issue the edits introduced — re-checking against every relevant section of
   `.claude/skills/codex/checklist.md` (the same single-source checklist as turn 1).

## Implementer notes

Findings explicitly marked as intentional decisions, environment limitations, or with a
doc-update to-do should NOT be re-flagged.

{{IMPLEMENTER_NOTES}}

Apply the same severity tags and the same approval gate from `checklist.md`. Do not re-read the
skill file — `checklist.md` is the only criteria file you need.

End with the same tag on its own line:
  APPROVED
  REQUEST_CHANGES
  NEEDS_REWORK
