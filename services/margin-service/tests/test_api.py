"""§8.4 margin REST surface — envelope + status codes via TestClient.

Wires the real ``SpanService`` over a synthetic-``.spn`` engine (real marginism
arithmetic), and a no-``.spn`` loader for the 503 DATA_GAP path."""

from fastapi.testclient import TestClient

from app.main import build_app
from app.settings import Settings
from app.span_loader import NoSpanFileError
from tests.fakes import FIXTURE_BUSINESS_DATE_ISO, synthetic_engine

SETTINGS = Settings(spn_dir="/nonexistent")

def _leg(ot: str) -> dict:
    return {
        "symbol": "NIFTY", "expiry": "2026-06-25", "strike": 23500,
        "optionType": ot, "netQty": -75,
    }


STRADDLE = {"positions": [_leg("CE"), _leg("PE")]}


class _Loaded:
    """A loader-shaped double that always serves the synthetic engine."""

    def __init__(self):
        self._engine = synthetic_engine()

    def engine(self):
        return self._engine

    def spn_date(self):
        return self._engine.business_date


class _Empty:
    """A loader-shaped double with no .spn loaded (503 DATA_GAP path)."""

    def engine(self):
        raise NoSpanFileError("/nonexistent-dir")

    def spn_date(self):
        return None


def _loaded_client() -> TestClient:
    return TestClient(build_app(SETTINGS, _Loaded()))


def _empty_client() -> TestClient:
    return TestClient(build_app(SETTINGS, _Empty()))


def test_valid_basket_returns_200_envelope():
    resp = _loaded_client().post("/api/v1/margin", json=STRADDLE)
    assert resp.status_code == 200
    body = resp.json()
    assert body["spnDate"] == FIXTURE_BUSINESS_DATE_ISO
    assert body["currency"] == "INR"
    # decimals on the wire are strings
    assert isinstance(body["total"], str)
    assert body["total"] == "87750.00"


def test_bad_option_type_is_400():
    bad = {"positions": [{"symbol": "NIFTY", "optionType": "XX", "netQty": -75, "strike": 23500}]}
    resp = _loaded_client().post("/api/v1/margin", json=bad)
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_option_leg_without_strike_is_400():
    bad = {"positions": [{"symbol": "NIFTY", "optionType": "CE", "netQty": -75}]}
    resp = _loaded_client().post("/api/v1/margin", json=bad)
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_no_spn_loaded_is_503_data_gap():
    resp = _empty_client().post("/api/v1/margin", json=STRADDLE)
    assert resp.status_code == 503
    assert resp.json()["code"] == "DATA_GAP"


def test_size_endpoint_returns_sized_response():
    body = {
        "capital": "500000", "maxCapitalPct": "0.20", "dailyLossUsed": "0",
        "dailyLossCap": "1000000", "entry": "120.50", "stop": "90.00", "rrTarget": "2.0",
        "legs": [{"symbol": "NIFTY", "expiry": "2026-06-25", "strike": 23500,
                  "optionType": "CE", "side": "SELL", "lotSize": 75}],
    }
    resp = _loaded_client().post("/api/v1/margin/size", json=body)
    assert resp.status_code == 200
    payload = resp.json()
    assert payload["lots"] == 2
    assert payload["withinRails"] is True
    assert payload["limitingRail"] == "capitalPct"
    # decimals on the wire are strings
    assert payload["marginRequired"] == "99000.00"


def test_health_reports_spn_date_when_loaded():
    resp = _loaded_client().get("/health")
    assert resp.status_code == 200
    assert resp.json()["spnDate"] == FIXTURE_BUSINESS_DATE_ISO
    assert resp.json()["spnLoaded"] == "True"


def test_health_reports_no_spn_when_empty():
    resp = _empty_client().get("/health")
    assert resp.status_code == 200
    assert resp.json()["spnDate"] is None
    assert resp.json()["spnLoaded"] == "False"
