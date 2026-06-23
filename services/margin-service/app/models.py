"""Pydantic request/response models for the §8.4 margin API.

All monetary/decimal values cross the wire as JSON **strings** (the repo-wide
Jackson-BigDecimal-as-string convention; the React ``core/decimal`` port parses
them). Requests accept Decimal-coercible strings; responses serialize Decimals
via the ``field_serializer`` below into plain (non-scientific) strings."""

from __future__ import annotations

from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, field_serializer

OptionType = Literal["CE", "PE", "FUT"]


class PositionIn(BaseModel):
    """One basket leg for the raw ``/margin`` endpoint."""

    symbol: str
    expiry: str | None = None
    strike: float | None = None
    optionType: str  # validated in service.py (CE | PE | FUT) for the shared envelope
    netQty: int  # signed: negative = short, positive = long; units (lots * lotSize)


class MarginRequest(BaseModel):
    positions: list[PositionIn]


class MarginResponse(BaseModel):
    span: Decimal
    exposure: Decimal
    total: Decimal
    hedgeBenefit: Decimal
    spnDate: str
    currency: str = "INR"

    @field_serializer("span", "exposure", "total", "hedgeBenefit")
    def _decimals_as_strings(self, value: Decimal) -> str:
        return f"{value:.2f}"


class LegIn(BaseModel):
    """One option/future leg for the ``/margin/size`` sizing endpoint."""

    symbol: str
    expiry: str | None = None
    strike: float | None = None
    optionType: str
    side: Literal["BUY", "SELL"]
    lotSize: int


class SizeRequest(BaseModel):
    capital: Decimal
    maxCapitalPct: Decimal = Decimal("0.20")
    dailyLossUsed: Decimal = Decimal("0")
    dailyLossCap: Decimal
    entry: Decimal
    stop: Decimal | None = None  # mandatory rail: absent => withinRails:false
    rrTarget: Decimal = Decimal("2.0")
    legs: list[LegIn]


class SizeResponse(BaseModel):
    lots: int
    qty: int
    marginRequired: Decimal
    capitalUsed: Decimal
    riskAmount: Decimal
    target: Decimal
    withinRails: bool
    warnings: list[str]
    limitingRail: str | None  # capitalPct | dailyLoss | marginAvailable | None

    @field_serializer("marginRequired", "capitalUsed", "riskAmount", "target")
    def _decimals_as_strings(self, value: Decimal) -> str:
        return f"{value:.2f}"
