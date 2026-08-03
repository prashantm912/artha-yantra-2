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

# --- a service no shard owns: fan out AND name it (CLAUDE.md sharding rule) ---
expect "unowned service fans out and is named" \
  "services/brand-new-service/src/main/java/Foo.java" \
  "market_data=true,backtest=true,strategy_gateway=true,unowned_services=brand-new-service"

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

if [ "$failures" -ne 0 ]; then
  echo "classify_java_shards: $failures assertion(s) failed"
  exit 1
fi
echo "classify_java_shards: all assertions passed"
