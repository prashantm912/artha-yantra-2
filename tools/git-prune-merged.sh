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
# ⚠️ "Never pushed" is detected by branch.<b>.merge, NOT by "does branch.<b>.remote exist" (fixed
# 2026-08-01 — the old check made this a false-DELETE hazard, the one failure direction this script
# must never take). Every worktree this repo dispatches is cut with `git worktree add <path> -b
# <branch> origin/main`, which sets branch.<branch>.remote=origin as a SIDE EFFECT of tracking the
# BASE branch — a never-pushed branch already "has a remote configured" and slipped through the old
# guard as if it had been pushed, clean, and ready to delete, sometimes before its agent had written a
# single file. Pushing the branch itself sets branch.<branch>.merge=refs/heads/<branch>; tracking
# origin/main at creation time sets it to refs/heads/main instead — any other value (including unset)
# means never pushed. A worktree whose HEAD still equals origin/main (nothing committed in it yet) is
# kept on that basis alone too, as a second, independent line of defense.
#
# Regression test: tools/git-prune-merged-test.sh (see branch_work_is_in_main below for the
# squash-merge bug it pins — that section was INERT until 2026-07-30 — and the worktree loop above
# for the never-pushed-worktree false-DELETE it pins, fixed 2026-08-01).
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
  # "Never pushed" is branch.<b>.merge != refs/heads/<b> — NOT "no branch.<b>.remote configured".
  # See the file header for why the remote-only check was wrong. Anything other than the branch's
  # own ref (including unset) means never pushed = local WIP. Never auto-delete someone's WIP.
  upstream_merge="$(git config --get "branch.$branch.merge" 2>/dev/null || true)"
  if [ "$upstream_merge" != "refs/heads/$branch" ]; then
    kept+=("$branch (never pushed — local WIP)")
    continue
  fi
  # Second, independent line of defense: a worktree whose HEAD is still exactly origin/main has had
  # zero commits made in it — a freshly-cut worktree and an abandoned empty one are indistinguishable
  # by content, so the safe reading is KEEP regardless of what the tracking config says.
  if [ "$(git -C "$wt_path" rev-parse HEAD 2>/dev/null || true)" = "$(git rev-parse origin/main 2>/dev/null || true)" ]; then
    kept+=("$branch (HEAD == origin/main — nothing has happened here yet)")
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
# $2 is the ref to compare the branch's content AGAINST — origin/main by default, or a specific
# squash-merge commit when gh_says_merged wants to ask "did THIS tip's content land?" without main's
# later commits polluting the answer.
branch_work_is_in_main() {
  local branch="$1" against="${2:-origin/main}" base tmp
  base="$(git merge-base origin/main "$branch" 2>/dev/null)" || return 1

  # ⚠️ --no-renames is LOAD-BEARING, not tidiness (review R1). With rename detection on (the
  # default) a rename collapses to ONE entry and --name-only prints only the DESTINATION. The
  # branch's deletion of the source path would then never be compared: if main happens to have the
  # destination but still carries the source, the scoped diff is quiet and we delete a branch whose
  # deletion never landed. Listing both sides keeps the deletion in the comparison.
  #
  # ⚠️ The producer's exit status is captured EXPLICITLY (review R3). `mapfile < <(cmd)` does NOT
  # propagate cmd's failure — a failed `git diff` yields an empty array, which the length-0 branch
  # below would read as "nothing to lose, therefore merged" and DELETE the branch. Redirect to a
  # file and check the status first, so failure is refusal rather than deletion.
  tmp="$(mktemp)" || return 1
  if ! git diff --no-renames --name-only -z "$base" "$branch" -- >"$tmp" 2>/dev/null; then
    rm -f "$tmp"
    return 1
  fi
  local paths=()
  mapfile -d '' -t paths <"$tmp"
  rm -f "$tmp"

  # Genuinely empty now means what it says: the branch changed nothing since the merge base.
  [ "${#paths[@]}" -eq 0 ] && return 0
  git diff --no-renames --quiet "$against" "$branch" -- "${paths[@]}" 2>/dev/null
}

