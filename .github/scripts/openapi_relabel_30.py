#!/usr/bin/env python3
"""Relabel an OpenAPI document to 3.0.x for the breaking-change diff step ONLY.

task_ade97df8. openapi-diff 2.1.7 cannot diff `openapi: 3.1.0` schemas. Measured against our
real specs with the real image:

  * a RENAMED response property reports "No differences. Specifications are equivalent" (exit 0)
    — the property-set change is not even recorded;
  * a retype reports the nonsense "Changed property type: checksum (object -> object)" — the
    parser never resolves `type: string` at 3.1, so it trips on schema identity, not on types.

The SAME BYTES labelled 3.0.1 diff correctly and report `Missing property: items[n].tradingsymbol
(string)`. So the gate relabels both sides before handing them to openapi-diff. The COMMITTED specs
stay 3.1.0 — this is a transform on throwaway copies.

ONE 3.1 construct converts instead of refusing: the nullability type array. `type: ["number",
"null"]` and 3.0's `type: number, nullable: true` state the same fact (a canonical, lossless
two-way mapping), and it is the only way springdoc can express a nullable response field at 3.1 —
swagger-core's 3.1 serializer silently DROPS `nullable`, so `@Schema(types = {"number","null"})`
is the source-side spelling (strategy-signal's stopLoss/target carry it). Genuine unions
(`type: ["string","integer"]`) have no 3.0 equivalent and still refuse.

The relabel is lossless ONLY while the specs use no OTHER 3.1-only construct, and this GUARANTEES
that rather than assuming it — with an ALLOW-LIST, not a deny-list. After relabeling, it VALIDATES the
document against the official OpenAPI 3.0 schema (openapi-spec-validator) and refuses (exit 2) on
ANY validation error. A deny-list is incomplete by construction — the earlier one silently accepted
`if`/`then`/`else`, `$id`, bare `type: "null"` (only the LIST form was caught), schema-level
`examples` arrays, and `components.pathItems`. The 3.0 validator cannot miss a construct 3.0
forbids: whatever 3.1-only shape reaches it, a document labelled 3.0.1 that is not valid 3.0 is
rejected, so the gate fails closed instead of diffing a silently-mangled spec.

The two checks are complementary and together complete. The 3.0 schema validation is the backstop
for every construct 3.0 forbids SYNTACTICALLY (a deny-list cannot match that coverage). The scan()
deny-list adds two things the validator alone does not give: (a) a FAST, HUMAN-READABLE hint that
names the exact keyword and path — what the owner needs to decide between upgrading openapi-diff and
dropping the construct; and (b) the ONE class the structural validator is blind to — a $ref with
sibling keys, which is valid 3.0 yet means something different at 3.1 (3.0 ignores the siblings, 3.1
applies them). A silently mangled spec would make the gate answer the wrong question, which is worse
than the blindness it replaces, so if either check fires, do not delete it — decide, with the owner,
between upgrading openapi-diff and dropping the construct.

Usage: openapi_relabel_30.py <src> <dst> [label]
Exits 2 (a TOOL failure, never a compatibility verdict) if the relabeled document is not valid
OpenAPI 3.0 — including when the validator library is missing (a loud refusal, never a silent skip).
"""

import json
import sys

# JSON-Schema 2020-12 keywords OpenAPI 3.0 does not understand. `examples` is deliberately absent:
# as a MAP it is valid in both, and openapi-diff ignores examples for compatibility either way.
# This set is NOT exhaustive and is NOT relied on to be — it only sharpens the pre-check message;
# the OpenAPI-3.0 schema validation in main() is the authoritative backstop for every SYNTACTIC
# 3.1-only construct (the $ref-sibling semantic class is caught separately in scan(), see docstring).
SCHEMA_31_ONLY = {
    "const", "$schema", "$defs", "unevaluatedProperties", "unevaluatedItems", "prefixItems",
    "contains", "minContains", "maxContains", "dependentSchemas", "dependentRequired",
    "propertyNames", "patternProperties", "contentMediaType", "contentEncoding", "contentSchema",
    "$anchor", "$dynamicRef", "$dynamicAnchor",
}
DOC_31_ONLY = {"webhooks", "jsonSchemaDialect"}


