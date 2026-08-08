#!/usr/bin/env bash
# Runbook hygiene guard for the TWO skill trees — .claude/skills/ (Claude sessions) and
# .agents/skills/ (Codex sessions). These files load into every delegated builder session, so a
# stale instruction here is not documentation rot: it is an agent being actively told to do the
# wrong thing. Two independent assertions, both cheap pure-file checks.
#
# WHY A SCRIPT IN AN UNGATED CI JOB, NOT A JUNIT TEST (revised 2026-08-04 after review):
# the first cut lived in edge-gateway as SkillTreeSyncTest and rode `build-test
# (strategy-gateway)`. That was wrong on two counts. (1) COST/REACH — it needed a bespoke
# ci-java classifier entry to route .claude|.agents/skills/** to the shard, then paid the full
# ~6-minute Testcontainers shard for a markdown edit, and still ran only on PRs the classifier
# routed there. This job runs on EVERY PR in ~10s and needs no classifier entry. (2) SELF-DEFEAT,
# the decisive one — a guard whose entire purpose is "never merge past a red check with --admin"
# must not itself be the slowest, flakiest required context, because that is precisely how this
# repo has historically ended up admin-merging past red checks. `it-naming-guard` in ci-java.yml
# is the same shape and the direct precedent.
#
# ⚠️ TRADE RECORDED HONESTLY: `build-test (strategy-gateway)` IS one of main's eight required
# contexts and this job is NOT, so today this reports red rather than blocking the merge.
# Promoting `runbook-hygiene` to a required context is a one-call owner decision and would make
# the end state strictly better than the original (fast, every-PR, blocking, and — unlike the
# Testcontainers shard — incapable of flaking). Until then the guard is visible, not binding.
#
# Usage: check_runbook_hygiene.sh [repo_root]   (defaults to the enclosing git worktree root)
set -euo pipefail

repo_root="${1:-$(git rev-parse --show-toplevel)}"
cd "$repo_root"

CLAUDE_TREE=".claude/skills"
AGENTS_TREE=".agents/skills"
failures=0

fail() {
  echo "::error::$*"
  echo "FAIL: $*"
  failures=$((failures + 1))
}

# ---------------------------------------------------------------------------
# ASSERTION 1 — the nine already-identical shared pairs stay identical.
#
# ⚠️ THE TREES ARE NOT BYTE-IDENTICAL AND MUST NOT BE ASSERTED TO BE. Measured 2026-08-04:
# .claude/skills has 24 skills, .agents/skills 14; of the 14 shared, 9 are byte-identical and 5
# differ, and those differences MIX deliberate with stale. Deliberate: each tree names its own
# host doc (AGENTS.md vs CLAUDE.md) and its own scheduled-task owner; .claude carries Claude-only
# sections that would be circular in the Codex tree (the Fable plan gate; a cross-vendor gate
# whose whole instruction is "call codex-code-review, the OPPOSITE vendor"). Stale: .agents is
# missing owner revisions that landed weeks ago. A blanket byte-comparison would assert something
# FALSE and be disabled within a week — and a disabled guard reads as coverage.
#
# ⚠️ SO STATE THE INVARIANT HONESTLY (review MEDIUM-1): this asserts "the nine pairs that are
# ALREADY identical stay identical", NOT "shared pairs stay in sync unless allowlisted". The
# allowlist is NAME-scoped, not diff-scoped — once a name is listed, ANY future one-tree-only edit
# to it is invisible, including a bad one. And coverage is inversely correlated with where drift
# actually happens: 21 of 37 historical edits to shared runbooks landed in the five allowlisted
# files. It is still worth having — it is free, its failure message states the remedy verbatim,
# and it makes "these two copies may now say different things" a visible diff decision rather
# than something that happens by silence. It is NOT the assertion carrying the weight here;
# assertion 2 is.
#
# EXACT, not a floor: a listed pair that becomes byte-identical ALSO fails, so the list cannot
# accumulate dead entries and quietly stop meaning anything.
# ⚠️ Consequence worth knowing before you start (review LOW-2): back-porting stale .agents
# content — the fix 4 of these 5 pairs genuinely need — makes the pair identical and therefore
# REDDENS this check until you also delete the name from KNOWN_DIVERGENT. That is the intended
# ratchet direction, not a bug; do both edits in the one PR.
KNOWN_DIVERGENT="daily-ops delegated-ship fable-method session-analysis ship-a-change"