# GitHub is authoritative and cheap; it also rescues the false "keep" above. Only consulted when the
# content test was inconclusive, so the common path stays offline and network-free.
#
# ⚠️ Matching on branch NAME alone is not enough (review R2). Branch names get reused: merge
# `fix/foo`, then later recreate `fix/foo` locally with new work and push it. `gh pr list --head
# fix/foo --state merged` still returns the OLD merged PR, and we would force-delete the NEW commits.
# Require the merged PR's headRefOid to be the branch's CURRENT tip — that is what makes the answer
# about THIS work rather than about a name.
#
# WARNING: headRefOid ALONE is too strict, and it made this whole fallback DEAD (measured
# 2026-08-11: 25 branches kept, every one of them merged). `main` carries `strict: true`, so a green
# PR goes BEHIND whenever main moves, and the standard fix is a server-side
# `gh api -X PUT repos/{o}/{r}/pulls/<n>/update-branch`. That writes a MERGE COMMIT onto the remote
# head which the local checkout never fetches, so headRefOid advances PAST the local tip and the
# equality test fails for every branch we ever updated that way -- which, on a busy evening, is most
# of them. Measured on #1335: local tip a98885e6, headRefOid 5d30596f, and a98885e6 sits in that
# PR's own commit list one entry below it.
#
# WARNING: commit-list membership ALONE is too LOOSE, and it is a false-DELETE -- the strictly worse
# direction (cross-vendor review, Critical, 2026-08-11). Membership proves the tip is *a* commit of a
# merged PR; it does not prove the tip's TREE is what landed. An INTERMEDIATE commit of that PR
# matches just as well as its final one, and its tree differs. The reviewer demonstrated it on this
# script's own PR: 7dd03e3e is an ancestor of 06d96383 with a different test-file tree, and bare
# membership matches it -- so a checkout stranded there would have been deleted. Same hole for an
# intra-PR revert, a later revert on main, and a reused branch name whose old merged PR happens to
# share a commit.
#
# So the two questions are asked separately, and both must answer yes:
#   IDENTITY -- is this merged PR about THIS branch's work? (tip is the head or one of its commits)
#   PROOF    -- did THIS tip's content actually land? (its touched paths are identical in the PR's
#               own squash-merge commit)
# Comparing against the SQUASH COMMIT rather than current main is what makes the proof usable: it is
# the exact point the work landed, so main's later commits on the same paths -- the very thing that
# made the cheap test inconclusive and sent us here -- cannot pollute the answer.
#
# Every uncertainty KEEPS: no gh, no merged PR, no identity match, a merge commit we do not have
# locally (GC'd or never fetched), or a non-empty scoped diff.
#
# GH_REASON records WHY a keep happened, because "kept" alone is what got this script re-reported as
# broken (2026-08-11, owner): a run that kept a demonstrably squash-merged branch printed
# "its changes are NOT in main -- not merged", which is a claim the script had not established and
# in two of the four reported cases was simply false. Keeping is right; MISDESCRIBING the keep is
# what wastes an investigation. Values:
#   merged              -- identity and proof both hold; the branch is deletable
#   gh-unavailable      -- gh missing, or the API refused to answer (see the rate-limit note below)
#   no-merged-pr        -- gh answered, and there is no merged PR for this head
#   tip-not-in-pr       -- a merged PR exists but this tip is none of its commits (reused branch name)
#   tip-behind-merged-pr-- this tip IS one of its commits but an EARLIER one; its tree never landed
# GH_PR carries the PR number for that case, so the remedy can name a ref that actually exists.
#   merge-commit-absent -- we do not hold the squash commit locally, so the proof cannot be run
GH_REASON=""
GH_PR=""

