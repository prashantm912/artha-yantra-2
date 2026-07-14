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
# D3 (revised 2026-07-14): the codex-build DRAFT runs gpt-5.6-luna (fast + cheap
# for the bulk edit); a sol review+fix pass then refines it (the codex-build skill
# runs that pass with CODEX_MODEL=gpt-5.6-sol on a fresh -refine thread); and
# REVIEW/ask/plan flows run gpt-5.6-sol. After the sol refine, the Opus cross-vendor
# review (claude-review, ROUTING.md) still runs — luna != sol != Opus, three
# perspectives. CODEX_MODEL / CODEX_EFFORT override per run.
# The at-capacity fallback is the OTHER tier per flow (build luna->sol, review
# sol->luna) so a retry uses a different model, not the same one again.
case "$STATE_DIR" in
    *codex-build*)
        CODEX_MODEL="${CODEX_MODEL:-gpt-5.6-luna}"
        CODEX_FALLBACK_MODELS="${CODEX_FALLBACK_MODELS-gpt-5.6-sol}" ;;
    *)
        CODEX_MODEL="${CODEX_MODEL:-gpt-5.6-sol}"
        CODEX_FALLBACK_MODELS="${CODEX_FALLBACK_MODELS-gpt-5.6-luna}" ;;
esac
CODEX_EFFORT="${CODEX_EFFORT:-xhigh}"
export CODEX_MODEL CODEX_EFFORT

# Capacity fallback chain (ROUTING.md): when a run fails with an at-capacity
# error, start.sh/resume.sh retry with each model above, in order. Set to "" to
# disable. Codex-down-entirely is NOT handled here — that's the cross-vendor
# (Opus subagent) column of ROUTING.md.
export CODEX_FALLBACK_MODELS

# True iff the stderr capture shows a capacity/availability error — retryable
# with the next model in the chain, as opposed to a real failure (fail fast).
capacity_error() {
    grep -qiE 'at capacity|overloaded|temporarily unavailable' "$1" 2>/dev/null
}

# Derive a per-target key. Real paths resolve to absolute (so two worktrees
# reviewing the "same" plan path never collide); non-path targets (labels,
# branch names) are sanitized in place. '/' -> '__', other unsafe chars -> '_'.
# A short cksum of the UNSANITIZED value is appended so distinct targets that
# sanitize identically ('a/b' vs 'a__b', 'x y' vs 'x_y') can never share state.
target_key() {
    local target="$1" raw
    if [ -e "$target" ]; then
        raw="$(realpath -- "$target" 2>/dev/null || readlink -f -- "$target")"
        if [ -z "$raw" ]; then
            echo "error: cannot resolve target path: $target" >&2
            return 1
        fi
    else
        raw="$target"
    fi
    printf '%s.%s' \
        "$(printf '%s' "$raw" | sed 's|^/||; s|/|__|g; s|[^A-Za-z0-9._-]|_|g')" \
        "$(printf '%s' "$raw" | cksum | cut -d' ' -f1)"
}

# Fold a worktree dir into the state key (concurrent same-target reviews in
# different worktrees must not share a thread). No-op for empty input. Call
# from every command that resolves a target (start/resume/reset/show) after
# parsing --cd, so they all agree on the key.
set_cdkey() {
    [ -n "${1:-}" ] && export CDKEY="__wt_$(realpath "$1" 2>/dev/null | sed 's|[^A-Za-z0-9._-]|_|g')"
    return 0
}

# KEY_SUFFIX lets a caller give the SAME target a distinct thread (e.g. the
# codex-build sol-refine pass, KEY_SUFFIX=_refine) without mangling the TARGET
# string the prompt sees. Empty by default.
: "${KEY_SUFFIX:=}"
thread_file() { printf '%s/%s%s%s.thread'        "$STATE_DIR" "$(target_key "$1")" "${CDKEY-}" "${KEY_SUFFIX-}"; }
review_file() { printf '%s/%s%s%s.review.txt'    "$STATE_DIR" "$(target_key "$1")" "${CDKEY-}" "${KEY_SUFFIX-}"; }
events_file() { printf '%s/%s%s%s.events.ndjson' "$STATE_DIR" "$(target_key "$1")" "${CDKEY-}" "${KEY_SUFFIX-}"; }

# One-time migration: state written before the cksum-suffixed key format
# (2026-07-14) lives under the unsuffixed legacy name and would otherwise
# read as "no session". Move it to the new key on first touch. Call after
# set_cdkey in every command that resolves a target.
migrate_legacy_state() {
    local key legacy ext
    key="$(target_key "$1")" || return 0
    legacy="${key%.*}"   # strip the trailing .<cksum> -> the pre-change key
    for ext in thread review.txt events.ndjson events.ndjson.stderr; do
        if [ ! -f "$STATE_DIR/${key}${CDKEY-}${KEY_SUFFIX-}.$ext" ] \
           && [ -f "$STATE_DIR/${legacy}${CDKEY-}${KEY_SUFFIX-}.$ext" ]; then
            mv -- "$STATE_DIR/${legacy}${CDKEY-}${KEY_SUFFIX-}.$ext" "$STATE_DIR/${key}${CDKEY-}${KEY_SUFFIX-}.$ext"
        fi
    done
    return 0
}

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
