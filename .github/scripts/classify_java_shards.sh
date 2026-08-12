#!/usr/bin/env bash
# Maps a changed-file list (stdin, one path per line) to the `build-test` shards that
# actually need to run, emitting `key=value` lines on stdout for $GITHUB_OUTPUT.
#
# WHY THIS IS A SCRIPT AND NOT AN INLINE `run:` BLOCK (unlike ci-contracts' classifier):
# the three `build-test (<shard>)` job names are REQUIRED CONTEXTS. A bug that wrongly
# emits `false` does not fail — it reports a required check GREEN without having built
# anything, which is the one failure mode no human notices. So the logic lives in a file
# that classify_java_shards_test.sh can drive with synthetic file lists, and that test runs
# in CI ahead of the classification itself.
#
# THE FAN-OUT RULE, DERIVED FROM THE REAL REACTOR — NOT GUESSED. Measured 2026-08-03 with
# `./mvnw -o -pl <shard modules> -am validate` on each of the three shards; all three
# reactors contain the parent POM and ALL FIVE libs (common-web{,-core,-servlet},
# market-calendar, strategy-schema, strategy-engine, black76-math). `-am` builds and TESTS
# every reactor module, so a `libs/` edit is exercised by all three shards today and MUST
# keep triggering all three. (The pre-existing ci-java header comment claiming black76-math
# "rides market-data/backtest" only is stale — it is in the strategy-gateway reactor too,
# transitively. Left in place; corrected here.)
#
# Fail-safe direction is RUN: anything unrecognised fans out to every shard. Over-triggering
# costs ~8 wasted minutes; under-triggering merges untested code.
set -euo pipefail

files="$(cat)"

market_data=false
backtest=false
strategy_gateway=false

mark_all() {
  market_data=true
  backtest=true
  strategy_gateway=true
}

# Anything every shard's `-am` reactor pulls in, plus the build/lint configuration that
# changes how every shard compiles. deploy/flyway is here because the ITs migrate the REAL
# lineages into Testcontainers (audit P2) — a column rename merges green otherwise and
# breaks every service IT on the next unrelated PR. contracts/fixtures is here because
# exit-equivalence.json is asserted by backtest, market-data AND strategy-signal suites.
if grep -Eq '^(libs/|tools/|\.mvn/|config/checkstyle/|deploy/flyway/|contracts/fixtures/|pom\.xml$|\.github/workflows/ci-java\.yml$|\.github/scripts/classify_java_shards(_test)?\.sh$)' <<<"$files"; then
  mark_all
fi

# Per-shard inputs. `contracts/<svc>.` matches the committed OpenAPI spec that service's
# ContractCaptureTest asserts against; a hand-edit of that file is a real way to redden the
# owning shard, and the old single-boolean classifier did not match contracts/ at all.
if grep -Eq '^(services/market-data-service/|contracts/market-data-service\.)' <<<"$files"; then
  market_data=true
fi
# contracts/metrics/ = trial-metrics-catalog.json, pinned by backtest-service's
# TrialMetricsCatalogConsistencyTest (and, on the Python side, by ci-optimizer).
if grep -Eq '^(services/backtest-service/|contracts/backtest-service\.|contracts/metrics/)' <<<"$files"; then
  backtest=true
fi
if grep -Eq '^(services/(strategy-signal-service|edge-gateway)/|contracts/(strategy-signal-service|edge-gateway)\.)' <<<"$files"; then
  strategy_gateway=true
fi
# edge-gateway's SpecOpenObjectRatchetTest is REPO-WIDE, not edge-gateway-scoped, so it pulls
# extra inputs into this shard (verified by reading the test, 2026-08-03):
#   - `everyCommittedSpecIsEitherRatchetedOrDeclaredOutOfScope` LISTS contracts/*.openapi.json and
#     fails on any spec accounted for in neither FROZEN_OPEN_OBJECTS nor OUT_OF_SCOPE — so ADDING
#     or REMOVING any spec, INCLUDING the Python services' (optimizer/margin), reddens this shard.
#   - it reads each accounted spec's CONTENT, and
#   - `keywordArtifact()` reads contracts/json-schema-2020-12-keywords.json.
# Without this rule a spec-only edit would classify to no shard at all and the ratchet would be
# unreachable — the same class of hole the old contracts-blind classifier had.
if grep -Eq '^contracts/([^/]*\.openapi\.json|json-schema-2020-12-keywords\.json)$' <<<"$files"; then
  strategy_gateway=true
fi
# deploy/docker-compose.yml is a TEST INPUT for both of these shards, not just deploy config:
#   - CronPassthroughParityTest (both services) reads every ARTHA_*_CRON default out of it and
#     fails if one drifts from the @Scheduled code default it is supposed to mirror, and
#   - strategy-signal's SwingCoverageGateDefaultTest reads the coverage-gate passthrough.
# Without this rule the one edit those guards exist to catch — a cron changed in compose, touching
# nothing else — classifies to NO shard, so the guard never runs and the PR goes green. That was
# already true of SwingCoverageGateDefaultTest before this rule existed. backtest is deliberately
# NOT marked: nothing in that shard reads this file.
if grep -Eq '^deploy/docker-compose\.yml$' <<<"$files"; then
  market_data=true
  strategy_gateway=true
fi

# Every service directory this repo knows about. The four JVM services are mapped to shards
# above; the two Python services own their own workflows (ci-optimizer / ci-margin) and are
# listed here so they do not read as unowned. EDIT THIS when a service is added, renamed or
# removed — ci-java.yml turns a non-empty `unowned_services` into a hard failure (owner
# decision 2026-08-03), because CLAUDE.md's rule is "add a matrix shard or its tests NEVER run
# in CI", and fanning out to three shards does NOT build a new service. It only looks busy.
KNOWN_SERVICES='market-data-service|backtest-service|strategy-signal-service|edge-gateway|optimizer-service|margin-service'

# Note this matches `services/<dir>/`, so a loose file directly under services/ (e.g.
# services/README.md) is not a service and does not trip it. `tools/` is a different tree
# entirely and can never reach this — tools/hash-password is in the root reactor but no shard,
# a pre-existing gap that must NOT start failing every PR.
unowned="$(grep -Eo '^services/[^/]+/' <<<"$files" \
  | cut -d/ -f2 \
  | sort -u \
  | grep -Ev "^($KNOWN_SERVICES)$" \
  | paste -sd, - || true)"
# Still fan out. The workflow fails the run on this, so the booleans are moot there — but they
# stay fail-safe for any caller that treats `unowned_services` as advisory.
if [ -n "$unowned" ]; then
  mark_all
fi

# `java` stays for build-images (main-push only), which must not package an unchanged tree.
java=false
if [ "$market_data" = true ] || [ "$backtest" = true ] || [ "$strategy_gateway" = true ]; then
  java=true
fi

echo "market_data=$market_data"
echo "backtest=$backtest"
echo "strategy_gateway=$strategy_gateway"
echo "java=$java"
echo "unowned_services=$unowned"
