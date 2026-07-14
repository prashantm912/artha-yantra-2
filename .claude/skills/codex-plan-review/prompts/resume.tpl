The plan `{{TARGET}}` has been revised since your previous review. Re-read it and produce an
INCREMENTAL review:

1. For each prior finding: quote it briefly, state addressed / not addressed / partially addressed,
   pointing at the plan section that resolved (or didn't) it.
2. Flag any NEW issue the revision introduced — re-checking soundness, invariant conformance
   (`.claude/skills/codex/checklist.md`), completeness, and risk.

## Author notes

Findings marked as intentional decisions or with a rationale should not be re-flagged.

{{IMPLEMENTER_NOTES}}

End with the same tag on its own line:
  APPROVED
  REQUEST_CHANGES
  NEEDS_REWORK
