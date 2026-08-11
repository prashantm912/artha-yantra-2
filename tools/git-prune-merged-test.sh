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
# check_worktree <description> <expected: KEPT|GONE> <worktree-path> <output-file>
# Branch existence (checked by `check` above) does not prove a WORKTREE was removed — `git worktree
# remove` never deletes the branch ref, only the working directory + its registration.
# ⚠️ Do NOT grep `git worktree list --porcelain` for this literal path (measured, 2026-08-01): on this
# Windows/Git-Bash box, git reports worktree paths in WINDOWS form (`C:/Users/...`) while `$TMP` from
# `mktemp -d` is POSIX form (`/tmp/...`) — the two spellings of the same directory never string-match,
# so a porcelain grep silently reports GONE unconditionally, regardless of the real state. Directory
# existence has no such format ambiguity: `git worktree remove` deletes the tree from disk, full stop.
check_worktree() {
  local desc="$1" want="$2" wt_path="$3" out="$4"
  local got="GONE"
  [ -d "$wt_path" ] && got="KEPT"
  if [ "$got" = "$want" ]; then
    printf 'ok   %-46s %s\n' "$desc" "$want"
    pass=$((pass + 1))
  else
    printf 'FAIL %-46s want=%s got=%s\n' "$desc" "$want" "$got"
    echo "--- script output ---"; cat "$out"; echo "---------------------"
    fail=$((fail + 1))
  fi
}
# check_not_flagged <description> <branch> <output-file>
# The literal repro (2026-08-01): a live, never-pushed, untouched worktree appeared under the
# `worktree  -> ` removal-candidate line in `--dry` output, before its agent had written a file.
# --dry never physically deletes anything either way, so the only way to see the bug via --dry is to
# check WHICH list the report put it in, not whether the directory survived. Matched by BRANCH NAME,
# not by path — same Windows-vs-POSIX path-spelling trap as check_worktree above applies here too.
check_not_flagged() {
  local desc="$1" branch="$2" out="$3"
  if grep -qE "^worktree  +-> .*\[$branch\]" "$out"; then
    printf 'FAIL %-46s flagged for removal\n' "$desc"
    echo "--- script output ---"; cat "$out"; echo "---------------------"
    fail=$((fail + 1))
  else
    printf 'ok   %-46s %s\n' "$desc" "not flagged"
    pass=$((pass + 1))
  fi
}

git init --quiet --bare "$TMP/remote"
git clone --quiet "$TMP/remote" "$TMP/local"
cd "$TMP/local"
git config user.email t@t.t
git config user.name t
git config commit.gpgsign false
# Stub `gh` so the real one can never answer for an unrelated repo, AND so the fallback path is
# actually EXERCISED rather than merely disabled. It answers from $GH_FIXTURE: one
# "<branch> <headRefOid> [<commitOid>...]" line per merged PR; an unlisted branch exits 1 (= no
# merged PR). The trailing OIDs stand in for `.commits[].oid`, which the script matches as well as
# the head -- a server-side update-branch advances headRefOid past every commit the author wrote.
export PATH="$TMP/nogh:$PATH"
mkdir -p "$TMP/nogh"
export GH_FIXTURE="$TMP/gh-fixture"
: > "$GH_FIXTURE"
cat > "$TMP/nogh/gh" <<'STUB'
#!/bin/bash
# Mirrors: gh pr list --head <b> --state merged
#            --json number,mergeCommit,headRefOid,commits
#            --jq '.[] | [(.number|tostring), (.mergeCommit.oid // "-"), .headRefOid]
#                        + [.commits[].oid] | @tsv'
#
# Fixture line: "<branch> <prNumber> <mergeCommit|-> <headRefOid> [<commitOid>...]", ONE LINE PER
# MERGED PR (repeat the branch to model several). "-" in the mergeCommit slot is passed THROUGH
# as the production sentinel for a NULL mergeCommit -- it must NOT be converted back to an empty
# column, because bash collapses consecutive tabs and the column would vanish. "<branch> FAIL"
# models an API refusal.
#
# WARNING: this double has diverged from real gh FOUR times in this PR series, and every
# divergence turned the suite green or red for a reason unrelated to the change under test. It
# now VALIDATES the whole invocation, because "the caller asked for something else" is exactly the
# class of regression the double is supposed to catch.
sub1=""; sub2=""; branch=""; state=""; fields=""; jq=""
while [ $# -gt 0 ]; do
  case "$1" in
    --head)  branch="$2"; shift 2 ;;
    --state) state="$2";  shift 2 ;;
    --json)  fields="$2"; shift 2 ;;
    --jq)    jq="$2";     shift 2 ;;
    -*)      shift ;;
    *)       if [ -z "$sub1" ]; then sub1="$1"; elif [ -z "$sub2" ]; then sub2="$1"; fi; shift ;;
  esac