def downgrade_nullable_type_arrays(node):
    """`type: ["number","null"]` is 3.1's ONLY nullability spelling (swagger-core's 3.1 serializer
    silently drops `nullable`), and 3.0 spells the same fact `type: number, nullable: true` — a
    canonical, LOSSLESS two-way mapping, so converting it is a sound relabel, not a mangle. Only
    the exactly-two-entries-one-of-them-"null" shape converts; every other type array still falls
    through to scan()'s refusal (3.0 has no spelling for a genuine union, so converting one WOULD
    mangle)."""
    if isinstance(node, dict):
        t = node.get("type")
        if isinstance(t, list) and len(t) == 2 and "null" in t:
            other = t[0] if t[1] == "null" else t[1]
            if isinstance(other, str) and other != "null":
                node["type"] = other
                node["nullable"] = True
        for value in node.values():
            downgrade_nullable_type_arrays(value)
    elif isinstance(node, list):
        for value in node:
            downgrade_nullable_type_arrays(value)


# Composition keywords this pass must never recurse INTO — see downgrade_nullable_ref_anyof.
COMPOSITION_KEYS = frozenset({"anyOf", "oneOf", "allOf"})


def downgrade_nullable_ref_anyof(node):
    """Strip the exact nullable-``$ref`` union for the throwaway 3.0 diff copy.

    OpenAPI 3.0 has no spelling for a nullable ``$ref``. openapi-diff is blind to nullability in both
    directions (measured with the pinned tool), so this copy legitimately drops only that fact and
    remains byte-identical to the pre-annotation copy. Genuine unions and every other ``anyOf`` shape
    still fall through to the validator/refusal path.

    **Never descends INTO a composition branch** (``anyOf`` / ``oneOf`` / ``allOf``). Recursing into
    one was a real hole, caught in review: ``oneOf: [anyOf: [$ref, null], string]`` got rewritten to
    ``oneOf: [$ref, string]`` — a silent semantic change that is VALID 3.0, so the validator backstop
    could never refuse it. The whole point of this pass is that it either performs the one exact
    substitution or leaves the document for the validator to reject; anything it can quietly mangle
    defeats that. A nullable ``$ref`` nested inside another composition is therefore left alone and
    correctly refused downstream.
    """
    if isinstance(node, dict):
        any_of = node.get("anyOf")
        if set(node) == {"anyOf"} and isinstance(any_of, list) and len(any_of) == 2:
            ref_branch, null_branch = any_of
            if (
                isinstance(ref_branch, dict)
                and set(ref_branch) == {"$ref"}
                and isinstance(ref_branch["$ref"], str)
                and isinstance(null_branch, dict)
                and set(null_branch) == {"type"}
                and null_branch["type"] == "null"
            ):
                node.clear()
                node["$ref"] = ref_branch["$ref"]
                return
        for key, value in node.items():
            if key in COMPOSITION_KEYS:
                continue  # see the docstring: descending here can silently mangle a nested union
            downgrade_nullable_ref_anyof(value)
    elif isinstance(node, list):
        for value in node:
            downgrade_nullable_ref_anyof(value)


