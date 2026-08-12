"""Position-attribution ratchet — the Python half of strategy-signal-service's
``PaperNaturalKeyRatchetTest``, same rule, same registration grammar.

WHY THIS FILE EXISTS, AND WHY IT IS NOT IN THE JAVA TEST. The count of places that can attribute a
paper position to the wrong strategy moved four times across four rounds of manual review — six,
seven, eight, nine, twelve. The twelfth was ``reconciliation._live_key_price`` below, and no earlier
round could have found it: it is a different SERVICE, in a different LANGUAGE, and it is **not a
query**, so a sweep scoped to "SQL in strategy-signal-service" — or even the corrected unit,
"queries that attribute rows to a position" — structurally cannot reach it. A Java regex cannot read
Python, so the coverage has to live here or nowhere.

WHAT IS FROZEN. Every place in ``app/`` that reads a ``tradingsymbol`` descriptor in executable code
— building a pairing key, selecting a position, or projecting one onto a wire shape. The invariant
is the same one the Java test guards: ``uq_paper_positions_open`` promises at most ONE open row per
``(book, exchange, tradingsymbol, side)``, and every descriptor-keyed read silently depends on it.
A key that is a PROPER SUBSET of that tuple depends on it even harder — which is exactly the defect
in ``_live_key_price``: it keys on ``(tradingsymbol, IST-date)``, dropping ``book``, ``exchange``
and ``side`` entirely, then resolves ties first-wins via ``setdefault`` with no secondary ordering.

NOT A LIVE INCIDENT, but the reason is narrower than it was. PR #1275 MERGED at ``060bbaf1`` and is
DEPLOYED: ``paper_positions`` now carries a ``strategy_id`` column and
``uq_paper_positions_open`` is
``(book, exchange, tradingsymbol, side, strategy_id) NULLS NOT DISTINCT WHERE status='OPEN'``
— verified against the live index definition. The four-tuple is still effectively unique ONLY
because the feature ships disarmed: ``ARTHA_PAPER_STRATEGY_SCOPED_BOOKS`` is empty, so every row
stamps
``strategy_id = NULL`` and ``NULLS NOT DISTINCT`` collapses them to one key. **So uniqueness is a
CONFIG guarantee now, not a SCHEMA one** — one ``.env`` line flips it, and on an armed book
``_live_key_price`` below starts choosing arbitrarily between two live siblings.

An earlier draft of this docstring said the flag was "armed nowhere", written when #1275 was still
open. That phrasing is worth retiring generally: a repo-wide grep returning nothing distinguishes
"the flag is disarmed" from "the code that reads it was never merged" not at all, and the two call
for completely different responses.

REGISTRATION lives in this file rather than as a marker comment at the call site, deliberately: a
marker travels with a copy-paste, so duplicating a registered function would carry its exemption
silently. Adding a line here forces the author past the invariant above first.

INTERPRETER: stdlib only (``pathlib``, ``re``) — it does not import ``app``, FastAPI, or anything
version-sensitive, so it runs on the AMBIENT interpreter. It does NOT need the pinned
``.venv-pinned`` that ``test_openapi_contract.py`` requires.

BLIND SPOTS, stated so nobody trusts this further than it goes — and this list is deliberately
concrete, because the whole thesis of this suite is that a guard whose search space quietly excludes
the defect is worse than no guard:

1. It reads source text, so a descriptor assembled at runtime (``getattr``, a dict built from a
   config key) is invisible.
2. It is scoped to ``app/`` in THIS service. A NEW Python service is invisible to both halves of
   this suite — the Java sibling deliberately walks all of ``services/`` and ``libs/``, but no
   equivalent cross-service Python walk exists, and adding one belongs with the second Python
   service rather than being speculated here.
3. Like its Java sibling, it cannot see a SUBSTITUTION inside an already-registered function —
   swapping one keying scheme for another keeps the count at 1.
4. The anchor is the BARE WORD ``tradingsymbol``, so an unrelated wire field of that name inside
   ``app/`` would trip it. That is the intended failure direction: over-detection costs one
   registration line, under-detection costs another manual sweep. The Java half uses a narrower
   anchor for the opposite reason — measured there, a bare word took the set from 18 sites to 39
   and would have buried the real members in column lists. The surfaces differ, so the anchors do.
5. ⚠️ If a registered function is REWRITTEN into a shape this detector cannot see, the ``stale``
   assertion fires and its message invites deleting a registration for a site that is still live.
   That message names the trap; do not follow it without confirming the function is really gone.
"""

