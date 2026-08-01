#!/usr/bin/env python3
"""Derive the JSON Schema 2020-12 subschema-bearing keyword inventory from the NORMATIVE source.

WHY THIS EXISTS. `SpecOpenObjectRatchetTest` (and its two Python siblings) assert that every
subschema-bearing keyword is either descended by the walker or explicitly excused. That assertion
was originally written against a HAND-MAINTAINED list, which made it circular: a keyword could
arrive unclassified and the equality still passed, because the expected set omitted the same
keyword. Cross-vendor review found exactly that -- the hand list omitted `$defs` and
`contentSchema`, and wrongly included `$ref` (which takes a URI string, not a subschema).

THE RULE, applied to the published 2020-12 vocabulary meta-schemas: a keyword takes subschema(s)
iff its declaration contains the dialect's idiom for "a schema goes here", `{"$dynamicRef":
"#meta"}` -- directly (`items`), under `additionalProperties` (`properties`, `$defs`), or through a
local `$ref` such as `#/$defs/schemaArray` (`allOf`). Nothing here is a judgement call.

USAGE:  python tools/derive-json-schema-keywords.py [--check]
        --check exits non-zero if the committed artifact disagrees with the derivation.

⚠️ BOUNDED CLAIM -- read this before trusting the artifact. `jsonschema-specifications` is NOT a
declared dependency of any service (it is a dev-machine package), so this does NOT run in CI and
the artifact is NOT re-derived per build. What is guaranteed is that the list was MECHANICALLY
derived from the normative meta-schemas rather than typed from memory, and that re-deriving it is
one command. What is NOT guaranteed is freshness against a future dialect.

⚠️ AND THE FIRST VERSION OF THAT BOUND WAS DRAWN IN THE WRONG PLACE. It described a future-dialect
freshness risk while the artifact was, at that moment, incomplete for the CURRENT dialect: scanning
`vocabularies/` alone missed the default metaschema's own compatibility layer, so `definitions`,
schema-valued `dependencies` and `$recursiveRef` were absent and a schema using `required` plus a
legacy `dependencies` entry reported nothing at all. "Derive from the vocabularies" is not the same
as "derive from the dialect". The dialect guard is real but it only ever covered the future risk;
completeness for TODAY comes from reading the metaschema itself, which is what this now does.
"""

import argparse
import json
import pathlib
import sys

ARTIFACT = pathlib.Path(__file__).resolve().parents[1] / "contracts" / "json-schema-2020-12-keywords.json"
DIALECT = "https://json-schema.org/draft/2020-12/schema"
# What OpenAPI 3.1 means when `jsonSchemaDialect` is omitted (OAS 3.1.1 §4.8.24.1).
OAS_31_BASE_DIALECT = "https://spec.openapis.org/oas/3.1/dialect/base"
SCHEMA_SLOT = {"$dynamicRef": "#meta"}


def _contains_schema_slot(node, defs) -> bool:
    """True when a keyword declaration has a subschema slot, following local $refs."""
    if isinstance(node, dict):
        if node.get("$dynamicRef") == "#meta":
            return True
        ref = node.get("$ref")
        if isinstance(ref, str) and ref.startswith("#/$defs/"):
            return _contains_schema_slot(defs.get(ref[len("#/$defs/"):], {}), defs)
        return any(_contains_schema_slot(v, defs) for v in node.values())
    if isinstance(node, list):
        return any(_contains_schema_slot(v, defs) for v in node)
    return False


def _is_reference_form(declaration, defs) -> bool:
    """True when a keyword's value is a URI REFERENCE pointing at another schema.

    Derived the same way as the schema slot: the declaration resolves to the dialect's
    ``uriReferenceString``. That yields $ref, $dynamicRef, $recursiveRef -- and $id, which is an
    identifier rather than a pointer to follow and is classified as such by the consumers.
    """
    if isinstance(declaration, dict):
        ref = declaration.get("$ref")
        if isinstance(ref, str) and ref.endswith("/uriReferenceString"):
            return True
        return any(_is_reference_form(v, defs) for v in declaration.values())
    return False


