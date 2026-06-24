"""Pinned-tag contract canary (mirrors ``KiteWireContractTest`` / ``ContractCanary``).

Asserts the upstream **marginism** surface that ``app/marginism_adapter`` consumes
still exists, so an upstream rename/retype is caught off the critical path rather
than at a live ``/margin`` call. Runs against the synthetic ``.spn`` engine (no
download); the real-``.spn`` variant is a guarded manual VERIFY gate (see README)."""

import inspect

import marginism

from app.marginism_adapter import MarginismEngine, Order, _result_from_basket_dict
from tests.fakes import synthetic_engine


def test_pinned_version():
    # Pin the canary to the version requirements.txt installs; bump deliberately.
    assert marginism.__version__ == "0.1.1"


def test_riskengine_exposes_consumed_api():
    assert hasattr(marginism, "RiskEngine")
    assert hasattr(marginism.RiskEngine, "from_file")
    assert hasattr(marginism.RiskEngine, "basket")
    # from_file(path) -> engine; basket(list[dict]) -> dict
    assert "spn_path" in inspect.signature(marginism.RiskEngine.from_file).parameters
    assert "orders" in inspect.signature(marginism.RiskEngine.basket).parameters
    # The adapter scopes the .spn parse to chosen underlyings via parse_spn(symbols=...)
    # (RiskEngine.from_file forwards **kw -> SpanCalculator.from_file -> parse_spn);
    # the symbols filter is consumed surface, so pin it here too.
    assert hasattr(marginism, "parse_spn")
    assert "symbols" in inspect.signature(marginism.parse_spn).parameters


def test_basket_dict_carries_the_fields_the_adapter_maps():
    # The adapter reads data.final.{span,exposure,total} + data.margin_benefit.
    engine = synthetic_engine()
    payload = engine._engine.basket(  # noqa: SLF001 - reach the raw upstream dict on purpose
        [
            {
                "symbol": "NIFTY", "instrument": "CE", "expiry": "2026-06-25",
                "strike": 23500, "transaction_type": "SELL", "quantity": 75,
            }
        ]
    )
    assert payload["status"] == "success"
    final = payload["data"]["final"]
    for key in ("span", "exposure", "total"):
        assert key in final
    assert "margin_benefit" in payload["data"]
    # the adapter's mapper must consume that exact shape without KeyError
    result = _result_from_basket_dict(payload)
    assert result.total == float(final["total"])


def test_adapter_business_date_is_iso():
    engine: MarginismEngine = synthetic_engine()
    assert engine.business_date == "2026-06-24"


def test_order_renders_marginism_dict():
    order = Order(
        symbol="NIFTY", instrument="PE", transaction_type="SELL", quantity=75,
        expiry="2026-06-25", strike=23500.0,
    )
    assert order.to_marginism() == {
        "symbol": "NIFTY", "instrument": "PE", "transaction_type": "SELL",
        "quantity": 75, "expiry": "2026-06-25", "strike": 23500.0,
    }