gh_says_merged() {
  local branch="$1" tip line tmp rc saw_pr=0 saw_identity=0 saw_merge=0
  local num merge head rest oid matched matched_pr=""
  GH_REASON="gh-unavailable"
  GH_PR=""
  command -v gh >/dev/null 2>&1 || return 1
  tip="$(git rev-parse "$branch" 2>/dev/null)" || return 1

  # Run gh into a FILE and check its exit code. Piping into a process substitution discards the
  # status, so an API failure becomes indistinguishable from "no merged PR" -- and it degrades in
  # exactly the direction that looks like a correct answer. Measured 2026-08-11: gh pr list is a
  # GRAPHQL call and that budget is SEPARATE from REST core, so core read 4993/5000 -- "the rate
  # limit is fine" by the obvious check -- while every call failed. A real prune run stopped
  # part-way with no indication.
  tmp="$(mktemp)" || return 1
  gh pr list --head "$branch" --state merged \
     --json number,mergeCommit,headRefOid,commits \
     --jq '.[] | [(.number|tostring), (.mergeCommit.oid // "-"), .headRefOid] + [.commits[].oid] | @tsv' \
     >"$tmp" 2>/dev/null
  rc=$?
  if [ "$rc" -ne 0 ]; then
    rm -f "$tmp"
    GH_UNAVAILABLE=1
    return 1
  fi

  # WARNING: parse the TSV as TSV, and make sure NO column can ever be empty. An earlier cut
  # which made a row with an EMPTY mergeCommit column shift every later field left by one
  # (cross-vendor review, Critical, 2026-08-11). .mergeCommit.oid CAN be null on a merged PR, and
  # then headRefOid slid into the merge-commit slot -- so in the COMMON case where the local tip
  # equals headRefOid, the proof compared the branch against ITSELF, found an empty diff, and
  # authorised a DELETE. Fields are now read tab-delimited and validated before they are trusted.
  #
  # ⚠️ IFS=$'	' is NOT sufficient on its own: bash treats tab as IFS WHITESPACE, so a run of tabs
  # collapses and an empty column still vanishes (cross-vendor review, round 2). That is why the jq
  # above emits "-" rather than "" for a null mergeCommit — a non-whitespace sentinel is the only
  # thing that survives the split. Without it, 8<TAB><TAB><head> read back as merge=<head>, head=""
  # and the branch was kept for the WRONG REASON (tip-not-in-pr rather than merge-commit-absent).
  # It failed safe, and it still misdescribed itself, which is the whole defect class this PR is
  # about.
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    saw_pr=1
    IFS=$'	' read -r num merge head rest <<<"$line"
    # a PR number is digits; an OID is 40 hex. Anything else is a shape we do not understand, and
    # the only safe response to that is to make no claim.
    case "$num" in ""|*[!0-9]*) continue ;; esac
    case "$head" in ""|*[!0-9a-f]*) continue ;; esac
    [ "${#head}" -eq 40 ] || continue

    # IDENTITY -- the tip must be the head, or one of the PR commits.
    if [ "$tip" != "$head" ]; then
      matched=0
      IFS=$'	' read -r -a __commits <<<"${rest:-}"
      for oid in "${__commits[@]}"; do
        [ "$oid" = "$tip" ] && { matched=1; break; }
      done
      [ "$matched" -eq 1 ] || continue
    fi
    saw_identity=1
    # Bind the remedy PR only on an IDENTITY match. Assigning per ROW named whichever merged PR
    # came last, so with a reused branch name or a same-named fork PR the printed
    # "git branch -f ... FETCH_HEAD" would have force-moved the branch onto a DIFFERENT PR.
    matched_pr="$num"

    # PROOF -- we must hold the squash commit, and the tip paths must match it.
    # "-" is the null-mergeCommit sentinel; anything non-hex or wrong-length is a shape we do not
    # understand. Both fall through with saw_merge=0, which renders as merge-commit-absent.
    case "$merge" in "-"|""|*[!0-9a-f]*) continue ;; esac
    [ "${#merge}" -eq 40 ] || continue
    git cat-file -e "${merge}^{commit}" 2>/dev/null || continue
    # A squash-merge commit is BY DEFINITION a commit on main. Requiring that is what actually closes
    # the malformed-row hole: field validation cannot help when every field is a well-formed OID.
    # Concretely, a row whose mergeCommit equals the branch TIP passes every syntactic check and then
    # makes branch_work_is_in_main compare the branch against ITSELF -- an empty diff, and a DELETE.
    # An unmerged tip is not an ancestor of origin/main, so this rejects it; a real squash commit is,
    # so nothing legitimate is lost. (Cross-vendor review Critical, 2026-08-11. The reviewer's exact
    # row shape is not something real gh emits -- a squash mergeCommit is never the head -- so this
    # is a GUARD against malformed input rather than a fix for a demonstrated live hole; the
    # plausible variant, a NULL mergeCommit, was already caught by the empty-string check.)
    git merge-base --is-ancestor "$merge" origin/main 2>/dev/null || continue
    saw_merge=1
    if branch_work_is_in_main "$branch" "$merge"; then
      rm -f "$tmp"
      GH_REASON="merged"
      GH_PR="$num"
      return 0
    fi
  done <"$tmp"
  rm -f "$tmp"

  GH_PR="$matched_pr"
  if [ "$saw_pr" -eq 0 ]; then
    GH_REASON="no-merged-pr"
  elif [ "$saw_identity" -eq 0 ]; then
    GH_REASON="tip-not-in-pr"
  elif [ "$saw_merge" -eq 0 ]; then
    GH_REASON="merge-commit-absent"
  else
    GH_REASON="tip-behind-merged-pr"
  fi
  return 1
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
      case "$GH_REASON" in
        gh-unavailable)
          why="GitHub could not be asked (gh missing, or the API refused) — status UNKNOWN, kept" ;;
        tip-behind-merged-pr)
          # WARNING: do NOT say "fetch the merged head" -- delete_branch_on_merge removed the
          # remote branch, so no branch ref exists to fetch and that advice is unactionable.
          # The PR head ref survives and IS fetchable. Verified 2026-08-11 on #1333: the fetch
          # returns a54a4b4b and the branch prunes on the next run.
          # WARNING: do NOT say "fetch the merged head" -- delete_branch_on_merge removed the
          # remote branch, so no branch ref exists to fetch and that advice is unactionable. The
          # PR head ref survives and IS fetchable. Verified 2026-08-11 on #1333: the fetch returns
          # a54a4b4b and the branch prunes on the next run. Works for fork PRs and reopened ones.
          #
          # printf %q on the branch is NOT tidiness: git accepts names like fix/$(id), and this
          # line is printed for a human to PASTE INTO A SHELL. Unquoted, that name would execute
          # (cross-vendor review, Major). %q renders it inert.
          why="its PR${GH_PR:+ #$GH_PR} MERGED, but this local tip is an EARLIER commit whose"
          why="$why tree never landed. Clear it with:  git fetch origin"
          why="$why refs/pull/${GH_PR:-N}/head && git branch -f $(printf %q "$branch") FETCH_HEAD"
          # git branch -f REFUSES a branch checked out in any worktree, and the script skips the
          # root worktree rather than reporting it, so the paste can fail with no hint why.
          if git worktree list --porcelain 2>/dev/null | grep -Fxq "branch refs/heads/$branch"; then
            why="$why  (checked out in a worktree — switch or detach it there first)"
          fi
          ;;
        tip-not-in-pr)
          why="a merged PR exists for this branch NAME, but this tip is none of its commits" ;;
        merge-commit-absent)
          why="its PR merged, but we do not hold the squash commit locally — cannot verify, kept" ;;
        *)
          why="no merged PR, and its changes are not in main — not merged" ;;
      esac
      kept+=("$branch ($ahead commit(s) ahead; $why)")
      continue
    fi
  fi
  echo "branch    -> $branch"
  [ -z "$DRY" ] && git branch -D "$branch" >/dev/null && removed_br=$((removed_br + 1))
done < <(git for-each-ref --format='%(refname:short) %(upstream:track)' refs/heads \
           | awk '$2 == "[gone]" {print $1}')

echo
if [ -n "${GH_UNAVAILABLE:-}" ]; then
  # Not decoration. Without this line a rate-limited run reads as "nothing left to prune", which is
  # the exact false conclusion that had this script re-reported as broken.
  echo "WARNING: at least one GitHub lookup FAILED (rate limit or auth), so any branch whose"
  echo "         content test was inconclusive was kept WITHOUT being checked. Re-run after"
  echo "         'gh api rate_limit --jq .resources.graphql' shows headroom."
  echo
fi
if [ -n "$DRY" ]; then
  echo "dry run — nothing removed"
else
  echo "removed $removed_wt worktree(s), $removed_br branch(es)"
fi
if [ ${#kept[@]} -gt 0 ]; then
  echo "kept:"
  printf '  - %s\n' "${kept[@]}"
fi
