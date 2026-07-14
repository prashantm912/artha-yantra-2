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

# Remove state for this target+worktree across the KNOWN flow suffixes — the base
# thread ("") and the codex-build sol `_refine` thread. Enumerated (not a prefix
# glob) so it stays worktree-safe: a glob on `...wt-foo` would also match
# `...wt-foo2`; exact filenames per suffix cannot bleed across worktrees.
removed=0
for KEY_SUFFIX in "" "_refine"; do
    for f in "$(thread_file "$1")" "$(review_file "$1")" \
             "$(events_file "$1")" "$(events_file "$1").stderr"; do
        if [ -f "$f" ]; then
            rm -- "$f"
            echo "removed $f"
            removed=$((removed + 1))
        fi
    done
done

[ "$removed" = 0 ] && echo "no state on file for $1"
exit 0
