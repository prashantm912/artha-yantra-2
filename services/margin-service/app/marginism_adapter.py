"""Anti-corruption boundary around the upstream **marginism** SPAN engine (§8.1).

marginism (MIT, marketcalls/marginism) is the offline NSCCL/CME-SPAN margin
calculator. Its public surface (verified against the pinned ``marginism==0.1.1``):

  * ``RiskEngine.from_file(path)`` -> engine parsed from a ``.spn`` file.
  * ``engine.basket([order_dict, ...])`` -> a Zerodha-style margins **dict**
    ``{"status", "data": {"initial", "final", "orders": [...], "margin_benefit"}}``
    where ``data.final`` carries ``span`` / ``exposure`` / ``option_premium`` /
    ``additional`` / ``total`` (all floats, rounded to 2dp by upstream).
  * Each order dict is the trader-friendly form
    ``{"symbol", "instrument" ("CE"|"PE"|"FUT"), "expiry", "strike",
       "transaction_type" ("BUY"|"SELL"), "quantity"}`` (quantity in *units*).

We pin marginism as a normal pip dependency, so the wheel ships its MIT LICENSE
(recorded in ``docs/LEGAL.md``). The whole rest of the appliance speaks only the
:class:`SpanEngine` protocol + :class:`MarginResult` dataclass below — exactly the
``kite/wire/`` anti-corruption pattern — so an upstream rename never leaks past
this module. The contract canary in ``tests/test_marginism_contract.py`` asserts
the consumed attributes still exist.

> VERIFY-PENDING (documented seam): the production margin figures are only as
> correct as the loaded ``.spn`` risk file. The unit/golden tests below run the
> REAL marginism algorithm against a *synthetic* :class:`SpanFile` fixture, which
> proves the wiring + sizing rails but NOT broker-parity numbers. The real-``.spn``
> golden (a NIFTY ATM straddle within +/-2-3% of a broker SPAN calculator) is a
> documented manual VERIFY gate — see ``services/margin-service/README.md``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol


@dataclass(frozen=True)
class Order:
    """A single basket leg, in marginism's trader-friendly explicit form."""

    symbol: str
    instrument: str  # "CE" | "PE" | "FUT"
    transaction_type: str  # "BUY" | "SELL"
    quantity: int  # in UNITS (lots * lot_size), always positive
    expiry: str | None = None  # "YYYY-MM-DD" (option/future legs)
    strike: float = 0.0  # required for option legs

    def to_marginism(self) -> dict[str, Any]:
        """Render to the order dict ``RiskEngine.basket`` consumes."""
        order: dict[str, Any] = {
            "symbol": self.symbol,
            "instrument": self.instrument,
            "transaction_type": self.transaction_type,
            "quantity": self.quantity,
        }
        if self.expiry:
            order["expiry"] = self.expiry
        if self.strike:
            order["strike"] = self.strike
        return order


@dataclass(frozen=True)
class MarginResult:
    """The stable margin shape the appliance depends on (decoupled from marginism).

    ``total`` is the upfront initial margin (SPAN + exposure + additional);
    ``hedge_benefit`` is the basket netting saved vs holding each leg outright.
    """

    span: float
    exposure: float
    total: float
    hedge_benefit: float


class SpanEngine(Protocol):
    """The single seam every consumer (and every test double) speaks.

    Production is :class:`MarginismEngine` (wraps ``marginism.RiskEngine``); unit
    tests inject a synthetic-``.spn``-backed engine or a pure fake. Either way the
    appliance never touches a marginism type directly outside this module."""

    @property
    def business_date(self) -> str:
        """The loaded SPAN file's business date as an ISO ``YYYY-MM-DD`` string."""
        ...

    def basket(self, orders: list[Order]) -> MarginResult:
        """Consolidated SPAN margin for a 1..N-leg basket (with hedge netting)."""
        ...


def _iso_date(raw: str) -> str:
    """``YYYYMMDD`` (the SPAN file's raw business date) -> ISO ``YYYY-MM-DD``."""
    if raw and len(raw) == 8 and raw.isdigit():
        return f"{raw[0:4]}-{raw[4:6]}-{raw[6:8]}"
    return raw or "unknown"


def _result_from_basket_dict(payload: dict[str, Any]) -> MarginResult:
    """Map marginism's broker-style basket dict to our :class:`MarginResult`.

    Raises ``KeyError`` if the upstream shape drifts — the contract canary turns
    that into an actionable test failure off the critical path."""
    final = payload["data"]["final"]
    return MarginResult(
        span=float(final["span"]),
        exposure=float(final["exposure"]),
        total=float(final["total"]),
        hedge_benefit=float(payload["data"]["margin_benefit"]),
    )


class MarginismEngine:
    """Production :class:`SpanEngine` wrapping a parsed ``marginism.RiskEngine``."""

    def __init__(self, risk_engine: Any) -> None:
        # ``risk_engine`` is a ``marginism.RiskEngine`` (or any object exposing the
        # same ``basket([dict]) -> dict`` contract — e.g. a synthetic-file engine).
        self._engine = risk_engine

    @classmethod
    def from_file(cls, path: str) -> MarginismEngine:
        """Parse a ``.spn`` file into a production engine (one parse per file).

        marginism is imported lazily so a missing optional dep never breaks app import.
        """
        from marginism import RiskEngine

        return cls(RiskEngine.from_file(path))

    @property
    def business_date(self) -> str:
        # marginism: RiskEngine.calc.span_file.business_date (raw 'YYYYMMDD').
        return _iso_date(self._engine.calc.span_file.business_date)

    def basket(self, orders: list[Order]) -> MarginResult:
        payload = self._engine.basket([o.to_marginism() for o in orders])
        return _result_from_basket_dict(payload)
