#!/usr/bin/env bash
# Regression guard for the shared harness mechanics that a cross-vendor review
# caught during the spike (C2 literal substitution, M3 worktree key fold).
# Pure-bash, no Codex call. Run: bash .claude/skills/codex/scripts/selftest.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export STATE_DIR="$TMP/state"
# shellcheck source=_common.sh
source "$SCRIPT_DIR/_common.sh"

fail() { echo "FAIL: $1" >&2; exit 1; }

# C2 — substitution must be LITERAL: '&', '&&', and '\' survive verbatim
# (awk gsub and bash patsub_replacement both corrupt '&').
printf 'T=[{{TARGET}}] X=[{{EXTRA_PROMPT}}]\n' > "$TMP/t.tpl"
export TARGET='plan&A'
export EXTRA_PROMPT='a && b \slash & amp'
out="$(load_prompt "$TMP/t.tpl")"
[ "$out" = 'T=[plan&A] X=[a && b \slash & amp]' ] || fail "literal substitution: got [$out]"

# C1 — STATE_DIR is absolute (survives a cd into a worktree).
case "$STATE_DIR" in /*|?:*) : ;; *) fail "STATE_DIR not absolute: $STATE_DIR" ;; esac

# M3 — CDKEY changes the per-target key so concurrent worktrees don't collide.
unset CDKEY
k0="$(thread_file plan.md)"
export CDKEY="__wt_x"
k1="$(thread_file plan.md)"
[ "$k0" != "$k1" ] || fail "CDKEY did not change the key"
[ "$k1" = "${k0%.thread}__wt_x.thread" ] || fail "CDKEY fold shape: $k1"

# Key collisions — targets that sanitize identically must still get distinct
# keys (the cksum suffix), and the key must be stable for the same input.
ka="$(target_key 'a/b')"; kb="$(target_key 'a__b')"; kc="$(target_key 'a/b')"
[ "$ka" != "$kb" ] || fail "sanitize-collision: 'a/b' and 'a__b' share a key"
[ "$ka" = "$kc" ] || fail "target_key not stable for the same input"

# Legacy-key migration — pre-cksum state files must be moved to the new key
# on first touch, so old sessions stay resumable.
unset CDKEY
newk="$(target_key 'legacy-topic')"
printf 'tid-123\n' > "$STATE_DIR/${newk%.*}.thread"     # legacy (unsuffixed) name
migrate_legacy_state 'legacy-topic'
[ -f "$STATE_DIR/${newk}.thread" ] || fail "legacy state not migrated to new key"
[ "$(cat "$STATE_DIR/${newk}.thread")" = "tid-123" ] || fail "migrated content mangled"

# ROUTING — capacity_error matches the retryable patterns and nothing else;
# the fallback chain default is non-empty.
printf 'ERROR: Selected model is at capacity\n' > "$TMP/cap.txt"
capacity_error "$TMP/cap.txt" || fail "capacity_error missed 'at capacity'"
printf 'error: invalid prompt\n' > "$TMP/nocap.txt"
! capacity_error "$TMP/nocap.txt" || fail "capacity_error false-positive on a real failure"
[ -n "$CODEX_FALLBACK_MODELS" ] || fail "CODEX_FALLBACK_MODELS default is empty"

echo "harness selftest: OK (C1 abs-state-dir, C2 literal-substitution, M3 worktree-key-fold, capacity-fallback)"
