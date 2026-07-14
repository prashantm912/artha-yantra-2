#!/usr/bin/env bash
# Turn 1: start a fresh Codex session for <target>, capture the thread_id
# from the JSONL event stream, and write Codex's final message to the
# per-target review/report file.
#
# Generic across the suite: --sandbox selects the policy (read-only for the
# review/ask/plan-review skills; workspace-write or the bypass flag for
# codex-build). --cd points Codex at a worktree working-root.
#
# Usage: start.sh --prompt-file <tpl> [--sandbox <mode>] [--cd <dir>] \
#                 [--bypass] <target> [extra prompt text...]
# Exits 0 ok, 1 on Codex/thread_id failure, 2 if a thread already exists.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_common.sh
source "$SCRIPT_DIR/_common.sh"

# jq is a hard dependency (thread_id capture) — fail fast with an actionable
# message rather than a confusing "no thread.started event" later (M4).
command -v jq >/dev/null 2>&1 || { echo "error: jq is required (install jq)" >&2; exit 69; }

PROMPT_FILE=""
SANDBOX="read-only"
CD_DIR=""
BYPASS=0
while [ $# -gt 0 ]; do
    case "$1" in
        --prompt-file)  PROMPT_FILE="$2"; shift 2 ;;
        --prompt-file=*) PROMPT_FILE="${1#*=}"; shift ;;
        --sandbox)      SANDBOX="$2"; shift 2 ;;
        --sandbox=*)    SANDBOX="${1#*=}"; shift ;;
        --cd)           CD_DIR="$2"; shift 2 ;;
        --cd=*)         CD_DIR="${1#*=}"; shift ;;
        --bypass)       BYPASS=1; shift ;;
        --)             shift; break ;;
        -*)             echo "error: unknown flag: $1" >&2; exit 64 ;;
        *)              break ;;
    esac
done

if [ -z "$PROMPT_FILE" ] || [ $# -lt 1 ]; then
    echo "usage: start.sh --prompt-file <tpl> [--sandbox <mode>] [--cd <dir>] [--bypass] <target> [extra...]" >&2
    exit 64
fi

TARGET="$1"; shift
EXTRA_PROMPT="${*:-}"
export TARGET EXTRA_PROMPT

# Fold the worktree into the state key (M3). Must be set before *_file.
set_cdkey "$CD_DIR"

THREAD_FILE="$(thread_file "$TARGET")"
REVIEW_FILE="$(review_file "$TARGET")"
EVENTS_FILE="$(events_file "$TARGET")"

if [ -f "$THREAD_FILE" ]; then
    echo "error: session already exists for $TARGET" >&2
    echo "       thread id: $(cat "$THREAD_FILE")" >&2
    echo "       run resume.sh to continue, or reset.sh to start fresh." >&2
    exit 2
fi

PROMPT="$(load_prompt "$PROMPT_FILE")"

# Sandbox: --bypass overrides --sandbox (codex-build needs the full toolchain
# ~/.m2 + node; D2 decision). Otherwise honor --sandbox (default read-only).
SANDBOX_ARGS=(--sandbox "$SANDBOX")
if [ "$BYPASS" = 1 ]; then
    SANDBOX_ARGS=(--dangerously-bypass-approvals-and-sandbox)
fi
CD_ARGS=()
[ -n "$CD_DIR" ] && CD_ARGS=(--cd "$CD_DIR")

codex exec \
    --json \
    --skip-git-repo-check \
    --color never \
    "${SANDBOX_ARGS[@]}" \
    "${CD_ARGS[@]}" \
    -c model="$CODEX_MODEL" \
    -c model_reasoning_effort="$CODEX_EFFORT" \
    -o "$REVIEW_FILE" \
    "$PROMPT" \
    </dev/null \
    >"$EVENTS_FILE" \
    2> "$EVENTS_FILE.stderr" || {
        rc=$?
        echo "error: codex exec failed (rc=$rc)" >&2
        echo "stderr tail:" >&2
        tail -20 "$EVENTS_FILE.stderr" >&2
        exit 1
    }

THREAD_ID="$(jq -r 'select(.type == "thread.started") | .thread_id' \
                "$EVENTS_FILE" 2>/dev/null | head -1)"

if [ -z "$THREAD_ID" ] || [ "$THREAD_ID" = "null" ]; then
    echo "error: no thread.started event found in $EVENTS_FILE" >&2
    echo "first 20 events:" >&2
    head -20 "$EVENTS_FILE" >&2
    exit 1
fi

printf '%s\n' "$THREAD_ID" > "$THREAD_FILE"
echo "started session for $TARGET"
echo "  thread id:    $THREAD_ID"
echo "  model/effort: $CODEX_MODEL / $CODEX_EFFORT"
echo "  sandbox:      $([ "$BYPASS" = 1 ] && echo bypass || echo "$SANDBOX")"
echo "  review file:  $REVIEW_FILE"
echo "---"
cat "$REVIEW_FILE"
