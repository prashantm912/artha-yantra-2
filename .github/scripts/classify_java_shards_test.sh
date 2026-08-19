#!/usr/bin/env bash
# Drives classify_java_shards.sh with synthetic changed-file lists and asserts the emitted
# shard booleans. Runs in ci-java's `changes` job BEFORE the real classification, so a
# classifier edit that would report a required `build-test (<shard>)` context green without
# building anything fails here instead of silently waving code through.
#
# Assertion format: <case name> | <newline-separated files> | <expected key=value,...>
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
classify="$script_dir/classify_java_shards.sh"
failures=0

# expect <name> <files> <expected k=v[,k=v...]>
expect() {
  local name="$1" files="$2" expected="$3"
  local out
  out="$(bash "$classify" <<<"$files")"
  local pair key want got
  while IFS= read -r pair; do
    [ -n "$pair" ] || continue
    key="${pair%%=*}"
    want="${pair#*=}"
    got="$(grep -E "^$key=" <<<"$out" | cut -d= -f2- || true)"
    if [ "$got" != "$want" ]; then
      echo "FAIL [$name] $key: expected '$want', got '$got'"
      echo "      files: $(tr '\n' ' ' <<<"$files")"
      failures=$((failures + 1))
    fi
  done < <(tr ',' '\n' <<<"$expected")
}

# --- nothing JVM-related: every shard must report success WITHOUT building ---
expect "docs only" \
  "docs/design/COMMON_REFERENCE.md
README.md" \
  "market_data=false,backtest=false,strategy_gateway=false,java=false"

expect "frontend only" \
  "frontend-react/src/pages/SignalsPage.tsx" \
  "market_data=false,backtest=false,strategy_gateway=false,java=false"

# The Python services have their own workflows; they own no JVM shard and must NOT be
# reported as an unowned service.
expect "python service only" \
  "services/optimizer-service/app/service.py" \
  "market_data=false,backtest=false,strategy_gateway=false,java=false,unowned_services="

# --- shared code must fan out to EVERY dependent shard (all 5 libs are in all 3 reactors) ---
expect "libs fan out to all shards" \
  "libs/strategy-engine/src/main/java/in/arthayantra/strategy/Foo.java" \
  "market_data=true,backtest=true,strategy_gateway=true,java=true"

expect "black76-math fans out to all shards" \
  "libs/black76-math/src/main/java/in/arthayantra/black76/Black76.java" \
  "market_data=true,backtest=true,strategy_gateway=true"

expect "root pom fans out" \
  "pom.xml" \
  "market_data=true,backtest=true,strategy_gateway=true"

expect "checkstyle config fans out" \
  "config/checkstyle/checkstyle.xml" \
  "market_data=true,backtest=true,strategy_gateway=true"

expect "flyway lineage fans out" \
  "deploy/flyway/marketdata/V060__x.sql" \
  "market_data=true,backtest=true,strategy_gateway=true"

expect "shared exit fixture fans out" \
  "contracts/fixtures/exit-equivalence.json" \
  "market_data=true,backtest=true,strategy_gateway=true"

expect "the workflow itself fans out" \
  ".github/workflows/ci-java.yml" \
  "market_data=true,backtest=true,strategy_gateway=true"

expect "the classifier itself fans out" \
  ".github/scripts/classify_java_shards.sh" \
  "market_data=true,backtest=true,strategy_gateway=true"

# --- per-service targeting ---
expect "market-data service targets its own shard" \
  "services/market-data-service/src/main/java/in/arthayantra/md/Foo.java" \
  "market_data=true,backtest=false,strategy_gateway=false,java=true"

expect "backtest service targets its own shard" \
  "services/backtest-service/src/main/java/in/arthayantra/backtest/Foo.java" \
  "market_data=false,backtest=true,strategy_gateway=false"

expect "strategy-signal targets the strategy-gateway shard" \
  "services/strategy-signal-service/src/main/java/in/arthayantra/signal/Foo.java" \
  "market_data=false,backtest=false,strategy_gateway=true"

expect "edge-gateway targets the strategy-gateway shard" \
  "services/edge-gateway/src/main/java/in/arthayantra/gateway/Foo.java" \
  "market_data=false,backtest=false,strategy_gateway=true"

# --- contracts/ (the class of gate the old single-boolean classifier could not reach) ---
expect "trial-metrics catalog targets the backtest shard" \
  "contracts/metrics/trial-metrics-catalog.json" \
  "market_data=false,backtest=true,strategy_gateway=false"

