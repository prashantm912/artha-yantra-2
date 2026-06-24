"""`.spn` file INGEST test — drives the real marginism XML parser end to end.

Every OTHER test in this suite builds a :class:`marginism.SpanFile` *in memory*
(``tests/fakes.py``), which bypasses ``marginism.parser.parse_spn`` entirely. This
module closes that gap: it loads the committed NSCCL-shaped ``.spn`` XML fixture
through the production seam (``SpanLoader`` -> ``MarginismEngine.from_file`` ->
``RiskEngine.from_file`` -> ``parse_spn``/iterparse) and asserts the parsed risk
parameters drive the SAME golden margin as the in-memory fixture. So a regression in
the XML ingest (tag rename, missing phyPf spot, dropped risk-array element) fails
here, off the live ``/margin`` critical path.

The fixture is synthetic (see ``tests/data/synthetic_nifty.spn``); broker-parity is
a separate OWNER-GATED gate exercised by ``test_spn_parity.py``.
"""

from pathlib import Path

from app.marginism_adapter import MarginismEngine, Order
from app.span_loader import SpanLoader
from tests.fakes import (
    FIXTURE_BUSINESS_DATE_ISO,
    NIFTY_ATM_STRIKE,
    NIFTY_EXPIRY_ISO,
    NIFTY_LOT,
)

FIXTURE_SPN = Path(__file__).parent / "data" / "synthetic_nifty.spn"


def _engine_from_fixture() -> MarginismEngine:
    """Parse the committed ``.spn`` XML through the production from_file path.

    ``symbols=["NIFTY"]`` mirrors how the appliance would scope a real ~50 MB file
    to the underlyings it cares about (parse_spn's streaming-filter argument)."""
    return MarginismEngine.from_file(str(FIXTURE_SPN), symbols=["NIFTY"])


def _short_straddle() -> list[Order]:
    return [
        Order(
            symbol="NIFTY", instrument=ot, transaction_type="SELL",
            quantity=NIFTY_LOT, expiry=NIFTY_EXPIRY_ISO, strike=NIFTY_ATM_STRIKE,
        )
        for ot in ("CE", "PE")
    ]


def test_fixture_file_exists_and_is_spn_xml():
    # Guards the harness itself: a deleted/renamed fixture must fail loudly here,
    # not as a confusing parse error deeper in marginism.
    assert FIXTURE_SPN.is_file()
    head = FIXTURE_SPN.read_text(encoding="utf-8")[:64]
    assert head.startswith("<?xml")


def test_riskengine_parses_the_spn_business_date():
    engine = _engine_from_fixture()
    # parse_spn read <date>20260624</date>; the adapter renders it ISO.
    assert engine.business_date == FIXTURE_BUSINESS_DATE_ISO


def test_parsed_spn_computes_the_golden_straddle_margin():
    # The whole point: the XML parse path must compute the SAME numbers as the
    # in-memory synthetic fixture (tests/fakes.py / test_margin.py golden).
    result = _engine_from_fixture().basket(_short_straddle())
    assert result.span == 17250.0
    assert result.exposure == 70500.0
    assert result.total == 87750.0
    assert result.hedge_benefit == 10500.0


def test_loader_parses_a_real_spn_file_from_disk(tmp_path):
    # End-to-end through SpanLoader's newest-by-mtime selection using the DEFAULT
    # (production) engine_factory = MarginismEngine.from_file — i.e. no injected
    # factory, the real disk -> parse_spn path the container runs.
    copy = tmp_path / "PR_240626.spn"
    copy.write_bytes(FIXTURE_SPN.read_bytes())
    loader = SpanLoader(str(tmp_path))
    engine = loader.engine()
    assert engine.business_date == FIXTURE_BUSINESS_DATE_ISO
    assert loader.spn_date() == FIXTURE_BUSINESS_DATE_ISO
    # second call is served from the (path, mtime) cache, no re-parse
    assert loader.engine() is engine
