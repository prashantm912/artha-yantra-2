#!/usr/bin/env bash
#
# THE contract-service list for ci-contracts.yml. Derived, not typed out, and
# fail-loud about anything it cannot classify.
#
# WHY THIS EXISTS - measured on this file's parent commit, 2026-08-01.
# ci-contracts.yml carried FIVE independent hardcoded service lists:
#     the Maven capture reactor (-pl ...), the breaking gate, the
#     removed-component-name gate, the warn-vs-code step, and the tsc --strict loop.
# A newly added service appeared in NONE of them and NOTHING failed - the checks
# simply never looked at it, and a check that never looked is indistinguishable from
# a check that passed. This is the same shape as the documented ci-java.yml trap
# ("adding a new service? add a matrix shard or its tests NEVER run in CI"), except
# that one is written down and this one was not.
#
# SOURCE OF TRUTH: contracts/<svc>.openapi.json.
# ci-contracts.yml's own opening line says "the committed OpenAPI 3.1 specs are the
# contract", so the set of committed specs IS the set of services with a published
# contract - definitional, not a heuristic. Two candidates were rejected:
#   - services/*/            over-enumerates. margin-service is a real FastAPI service
#                            with no committed OpenAPI spec (see EXEMPT_SERVICES).
#   - services/<svc>/target/contracts/<svc>.openapi.json
#                            only exists AFTER the Maven capture run, so it cannot
#                            drive the capture step that produces it, and an empty
#                            glob would silently yield an empty list - a false green.
#
# THE JAVA / NON-JAVA SPLIT is what the five lists actually disagreed about. A Java
# service has services/<svc>/pom.xml and therefore a freshly captured
# target/contracts artifact; a non-Java one has only its committed dump. That single
# distinction reproduces all five lists exactly (proven in the PR's membership table).
#
# WHAT A NEW SERVICE NOW COSTS:
#   - new JAVA service + committed spec  -> picked up by every gate automatically.
#   - new NON-JAVA service + spec        -> this script FAILS until someone adds it to
#                                           NON_JAVA_SERVICES below, which is also the
#                                           act of accepting that it skips the breaking
#                                           gate and the warn step. Deliberate, reviewed,
#                                           never silent.
#   - new service with NO spec at all    -> this script FAILS until someone either
#                                           commits a spec or adds it to EXEMPT_SERVICES
#                                           with a reason.
#
# Emits KEY=value assignments on stdout (append to $GITHUB_ENV); diagnostics and the
# per-gate coverage table go to stderr. Names only, never bytes, so *.json eol=lf and a
# CRLF checkout are irrelevant here.

set -euo pipefail
export LC_ALL=C

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"

# ---- THE DECLARATIONS -------------------------------------------------------------
# Both are cross-checked against the tree below, so a stale entry fails just as loudly
# as a missing one.

# Services WITH a committed spec but WITHOUT a Maven module. Consequence of being here,
# stated so nobody adds a name without accepting it: this service is excluded from the
# breaking gate and from the warn-vs-code step, and its removed-component-name check
# reads the COMMITTED spec instead of a freshly captured artifact.
#   optimizer-service - Python/FastAPI. Excluded from the breaking gate for a second,
#     independent and measured reason as well (openapi_relabel_30.py refuses the pydantic
#     `anyOf: [{...}, {"type":"null"}]` form, 134 occurrences); see the long loop-1 comment
#     in ci-contracts.yml's breaking-gate step. It gets the semantic staleness gate instead.
#   margin-service - Python/FastAPI SPAN appliance, gated into ci-contracts alongside
#     optimizer-service (previously EXEMPT_SERVICES - see below). THIS declaration (NON_JAVA_
#     SERVICES membership) is what excludes it from the breaking gate: that loop iterates
#     AY_CONTRACT_SERVICES_JAVA only, categorically, regardless of spec content.
#     openapi_relabel_30.py refusing its spec is a SEPARATE, independent reason it could not
#     ride that gate even if it were added to the loop: 6 of its pydantic `Optional` fields
#     (LegIn/PositionIn.expiry+strike, SizeResponse.limitingRail, SizeRequest.stop) carry a
#     primitive `anyOf: [{type: X}, {type: "null"}, ...]` WITH a `title` sibling, and its
#     plain-dict /health response carries the SAME primitive nullable-anyOf shape but BARE -
#     no `title` on the anyOf node itself (the title sits on the parent object schema wrapping
#     it). Measured: openapi_relabel_30.py exits 2 on contracts/margin-service.openapi.json,
#     first at paths./health.get.responses.200. Closing this for real needs BOTH a converter
#     fix (today's converters handle only a bare $ref+null anyOf or a type-ARRAY nullable,
#     never a primitive anyOf, titled or not) AND including non-Java specs in the breaking
#     loop - a separate, higher-risk redesign. margin-service gets the semantic staleness gate
#     instead (margin_spec_staleness.py mirrors optimizer_spec_staleness.py) - which, like its
#     sibling, projects route surface only (method/path/params/requestBody/response-codes),
#     never component properties or types: a response-field rename (e.g. SizeResponse.target
#     -> targetPrice) changes neither the surface nor any component KEY, so it is caught by NO
#     current gate, permanently - not a first-PR artifact that a later PR closes.
NON_JAVA_SERVICES="optimizer-service margin-service"