shared_skills="$(
  comm -12 \
    <(find "$CLAUDE_TREE" -mindepth 2 -maxdepth 2 -name SKILL.md -printf '%h\n' | xargs -rn1 basename | sort) \
    <(find "$AGENTS_TREE" -mindepth 2 -maxdepth 2 -name SKILL.md -printf '%h\n' | xargs -rn1 basename | sort)
)"

if [ -z "$shared_skills" ]; then
  fail "No skill name is present in BOTH $CLAUDE_TREE and $AGENTS_TREE. Either a tree moved or this guard is comparing nothing — silently comparing an empty set is the failure mode it exists to prevent."
fi

for skill in $shared_skills; do
  declared=false
  case " $KNOWN_DIVERGENT " in *" $skill "*) declared=true ;; esac

  if cmp -s "$CLAUDE_TREE/$skill/SKILL.md" "$AGENTS_TREE/$skill/SKILL.md"; then
    if [ "$declared" = true ]; then
      fail "'$skill' is listed in KNOWN_DIVERGENT but the two copies are now byte-identical. Remove it from the list in $(basename "$0"): an allowlist that keeps entries after they stop applying stops describing anything and licenses real drift later. (If you just back-ported content to sync them, this is expected — delete the name.)"
    fi
  else
    if [ "$declared" = false ]; then
      fail "'$skill' now differs between the two runbook trees but is not declared divergent. These files load into agent sessions, so a correction that reaches only one tree leaves the other actively instructing agents wrongly. Either mirror the edit into BOTH copies, or — if the difference is deliberately vendor-specific — add '$skill' to KNOWN_DIVERGENT in $(basename "$0") with the reason."
    fi
  fi
done

# ---------------------------------------------------------------------------
# ASSERTION 2 — no runbook prescribes an admin merge.
#
# This is the assertion that carries the weight. `--admin` bypasses ALL NINE of main's required
# contexts, and `lock_branch` — the branch-level lock that was the only reason it was ever needed
# — was set false on 2026-07-26.
#
# ⚠️ The count MOVES — it was six on 2026-08-01, eight earlier on 2026-08-04, and nine once this
# very guard was promoted. Verified nine on 2026-08-08 against the protection API:
# contracts · e2e · gitleaks · build-test (market-data|backtest|strategy-gateway) ·
# optimizer-lint-test · margin-lint-test · runbook-hygiene. **Re-read the API before quoting a
# number here** — CLAUDE.md says the same, and this comment was stale at EIGHT for four days.
#
# ⚠️ SCOPE IS ALL RUNBOOK TEXT, NOT JUST SKILL.md (review MAJOR-2). The first cut filtered to
# `SKILL.md` and was therefore blind to 29 tracked files, including .claude/skills/codex/ROUTING.md,
# checklist.md and twelve prompts/*.tpl templates that are substituted and sent to Codex VERBATIM —
# a prescription in a .tpl reaches a builder just as surely as one in a SKILL.md. Proven by planting
# the literal command in ROUTING.md and watching the SKILL.md-scoped version pass green.
#
# ⚠️ BOTH SPELLINGS ARE BANNED (review MAJOR-1). The first cut banned only the copy-pasteable
# `gh pr merge … --admin` command and justified excluding the bare "admin-merge" prose spelling as
# a deliberate scope limit — while SIX prescriptive prose instances were live in the tree at that
# moment (delegated-ship ×2 and session-analysis ×1, in BOTH trees). The justification named only
# the cautionary instance and omitted the prescriptive ones, so the limit read as considered when
# it was in fact the same cross-file sweep miss the guard exists to stop. There is no reliable
# regex that separates "do this" from "do not do this", so both spellings fail and the legitimate
# cautionary sentence carries an explicit marker instead.
#
# ⚠️ `.*` and NOT `[^\n]*`. In POSIX ERE a bracket expression has no escape processing, so
# `[^\n]` means "not a backslash and not the letter n" — which silently fails to match
# `gh pr merge <n> --squash --admin`, because `<n>` contains an n. That typo made three of this
# guard's own red-proofs pass green; the self-test caught it. grep is line-based regardless, so
# `.*` cannot span lines and is both simpler and correct.
ADMIN_PATTERNS='gh pr merge.*--admin|admin-merge'

