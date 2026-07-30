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
# Stub `gh` so the real one can never answer for an unrelated repo, AND so the fallback path is
# actually EXERCISED rather than merely disabled. It answers from $GH_FIXTURE: one
# "<branch> <headRefOid>" line per merged PR; an unlisted branch exits 1 (= no merged PR).
export PATH="$TMP/nogh:$PATH"
mkdir -p "$TMP/nogh"
export GH_FIXTURE="$TMP/gh-fixture"
: > "$GH_FIXTURE"
cat > "$TMP/nogh/gh" <<'STUB'
#!/bin/sh
# mirrors: gh pr list --head <branch> --state merged --json headRefOid --jq ...
branch=""
while [ $# -gt 0 ]; do
  case "$1" in --head) branch="$2"; shift 2;; *) shift;; esac
done
[ -n "$branch" ] || exit 1
[ -n "${GH_FIXTURE:-}" ] || { echo 'gh stub: GH_FIXTURE unset' >&2; exit 2; }
oid=$(awk -v b="$branch" '$1 == b {print $2}' "$GH_FIXTURE")
[ -n "$oid" ] || exit 1
echo "$oid"
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
echo r2 > rescue.txt && git add -A && git commit --quiet -m 'later change to rescue.txt'

# --- R2 STALE OID: same shape, but a NEW local commit lands after the merge -----------------------
# `gh pr list --head` matches by NAME and still returns the OLD merged PR. Matching on name alone
# would force-delete the new commit. The headRefOid check must keep it.
git checkout --quiet -b stale-oid main
echo s1 > stale.txt && git add -A && git commit --quiet -m s1
git push --quiet -u origin stale-oid
STALE_MERGED_OID="$(git rev-parse stale-oid)"
git checkout --quiet main
git merge --quiet --squash stale-oid && git commit --quiet -m 'squashed stale (#4)'
echo s2 > stale.txt && git add -A && git commit --quiet -m 'later change to stale.txt'
git checkout --quiet stale-oid
echo NEW-WORK > stale-new.txt && git add -A && git commit --quiet -m 'new work AFTER the merge'
git checkout --quiet main

# --- never pushed: local WIP, must be untouchable -------------------------------------------------
git checkout --quiet -b local-wip main
echo wip > wip.txt && git add -A && git commit --quiet -m wip

git checkout --quiet main
git push --quiet origin main
# GitHub's delete_branch_on_merge equivalent: drop the remote heads, then let the script see [gone].
{ echo "gh-rescue $GH_RESCUE_OID"; echo "stale-oid $STALE_MERGED_OID"; } > "$GH_FIXTURE"
git push --quiet origin --delete squash-merged never-merged touched-again renames-a-file gh-rescue stale-oid

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

echo
echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ]
