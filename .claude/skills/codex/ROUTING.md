# Model routing — single source of truth

Who runs on what, and what to do when a model is unavailable. The skills reference THIS table;
never hardcode a fallback decision inline in a brief or an ad-hoc command.

## The table

| Role | Primary | Auto fallback (bash) | Cross-vendor fallback (manual) |
| --- | --- | --- | --- |
| **Main loop (Architect)** | Fable 5 (owner's `/model` pick) | — | Opus 4.8 via `/model` — owner/summoner choice; all skills are main-loop-model-agnostic |
| **Builder** | Codex `gpt-5.6-sol` via `codex-build` | `gpt-5.6-luna` (harness retries the chain) | **Opus subagent** — Agent tool, `model: "opus"`, `isolation: "worktree"`, the SAME brief content + receipt contract (the `delegated-ship` path) |
| **Code review** | Codex `gpt-5.6-sol` via `codex-code-review` | `gpt-5.6-luna` | **Opus subagent, fresh thread** — writer≠reviewer is preserved (different thread) but cross-VENDOR is lost: label the review "same-vendor" in the record |
| **Plan review** | Codex via `codex-plan-review` | `gpt-5.6-luna` | same as code review |
| **Advisory ask** | Codex via `codex-ask` | `gpt-5.6-luna` | Opus subagent (plain Agent question) — or skip; ask is never load-bearing |
| **Recon / Explore** | Claude Explore agents | — | no Codex dependency; always available |

## Review router — reviewer is the opposite vendor of the builder

Every **non-trivial** change gets a *structured* review loop from the OTHER vendor (not just the
Architect's audit); trivial/docs-only changes skip it (say so instead of running a round). This is the
NORMAL path — when the opposite vendor is in an outage (see the ladder below), fall back to same-vendor
review and RECORD the cross-vendor loss in the review. Pick the review skill by who built it:

| Builder | Vendor | Review with | Reviewer vendor |
| --- | --- | --- | --- |
| `codex-build` | OpenAI (Codex) | **`claude-review`** (Opus subagent) | Anthropic ✓ opposite |
| `ship-a-change` / Architect-direct | Anthropic (Claude) | **`codex-code-review`** (Codex) | OpenAI ✓ opposite |
| `delegated-ship` (Opus subagent) | Anthropic (Opus) | **`codex-code-review`** (Codex) | OpenAI ✓ opposite |

Both reviewers judge against the SAME `.claude/skills/codex/checklist.md` and emit the same
`APPROVED`/`REQUEST_CHANGES`/`NEEDS_REWORK` tags, so the gate is identical whichever vendor reviews.
**Plan review is already cross-vendor** (Claude writes the plan, `codex-plan-review` = Codex).

**Canonical order for any change (one sequence everywhere):** testing gate → cross-vendor review loop
(converge to `APPROVED`) → **Architect receipt audit = the final gate** → tiered promotion (owner
approval for money/arming/HOLD). The audit is on top of the review, never the cross-vendor layer itself.

## Detection → response ladder

1. **Model at capacity** (codex runs but errors `at capacity` / `overloaded` / `temporarily
   unavailable`): the harness (`start.sh`/`resume.sh`) **auto-retries the chain**
   `$CODEX_MODEL` → `$CODEX_FALLBACK_MODELS` and echoes which model actually served. Override per
   run: `CODEX_MODEL=... CODEX_FALLBACK_MODELS="m1 m2" ...`; disable: `CODEX_FALLBACK_MODELS=""`.
2. **Whole chain at capacity**: transient — retry after a delay (10–30 min has worked), OR go
   cross-vendor now if the queue shouldn't wait (autonomous runs: go now, don't stall).
3. **Codex CLI down entirely** (command missing, auth broken, hangs): straight to the
   cross-vendor column. No probe needed — fallback at failure time, not preflight time.
4. **Codex died MID-BUILD with files on disk**: salvage — keep the worktree edits, the Architect
   finishes verify/commit personally (proven: #817). Don't re-run from scratch and clobber.

## Invariants that survive ANY routing

- The **receipt contract** (labeled claims + mandatory open-doubts) and the **audit** are
  model-independent — an Opus builder owes the same receipt as Codex.
- **Writer ≠ reviewer** minimum = a different thread; different vendor is preferred, and its loss
  must be stated in the review record.
- The **Architect keeps merge/deploy/ledger/memory** no matter which model built or reviewed.
- Parity/money changes gate on byte-identical Golden+Parity rerun by the Architect — never on any
  builder's or reviewer's say-so, whichever model it was.
