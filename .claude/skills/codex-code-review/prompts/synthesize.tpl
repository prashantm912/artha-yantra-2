The review loop for `{{TARGET}}` has converged across multiple turns. Each turn's state file held
only that turn's delta. Produce ONE consolidated review of the FINAL state of the change:

1. Re-run `git status -s` / `git diff HEAD` for the current change set.
2. Summarize what the change does (2-4 sentences).
3. List the findings that were raised and how each resolved (fixed / accepted-as-is with rationale /
   deferred), grouped by severity from `.claude/skills/codex/checklist.md`.
4. State the final verdict against the approval gate.

This is the promotable record — no new findings unless the current tree genuinely still violates a
Critical/Major gate. End with the sentinel on its own line:
  PROMOTION_READY

{{EXTRA_PROMPT}}