def derive() -> dict:
    import importlib.metadata as md
    import importlib.resources as resources

    import jsonschema_specifications

    root = resources.files(jsonschema_specifications) / "schemas" / "draft202012"

    # ⚠️ DERIVE FROM THE DIALECT, NOT MERELY ITS VOCABULARIES. The first cut of this script walked
    # draft202012/vocabularies/* only, and so missed the DEFAULT METASCHEMA'S OWN compatibility
    # layer -- `definitions`, schema-valued `dependencies` and `$recursiveRef`, all of which the
    # published 2020-12 metaschema still declares. That was CURRENT-dialect incompleteness, not the
    # future-dialect staleness the bounded claim described: a schema using `required` plus a
    # schema-valued legacy `dependencies` entry reported nothing at all.
    metaschema = json.loads((root / "metaschema.json").read_text(encoding="utf-8"))
    sources: list[tuple[str, dict]] = [("metaschema (compatibility layer)", metaschema)]
    for branch in metaschema.get("allOf", []):
        ref = branch.get("$ref", "")
        if ref.startswith("meta/"):
            name = ref[len("meta/"):]
            sources.append((name, json.loads((root / "vocabularies" / name).read_text("utf-8"))))

    bearing: dict[str, str] = {}
    references: dict[str, str] = {}
    for origin, doc in sources:
        defs = doc.get("$defs", {})
        for keyword, declaration in (doc.get("properties") or {}).items():
            if _contains_schema_slot(declaration, defs):
                bearing.setdefault(keyword, origin)
            elif _is_reference_form(declaration, defs):
                references.setdefault(keyword, origin)

    return {
        "_comment": "GENERATED by tools/derive-json-schema-keywords.py - do not hand-edit.",
        "dialect": DIALECT,
        # ⚠️ The dialect a document EFFECTIVELY uses is not this URI. OpenAPI 3.1 (OAS 3.1.1
        # §4.8.24.1/§4.8.24.5) says an omitted `jsonSchemaDialect` means the OAS BASE dialect, not
        # raw 2020-12 -- and all six committed contracts omit the field. Asserting they declare
        # 2020-12 asserted something the documents never said. The OAS base dialect EXTENDS 2020-12
        # with the OAS vocabulary (discriminator / xml / externalDocs / example), none of which is
        # subschema-bearing, so this inventory is complete for both. That last clause is reasoned,
        # not derived: the OAS dialect meta-schema is not bundled with jsonschema-specifications,
        # so nothing here machine-checks it.
        "applicableDialects": [DIALECT, OAS_31_BASE_DIALECT],
        "omittedJsonSchemaDialectMeans": OAS_31_BASE_DIALECT,
        "rule": 'subschemaBearing: the declaration contains {"$dynamicRef": "#meta"}. '
                'referenceCandidates: the declaration resolves to uriReferenceString.',
        "derivedFrom": "jsonschema-specifications %s - the 2020-12 DEFAULT METASCHEMA (its own "
                       "compatibility layer) plus every vocabulary it allOf-references"
                       % md.version("jsonschema-specifications"),
        "subschemaBearing": sorted(bearing),
        "referenceCandidates": sorted(references),
        "declaredIn": {k: bearing[k] for k in sorted(bearing)}
        | {k: references[k] for k in sorted(references)},
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    derived = derive()
    if not args.check:
        ARTIFACT.write_text(json.dumps(derived, indent=2) + "\n", encoding="utf-8", newline="\n")
        print("wrote %s (%d keywords)" % (ARTIFACT, len(derived["subschemaBearing"])))
        return 0

    if not ARTIFACT.exists():
        print("missing artifact: %s" % ARTIFACT, file=sys.stderr)
        return 1
    committed = json.loads(ARTIFACT.read_text(encoding="utf-8"))

    # ⚠️ COMPARE THE WHOLE SEMANTIC ARTIFACT, never a list of fields somebody remembered.
    # The first version of this check enumerated the fields to compare and named exactly one
    # ("subschemaBearing"), so `dialect` and `referenceCandidates` could both be replaced with
    # nonsense and it still printed "artifact matches the derivation" and exited 0 -- while the
    # reference classification it left unguarded had just become load-bearing. Enumerating what to
    # check has lost repeatedly in this file's history; the default is now COMPARE, with a short
    # list of provenance keys excluded for a stated reason. A new derived field is therefore
    # covered the moment derive() emits it, instead of the moment somebody remembers to add it.
    excluded = {
        "_comment",     # static boilerplate, carries no derived fact
        "derivedFrom",  # embeds the generating package's VERSION: bumping it must not fail a
                        # check whose subject is the derived content, which is compared in full
    }
    mismatches = {
        key: (committed.get(key), value)
        for key, value in derived.items()
        if key not in excluded and committed.get(key) != value
    }
    stale = sorted(set(committed) - set(derived) - excluded)
    if mismatches or stale:
        for key, (was, now) in sorted(mismatches.items()):
            print("artifact disagrees on %r.\n  committed: %s\n  derived  : %s" % (key, was, now),
                  file=sys.stderr)
        if stale:
            print("artifact carries fields the derivation no longer emits: %s" % stale,
                  file=sys.stderr)
        return 1
    print("artifact matches the derivation on %d field(s): %s"
          % (len(derived) - len(excluded), sorted(set(derived) - excluded)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