# Owning shard PLUS strategy-gateway: edge-gateway's SpecOpenObjectRatchetTest reads every
# committed spec's content and enumerates the whole contracts/ directory.
expect "committed spec targets its owning shard AND the repo-wide ratchet" \
  "contracts/market-data-service.openapi.json" \
  "market_data=true,backtest=false,strategy_gateway=true"

expect "edge-gateway spec targets the strategy-gateway shard" \
  "contracts/edge-gateway.openapi.json" \
  "market_data=false,backtest=false,strategy_gateway=true"

# A PYTHON service's spec still reddens the JVM ratchet, because that test fails on any spec
# in contracts/ it does not account for. Missing this leaves the ratchet unreachable.
expect "python service spec still reaches the repo-wide ratchet" \
  "contracts/optimizer-service.openapi.json" \
  "market_data=false,backtest=false,strategy_gateway=true,java=true"

expect "margin spec still reaches the repo-wide ratchet" \
  "contracts/margin-service.openapi.json" \
  "strategy_gateway=true,market_data=false,backtest=false"

expect "the shared json-schema keyword artifact reaches the ratchet" \
  "contracts/json-schema-2020-12-keywords.json" \
  "market_data=false,backtest=false,strategy_gateway=true"

# --- deploy/docker-compose.yml is a TEST INPUT, not just deploy config ---
# The edit these guards exist to catch — a cron changed, or a passthrough moved to the wrong
# service — touches compose and NOTHING else. Before this rule that classified to no shard at all,
# so CronPassthroughParityTest and SwingCoverageGateDefaultTest never ran and the PR went green.
# backtest stays false deliberately: nothing in that shard reads the file.
expect "a compose-only edit still runs the two shards that assert against it" \
  "deploy/docker-compose.yml" \
  "market_data=true,backtest=false,strategy_gateway=true,java=true"

# Sibling files under deploy/ are NOT test inputs and must not fan out — otherwise every unrelated
# deploy tweak pays two full shards and the rule above stops meaning anything.
expect "another deploy/ file does not reach any shard" \
  "deploy/README.md" \
  "market_data=false,backtest=false,strategy_gateway=false"

# --- market-data's application.yml is a CROSS-SHARD test input ---
# strategy-signal's ContextUnderlyingNamesTest reads market-data's snapshot-underlyings as the
# canonical instrument vocabulary. The edit it exists to catch — a name dropped or renamed there,
# touching nothing in strategy-signal — must still run the strategy_gateway shard, or the guard
# never executes and reds later on an unrelated PR.
expect "a market-data application.yml edit also runs the shard that asserts against it"   "services/market-data-service/src/main/resources/application.yml"   "market_data=true,backtest=false,strategy_gateway=true,java=true"

# A sibling market-data resource is NOT a cross-shard input and must not fan out.
expect "another market-data resource does not reach strategy_gateway"   "services/market-data-service/src/main/resources/nse-holidays.csv"   "market_data=true,backtest=false,strategy_gateway=false"

# --- a service no shard owns: named, which ci-java turns into a HARD FAILURE ---
expect "unowned service fans out and is named" \
  "services/brand-new-service/src/main/java/Foo.java" \
  "market_data=true,backtest=true,strategy_gateway=true,unowned_services=brand-new-service"

# tools/hash-password is in the root reactor but no shard — a pre-existing gap. It lives under
# tools/, not services/, so the unowned guard can never see it and it must NOT start failing
# every PR now that `unowned_services` is a hard failure.
expect "tools/ never trips the unowned guard" \
  "tools/hash-password/src/main/java/in/arthayantra/tools/HashPassword.java" \
  "unowned_services=,market_data=true,backtest=true,strategy_gateway=true"

# --- RENAME EXTRACTION (cross-vendor review finding, 2026-08-03) ---
# `git diff --name-only` reports ONLY the destination of a detected rename, so a cross-shard move
# made the SOURCE shard report its required context green while code was deleted out from under
# it. The classifiers run `git diff --no-renames --name-only` so BOTH paths reach this script.
# These cases pin the classifier's half of that contract; the assert_workflow_greps below pin the
# flag itself, which is the part that can actually regress.
expect "cross-shard rename runs BOTH the source and destination shards" \
  "services/backtest-service/src/main/java/in/arthayantra/backtest/analytics/BenchmarkAnalytics.java