import pathlib
import re

APP = pathlib.Path(__file__).resolve().parent.parent / "app"

_DEF = re.compile(r"^\s*(?:async\s+)?def\s+(\w+)\s*\(")
_DESCRIPTOR = re.compile(r"\btradingsymbol\b")

# Every place allowed to read a position descriptor, with the reason it is acceptable.
# Asserted as EXACT SET EQUALITY in both directions: a new site fails, and a registration whose
# site no longer exists also fails, so the registry cannot drift into fiction.
REGISTERED: dict[str, tuple[int, str]] = {
    "reconciliation._live_key_price": (
        1,
        "THE #1275 SITE (sweep A12). Keys live paper trades on (tradingsymbol, IST entry date) — a "
        "PROPER SUBSET of the natural key, dropping book/exchange/side — and the caller resolves "
        "ties with setdefault (first-wins) against an unordered fetch, so if the key is ever "
        "widened, which of two same-symbol/same-day positions supplies entryPriceDelta is "
        "arbitrary. Blast radius is a wrong NUMBER in the rollback-proposal path, never a wrong "
        "close: paired_count is a set cardinality and is unaffected. Left as-is deliberately — "
        "this ratchet is a guard, not a fix, and changing the pairing key is a behaviour change.",
    ),
    "reconciliation._sim_key_price": (
        1,
        "The sim-side counterpart, keyed IDENTICALLY on (tradingsymbol, IST entry date) so the two "
        "sides pair at all. It carries the same subset-key exposure and must change together with "
        "_live_key_price or pairing breaks silently.",
    ),
    "repos.paper_trades_for_version": (
        2,
        "ALREADY STRATEGY-SCOPED, and gets MORE accurate if #1275 lands: it joins "
        "signals.id = p.opening_signal_id and filters s.strategy_version_id, so it selects by "
        "strategy rather than by descriptor. The two hits are the SELECT column list and the wire "
        "projection, neither of which resolves a row.",
    ),
}

MIN_REASON_CHARS = 40


def _strip_docstrings_and_comments(source: str) -> list[str]:
    """Blank out triple-quoted regions and ``#`` comments, PRESERVING line numbering.

    Prose is where these tables are described, so scanning raw text would count every docstring
    mention of ``tradingsymbol`` as a site. Measured while building this: a one-line opening
    docstring in ``_norm_symbol`` leaked through a naive was-inside/is-inside check, because the
    delimiter opens and the word appears on the SAME line.
    """
    out = []
    in_doc = False
    delim = ""
    for line in source.splitlines():
        kept = []
        i = 0
        while i < len(line):
            if not in_doc:
                if line.startswith('"""', i) or line.startswith("'''", i):
                    delim = line[i : i + 3]
                    in_doc = True
                    i += 3
                    continue
                if line[i] == "#":
                    break
                kept.append(line[i])
                i += 1
            else:
                if line.startswith(delim, i):
                    in_doc = False
                    delim = ""
                    i += 3
                    continue
                i += 1
        out.append("".join(kept))
    return out


