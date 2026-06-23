"""Test doubles for the SPAN appliance — no binary ``.spn`` download required.

Two strategies:

1. :func:`synthetic_engine` builds a *real* ``marginism.RiskEngine`` over an
   in-memory :class:`SpanFile` (a synthetic NIFTY ATM straddle commodity). This
   drives the **actual** SPAN algorithm — so the golden + sizing tests exercise
   real margin arithmetic without a live exchange file. The numbers are golden to
   THIS fixture, not broker-parity (that is the documented VERIFY gate).
2. :class:`FakeEngine` is a pure :class:`SpanEngine` returning a fixed
   :class:`MarginResult` — used where the test only needs the appliance plumbing
   (loader cache, 503 path), not real arithmetic.
"""

from __future__ import annotations

from app.marginism_adapter import MarginResult, Order, SpanEngine

# ---- the golden fixture's known shape (NIFTY ATM straddle, lot 75) -----------
NIFTY_LOT = 75
NIFTY_ATM_STRIKE = 23500.0
NIFTY_EXPIRY_ISO = "2026-06-25"
FIXTURE_BUSINESS_DATE_RAW = "20260624"  # day BEFORE expiry => no expiry-day ELM add-on
FIXTURE_BUSINESS_DATE_ISO = "2026-06-24"


def synthetic_span_file():
    """An in-memory NIFTY commodity (ATM 23500 CE+PE) for the real marginism engine."""
    from marginism import CombinedCommodity, OptionContract, RiskArray, SpanFile

    # 16 SPAN scenario losses (per long unit); the CE/PE arrays are mirror-skewed
    # so a short straddle's scan risk is bounded and deterministic.
    ra_ce = RiskArray(
        values=[10, 5, 40, 30, -20, -25, 80, 70, -40, -45, 120, 110, -60, -65, 150, -70],
        composite_delta=0.5,
    )
    ra_pe = RiskArray(
        values=[10, 5, -20, -25, 40, 30, -40, -45, 80, 70, -60, -65, 120, 110, -70, 150],
        composite_delta=-0.5,
    )
    ce = OptionContract(
        cc="NIFTY", pf_id=1, pf_type="OOP", contract_id=1, expiry="20260625",
        price=120.0, delta=0.5, volatility=0.15, cvf=1.0, risk_array=ra_ce,
        option_type="C", strike=NIFTY_ATM_STRIKE,
    )
    pe = OptionContract(
        cc="NIFTY", pf_id=1, pf_type="OOP", contract_id=2, expiry="20260625",
        price=110.0, delta=-0.5, volatility=0.15, cvf=1.0, risk_array=ra_pe,
        option_type="P", strike=NIFTY_ATM_STRIKE,
    )
    cmty = CombinedCommodity(
        cc="NIFTY", name="NIFTY", underlying_price=NIFTY_ATM_STRIKE, options=[ce, pe]
    )
    return SpanFile(
        file_format="4.00", business_date=FIXTURE_BUSINESS_DATE_RAW,
        clearing_org="NSCCL", commodities={"NIFTY": cmty},
    )


def synthetic_engine():
    """A production :class:`MarginismEngine` backed by the synthetic ``SpanFile``."""
    from marginism import ExposureConfig, RiskEngine, SpanCalculator

    from app.marginism_adapter import MarginismEngine

    calc = SpanCalculator(synthetic_span_file(), exposure=ExposureConfig())
    return MarginismEngine(RiskEngine(calc))


class FakeEngine(SpanEngine):
    """A pure :class:`SpanEngine` returning a fixed result (appliance-plumbing tests)."""

    def __init__(
        self, result: MarginResult | None = None, business_date: str = FIXTURE_BUSINESS_DATE_ISO
    ) -> None:
        self._result = result or MarginResult(
            span=17250.0, exposure=70500.0, total=87750.0, hedge_benefit=10500.0
        )
        self._business_date = business_date
        self.calls: list[list[Order]] = []

    @property
    def business_date(self) -> str:
        return self._business_date

    def basket(self, orders: list[Order]) -> MarginResult:
        self.calls.append(orders)
        return self._result