done
[ "$sub1 $sub2" = "pr list" ] || { echo "gh stub: unexpected subcommand '$sub1 $sub2'" >&2; exit 2; }
[ "$state" = "merged" ]       || { echo "gh stub: expected --state merged, got '$state'" >&2; exit 2; }
[ "$fields" = "number,mergeCommit,headRefOid,commits" ] || {
  echo "gh stub: unexpected --json '$fields'" >&2; exit 2; }
# EXACT match, not a substring. Accepting any program containing @tsv left field REORDERING
# invisible to the suite (cross-vendor review, round 2): the stub independently emitted the
# order it expected, so production could swap two columns and every case stayed green.
expect_jq='.[] | [(.number|tostring), (.mergeCommit.oid // "-"), .headRefOid] + [.commits[].oid] | @tsv'
[ "$jq" = "$expect_jq" ] || { echo "gh stub: --jq differs from the pinned expression" >&2
                              echo "  expected: $expect_jq" >&2
                              echo "  actual:   $jq" >&2; exit 2; }
[ -n "$branch" ] || exit 1
[ -n "${GH_FIXTURE:-}" ] || { echo 'gh stub: GH_FIXTURE unset' >&2; exit 2; }

if awk -v b="$branch" '$1 == b && $2 == "FAIL" {f=1} END {exit !f}' "$GH_FIXTURE"; then
  echo 'gh stub: simulated API failure' >&2
  exit 1
fi

# No merged PR for this head: real gh prints NOTHING and exits 0. An earlier stub exited 1 here,
# which made "no merged PR" indistinguishable from "the API refused".
awk -v b="$branch" 'BEGIN { OFS = "	" }
  $1 == b {
    # "-" passes THROUGH: it is the production sentinel for a null mergeCommit, not a local
    # placeholder. Emitting "" here would reintroduce the collapsing-tab bug inside the double.
    line = $2 OFS $3 OFS $4
    for (i = 5; i <= NF; i++) line = line OFS $i
    print line
  }' "$GH_FIXTURE"
exit 0
STUB
chmod +x "$TMP/nogh/gh"

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

# --- RENAME (review R1): branch renames src -> dst; main has dst but STILL HAS src ----------------
# With default rename detection `--name-only` prints only dst, so the branch's DELETION of src would
# never be compared and the branch would be deleted with that deletion unlanded. Must be KEPT.
echo original > renamed-src.txt && git add -A && git commit --quiet -m 'seed src' && git push --quiet origin main
git checkout --quiet -b renames-a-file main
git mv renamed-src.txt renamed-dst.txt && git commit --quiet -m 'rename src -> dst'
git push --quiet -u origin renames-a-file
git checkout --quiet main
# main gains dst by another route but KEEPS src — the rename never landed here.
cp renamed-src.txt renamed-dst.txt && git add -A && git commit --quiet -m 'dst arrives separately'

# --- gh RESCUE (positive): merged, main moved on the same path, tip STILL equals the PR head ------
# Content test is inconclusive; gh confirms this exact commit merged, so it SHOULD be removed.
git checkout --quiet -b gh-rescue main
echo r1 > rescue.txt && git add -A && git commit --quiet -m r1
git push --quiet -u origin gh-rescue
GH_RESCUE_OID="$(git rev-parse gh-rescue)"
git checkout --quiet main
git merge --quiet --squash gh-rescue && git commit --quiet -m 'squashed rescue (#3)'
GH_RESCUE_MERGE="$(git rev-parse HEAD)"
echo r2 > rescue.txt && git add -A && git commit --quiet -m 'later change to rescue.txt'

