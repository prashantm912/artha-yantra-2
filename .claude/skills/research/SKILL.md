---
name: research
description: Structured no-production-code spike — investigate a question, feasibility, or tech-eval; produce documented findings + a BUILD/DEFER/SKIP verdict; never ship code
---

# Research

For a question, feasibility study, or technology evaluation that must produce **documented findings and
a verdict, not production code**. Use it before committing to a build when the answer is genuinely
unknown (does the CLI support X? is this data source usable? which approach wins?). It feeds
`fable-method` → a plan (plan-review round) → `delegated-ship`. Not for tasks where the answer is clear
— just build those.

## Rules

- **No production code.** Throwaway probes/spikes go in the scratchpad dir only; the repo tree is not
  touched (a plan doc or memo IS an allowed output).
- **Read-only probes only.** Never mutate live/DB/state to answer a question — read endpoints, SQL
  SELECTs, `docker logs`, `--json` CLI dry-runs. If a probe must run something side-effectful, it goes
  in the scratchpad or a mock stack, never live.
- **Recon-first keeps context lean.** Delegate breadth to read-only Explore agents that return a
  conclusion + `file:line` map, not file dumps — the raw reading stays in the subagent.

## Steps

1. **Frame** the question in 1–2 sentences. If scope/depth is ambiguous, `AskUserQuestion` once
   (what's in/out, how deep). Otherwise proceed.
2. **Pick depth** (state it):
   - *quick* — read the code + 1–2 probes, answer in chat.
   - *standard* — 1–2 Explore agents for breadth + read-only probes; a short findings summary.
   - *deep* — multi-agent recon + a scratchpad feasibility spike + a fresh-thread Opus
     red-team of the conclusion; a written memo.
3. **Investigate.** Recon agents for breadth; read the actual code for the load-bearing bits; run
   read-only probes and record the exact command + result. For a feasibility claim, PROVE it with a
   scratchpad spike (e.g. "does `codex exec resume` retain context?" → run it), don't assert it.
4. **Red-team** (deep, optional otherwise). Spawn a fresh Opus subagent: "Here's my conclusion: … . Red-team
   it."` — a disagreement is a strong signal to surface to the owner.
5. **Synthesize.** Separate what you VERIFIED (cite command/file:line) from what you ASSUMED; list the
   risks and the kill-criteria. Label decision-grade claims computed/sourced/recalled/assumed.
6. **Verdict + output.** End with **BUILD** (feasible, worth it), **DEFER** (owner-gated or blocked —
   name what unblocks it), or **SKIP** (not worth it). For decision-grade work write a memo to
   `docs/superpowers/plans/<yyyy-mm-dd>-<topic>.md` (feasibility folded in as a Phase-0 section, the
   [codex-review-harness-spike](../../../docs/superpowers/plans/2026-07-14-codex-review-harness-spike.md)
   plan is the template); small results stay in chat.
7. **Hand off.** BUILD → `fable-method` → plan → plan-review → `delegated-ship`. DEFER → flip a
   ledger row with the reason (never stop a run to ask; skip + note). Record a durable finding in
   memory if it will matter next session.

## Notes

- The gateway-OOM analysis + Phase-0 feasibility earlier this session IS this skill run ad-hoc: probe
  → prove → verdict → memo. Codify it so the next spike is repeatable, not improvised.
- Owner-judgment questions (a preference, an arming call, a risk that can't be discharged by evidence)
  are NOT research targets — surface them to the owner, don't spike them.
