"""Broker-parity GOLDEN harness — feed a ``.spn`` + a position, assert the margin.

This is the structural rig for the documented VERIFY gate (README §8.5 / memory
"#47 marginism live-verify pending"): a parametrized case carries a ``.spn`` file,
a basket of legs, and the EXPECTED ``span`` / ``exposure`` / ``total`` / hedge
benefit. The synthetic case below runs in CI today (it proves the full
file -> parse -> SPAN-algorithm -> :class:`MarginResult` chain). A REAL case is a
one-line drop-in once the owner supplies the two owner-gated inputs.

OWNER-GATED real-broker-parity step (NOT done here, NOT claimable as correct):

  1. Drop a genuine NSCCL ``.spn`` into ``tests/data/`` (e.g. from
     ``deploy/span-files/PR_<DDMMYY>.spn``, fetched by ``tools/span-fetch/fetch_spn.ps1``).
  2. Read that SAME basket's margin off a broker SPAN calculator (Zerodha/Upstox/
     Sensibull) on the SAME business date.
  3. Append a ``ParityCase`` below with ``tol_pct`` ~3% (exposure/scan add-ons differ
     a few % by source) and ``marker="real"``; un-skip it.

Until then ``test_real_spn_parity_is_owner_gated`` is an explicit ``skip`` so the
suite stays green AND the missing gate is visible in the pytest report (never a
silent pass).
"""

from dataclasses import dataclass, field
from pathlib import Path

import pytest

from app.marginism_adapter import MarginismEngine, Order

DATA = Path(__file__).parent / "data"


@dataclass(frozen=True)
class ParityCase:
    """One ``.spn`` + basket + expected margin (a broker-parity golden row)."""

    name: str
    spn_file: str          # filename under tests/data/
    symbols: tuple[str, ...]
    legs: tuple[Order, ...]
    expected_span: float
    expected_exposure: float
    expected_total: float
    expected_benefit: float
    # Tolerance as a FRACTION of the expected total. 0 == byte-exact (synthetic
    # golden); ~0.03 for a real broker number (exposure/scan params differ by source).
    tol_pct: float = 0.0
    marker: str = "synthetic"  # "synthetic" (CI) | "real" (owner-supplied)
    note: str = field(default="")


_SHORT_STRADDLE = (
    Order(symbol="NIFTY", instrument="CE", transaction_type="SELL",
          quantity=75, expiry="2026-06-25", strike=23500.0),
    Order(symbol="NIFTY", instrument="PE", transaction_type="SELL",
          quantity=75, expiry="2026-06-25", strike=23500.0),
)

# Cases that run in CI today. Add a ``marker="real"`` row here once a genuine .spn
# and its broker margin are available (see module docstring); the test body is
# already generic over span/exposure/total/benefit + tolerance.
PARITY_CASES: tuple[ParityCase, ...] = (
    ParityCase(
        name="synthetic_nifty_short_atm_straddle",
        spn_file="synthetic_nifty.spn",
        symbols=("NIFTY",),
        legs=_SHORT_STRADDLE,
        expected_span=17250.0,
        expected_exposure=70500.0,
        expected_total=87750.0,
        expected_benefit=10500.0,
        tol_pct=0.0,  # golden to the synthetic fixture (NOT broker parity)
        marker="synthetic",
        note="Proves the file->parse->algorithm chain; numbers are golden to the "
             "synthetic .spn, not a broker calculator.",
    ),
)


def _run(case: ParityCase):
    engine = MarginismEngine.from_file(
        str(DATA / case.spn_file), symbols=list(case.symbols)
    )
    return engine.basket(list(case.legs))


def _within(actual: float, expected: float, tol_pct: float, anchor: float) -> bool:
    """``actual`` within ``tol_pct`` of ``expected`` (tolerance scaled to ``anchor``)."""
    if tol_pct == 0.0:
        return actual == expected
    return abs(actual - expected) <= tol_pct * anchor


@pytest.mark.parametrize("case", PARITY_CASES, ids=lambda c: c.name)
def test_spn_basket_matches_expected_margin(case: ParityCase):
    result = _run(case)
    anchor = case.expected_total  # scale every tolerance to the basket total
    assert _within(result.span, case.expected_span, case.tol_pct, anchor), (
        f"{case.name}: span {result.span} vs expected {case.expected_span}"
    )
    assert _within(result.exposure, case.expected_exposure, case.tol_pct, anchor), (
        f"{case.name}: exposure {result.exposure} vs expected {case.expected_exposure}"
    )
    assert _within(result.total, case.expected_total, case.tol_pct, anchor), (
        f"{case.name}: total {result.total} vs expected {case.expected_total}"
    )
    assert _within(result.hedge_benefit, case.expected_benefit, case.tol_pct, anchor), (
        f"{case.name}: benefit {result.hedge_benefit} vs expected {case.expected_benefit}"
    )


def test_no_real_spn_case_is_registered_yet():
    # Documents the open gate: today every parity case is synthetic. When a real
    # broker-parity row is added this assertion flips and must be deleted — a
    # tripwire so "real parity done" can't pass unnoticed without owner sign-off.
    assert not [c for c in PARITY_CASES if c.marker == "real"]


@pytest.mark.skip(
    reason="OWNER-GATED: needs a real NSCCL .spn in tests/data/ + that basket's "
    "margin from a broker SPAN calculator (Zerodha/Upstox/Sensibull) on the same "
    "business date. See module docstring + README VERIFY-pending #1."
)
def test_real_spn_parity_is_owner_gated():  # pragma: no cover - skipped until inputs exist
    # Template the owner fills in:
    #   case = ParityCase(
    #       name="real_nifty_<date>_short_straddle",
    #       spn_file="PR_<DDMMYY>.spn",            # a genuine NSCCL file in tests/data/
    #       symbols=("NIFTY",),
    #       legs=_SHORT_STRADDLE,                  # or the exact basket priced on the broker
    #       expected_span=..., expected_exposure=..., expected_total=...,  # from broker
    #       expected_benefit=...,
    #       tol_pct=0.03, marker="real",
    #   )
    #   result = _run(case)
    #   assert _within(result.total, case.expected_total, case.tol_pct, case.expected_total)
    raise AssertionError("supply a real .spn + broker margin, then implement")