def _detect() -> dict[str, int]:
    found: dict[str, int] = {}
    for path in sorted(APP.rglob("*.py")):
        raw = path.read_text(encoding="utf-8").splitlines()
        code = _strip_docstrings_and_comments(path.read_text(encoding="utf-8"))
        current = "<module>"
        for lineno, stripped in enumerate(code):
            match = _DEF.match(raw[lineno])
            # A def line is only a real def when it survives docstring stripping.
            if match and _DEF.match(stripped):
                current = match.group(1)
            hits = len(_DESCRIPTOR.findall(stripped))
            if hits:
                key = f"{path.stem}.{current}"
                found[key] = found.get(key, 0) + hits
    return found


def test_every_position_descriptor_site_is_registered() -> None:
    detected = _detect()

    unregistered = sorted(set(detected) - set(REGISTERED))
    assert not unregistered, (
        f"NEW position-attribution site(s) with no registration: {unregistered}\n\n"
        "These read a paper-position descriptor (tradingsymbol) rather than an id. Every such\n"
        "site depends on uq_paper_positions_open holding at most ONE open row per\n"
        "(book, exchange, tradingsymbol, side) -- and a key that is a SUBSET of that tuple\n"
        "depends on it harder. The manual count of these moved four times across four rounds;\n"
        "this test exists so the next one fails CI instead.\n\n"
        "If the site is correct, register it in REGISTERED:\n"
        '    "<module>.<function>": (<hits>, "<why this is correct>")\n'
        "Read the module docstring first -- 'add a strategy predicate' is not automatically the\n"
        "right fix, and one registered site here is a KNOWN subset key left deliberately intact."
    )

    stale = sorted(set(REGISTERED) - set(detected))
    assert not stale, (
        f"Registered site(s) the detector no longer sees: {stale}\n\n"
        "TWO very different causes, needing OPPOSITE responses -- check which:\n\n"
        "  (a) The function was genuinely removed, renamed, or moved to another module.\n"
        "      -> Update or delete its entry in REGISTERED.\n\n"
        "  (b) The function still EXISTS but was rewritten into a shape this detector cannot\n"
        "      see (a descriptor assembled at runtime, or one that no longer spells\n"
        "      'tradingsymbol').\n"
        "      -> DO NOT delete the entry. The site is still live and would silently lose its\n"
        "         guard. Widen the detector to cover the new shape, then re-derive the set.\n\n"
        "Deleting a registration is only correct for (a). Confirm the function is really gone."
    )

    for key, (expected, _reason) in REGISTERED.items():
        assert detected[key] == expected, (
            f"{key} now has {detected[key]} descriptor read(s), registered for {expected}. "
            "A read was added to or removed from an already-registered function -- re-read it, "
            "then update the count in REGISTERED."
        )


def test_every_registration_states_a_reason() -> None:
    """A registration is a claim; make it cost a sentence so it cannot be added thoughtlessly."""
    for key, (_count, reason) in REGISTERED.items():
        assert len(reason.strip()) >= MIN_REASON_CHARS, (
            f"Registration for {key} has a {len(reason.strip())}-character reason. Say WHY "
            "keying on a descriptor is correct there, so the next reader can audit the claim "
            "without re-deriving it."
        )


def test_docstring_prose_is_not_counted_as_a_site() -> None:
    """Guards the detector itself: the stripper must blank a docstring that OPENS on the same line
    as the word it mentions. That exact shape (``_norm_symbol``) defeated an earlier cut, and a
    detector that counts prose would make the registry meaningless."""
    same_line = '''def f():
    """Mentions tradingsymbol on the opening line."""
    return 1
'''
    assert "tradingsymbol" not in "".join(_strip_docstrings_and_comments(same_line))

    multi = '''def g():
    """
    Mentions tradingsymbol on a continuation line.
    """
    return 2
'''
    assert "tradingsymbol" not in "".join(_strip_docstrings_and_comments(multi))

    live = 'key = (_norm_symbol(trade.get("tradingsymbol")), None)'
    assert "tradingsymbol" in "".join(_strip_docstrings_and_comments(live))
