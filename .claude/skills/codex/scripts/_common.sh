#!/usr/bin/env bash
# Shared helpers for the ArthaYantra Codex skill suite (codex-build,
# codex-code-review, codex-plan-review, codex-ask). Source-only.
#
# Ported from TRIP-workflow (github.com/PiLastDigit/TRIP-workflow, MIT),
# adapted to our worktree model + delegation contract. See
# docs/superpowers/plans/2026-07-14-codex-review-harness-spike.md.

set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# STATE_DIR is set by each skill before sourcing (per-skill state dir).
# Default falls back to this shared skill's own dir.
: "${STATE_DIR:=$SKILL_DIR/state}"
mkdir -p "$STATE_DIR"
# Canonicalize to ABSOLUTE — start/resume may `cd` into a worktree (--cd), after
# which a relative STATE_DIR would resolve against the wrong tree (C1).
STATE_DIR="$(cd "$STATE_DIR" && pwd)"
export STATE_DIR

# Optional worktree discriminator, folded into the per-target key so the SAME
# label/plan-path reviewed in two concurrent worktrees does not share a thread
# (M3). start/resume export CDKEY from --cd; empty otherwise.
: "${CDKEY:=}"

# Model / effort per flow (single source of truth for all codex skills).
# D3 decision (2026-07-14): build + reviews both run gpt-5.6-sol for now
# (writer != reviewer is preserved by using a SEPARATE THREAD, not a
# separate model). Trial gpt-5.6-luna for the build flow later by editing
# the *codex-build* case below. CODEX_MODEL / CODEX_EFFORT override per run.
case "$STATE_DIR" in
    *codex-build*) CODEX_MODEL="${CODEX_MODEL:-gpt-5.6-sol}" ;;
    *)             CODEX_MODEL="${CODEX_MODEL:-gpt-5.6-sol}" ;;
esac
CODEX_EFFORT="${CODEX_EFFORT:-xhigh}"
export CODEX_MODEL CODEX_EFFORT

# Derive a per-target key. Real paths resolve to absolute (so two worktrees
# reviewing the "same" plan path never collide); non-path targets (labels,
# branch names) are sanitized in place. '/' -> '__', other unsafe chars -> '_'.
target_key() {
    local target="$1"
    if [ -e "$target" ]; then
        local abs
        abs="$(realpath -- "$target" 2>/dev/null || readlink -f -- "$target")"
        if [ -z "$abs" ]; then
            echo "error: cannot resolve target path: $target" >&2
            return 1
        fi
        printf '%s' "$abs" | sed 's|^/||; s|/|__|g; s|[^A-Za-z0-9._-]|_|g'
    else
        printf '%s' "$target" | sed 's|^/||; s|/|__|g; s|[^A-Za-z0-9._-]|_|g'
    fi
}

# Fold a worktree dir into the state key (concurrent same-target reviews in
# different worktrees must not share a thread). No-op for empty input. Call
# from every command that resolves a target (start/resume/reset/show) after
# parsing --cd, so they all agree on the key.
set_cdkey() {
    [ -n "${1:-}" ] && export CDKEY="__wt_$(realpath "$1" 2>/dev/null | sed 's|[^A-Za-z0-9._-]|_|g')"
    return 0
}

thread_file() { printf '%s/%s%s.thread'         "$STATE_DIR" "$(target_key "$1")" "${CDKEY-}"; }
review_file() { printf '%s/%s%s.review.txt'     "$STATE_DIR" "$(target_key "$1")" "${CDKEY-}"; }
events_file() { printf '%s/%s%s.events.ndjson'  "$STATE_DIR" "$(target_key "$1")" "${CDKEY-}"; }

# Load a prompt template and substitute {{TARGET}}, {{EXTRA_PROMPT}},
# {{IMPLEMENTER_NOTES}} from the like-named env vars. Uses bash literal
# replacement (NOT awk gsub) — gsub treats '&' and '\' as special in the
# replacement, which silently corrupts inline diffs / prompts containing '&&'
# or backslashes (C2). Bash ${var//pat/rep} replacement is literal. Everything
# else passes through verbatim. Writes to stdout.
load_prompt() {
    local tpl="$1" content
    if [ ! -f "$tpl" ]; then
        echo "error: prompt template not found: $tpl" >&2
        return 1
    fi
    # bash 5.2 turns '&' in a ${//} replacement into the matched text
    # (patsub_replacement, on by default) — the same corruption class as awk
    # gsub. Disable it so the substitution is fully literal. No-op on older bash.
    shopt -u patsub_replacement 2>/dev/null || true
    content="$(cat "$tpl")"
    content="${content//\{\{TARGET\}\}/${TARGET-}}"
    content="${content//\{\{EXTRA_PROMPT\}\}/${EXTRA_PROMPT-}}"
    content="${content//\{\{IMPLEMENTER_NOTES\}\}/${IMPLEMENTER_NOTES-}}"
    printf '%s\n' "$content"
}
