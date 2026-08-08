#!/usr/bin/env bash
# Drives check_runbook_hygiene.sh against SYNTHETIC runbook trees and asserts it passes exactly
# when it should. Runs in the same CI job, BEFORE the real check, so a guard edit that would let a
# prescription through fails here instead of silently reporting green.
#
# Every case below is a red-proof made permanent. The two the review reproduced by hand — a
# one-character divergence, and the literal pre-fix `gh pr merge … --admin` body — are cases 2 and
# 4. The two the review found MISSING from the first cut are cases 5 (bare "admin-merge" prose,
# which was live in six places while being documented as an accepted limit) and 6 (a non-SKILL.md
# runbook file, which was outside the walk entirely). Both fail here before the fix and pass after.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
guard="$script_dir/check_runbook_hygiene.sh"
failures=0
tmp_root="$(mktemp -d)"
trap 'rm -rf "$tmp_root"' EXIT

# build_tree <dir> — a minimal valid fixture: one shared skill, identical in both trees.
build_tree() {
  local root="$1"
  mkdir -p "$root/.claude/skills/live-verify" "$root/.agents/skills/live-verify"
  mkdir -p "$root/.claude/skills/ship-a-change" "$root/.agents/skills/ship-a-change"
  mkdir -p "$root/.claude/skills/hotfix" "$root/.claude/skills/codex"
  echo "shared runbook body" | tee "$root/.claude/skills/live-verify/SKILL.md" \
    >"$root/.agents/skills/live-verify/SKILL.md"
  # ship-a-change is in the real KNOWN_DIVERGENT list, so the BASELINE must have it divergent —
  # otherwise the "declared but identical" rule correctly reddens the clean fixture. (Caught by
  # this test on its own first run.)
  echo "claude-side body" >"$root/.claude/skills/ship-a-change/SKILL.md"
  echo "codex-side body" >"$root/.agents/skills/ship-a-change/SKILL.md"
  # hotfix is the one file-level exemption; it legitimately prescribes the command.
  echo 'run `gh pr merge <#> --squash --admin` past unrelated flakes' \
    >"$root/.claude/skills/hotfix/SKILL.md"
  echo "routing notes" >"$root/.claude/skills/codex/ROUTING.md"
}

# expect <name> <expected exit: pass|fail> <setup fn body...>
expect() {
  local name="$1" want="$2"; shift 2
  local root="$tmp_root/$(tr -cd '[:alnum:]' <<<"$name")"
  rm -rf "$root"; mkdir -p "$root"
  build_tree "$root"
  ( cd "$root" && eval "$@" )
  # The guard scans `git ls-files`, not the working tree (see its ASSERTION 2 comment), so a fixture
  # has to BE a git repo with its files staged or the guard sees nothing and dies
  # `fatal: not a git repository`. Staging happens AFTER the setup body, since setup adds files.
  # `add` is enough — `ls-files` reads the index, and committing would need a user identity.
  # A test that wants a file INVISIBLE to the guard simply leaves it unstaged (see the untracked
  # case below).
  ( cd "$root" && git init -q . && git add -A -f . 2>/dev/null; true )
  ( cd "$root" && eval "${UNSTAGE_CMD:-true}" )
  local got=pass
  bash "$guard" "$root" >/dev/null 2>&1 || got=fail
  if [ "$got" != "$want" ]; then
    echo "FAIL [$name]: expected the guard to $want, it $got"
    echo "      ---- guard output ----"
    bash "$guard" "$root" 2>&1 | sed 's/^/      /'
    failures=$((failures + 1))
  fi
}

# 1 — the clean baseline must PASS, or every red-proof below proves nothing.
expect "clean tree passes" pass "true"

# 2 — RED-PROOF A: a one-character divergence in an undeclared pair.
expect "one-character divergence in an undeclared pair fails" fail \
  "printf 'x' >> .claude/skills/live-verify/SKILL.md"

# 3 — the allowlist is EXACT: a declared pair that is byte-identical must ALSO fail, so the list
#     cannot accumulate dead entries. ship-a-change is in KNOWN_DIVERGENT; syncing it must redden.
expect "declared-but-identical pair fails" fail \
  "cp .claude/skills/ship-a-change/SKILL.md .agents/skills/ship-a-change/SKILL.md"

# 4 — RED-PROOF B: the literal pre-fix command body.
expect "gh pr merge --admin command fails" fail \
  "printf 'on green, \`gh pr merge <n> --squash --admin\` (solo repo)\n' >> .claude/skills/live-verify/SKILL.md
   printf 'on green, \`gh pr merge <n> --squash --admin\` (solo repo)\n' >> .agents/skills/live-verify/SKILL.md"

# 5 — MAJOR-1: the bare prose spelling. Six of these were live while the first cut documented
#     excluding them as a considered limit.
expect "bare admin-merge prose spelling fails" fail \
  "printf 'unreachable diff -> admin-merge once every other gate is green\n' >> .claude/skills/live-verify/SKILL.md
   printf 'unreachable diff -> admin-merge once every other gate is green\n' >> .agents/skills/live-verify/SKILL.md"