# --- R2 STALE OID: same shape, but a NEW local commit lands after the merge -----------------------
# UPDATE-BRANCH: the shape that made the gh fallback dead in production (2026-08-11). `main` is
# strict:true, so a green PR is brought forward with a server-side
# `gh api -X PUT .../update-branch`; that merge commit becomes headRefOid and the local checkout
# never fetches it. The local tip is therefore NOT the head -- but it IS one of the PR's commits,
# which is what the script must match on. Fixture lists a head OID that exists nowhere locally.
git checkout --quiet -b updated-branch main
echo updated > updated.txt && git add -A && git commit --quiet -m 'work on updated-branch'
git push --quiet -u origin updated-branch
UPDATED_TIP="$(git rev-parse updated-branch)"
git checkout --quiet main
git merge --quiet --squash updated-branch && git commit --quiet -m 'squashed updated (#5)'
UPDATED_BRANCH_MERGE="$(git rev-parse HEAD)"
# main moves on the SAME path afterwards, so the path-scoped test is inconclusive and the run is
# forced through gh_says_merged -- without this the branch would be deleted by the cheap test and
# prove nothing about the fallback.
echo 'later edit' >> updated.txt && git add -A && git commit --quiet -m 'main touches updated.txt again'
git push --quiet origin main

# `gh pr list --head` matches by NAME and still returns the OLD merged PR. Matching on name alone
# would force-delete the new commit. Neither the head NOR any PR commit may equal the new tip.
git checkout --quiet -b stale-oid main
echo s1 > stale.txt && git add -A && git commit --quiet -m s1
git push --quiet -u origin stale-oid
STALE_MERGED_OID="$(git rev-parse stale-oid)"
git checkout --quiet main
git merge --quiet --squash stale-oid && git commit --quiet -m 'squashed stale (#4)'
STALE_OID_MERGE="$(git rev-parse HEAD)"
echo s2 > stale.txt && git add -A && git commit --quiet -m 'later change to stale.txt'
git checkout --quiet stale-oid
echo NEW-WORK > stale-new.txt && git add -A && git commit --quiet -m 'new work AFTER the merge'
git checkout --quiet main

# --- INTERMEDIATE COMMIT (cross-vendor review, Critical 2026-08-11) -------------------------------
# Commit-list membership ALONE is a false-DELETE. A branch stranded on an EARLIER commit of a merged
# PR matches the commit list just as well as the final one -- but its TREE is not what landed. The
# reviewer demonstrated it on this script's own PR (7dd03e3e vs 06d96383, different test-file trees).
# So: two commits, the PR merged BOTH, the local tip left on the FIRST. Identity matches; the proof
# step must reject it, because the second commit's content is in main and this tip's is not.
git checkout --quiet -b intermediate main
echo first > intermediate.txt && git add -A && git commit --quiet -m 'first commit'
INTERMEDIATE_FIRST="$(git rev-parse HEAD)"
echo second >> intermediate.txt && git add -A && git commit --quiet -m 'second commit'
INTERMEDIATE_TIP="$(git rev-parse HEAD)"
git push --quiet -u origin intermediate
git checkout --quiet main
git merge --quiet --squash intermediate && git commit --quiet -m 'squashed intermediate (#6)'
INTERMEDIATE_MERGE="$(git rev-parse HEAD)"
echo 'later edit' >> intermediate.txt && git add -A && git commit --quiet -m 'main touches intermediate.txt again'
git push --quiet origin main
# strand the local branch on the FIRST commit, exactly as an unfetched amend/force-push would
git branch --quiet -f intermediate "$INTERMEDIATE_FIRST"

# --- GH UNAVAILABLE: the API refuses, so the branch's status is UNKNOWN and it must be KEPT --------
# Measured 2026-08-11: `gh pr list` is a GRAPHQL call and the GraphQL budget is separate from REST
# core, so core can read 4993/5000 -- "the rate limit is fine" by the obvious check -- while every
# lookup fails. The old code piped gh straight into the read loop, discarding its exit status, so a
# refusal was indistinguishable from "no merged PR" and a real prune run stopped part-way with no
# indication. Shape here: merged and squashed exactly like gh-rescue, but the fixture marks it FAIL.
git checkout --quiet -b gh-down main
echo d1 > down.txt && git add -A && git commit --quiet -m d1
git push --quiet -u origin gh-down
git checkout --quiet main
git merge --quiet --squash gh-down && git commit --quiet -m 'squashed gh-down (#7)'
echo d2 > down.txt && git add -A && git commit --quiet -m 'later change to down.txt'
git push --quiet origin main

