Continue the implementation session for `{{TARGET}}`.

The working tree may have changed since your last turn — the Architect reviews your work and may
adjust it directly. Run `git status -s` and `git diff HEAD` to resync first; treat the current tree
as authoritative and do NOT revert the Architect's adjustments.

## New instructions

{{EXTRA_PROMPT}}

Same rules as before: stay within the stated scope; respect the parity firewall and Modulith
boundaries; typed records not `Map`; no test authoring unless asked; no commit / push / version /
changelog / `.env` / secrets / applied-migration edits — the Architect owns all of that.

Same RECEIPT format (files changed, diff summary, labeled claims, deviations, open-doubts [mandatory],
leftovers, lint/build status), ending with exactly one tag on its own line:
  IMPLEMENTATION_COMPLETE
  IMPLEMENTATION_PARTIAL
