"""§8.4 sizing rails — ``/margin/size`` clamps lots to the binding rail.

Cases (per §8.5 step 6):
  (a) max-capital-% bound, (b) daily-loss-room bound,
  (c) reject (withinRails:false, lots:0) when stop missing OR daily room <= 0,
  (d) RR-target computed correctly.

The short-CE leg costs 49500/lot of SPAN margin in the synthetic fixture
(lot 75) — large enough for the capital rail to bite at small lot counts."""

from decimal import Decimal

from app.models import LegIn, SizeRequest
from app.service import SpanService
from tests.fakes import synthetic_engine


class _StaticLoader:
    def __init__(self):
        self._engine = synthetic_engine()

    def engine(self):
        return self._engine

    def spn_date(self):
        return self._engine.business_date


def _service() -> SpanService:
    return SpanService(_StaticLoader())


def _short_ce_leg(side: str = "SELL") -> LegIn:
    return LegIn(
        symbol="NIFTY", expiry="2026-06-25", strike=23500, optionType="CE",
        side=side, lotSize=75,
    )


# ---- (a) max-capital-% bound -------------------------------------------------
def test_clamped_by_capital_pct():
    # budget = 500000 * 0.20 = 100000; 2 lots = 99000 <= budget, 3 lots = 148500 > budget.
    req = SizeRequest(
        capital=Decimal("500000"), maxCapitalPct=Decimal("0.20"),
        dailyLossUsed=Decimal("0"), dailyLossCap=Decimal("1000000"),
        entry=Decimal("120.50"), stop=Decimal("90.00"), rrTarget=Decimal("2.0"),
        legs=[_short_ce_leg()],
    )
    resp = _service().size(req)
    assert resp.withinRails is True
    assert resp.lots == 2
    assert resp.qty == 150
    assert resp.limitingRail == "capitalPct"
    assert resp.marginRequired == Decimal("99000.00")


# ---- (b) daily-loss-room bound ----------------------------------------------
def test_clamped_by_daily_loss_room():
    # riskPerUnit = |120.50 - 90.00| = 30.50; room = 10000 - 4000 = 6000;
    # maxQtyByLoss = floor(6000 / 30.50) = 196 -> 196 // 75 = 2 lots.
    # Capital is large enough that the daily-loss rail binds first.
    req = SizeRequest(
        capital=Decimal("5000000"), maxCapitalPct=Decimal("0.20"),
        dailyLossUsed=Decimal("4000"), dailyLossCap=Decimal("10000"),
        entry=Decimal("120.50"), stop=Decimal("90.00"), rrTarget=Decimal("2.0"),
        legs=[_short_ce_leg()],
    )
    resp = _service().size(req)
    assert resp.withinRails is True
    assert resp.lots == 2
    assert resp.limitingRail == "dailyLoss"
    assert resp.riskAmount == Decimal("4575.00")  # 30.50 * 150


# ---- (c) rejects -------------------------------------------------------------
def test_missing_stop_is_rejected():
    req = SizeRequest(
        capital=Decimal("500000"), dailyLossCap=Decimal("10000"),
        entry=Decimal("120.50"), stop=None, legs=[_short_ce_leg()],
    )
    resp = _service().size(req)
    assert resp.withinRails is False
    assert resp.lots == 0
    assert resp.qty == 0
    assert any("no-naked-risk" in w or "stop" in w for w in resp.warnings)


def test_daily_room_exhausted_is_rejected():
    req = SizeRequest(
        capital=Decimal("500000"), dailyLossCap=Decimal("10000"),
        dailyLossUsed=Decimal("10000"),  # room == 0
        entry=Decimal("120.50"), stop=Decimal("90.00"), legs=[_short_ce_leg()],
    )
    resp = _service().size(req)
    assert resp.withinRails is False
    assert resp.lots == 0


# ---- (d) RR target ------------------------------------------------------------
def test_rr_target_for_long_leg():
    # BUY: target = entry + rrTarget * |entry - stop| = 120.50 + 2 * 30.50 = 181.50.
    req = SizeRequest(
        capital=Decimal("500000"), dailyLossCap=Decimal("1000000"),
        entry=Decimal("120.50"), stop=Decimal("90.00"), rrTarget=Decimal("2.0"),
        legs=[_short_ce_leg(side="BUY")],
    )
    resp = _service().size(req)
    assert resp.target == Decimal("181.50")


def test_rr_target_for_short_leg():
    # SELL: target = entry - rrTarget * |entry - stop| = 120.50 - 2 * 30.50 = 59.50.
    req = SizeRequest(
        capital=Decimal("500000"), dailyLossCap=Decimal("1000000"),
        entry=Decimal("120.50"), stop=Decimal("90.00"), rrTarget=Decimal("2.0"),
        legs=[_short_ce_leg(side="SELL")],
    )
    resp = _service().size(req)
    assert resp.target == Decimal("59.50")