# --- NULL mergeCommit (cross-vendor Critical, 2026-08-11) -----------------------------------------
# tip == headRefOid, so identity passes trivially; the PR reports NO merge commit. Must be KEPT --
# there is nothing to prove the tree against.
git checkout --quiet -b null-merge main
echo n1 > nullmerge.txt && git add -A && git commit --quiet -m n1
git push --quiet -u origin null-merge
NULL_MERGE_TIP="$(git rev-parse null-merge)"
git checkout --quiet main
git merge --quiet --squash null-merge && git commit --quiet -m "squashed null-merge (#8)"
echo n2 > nullmerge.txt && git add -A && git commit --quiet -m 'later change to nullmerge.txt'
git push --quiet origin main

# --- TWO merged PRs for one head: the remedy must name the one that CONTAINS the tip -------------
git checkout --quiet -b two-prs main
echo t1 > twoprs.txt && git add -A && git commit --quiet -m t1
TWO_PRS_FIRST="$(git rev-parse HEAD)"
echo t2 >> twoprs.txt && git add -A && git commit --quiet -m t2
TWO_PRS_HEAD="$(git rev-parse HEAD)"
git push --quiet -u origin two-prs
git checkout --quiet main
git merge --quiet --squash two-prs && git commit --quiet -m "squashed two-prs (#9)"
TWO_PRS_MERGE="$(git rev-parse HEAD)"
echo t3 >> twoprs.txt && git add -A && git commit --quiet -m 'later change to twoprs.txt'
git push --quiet origin main
git branch --quiet -f two-prs "$TWO_PRS_FIRST"

# --- MALFORMED gh ROW: mergeCommit == headRefOid == tip (must be KEPT) ---------------------------
git checkout --quiet -b crafted main
echo c1 > crafted.txt && git add -A && git commit --quiet -m c1
git push --quiet -u origin crafted
CRAFTED_TIP="$(git rev-parse crafted)"
git checkout --quiet main
git merge --quiet --squash crafted && git commit --quiet -m "squashed crafted (#11)"
echo c2 > crafted.txt && git add -A && git commit --quiet -m 'later change to crafted.txt'
git push --quiet origin main

# --- never pushed: local WIP, must be untouchable -------------------------------------------------
git checkout --quiet -b local-wip main
echo wip > wip.txt && git add -A && git commit --quiet -m wip

git checkout --quiet main

# --- LIVE WORKTREE, clean, never pushed (the bug this fix addresses: a false DELETE) ---------------
# Mirrors exactly how agents are dispatched here: `git worktree add <path> -b <branch> origin/main`.
# That call sets branch.<branch>.remote=origin as a SIDE EFFECT of tracking the base branch — which is
# what made the OLD "has a remote configured" check treat a never-pushed branch as pushed. The
# worktree is left completely untouched (no commits, no dirty files, exactly like an agent's worktree
# before it has written anything) and must survive both --dry and the real run verbatim.
git worktree add --quiet "$TMP/wt-live" -b live-worktree-guard origin/main

# --- LIVE WORKTREE, real committed work, squash-merged, remote deleted (the inverse case) -----------
# Confirms the new guard does not regress real cleanup: a worktree that WAS pushed, WAS squash-merged,
# and is clean must still be removed — and its now-orphaned branch handle cleaned up with it.
git worktree add --quiet "$TMP/wt-merged" -b worktree-squash-merged origin/main
(cd "$TMP/wt-merged" && echo w1 > wtfile.txt && git add -A && git commit --quiet -m 'worktree commit')
git push --quiet -u origin worktree-squash-merged
git merge --quiet --squash worktree-squash-merged && git commit --quiet -m 'squashed worktree work (#5)'