# ESCAPE HATCH (review MEDIUM-3). Ledger row task_b3b59719: "a hard fail with no override gets the
# workflow disabled the first time it is inconvenient." A line carrying this marker is exempt. The
# marker is deliberately ugly and greppable so every exemption is a visible, reviewable decision —
# and in markdown an HTML comment renders invisibly:
#     <!-- runbook-hygiene:allow quoting the retracted instruction -->
# The concrete case this exists for: the natural way to document a REMOVAL is to quote the removed
# command verbatim, which necessarily matches.
ALLOW_MARKER='runbook-hygiene:allow'

# FILE-LEVEL EXEMPTION, one entry, and it is by DESIGN not oversight. CLAUDE.md's merge rule ends
# "hotfix/* keeps its own fast-lane", and ci-contracts + ci-review-verdict exempt the same branch
# prefix. The whole skill IS the admin-merge fast lane, so marking each line would be noise. A
# live-incident lane that cannot merge past an unrelated red check is not a fast lane.
EXEMPT_SKILLS="hotfix"

# ⚠️ `git ls-files`, NOT `find` (fixed 2026-08-08). `find` walks the WORKING TREE, so it also sees
# gitignored local state that CI never checks out — measured: it picked up three untracked files
# under .claude/skills/comprehensive-audit/state/ (context-pack.md, findings-ledger.md,
# shard-scopes.md, all matched by that dir's own .gitignore `*`), and one of them carries a
# CAUTIONARY sentence about admin-merge. Net effect: this guard FAILED locally while passing in CI.
# That split is corrosive — a check that reds on your machine and greens on the runner trains people
# to ignore it, which is exactly the disable-pressure the escape hatch above exists to relieve.
# 55 files under `find` vs 52 under `git ls-files`; the three-file delta IS the bug.
runbook_files="$(
  git ls-files -- \
    "$CLAUDE_TREE/*.md" "$CLAUDE_TREE/*.tpl" \
    "$AGENTS_TREE/*.md" "$AGENTS_TREE/*.tpl" | sort
)"

for file in $runbook_files; do
  # skills/<name>/... — field 3 of the path is the skill directory.
  skill="$(cut -d/ -f3 <<<"$file")"
  case " $EXEMPT_SKILLS " in *" $skill "*) continue ;; esac

  # Offending lines = match a banned spelling AND do not carry the marker.
  hits="$(grep -nE "$ADMIN_PATTERNS" "$file" 2>/dev/null | grep -v "$ALLOW_MARKER" || true)"
  if [ -n "$hits" ]; then
    while IFS= read -r hit; do
      [ -n "$hit" ] || continue
      fail "$file:${hit%%:*} prescribes an admin merge: $(cut -d: -f2- <<<"$hit" | sed 's/^[0-9]*://' | cut -c1-120)"
    done <<<"$hits"
  fi
done

if [ "$failures" -gt 0 ]; then
  cat >&2 <<'MSG'

--------------------------------------------------------------------------------
runbook-hygiene FAILED.

`--admin` bypasses ALL NINE of main's required contexts. `lock_branch` -- the only
reason it was ever needed -- was set false on 2026-07-26. Reaching for it now means a
required check is genuinely red, and reading that check is the job.

Only the `hotfix` skill is exempt (CLAUDE.md: "hotfix/* keeps its own fast-lane").
To quote the retracted instruction while documenting its removal, put the marker on
that line:  <!-- runbook-hygiene:allow <why> -->
--------------------------------------------------------------------------------
MSG
  exit 1
fi

echo "runbook-hygiene: all assertions passed ($(wc -w <<<"$shared_skills") shared pairs, $(wc -l <<<"$runbook_files") runbook files scanned)"
