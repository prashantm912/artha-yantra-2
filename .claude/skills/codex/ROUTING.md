# Model routing — single source of truth

Who runs on what, and what to do when a model is unavailable. The skills reference THIS table;
never hardcode a fallback decision inline in a brief or an ad-hoc command.

## The table (owner revision 2026-07-25 — Opus 5 orchestrates, Fable 5 plans)

| Role | Primary | Auto fallback (bash) | Cross-vendor / capacity fallback (manual) |
| --- | --- | --- | --- |
| **Main loop (Architect)** | **Opus 5** — orchestrator + FINAL GATE; never builds substantive code | — | all skills are main-loop-model-agnostic; another model in the seat still owes every gate below |
| **Planner** | **Fable 5** — Agent tool, `model: "fable"`; produces the detailed technical implementation plan | — | **Opus 5** writes the plan itself on a capacity error (don't stall the item) |
| **Builder (draft)** | Codex `gpt-5.6-luna` via `codex-build` — fast/cheap bulk build | `gpt-5.6-sol` (harness retries the chain) | **Sonnet 5** subagent for MECHANICAL work (docs, tests, renames, config, FE slices) · **Opus subagent** (`model: "opus"`, `isolation: "worktree"`) whenever the surface is parity / money / exit doctrine / migrations / the live engine. Same brief content + receipt contract either way (the `delegated-ship` path). |
| **Builder (refine)** | Codex `gpt-5.6-sol` — reviews+FIXES luna's draft on a fresh `-refine` thread (write access) | — | Architect finishes the fix during self-review if sol is down |
| **Code review** | opposite vendor of the builder — see the router below | `gpt-5.6-luna` when the reviewer is Codex | **fresh-thread same-vendor** — writer≠reviewer is preserved (different thread) but cross-VENDOR is lost: label the review "same-vendor" in the record |
| **Plan review** | Codex via `codex-plan-review` (the planner is Fable = Anthropic, so this stays cross-vendor) | `gpt-5.6-luna` | Opus subagent, fresh thread — same-vendor, record the loss |
| **Advisory ask** | Codex via `codex-ask` | `gpt-5.6-luna` | Opus subagent (plain Agent question) — or skip; ask is never load-bearing |
| **Recon / Explore** | Claude Explore agents | — | no Codex dependency; always available |

**When does the Planner stage run?** Only for real items: **HOLD tier, migrations, money/parity
surfaces, or >~3 files / multi-PR.** A 1-file chip's plan costs more than the chip — go straight to
a self-contained brief. This is a size gate, not an optional step: an item that meets the bar gets a
written plan before any code.

**Why Fable plans instead of arbitrating at the end** (owner decision 2026-07-25): a second reasoning
style pays most where it lands *before* code exists — decomposition, design forks, and catching
"this is already built" (3 of 10 items in one past batch were). As a final arbiter that perspective
arrives where change is most expensive. Honest cost of the trade: a Codex-built item now sees Codex →
Opus review → Opus audit (two distinct models at the end, where the old chain had three). The
mitigation is that the review round stays mandatory and on a fresh thread — do not also collapse it.

**codex-build pipeline (three perspectives, luna ≠ sol ≠ Opus):** luna DRAFTS (fast/cheap) → sol
REVIEWS+FIXES the draft (fresh Codex thread, write access) → Architect testing gate → **Opus**
cross-vendor review (`claude-review`, per the router below) → **Architect receipt audit = final gate**
→ tiered promotion. Same canonical order as everything else (below); the sol refine is a Codex-internal
quality pass BEFORE handoff, the Opus review is the cross-vendor gate — both run, not alternatives.

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
| `delegated-ship` (Sonnet 5, mechanical) | Anthropic (Sonnet) | **`codex-code-review`** (Codex) | OpenAI ✓ opposite |

⚠️ **A Sonnet build exists only because Codex was unavailable — so its cross-vendor reviewer usually
is too.** Expect the same-vendor fallback (fresh-thread Opus review) on that path, record the loss,
and prefer to hold a parity/money item until Codex is back rather than ship it with both the builder
AND the reviewer degraded. That combination is the weakest configuration this table can produce.

Both reviewers judge against the SAME `.claude/skills/codex/checklist.md` and emit the same
`APPROVED`/`REQUEST_CHANGES`/`NEEDS_REWORK` tags, so the gate is identical whichever vendor reviews.
**Plan review is already cross-vendor** (Claude writes the plan, `codex-plan-review` = Codex).

**Canonical order for any change (one sequence everywhere):** orient + classify tier → *(plan, if the
item meets the size gate)* → build → testing gate **run by the Architect, not the builder** →
cross-vendor review loop (converge to `APPROVED`) → **Architect receipt audit = the final gate** →
tiered promotion (owner approval for money/arming/HOLD) → deploy + live-verify → ledger.

**The review and the audit are two gates. Never merge them into one.** "The orchestrator reviews it
itself" is only ever *audit on top of a review round*. Proof from 2026-07-25: the cross-vendor review
found a live Critical that CI structurally could not (`premium_pct` exit rules resolved against the
INDEX entry price → a one-bar force-exit on every held-PE take), and a later round caught a foreign
hunk the Architect had already read past during audit. Build → audit, with no review round, would
have shipped both.

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