git push --quiet origin main
# GitHub's delete_branch_on_merge equivalent: drop the remote heads, then let the script see [gone].
{
  # <branch> <prNumber> <mergeCommit|-> <headRefOid> [<commitOid>...]   -- one line per merged PR
  echo "gh-rescue 3 $GH_RESCUE_MERGE $GH_RESCUE_OID"
  echo "stale-oid 4 $STALE_OID_MERGE $STALE_MERGED_OID $STALE_MERGED_OID"
  # head OID is a fabricated 40-hex that exists in no repo -- exactly like an unfetched
  # update-branch merge commit. Only the trailing commit OID can identify this branch.
  echo "updated-branch 5 $UPDATED_BRANCH_MERGE 0123456789abcdef0123456789abcdef01234567 $UPDATED_TIP"
  # both commits are in the PR; the local tip is the FIRST. Identity passes, proof must not.
  echo "intermediate 6 $INTERMEDIATE_MERGE $INTERMEDIATE_TIP $INTERMEDIATE_FIRST $INTERMEDIATE_TIP"
  echo "gh-down FAIL"
  # MALFORMED ROW, the reviewer's exact shape: mergeCommit == headRefOid == the local tip. Under
  # the old space-collapsing parse this made branch_work_is_in_main compare the branch against
  # ITSELF -- empty diff, DELETE. ⚠️ Kept as a GUARD, not as a reproduction of a live hole: no real
  # gh output was found that emits this (a squash mergeCommit is never the head), and the plausible
  # variant -- a NULL mergeCommit -- was already caught by the old empty-string check. See the
  # null-merge case above, which passes on BOTH versions.
  echo "crafted 11 $CRAFTED_TIP $CRAFTED_TIP"
  # NULL mergeCommit on a merged PR (the "-"), with tip == headRefOid. Cross-vendor Critical: the
  # old space-split collapsed the empty column, slid headRefOid into the merge slot, and the
  # proof then compared the branch against ITSELF -- an empty diff, and a DELETE.
  echo "null-merge 8 - $NULL_MERGE_TIP"
  # TWO merged PRs for one head. Only the OLDER contains the tip, so the remedy must name 9, not
  # 10 -- binding the number per ROW would have printed a fetch of the wrong PR.
  echo "two-prs 9 $TWO_PRS_MERGE $TWO_PRS_HEAD $TWO_PRS_FIRST $TWO_PRS_HEAD"
  echo "two-prs 10 0123456789abcdef0123456789abcdef0123beef 89abcdef0123456789abcdef0123456789abcdef"
} > "$GH_FIXTURE"
git push --quiet origin --delete squash-merged never-merged touched-again renames-a-file gh-rescue stale-oid updated-branch intermediate gh-down null-merge two-prs crafted worktree-squash-merged

echo "=== --dry must remove NOTHING ==="
# ⚠️ No `|| true` (review R4). It masked the script's exit status, so a fatal error AFTER the one
# deletion could still leave every assertion satisfied — the suite would go green on a broken script.
dry_status=0
bash "$SCRIPT" --dry > "$TMP/dry.out" 2>&1 || dry_status=$?
if [ "$dry_status" -eq 0 ]; then
  printf 'ok   %-46s %s\n' "--dry exits 0" "0"; pass=$((pass + 1))
else
  printf 'FAIL %-46s exit=%s\n' "--dry exits 0" "$dry_status"; cat "$TMP/dry.out"; fail=$((fail + 1))
fi
check "--dry leaves the squash-merged branch"  KEPT squash-merged "$TMP/dry.out"
check "--dry leaves the unmerged branch"       KEPT never-merged  "$TMP/dry.out"
# The literal repro: a never-pushed, untouched live worktree must NOT be listed as a removal
# candidate in --dry output (RED against the pre-fix script — see receipt for the failure text).
check_not_flagged "--dry does not flag the live worktree for removal" live-worktree-guard "$TMP/dry.out"

echo
echo "=== real run ==="
run_status=0
bash "$SCRIPT" > "$TMP/run.out" 2>&1 || run_status=$?
cat "$TMP/run.out"
echo
if [ "$run_status" -eq 0 ]; then
  printf 'ok   %-46s %s\n' "real run exits 0" "0"; pass=$((pass + 1))