# 6 — MAJOR-2: a NON-SKILL.md runbook file. ROUTING.md and the prompts/*.tpl templates are
#     substituted and sent to Codex verbatim; the first cut's filename filter could not see them.
expect "prescription in ROUTING.md fails" fail \
  "printf 'on green run \`gh pr merge <n> --squash --admin\`\n' >> .claude/skills/codex/ROUTING.md"

expect "prescription in a .tpl template fails" fail \
  "mkdir -p .claude/skills/codex-build/prompts
   printf 'then admin-merge the PR\n' > .claude/skills/codex-build/prompts/build.tpl"

# 6b — the `find` -> `git ls-files` fix (2026-08-08). An UNTRACKED file carrying the banned pattern
#      must be INVISIBLE, because CI never checks it out. This is the exact live case: three
#      gitignored files under .claude/skills/comprehensive-audit/state/ made the guard FAIL locally
#      while it PASSED in CI, and one of them held a merely cautionary sentence. A guard that reds on
#      your machine and greens on the runner is one people learn to ignore.
#      ⚠️ Note the pattern here is a REAL violation — the test proves invisibility comes from being
#      untracked, not from the text being harmless. Paired with 6c so "invisible" cannot silently
#      become "blind".
UNSTAGE_CMD="git rm -q --cached .claude/skills/comprehensive-audit/state/context-pack.md" \
expect "untracked runbook carrying the banned pattern is IGNORED" pass \
  "mkdir -p .claude/skills/comprehensive-audit/state
   printf 'on green run \`gh pr merge <n> --squash --admin\`\n' > .claude/skills/comprehensive-audit/state/context-pack.md"

# 6c — the other half: the SAME file, TRACKED, must still fail. Without this, 6b alone would also
#      pass if the guard had simply gone blind to that directory.
expect "the same file TRACKED still fails" fail \
  "mkdir -p .claude/skills/comprehensive-audit/state
   printf 'on green run \`gh pr merge <n> --squash --admin\`\n' > .claude/skills/comprehensive-audit/state/context-pack.md"

# 7 — MEDIUM-3: the escape hatch works, on BOTH spellings. Without it, documenting a removal by
#     quoting the removed command is impossible.
expect "marker exempts a quoted command" pass \
  "printf 'was \`gh pr merge <n> --squash --admin\` <!-- runbook-hygiene:allow quotes the retracted line -->\n' >> .claude/skills/live-verify/SKILL.md
   printf 'was \`gh pr merge <n> --squash --admin\` <!-- runbook-hygiene:allow quotes the retracted line -->\n' >> .agents/skills/live-verify/SKILL.md"

expect "marker exempts a cautionary prose mention" pass \
  "printf 'do not reflexively admin-merge a known flake <!-- runbook-hygiene:allow cautionary -->\n' >> .claude/skills/live-verify/SKILL.md
   printf 'do not reflexively admin-merge a known flake <!-- runbook-hygiene:allow cautionary -->\n' >> .agents/skills/live-verify/SKILL.md"

# 8 — the hotfix file-level exemption holds (its fixture body carries the literal command).
expect "hotfix stays exempt" pass "true"

# 9 — the exemption is scoped to hotfix ALONE and does not leak to a neighbour.
expect "exemption does not leak to another skill" fail \
  "mkdir -p .claude/skills/research
   printf 'run \`gh pr merge <n> --squash --admin\`\n' > .claude/skills/research/SKILL.md"

# 10 — an empty intersection must FAIL, never silently pass.
expect "empty shared intersection fails" fail "rm -rf .agents/skills"

# 11 — RED-PROOF for the review's Critical: assertion 2 must ALSO fail closed. `git ls-files` can
#      SUCCEED and return nothing — a pathspec that stops matching after a tree move, a partial
#      sparse checkout, a rename of CLAUDE_TREE. Before the fix that scanned zero files and printed
#      "all assertions passed". Here the whole index is emptied while the worktree keeps every file,
#      which is exactly the index-vs-worktree split that switching to `git ls-files` created and
#      `find` could not produce. Assertion 1 walks the WORKTREE, so it still sees its pairs and stays
#      green — that is what makes this a proof of assertion 2 specifically, not a restatement of
#      case 10. The fixture is otherwise clean, so pre-fix the guard passes and post-fix it fails on
#      the empty list alone.
UNSTAGE_CMD="git rm -r -q --cached .claude .agents" \
  expect "empty tracked-file list fails closed" fail "true"

# 12 — RED-PROOF for the same Critical, one file at a time: a file that is TRACKED but ABSENT from
#      the checkout (sparse-checkout cone exclusion, partial clone) must fail, not be silently
#      treated as clean — before the fix `grep … 2>/dev/null || true` turned "cannot read it" into
#      "it has no hits". ROUTING.md is deliberately the victim: it is a runbook file assertion 2
#      scans but NOT a SKILL.md, so assertion 1's `find`-based pair comparison never looks at it and
#      cannot redden this case for an unrelated reason. (Deleting a SKILL.md instead would fail via
#      `cmp -s` on a missing file — a mechanically-red proof that never reaches the new assertion.)
UNSTAGE_CMD="rm -f .claude/skills/codex/ROUTING.md" \
  expect "tracked-but-unreadable file fails closed" fail "true"

if [ "$failures" -gt 0 ]; then
  echo "check_runbook_hygiene_test: $failures case(s) failed"
  exit 1
fi
echo "check_runbook_hygiene_test: all assertions passed"
