"""Golden SPAN test — a short NIFTY ATM straddle against the synthetic ``.spn`` fixture.

The numbers below are golden to the in-memory fixture in ``tests/fakes.py``, which
drives the **real** marginism SPAN algorithm. This proves the adapter + wire shape
end to end.

> VERIFY-PENDING (documented gate, §8.5 step 5): broker-parity is NOT asserted here
> — exposure/scan params on a synthetic file are not a real NSCCL file. The real
> golden (a NIFTY ATM straddle within +/-2-3% of a Zerodha/Upstox SPAN calculator,
> against a real ``deploy/span-files/*.spn``) is a manual VERIFY step recorded in
> ``services/margin-service/README.md``. Do NOT treat the fixture total as a
> broker-correct margin.
"""

from decimal import Decimal

from app.marginism_adapter import Order
from app.models import MarginRequest, PositionIn
from app.service import SpanService
from tests.fakes import (
    FIXTURE_BUSINESS_DATE_ISO,
    NIFTY_ATM_STRIKE,
    NIFTY_EXPIRY_ISO,
    NIFTY_LOT,
    synthetic_engine,
)


class _StaticLoader:
    def __init__(self):
        self._engine = synthetic_engine()

    def engine(self):
        return self._engine

    def spn_date(self):
        return self._engine.business_date


def _service() -> SpanService:
    return SpanService(_StaticLoader())


def _straddle_request() -> MarginRequest:
    legs = [
        PositionIn(
            symbol="NIFTY", expiry=NIFTY_EXPIRY_ISO, strike=NIFTY_ATM_STRIKE,
            optionType=ot, netQty=-NIFTY_LOT,
        )
        for ot in ("CE", "PE")
    ]
    return MarginRequest(positions=legs)


def test_short_atm_straddle_golden_total():
    resp = _service().margin(_straddle_request())
    # Golden to the synthetic fixture (real marginism arithmetic):
    #   span 17250 + exposure 70500 + additional 0 = total 87750; basket hedge benefit 10500.
    assert resp.span == Decimal("17250.00")
    assert resp.exposure == Decimal("70500.00")
    assert resp.total == Decimal("87750.00")
    assert resp.hedgeBenefit == Decimal("10500.00")
    assert resp.spnDate == FIXTURE_BUSINESS_DATE_ISO
    assert resp.currency == "INR"


def test_basket_hedge_benefit_is_nonzero_for_a_straddle():
    # A two-leg basket nets less than the sum of standalone legs (hedging benefit).
    resp = _service().margin(_straddle_request())
    assert resp.hedgeBenefit > Decimal("0")


def test_adapter_maps_short_leg_to_sell_order():
    # A negative netQty must become a SELL order of abs(qty) units.
    pos = PositionIn(
        symbol="NIFTY", expiry=NIFTY_EXPIRY_ISO, strike=23500, optionType="CE", netQty=-75
    )
    order = SpanService._to_order(pos)
    assert order == Order(
        symbol="NIFTY", instrument="CE", transaction_type="SELL", quantity=75,
        expiry=NIFTY_EXPIRY_ISO, strike=23500.0,
    )