else
  printf 'FAIL %-46s exit=%s\n' "real run exits 0" "$run_status"; fail=$((fail + 1))
fi
check "squash-merged branch is REMOVED"        GONE squash-merged "$TMP/run.out"
check "genuinely unmerged branch SURVIVES"     KEPT never-merged  "$TMP/run.out"
check "never-pushed local WIP SURVIVES"        KEPT local-wip     "$TMP/run.out"
check "main SURVIVES"                          KEPT main          "$TMP/run.out"
# Merged, but a later commit on main touched the same path. With gh unavailable the content test
# cannot tell this from unmerged work, so the SAFE answer is to keep it. Asserted deliberately: this
# pins the failure direction (a false keep, never a false delete).
check "merged-then-overwritten is kept (safe)" KEPT touched-again "$TMP/run.out"
# R1: the branch DELETED renamed-src.txt; main still has it. Rename detection would have hidden that.
check "rename with unlanded deletion SURVIVES"  KEPT renames-a-file "$TMP/run.out"
# R2 positive: content test inconclusive, gh confirms THIS commit merged -> removed.
check "gh rescue removes a confirmed merge"     GONE gh-rescue      "$TMP/run.out"
# R2 negative: gh still returns the OLD merged PR for this reused name; the new commit must survive.
check "stale gh OID does NOT delete new work"   KEPT stale-oid      "$TMP/run.out"
check "update-branch head is rescued by commits" GONE updated-branch "$TMP/run.out"
check "INTERMEDIATE commit of a merged PR is KEPT" KEPT intermediate   "$TMP/run.out"
check "gh API refusal KEEPS rather than answers no" KEPT gh-down       "$TMP/run.out"
check "NULL mergeCommit must NOT authorise a delete" KEPT null-merge  "$TMP/run.out"
if grep -q "null-merge.*do not hold the squash commit locally" "$TMP/run.out"; then
  echo "ok   null mergeCommit names merge-commit-absent    accurate"
  pass=$((pass + 1))
else
  echo "FAIL null mergeCommit names merge-commit-absent    wrong reason rendered"
  fail=$((fail + 1))
fi
check "two merged PRs for one head: still KEPT"      KEPT two-prs     "$TMP/run.out"
check "malformed row (merge==head==tip) is KEPT"     KEPT crafted     "$TMP/run.out"
if grep -q "refs/pull/9/head" "$TMP/run.out"; then
  echo "ok   remedy names the PR that CONTAINS the tip   #9"
  pass=$((pass + 1))
else
  echo "FAIL remedy names the PR that CONTAINS the tip   wrong or missing PR number"
  fail=$((fail + 1))
fi
if grep -q "at least one GitHub lookup FAILED" "$TMP/run.out"; then
  echo "ok   a gh refusal is REPORTED, not silent            warned"
  pass=$((pass + 1))
else
  echo "FAIL a gh refusal is REPORTED, not silent            no warning line"
  fail=$((fail + 1))
fi
if grep -q "this local tip is an EARLIER commit" "$TMP/run.out" \
   && grep -q "git fetch origin refs/pull/" "$TMP/run.out"; then
  echo "ok   intermediate keep names its REAL reason         accurate"
  pass=$((pass + 1))
else
  echo "FAIL intermediate keep names its REAL reason         no reason or no refs/pull remedy"
  fail=$((fail + 1))
fi
# The bug this fix pins: a clean, never-pushed live worktree must survive the real run, not just
# --dry's report (RED against the pre-fix script — see receipt for the failure text).
check_worktree "clean never-pushed worktree SURVIVES (the fix)" KEPT "$TMP/wt-live" "$TMP/run.out"
check "live worktree's branch SURVIVES"                          KEPT live-worktree-guard "$TMP/run.out"
# The inverse: a worktree that WAS pushed and WAS squash-merged must still be removed, worktree and
# branch both — the fix must not regress the cleanup path the whole script exists for.
check_worktree "squash-merged worktree is REMOVED"      GONE "$TMP/wt-merged" "$TMP/run.out"
check "worktree-squash-merged branch is REMOVED"        GONE worktree-squash-merged "$TMP/run.out"

echo
echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ]
