#!/usr/bin/env bash
# Display the latest per-target review/report without calling Codex.
#
# Usage: show.sh [--cd <worktree>] <target>
# --cd must match the start/resume invocation to find worktree-qualified state.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_common.sh
source "$SCRIPT_DIR/_common.sh"

CD_DIR=""
while [ $# -gt 0 ]; do
    case "$1" in
        --cd)   CD_DIR="$2"; shift 2 ;;
        --cd=*) CD_DIR="${1#*=}"; shift ;;
        --)     shift; break ;;
        -*)     echo "error: unknown flag: $1" >&2; exit 64 ;;
        *)      break ;;
    esac
done

if [ $# -ne 1 ]; then
    echo "usage: show.sh [--cd <worktree>] <target>" >&2
    exit 64
fi
set_cdkey "$CD_DIR"

REVIEW_FILE="$(review_file "$1")"
THREAD_FILE="$(thread_file "$1")"

if [ ! -f "$REVIEW_FILE" ]; then
    echo "no review on file for $1" >&2
    exit 1
fi

[ -f "$THREAD_FILE" ] && echo "thread id: $(cat "$THREAD_FILE")"
echo "review file: $REVIEW_FILE"
echo "---"
cat "$REVIEW_FILE"
