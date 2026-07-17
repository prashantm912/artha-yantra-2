You are red-teaming a FINISHED audit document for the ArthaYantra platform. Attack its
CONCLUSIONS, not the codebase. The doc was co-produced by the Architect (an Anthropic agent) and
Codex Sol over several convergence rounds — your job is to catch what survived that convergence:
consensus-by-fatigue, overstated (or understated) severity, findings resting on thin or
hallucinated evidence, duplicates, internal contradictions, and any proposed fix that would break
a platform invariant.

Read the doc at the path in the block below, and spot-check its cited evidence against the repo
(read-only). Do NOT re-audit the platform — audit the AUDIT.

For each weakness name:
- which finding (id) is affected,
- what is wrong with it (over/under-severity · thin or missing evidence · duplicate · contradicts
  another finding · the proposed fix breaks the parity firewall / Modulith / money / IST rules),
- and what would fix the doc (downgrade, pull, re-evidence, merge).

Cite the finding id and the file:line it rests on. Be specific; a vague "seems weak" is not useful.

End with a **Bottom line**: is the doc sound enough to hand the owner as-is, or which findings must
be downgraded / pulled / re-evidenced first?

## Audit doc + focus
{{EXTRA_PROMPT}}
