#!/usr/bin/env bash
# Drop the per-target thread id, review, and event log so the next start.sh
# begins a fresh Codex session.
#
# Usage: reset.sh [--cd <worktree>] <target>
# --cd must match the start/resume invocation so the same worktree-qualified
# state key is targeted.

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
    echo "usage: reset.sh [--cd <worktree>] <target>" >&2
    exit 64
fi
set_cdkey "$CD_DIR"
migrate_legacy_state "$1"

THREAD_FILE="$(thread_file "$1")"
REVIEW_FILE="$(review_file "$1")"
EVENTS_FILE="$(events_file "$1")"

removed=0
for f in "$THREAD_FILE" "$REVIEW_FILE" "$EVENTS_FILE" "$EVENTS_FILE.stderr"; do
    if [ -f "$f" ]; then
        rm -- "$f"
        echo "removed $f"
        removed=$((removed + 1))
    fi
done

[ "$removed" = 0 ] && echo "no state on file for $1"
exit 0
