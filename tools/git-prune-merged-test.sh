#!/usr/bin/env bash
# Regression test for tools/git-prune-merged.sh.
#
# Builds a throwaway repo with a real remote and exercises the cases that matter. The one that
# matters MOST is `squash-merged`: the original check (`git rev-list --count origin/main..$branch`)
# passes every other case here and fails only that one — which is the only merge mode this repo uses,
# so the script was inert in practice while looking correct in principle.
#
# Usage:  bash tools/git-prune-merged-test.sh
set -euo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/git-prune-merged.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
check() { # check <description> <expected: KEPT|GONE> <branch> <output-file>
  local desc="$1" want="$2" branch="$3" out="$4"
  local got="GONE"
  git -C "$TMP/local" show-ref --quiet --verify "refs/heads/$branch" && got="KEPT"
  if [ "$got" = "$want" ]; then
    printf 'ok   %-46s %s\n' "$desc" "$want"
    pass=$((pass + 1))
  else
    printf 'FAIL %-46s want=%s got=%s\n' "$desc" "$want" "$got"
    echo "--- script output ---"; cat "$out"; echo "---------------------"
    fail=$((fail + 1))
  fi
}

git init --quiet --bare "$TMP/remote"
git clone --quiet "$TMP/remote" "$TMP/local"
cd "$TMP/local"
git config user.email t@t.t
git config user.name t
git config commit.gpgsign false
# `gh` must not influence the outcome here — these branches have no GitHub PRs behind them, and on a
# machine where gh is authenticated against a real repo it could answer for an unrelated branch name.
export PATH="$TMP/nogh:$PATH"
mkdir -p "$TMP/nogh"
printf '#!/bin/sh\nexit 1\n' > "$TMP/nogh/gh"; chmod +x "$TMP/nogh/gh"

echo base > base.txt && git add -A && git commit --quiet -m base && git push --quiet -u origin HEAD:main
git branch --quiet -M main 2>/dev/null || true

# --- the case the old check got wrong: SQUASH-merged, multi-commit -------------------------------
git checkout --quiet -b squash-merged
echo one > feature.txt && git add -A && git commit --quiet -m one
echo two >> feature.txt && git add -A && git commit --quiet -m two
git push --quiet -u origin squash-merged
git checkout --quiet main
git merge --quiet --squash squash-merged && git commit --quiet -m 'squashed (#1)'

# --- genuinely unmerged: pushed, remote later deleted, work never landed --------------------------
git checkout --quiet -b never-merged main
echo unmerged > orphan.txt && git add -A && git commit --quiet -m orphan
git push --quiet -u origin never-merged

# --- merged, but main later moved ON THE SAME PATH (exercises the gh rescue / safe keep) ----------
git checkout --quiet -b touched-again main
echo v1 > shared.txt && git add -A && git commit --quiet -m v1
git push --quiet -u origin touched-again
git checkout --quiet main
git merge --quiet --squash touched-again && git commit --quiet -m 'squashed shared (#2)'
echo v2 > shared.txt && git add -A && git commit --quiet -m 'later change to the same file'

# --- never pushed: local WIP, must be untouchable -------------------------------------------------
git checkout --quiet -b local-wip main
echo wip > wip.txt && git add -A && git commit --quiet -m wip

git checkout --quiet main
git push --quiet origin main
# GitHub's delete_branch_on_merge equivalent: drop the remote heads, then let the script see [gone].
git push --quiet origin --delete squash-merged never-merged touched-again

echo "=== --dry must remove NOTHING ==="
bash "$SCRIPT" --dry > "$TMP/dry.out" 2>&1 || true
check "--dry leaves the squash-merged branch"  KEPT squash-merged "$TMP/dry.out"
check "--dry leaves the unmerged branch"       KEPT never-merged  "$TMP/dry.out"

echo
echo "=== real run ==="
bash "$SCRIPT" > "$TMP/run.out" 2>&1 || true
cat "$TMP/run.out"
echo
check "squash-merged branch is REMOVED"        GONE squash-merged "$TMP/run.out"
check "genuinely unmerged branch SURVIVES"     KEPT never-merged  "$TMP/run.out"
check "never-pushed local WIP SURVIVES"        KEPT local-wip     "$TMP/run.out"
check "main SURVIVES"                          KEPT main          "$TMP/run.out"
# Merged, but a later commit on main touched the same path. With gh unavailable the content test
# cannot tell this from unmerged work, so the SAFE answer is to keep it. Asserted deliberately: this
# pins the failure direction (a false keep, never a false delete).
check "merged-then-overwritten is kept (safe)" KEPT touched-again "$TMP/run.out"

echo
echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ]