def scan(node, path, found):
    if isinstance(node, dict):
        # A $ref with sibling keys is the ONE version-divergent shape the 3.0 SCHEMA validator
        # cannot catch: it is structurally VALID 3.0, but 3.0 IGNORES the siblings while 3.1
        # APPLIES them, so relabeling would silently drop a constraint. The structural backstop is
        # blind here, so the deny-list is load-bearing for this class — refuse rather than mangle.
        if "$ref" in node and len(node) > 1:
            siblings = sorted(k for k in node if k != "$ref")
            found.append((path or "/", "$ref with sibling keys %r (3.0 ignores them, 3.1 applies)" % siblings))
        for key, value in node.items():
            here = "%s/%s" % (path, key)
            if key in SCHEMA_31_ONLY:
                found.append((here, "3.1-only schema keyword: %s" % key))
            # A type array that survived downgrade_nullable_type_arrays is a genuine union —
            # 3.0 cannot express it, so refuse rather than mangle.
            if key == "type" and isinstance(value, list):
                found.append((here, "3.1-only type array: %r" % (value,)))
            # 3.0 spells these as booleans; 3.1 as numbers.
            if key in ("exclusiveMinimum", "exclusiveMaximum") and not isinstance(value, bool):
                found.append((here, "3.1-style numeric %s: %r" % (key, value)))
            scan(value, here, found)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            scan(value, "%s/%d" % (path, index), found)


def deny_list_hits(spec):
    """Fast pre-check: the 3.1-only constructs we can name precisely for the owner."""
    found = []
    for key in DOC_31_ONLY:
        if key in spec:
            found.append(("/%s" % key, "3.1-only document key: %s" % key))
    scan(spec.get("components"), "/components", found)
    scan(spec.get("paths"), "/paths", found)
    return found


def is_valid_30(spec, src):
    """Authoritative backstop: the relabeled doc must be valid OpenAPI 3.0, or the gate fails
    closed. openapi-spec-validator selects the 3.0 schema from the `openapi` field just set, so any
    3.1-only construct 3.0 forbids is rejected here even when the deny-list never listed it. A
    missing library is a LOUD refusal (exit 2), never a silent skip."""
    try:
        from openapi_spec_validator import validate
    except ImportError:
        print(
            "::error::openapi-spec-validator is not installed, so the OpenAPI-3.0 backstop that "
            "makes this relabel sound cannot run. The gate refuses to diff a possibly-mangled spec "
            "rather than pass blind. Install it: python3 -m pip install openapi-spec-validator"
        )
        return False
    try:
        validate(spec)
        return True
    except Exception as error:  # OpenAPIValidationError and any resolver/parse failure.
        where = getattr(error, "json_path", "<root>")
        message = str(getattr(error, "message", error)).splitlines()[0][:200]
        print(
            "::error::%s is not valid OpenAPI 3.0 after the relabel, so the 3.0 diff would read a "
            "SILENTLY MANGLED spec — a 3.1-only construct 3.0 does not permit. The gate cannot "
            "answer its question and fails." % src
        )
        print("  at %s -> %s" % (where, message))
        return False


def main():
    src, dst = sys.argv[1], sys.argv[2]
    label = sys.argv[3] if len(sys.argv) > 3 else "3.0.1"

    with open(src, encoding="utf-8") as handle:
        spec = json.load(handle)

    # BEFORE the deny-list: the one 3.1 construct with an exact 3.0 equivalent converts
    # losslessly (nullability type arrays); anything the converter leaves behind still refuses.
    downgrade_nullable_type_arrays(spec.get("components"))
    downgrade_nullable_type_arrays(spec.get("paths"))
    downgrade_nullable_ref_anyof(spec.get("components"))
    downgrade_nullable_ref_anyof(spec.get("paths"))

    found = deny_list_hits(spec)
    if found:
        print(
            "::error::%s uses OpenAPI 3.1-only constructs, so the 3.0 relabel the breaking gate "
            "needs would SILENTLY MANGLE it. The gate cannot answer its question and fails." % src
        )
        for where, why in found:
            print("  %s -> %s" % (where, why))
        return 2

    spec["openapi"] = label

    # An allow-list validator cannot miss a construct the deny-list above omits — this is the
    # real guarantee that the relabel is lossless.
    if not is_valid_30(spec, src):
        return 2

    with open(dst, "w", encoding="utf-8") as handle:
        json.dump(spec, handle)
    return 0


if __name__ == "__main__":
    sys.exit(main())