services/market-data-service/src/main/java/in/arthayantra/marketdata/RenameProbe.java" \
  "market_data=true,backtest=true,strategy_gateway=false"

expect "destination-only (what rename detection WOULD have produced) misses the source shard" \
  "services/market-data-service/src/main/java/in/arthayantra/marketdata/RenameProbe.java" \
  "market_data=true,backtest=false,strategy_gateway=false"

expect "moving the metrics catalog out reaches both owning gates" \
  "contracts/metrics/trial-metrics-catalog.json
docs/metrics/trial-metrics-catalog.json" \
  "backtest=true,market_data=false,strategy_gateway=false"

# A loose file directly under services/ is not a service directory and must not trip the guard.
expect "loose file under services/ is not an unowned service" \
  "services/README.md" \
  "unowned_services=,market_data=false,backtest=false,strategy_gateway=false"

# --- combinations ---
expect "two shards at once" \
  "services/backtest-service/src/main/java/A.java
services/edge-gateway/src/main/java/B.java" \
  "market_data=false,backtest=true,strategy_gateway=true"

expect "docs alongside a service change still runs that shard" \
  "README.md
services/market-data-service/src/main/java/A.java" \
  "market_data=true,backtest=false,strategy_gateway=false"

# --- the WORKFLOW side of the contract -------------------------------------------------------
# The classifier above is only half of it. The other half is how the workflows FEED it, and that
# is where the rename bug actually lived: no input this script can be given would have caught a
# missing `--no-renames`, because by then git has already dropped the source path. So assert on
# the workflow text directly. These are the two regressions that reintroduce a green required
# context over a shard that never ran.
workflows="$script_dir/../workflows"

# must_grep <file> <fixed-string> <why>
must_grep() {
  if ! grep -qF -- "$2" "$workflows/$1"; then
    echo "FAIL [$1] missing required text: $2"
    echo "      why: $3"
    failures=$((failures + 1))
  fi
}
# must_not_grep <file> <extended-regex> <why>
# COMMENT LINES ARE STRIPPED FIRST. These files explain the rename trap in prose, and the prose
# necessarily quotes the very form being banned — without this filter the check fired on its own
# documentation and failed permanently (caught while writing it).
must_not_grep() {
  if sed 's/[[:space:]]*#.*$//' "$workflows/$1" | grep -qE -- "$2"; then
    echo "FAIL [$1] contains forbidden pattern in CODE: $2"
    echo "      why: $3"
    failures=$((failures + 1))
  fi
}

# EVERY path classifier in .github/workflows, not just the JVM one — a swept list, verified
# 2026-08-03 to be exhaustive (`grep -rn "git diff" .github/` found these four and nothing else).
# ci-contracts is in here because it had the identical defect: a move out of a service directory
# to docs/ classified contracts=FALSE, and `contracts` is a REQUIRED context, so it reported green
# with every step skipped while a service source file was deleted.
# This runs in ci-java's `changes` job, which is UNGATED and on a workflow with no `paths:` filter,
# so it fires on every PR — including one that touches only ci-contracts.yml.
for wf in ci-java.yml ci-optimizer.yml ci-margin.yml ci-contracts.yml; do
  must_grep "$wf" 'git diff --no-renames --name-only' \
    "without --no-renames git reports only a rename's DESTINATION, so a cross-shard move leaves the source gate green having never run"
  # Catches a partial revert that leaves the flag off on one line while another still has it.
  must_not_grep "$wf" 'git diff --name-only' \
    "a bare 'git diff --name-only' is the rename-blind form; every classifier must use --no-renames"
done

must_grep ci-java.yml 'unowned_services=' \
  "the unowned-service guard must stay wired to the classifier output"
# Deliberately NOT a bare `exit 1` grep: ci-java.yml has several other `exit 1`s (the two
# fail-closed steps), so that assertion would pass with the whole policy deleted. Pin the
# annotation title, which exists only in this policy, and the ::warning form it replaced.
must_grep ci-java.yml '::error title=Service directory not covered by any build-test shard::' \
  "an unmapped service directory is a HARD FAILURE (owner decision 2026-08-03), not a warning — fanning out to three shards does not BUILD it"
must_not_grep ci-java.yml '::warning title=Service not covered by any build-test shard' \
  "the old warning-only form must not come back; it reads as covered while providing none"

if [ "$failures" -ne 0 ]; then
  echo "classify_java_shards: $failures assertion(s) failed"
  exit 1
fi
echo "classify_java_shards: all assertions passed"
