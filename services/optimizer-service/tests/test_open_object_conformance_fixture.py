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
``_is_open``, independently re-derived from doctrine for every row in the fixture and empirically
checked against the actual committed #1196 code at the time of writing (imported and called
directly, not read-and-hand-traced), rather than importing production code that is not there to
import.

When #1196 lands, the intended follow-up is for both production suites to read this SAME fixture
directly (ideally by exposing their predicate as testable and asserting equality against it,
retiring the duplicate below) - tracked as an open doubt, not silently assumed done here.

CORRECTION HISTORY, kept honest rather than quietly folded away. This fixture's first draft got
two things wrong, both caught by cross-vendor review, neither by hand-tracing after the fact:
(1) it claimed a live disagreement between #1196's Java and Python predicates over
``{"title": "..."}``, based on reading an ACTIVELY-CHANGING worktree rather than running the
committed code - importing and calling the real function immediately falsified it, both sides
agree and match this fixture. (2) it classified ``required``/``minProperties``/``patternProperties``
as pinning on mere PRESENCE and ``format`` as always constraining; review ruled the first three are
constraints only at a NON-vacuous value and ``format`` is never a constraint by default (OpenAPI
3.1 / JSON Schema 2020-12: format is annotation-only unless the format-assertion vocabulary is
explicitly active, which neither springdoc nor pydantic opt into). Both fixes are reflected in
``_is_open`` below and pinned by fixture rows.
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

# Keywords that ALWAYS pin what an object may CONTAIN, at any value. required/minProperties/
# patternProperties are handled below instead, VALUE-aware, because each has a vacuous value
# (empty list / zero / empty dict) that constrains nothing - a correction from this fixture's
# first draft, which put them here unconditionally; cross-vendor review of #1196 ruled that
# mistakes a keyword's PRESENCE for a real constraint. See the module docstring.
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
    "propertyNames",
    "dependentSchemas",
    "unevaluatedProperties",
    "maxProperties",
)

# Every keyword that says ANYTHING about a value; unrecognised keywords fail OPEN. `format` is
# deliberately absent - annotation-only by default, never a constraint (another first-draft
# correction; see the module docstring) - and `properties` is deliberately absent too: by the
# time this fallback runs it can only be absent or vacuously empty (a non-empty value already
# returned False above), so testing its presence here would silently reintroduce that same bug.
CONSTRAINING = PINS_CONTENTS + (
    "type",
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
    "maxItems",
    "minItems",
    "uniqueItems",
    "maxContains",
    "minContains",
)


def _has_non_vacuous_required(node: dict) -> bool:
    required = node.get("required")
    return required is not None and len(required) > 0


def _has_non_vacuous_min_properties(node: dict) -> bool:
    min_properties = node.get("minProperties")
    return min_properties is not None and min_properties > 0


def _has_non_vacuous_pattern_properties(node: dict) -> bool:
    pattern_properties = node.get("patternProperties")
    return pattern_properties is not None and len(pattern_properties) > 0


def _is_open(node: Any) -> bool:
    """Reference copy of the is-open predicate; see the module docstring for why a copy exists."""
    if any(k in node for k in PINS_CONTENTS):
        return False
    if (
        _has_non_vacuous_required(node)
        or _has_non_vacuous_min_properties(node)
        or _has_non_vacuous_pattern_properties(node)
    ):
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
