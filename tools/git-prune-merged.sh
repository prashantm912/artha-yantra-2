#!/usr/bin/env bash
# Prune everything a merged PR leaves behind locally.
#
# GitHub now deletes the head branch on merge (`delete_branch_on_merge`), so the REMOTE half is
# automatic. Nothing server-side can touch this machine, though, and the local leftovers are what
# actually bite: `gh pr merge --delete-branch` FAILS its local step when a worktree holds the branch
# ("fatal: '<branch>' is already used by worktree at ..."), while the server-side merge has already
# landed. That happened twice on 2026-07-26 alone.
#
# Safety: this only removes branches whose upstream is GONE (i.e. GitHub deleted it after a merge)
# and whose worktree is CLEAN. It never touches `main`, never touches a branch with no upstream (a
# local-only WIP branch), never touches a branch whose remote still exists, never force-removes a
# dirty worktree, and never removes a branch whose own changes are not yet in main.
# `worktree-agent-abb02bf43adbb895d` — the parked, genuinely-unmerged swing catch-up — survives on
# every count.
#
# Regression test: tools/git-prune-merged-test.sh (see branch_work_is_in_main below for the bug it
# pins — this section was INERT until 2026-07-30).
#
# Usage:  bash tools/git-prune-merged.sh          # report + prune
#         bash tools/git-prune-merged.sh --dry    # report only
set -euo pipefail

DRY=""
[ "${1:-}" = "--dry" ] && DRY=1

cd "$(git rev-parse --show-toplevel)"
git fetch --prune --quiet

removed_wt=0
removed_br=0
kept=()

# 1) Worktrees whose branch no longer exists on the remote AND whose tree is clean.
while read -r wt_path; do
  [ -z "$wt_path" ] && continue
  [ "$wt_path" = "$(git rev-parse --show-toplevel)" ] && continue
  branch="$(git -C "$wt_path" symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
  [ -z "$branch" ] && continue
  [ "$branch" = "main" ] && continue
  # Upstream still present? then the PR is not merged (or never existed) — leave it alone.
  if git show-ref --quiet "refs/remotes/origin/$branch"; then
    kept+=("$branch (remote branch still exists)")
    continue
  fi
  # No upstream configured at all = never pushed = local WIP. Never auto-delete someone's WIP.
  if ! git config --get "branch.$branch.remote" >/dev/null 2>&1; then
    kept+=("$branch (never pushed — local WIP)")
    continue
  fi
  if [ -n "$(git -C "$wt_path" status --porcelain)" ]; then
    kept+=("$branch (worktree DIRTY — resolve by hand)")
    continue
  fi
  echo "worktree  -> $wt_path  [$branch]"
  [ -z "$DRY" ] && git worktree remove "$wt_path" && removed_wt=$((removed_wt + 1))
done < <(git worktree list --porcelain | awk '/^worktree /{print $2}')

[ -z "$DRY" ] && git worktree prune

# Is this branch's work already in main?
#
# ⚠️ NOT `git rev-list --count origin/main..$branch`. That was the original check and it made this
# whole section INERT: a SQUASH-merge creates a brand-new commit on main, so the branch's own commits
# are never ancestors of main and the count is ALWAYS > 0. CLAUDE.md mandates squash-merge only, so
# the check vetoed 100% of candidates — verified 2026-07-30, when PRs #1129/#1130/#1131/#1132 all
# merged, all four local branches went `[gone]`, and the script still reported "removed 0 branch(es)".
#
# Two other tempting tests are also wrong, both checked on those same four branches:
#   - `git diff origin/main..$branch` (two-dot) being empty is NOT a merged-test. Three of the four
#     had a NON-empty two-dot diff purely because they were BEHIND main, cut before later PRs landed.
#     It conflates "behind main" with "has unmerged content".
#   - `git cherry` / patch-id matching cannot work either: a squash collapses N commits into one, so
#     no individual commit's patch-id ever matches.
#
# What actually holds: restrict the comparison to the paths THIS branch touched, then ask whether
# main's content for those paths already equals the branch's. A squash puts identical content on
# main, so the diff is empty however many commits it collapsed, and however far main has moved on
# OTHER files. Genuinely-unmerged work differs on its own paths and is kept.
#
# Failure mode is deliberately one-directional: if a LATER commit on main also touched one of those
# paths, the diff is non-empty and we keep a branch that was in fact merged. A false "keep" costs one
# manual `git branch -D`; a false "delete" costs work. `gh` below recovers most of those anyway.
branch_work_is_in_main() {
  local branch="$1" base paths
  base="$(git merge-base origin/main "$branch" 2>/dev/null)" || return 1
  # Paths the branch itself changed, NUL-separated so spaces/quotes in filenames survive.
  mapfile -d '' -t paths < <(git diff --name-only -z "$base" "$branch" -- 2>/dev/null)
  # A branch that changed nothing relative to the merge base has no work to lose.
  [ "${#paths[@]}" -eq 0 ] && return 0
  git diff --quiet origin/main "$branch" -- "${paths[@]}" 2>/dev/null
}

# GitHub is authoritative and cheap; it also rescues the false "keep" above. Only consulted when the
# content test was inconclusive, so the common path stays offline and network-free.
gh_says_merged() {
  local branch="$1"
  command -v gh >/dev/null 2>&1 || return 1
  [ -n "$(gh pr list --head "$branch" --state merged --json number --jq '.[0].number' 2>/dev/null)" ]
}

# 2) Local branches whose upstream is gone (git marks these "[gone]").
#    A branch with NO upstream has an EMPTY %(upstream:track), so this filter already excludes
#    never-pushed local WIP — that protection lives here, not in the merge test.
while read -r branch; do
  [ -z "$branch" ] && continue
  [ "$branch" = "main" ] && continue
  if ! branch_work_is_in_main "$branch"; then
    if gh_says_merged "$branch"; then
      echo "branch    -> $branch  (main moved on its paths; GitHub confirms the PR merged)"
    else
      ahead="$(git rev-list --count "origin/main..$branch" 2>/dev/null || echo '?')"
      kept+=("$branch ($ahead commit(s) ahead; its changes are NOT in main — not merged)")
      continue
    fi
  fi
  echo "branch    -> $branch"
  [ -z "$DRY" ] && git branch -D "$branch" >/dev/null && removed_br=$((removed_br + 1))
done < <(git for-each-ref --format='%(refname:short) %(upstream:track)' refs/heads \
           | awk '$2 == "[gone]" {print $1}')

echo
if [ -n "$DRY" ]; then
  echo "dry run — nothing removed"
else
  echo "removed $removed_wt worktree(s), $removed_br branch(es)"
fi
if [ ${#kept[@]} -gt 0 ]; then
  echo "kept:"
  printf '  - %s\n' "${kept[@]}"
fi