# Directories under services/ that are NOT contract services at all - no committed
# OpenAPI spec, therefore in none of ci-contracts' gates. Currently empty: margin-service
# was the last resident (Python/FastAPI SPAN appliance with only a hand-maintained
# api-surface.json and no OpenAPI document) and has since been given one - see
# NON_JAVA_SERVICES above.
EXEMPT_SERVICES=""
# -----------------------------------------------------------------------------------

die() { printf 'contract-service inventory: %s\n' "$*" >&2; exit 1; }
has() { case " $2 " in *" $1 "*) return 0 ;; esac; return 1; }

# ---- Derive ALL from the committed specs -------------------------------------------
ALL=""
for spec in contracts/*.openapi.json; do
  # Unmatched glob expands to the literal pattern; an empty list must never be mistaken
  # for "no services to check" - every consuming loop would run zero times and exit 0.
  [ -e "$spec" ] || die "no contracts/*.openapi.json found under $REPO - refusing to hand every gate an empty service list, which would pass green having checked nothing."
  svc="$(basename "$spec" .openapi.json)"
  ALL="${ALL:+$ALL }$svc"
done

# ---- Cross-check every committed spec against the tree ------------------------------
NON_JAVA=""
JAVA=""
for svc in $ALL; do
  [ -d "services/$svc" ] || die "contracts/$svc.openapi.json has no services/$svc/ directory. Either the service was renamed or removed and its spec was left behind, or the spec is misnamed - the gates below would look for artifacts that cannot exist."
  if [ -f "services/$svc/pom.xml" ]; then
    if has "$svc" "$NON_JAVA_SERVICES"; then
      die "$svc is declared in NON_JAVA_SERVICES but services/$svc/pom.xml exists. Remove it from the declaration: as a Maven module it can and must ride the breaking gate and the warn step."
    fi
    JAVA="${JAVA:+$JAVA }$svc"
  else
    if ! has "$svc" "$NON_JAVA_SERVICES"; then
      die "$svc publishes contracts/$svc.openapi.json but has no services/$svc/pom.xml, and is not declared in NON_JAVA_SERVICES. A non-Maven service produces no target/contracts artifact, so it cannot ride the breaking gate or the warn step - and silently omitting it is exactly the hole this script exists to close. Add it to NON_JAVA_SERVICES in $0 WITH the reason, which is also the act of accepting that reduced coverage."
    fi
    NON_JAVA="${NON_JAVA:+$NON_JAVA }$svc"
  fi
done

# ---- Cross-check the declarations for staleness -------------------------------------
for svc in $NON_JAVA_SERVICES; do
  has "$svc" "$ALL" || die "NON_JAVA_SERVICES names '$svc', which publishes no contracts/$svc.openapi.json. Stale declaration - drop it."
done
for svc in $EXEMPT_SERVICES; do
  [ -d "services/$svc" ] || die "EXEMPT_SERVICES names '$svc', which is not a directory under services/. Stale declaration - drop it."
  if has "$svc" "$ALL"; then
    die "EXEMPT_SERVICES names '$svc', but contracts/$svc.openapi.json now exists. It is a contract service - drop it from EXEMPT_SERVICES so the gates pick it up."
  fi
done

# ---- Every service directory must be accounted for ----------------------------------
# Closes the wider hole: a new service that never commits a spec is invisible to a
# contracts/*-derived list, so deriving alone would still let it slip past unnoticed.
for dir in services/*/; do
  svc="$(basename "$dir")"
  has "$svc" "$ALL" && continue
  has "$svc" "$EXEMPT_SERVICES" && continue
  die "services/$svc/ exists but publishes no contracts/$svc.openapi.json and is not in EXEMPT_SERVICES. A service with no committed spec is in NONE of ci-contracts' gates. Either capture and commit its spec, or declare it in EXEMPT_SERVICES in $0 with the reason."
done

[ -n "$JAVA" ] || die "no Java contract services resolved - every Maven-backed gate would loop zero times and report green having checked nothing."

# ---- Report + emit -------------------------------------------------------------------
{
  printf '\n'
  printf 'contract-service inventory (source of truth: contracts/*.openapi.json)\n'
  printf '%-26s %-8s %-9s %-13s %-15s %s\n' service capture breaking removed-name warn-recapture tsc-strict
  for svc in $ALL; do
    if has "$svc" "$NON_JAVA"; then
      printf '%-26s %-8s %-9s %-13s %-15s %s\n' "$svc" -- -- yes -- yes
    else
      printf '%-26s %-8s %-9s %-13s %-15s %s\n' "$svc" yes yes yes yes yes
    fi
  done
  printf '\n'
  printf 'non-Java (no Maven capture artifact; skips the breaking gate + the warn step): %s\n' "${NON_JAVA:-<none>}"
  printf 'services/ dirs with no committed spec, in NO gate by declaration: %s\n' "${EXEMPT_SERVICES:-<none>}"
  printf '\n'
} >&2

printf 'AY_CONTRACT_SERVICES_ALL=%s\n' "$ALL"
printf 'AY_CONTRACT_SERVICES_JAVA=%s\n' "$JAVA"
printf 'AY_CONTRACT_SERVICES_NON_JAVA=%s\n' "$NON_JAVA"
