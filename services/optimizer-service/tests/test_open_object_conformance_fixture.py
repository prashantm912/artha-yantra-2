"""Cross-language conformance pin for ``contracts/fixtures/open-object-conformance.json``.

WHY THIS FILE EXISTS, AND WHY IT DOES NOT (YET) CALL THE PRODUCTION RATCHET. The open-object
ratchet exists in two independent implementations of the same schema walk -
``SpecOpenObjectRatchetTest`` in edge-gateway and ``test_open_object_ratchet.py`` here - sharing
an "is this schema open" predicate and a location-string grammar by convention and code review
only. Cross-vendor review round 2 on the PR that introduced both (#1196) found BOTH
implementations returned "closed" for an annotation-only schema like ``{"title": "..."}``, which
is semantically as open as ``{}`` - one bug, two places, found once. This fixture pins the
DOCTRINE (schema shape -> open/closed, and the location-string form) as first-principles-derived
data, so a future drift between the two suites is caught by BOTH independently reading the same
file rather than by a reviewer noticing.

#1196 was still an open, actively-changing PR when this file was authored - its two target files
do not exist on this branch's base. This test therefore carries its OWN reference copy of
``_is_open``, calibrated against ``test_open_object_ratchet.py``'s implementation and
independently re-derived from doctrine for every row in the fixture, rather than importing
production code that is not there to import. ⚠️ Calibration note: as read on the #1196 branch on
2026-08-02, that file's ``_is_open`` does NOT implement the annotation-only branch that
edge-gateway's Java predicate does (it returns False / closed for ``{"title": "..."}``) - a live
cross-language disagreement with this fixture's doctrine, reported rather than quietly matched.
See this PR's open-doubts.

When #1196 lands, the intended follow-up is for both production suites to read this SAME fixture
directly (ideally by exposing their predicate as testable and asserting equality against it,
retiring the duplicate below) - tracked as an open doubt, not silently assumed done here.
"""

import json
import re
from pathlib import Path
from typing import Any


def _find_repo_root() -> Path:
    directory = Path(__file__).resolve().parent
    while not (directory / "contracts").is_dir():
        if directory.parent == directory:
            raise RuntimeError("repo root (contracts/ dir) not found above this test file")
        directory = directory.parent
    return directory


FIXTURE_PATH = _find_repo_root() / "contracts" / "fixtures" / "open-object-conformance.json"

# Keywords that pin what an object may CONTAIN. Any of them present => not an open object.
PINS_CONTENTS = (
    "$ref",
    "allOf",
    "anyOf",
    "oneOf",
    "not",
    "items",
    "prefixItems",
    "enum",
    "const",
    "discriminator",
    "patternProperties",
    "propertyNames",
    "dependentSchemas",
    "unevaluatedProperties",
    "required",
    "minProperties",
    "maxProperties",
)

# Every keyword that says ANYTHING about a value; unrecognised keywords fail OPEN.
CONSTRAINING = PINS_CONTENTS + (
    "type",
    "properties",
    "additionalProperties",
    "unevaluatedItems",
    "contains",
    "dependentRequired",
    "if",
    "then",
    "else",
    "multipleOf",
    "maximum",
    "exclusiveMaximum",
    "minimum",
    "exclusiveMinimum",
    "maxLength",
    "minLength",
    "pattern",
    "format",
    "maxItems",
    "minItems",
    "uniqueItems",
    "maxContains",
    "minContains",
)


def _is_open(node: Any) -> bool:
    """Reference copy of the is-open predicate; see the module docstring for why a copy exists."""
    if any(k in node for k in PINS_CONTENTS):
        return False
    properties = node.get("properties")
    if properties is not None and len(properties) > 0:
        return False
    additional = node.get("additionalProperties")
    if additional is not None and not (additional is True or additional == {}):
        return False
    declared = node.get("type")
    types = declared if isinstance(declared, list) else ([declared] if declared else [])
    declares_object = bool(types) and "object" in types
    if declared is not None:
        return declares_object
    if additional is not None:
        return True
    return not any(k in node for k in CONSTRAINING)


def _load_fixture() -> dict:
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


def test_every_predicate_case_matches_the_fixture():
    fixture = _load_fixture()
    for row in fixture["predicateCases"]:
        actual = _is_open(row["schema"])
        assert actual == row["expectedOpen"], (
            f"predicateCases[{row['id']}] expected isOpenObject={row['expectedOpen']} but got "
            f"{actual} for schema {row['schema']}"
        )


def test_location_grammar_pattern_accepts_positive_examples_and_rejects_negative_ones():
    fixture = _load_fixture()
    grammar = fixture["locationGrammar"]
    pattern = re.compile(grammar["pattern"])

    for example in grammar["positiveExamples"]:
        location = example["location"]
        assert pattern.fullmatch(location), (
            f"expected the location-grammar pattern to MATCH: {location}"
        )

    for example in grammar["negativeExamples"]:
        location = example["location"]
        assert not pattern.fullmatch(location), (
            f"expected the location-grammar pattern to REJECT: {location} ({example['reason']})"
        )
