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
# local-only WIP branch), never touches a branch whose remote still exists, and never force-removes
# a dirty worktree. `worktree-agent-abb02bf43adbb895d` — the parked, genuinely-unmerged swing
# catch-up — survives on both counts.
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

# 2) Local branches whose upstream is gone (git marks these "[gone]").
while read -r branch; do
  [ -z "$branch" ] && continue
  [ "$branch" = "main" ] && continue
  # Refuse if it still holds commits main does not have — a merged PR leaves none.
  ahead="$(git rev-list --count "origin/main..$branch" 2>/dev/null || echo 0)"
  if [ "$ahead" != "0" ]; then
    kept+=("$branch ($ahead commit(s) not on main — NOT merged)")
    continue
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
