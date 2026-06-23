"""SpanService: raw basket margin + rail-bounded position sizing (§8.4).

Stateless. ``margin()`` validates a basket and delegates to the loaded SPAN
engine; ``size()`` runs the Siva risk rails (max-capital-%, daily-loss room) over
candidate lot counts, binary-searching the largest basket whose SPAN total fits
the capital budget. Decimals stay :class:`Decimal` end to end; the API layer
serializes them as strings."""

from __future__ import annotations

from decimal import ROUND_FLOOR, ROUND_HALF_UP, Decimal

from app.errors import ApiError
from app.marginism_adapter import MarginResult, Order
from app.models import (
    MarginRequest,
    MarginResponse,
    PositionIn,
    SizeRequest,
    SizeResponse,
)
from app.span_loader import NoSpanFileError, SpanLoader

_VALID_TYPES = {"CE", "PE", "FUT"}


def _q2(value: Decimal) -> Decimal:
    """Round to 2dp HALF_UP (rupee-paise wire scale)."""
    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


class SpanService:
    """Wires the SPAN engine loader to the two REST operations."""

    def __init__(self, loader: SpanLoader) -> None:
        self._loader = loader

    # ---- shared helpers -------------------------------------------------
    def _engine(self):
        try:
            return self._loader.engine()
        except NoSpanFileError as exc:
            raise ApiError(
                503, "DATA_GAP", "no SPAN .spn file loaded yet", {"spnDir": str(exc)}
            ) from exc

    @staticmethod
    def _to_order(p: PositionIn) -> Order:
        """Validate one leg and map it to a marginism order (raises 400 on bad input)."""
        opt = (p.optionType or "").upper()
        if opt not in _VALID_TYPES:
            raise ApiError(
                400,
                "VALIDATION_FAILED",
                f"optionType must be one of {sorted(_VALID_TYPES)}",
                {"optionType": p.optionType},
            )
        if opt in ("CE", "PE") and not p.strike:
            raise ApiError(
                400, "VALIDATION_FAILED", "strike is required for an option leg",
                {"symbol": p.symbol, "optionType": opt},
            )
        if p.netQty == 0:
            raise ApiError(
                400, "VALIDATION_FAILED", "netQty must be non-zero", {"symbol": p.symbol}
            )
        side = "SELL" if p.netQty < 0 else "BUY"
        return Order(
            symbol=p.symbol,
            instrument=opt,
            transaction_type=side,
            quantity=abs(p.netQty),
            expiry=p.expiry,
            strike=float(p.strike or 0.0),
        )

    # ---- POST /api/v1/margin -------------------------------------------
    def margin(self, req: MarginRequest) -> MarginResponse:
        if not req.positions:
            raise ApiError(400, "VALIDATION_FAILED", "positions must be non-empty")
        engine = self._engine()
        orders = [self._to_order(p) for p in req.positions]
        result = engine.basket(orders)
        return MarginResponse(
            span=_q2(Decimal(str(result.span))),
            exposure=_q2(Decimal(str(result.exposure))),
            total=_q2(Decimal(str(result.total))),
            hedgeBenefit=_q2(Decimal(str(result.hedge_benefit))),
            spnDate=engine.business_date,
        )

    # ---- POST /api/v1/margin/size --------------------------------------
    def _basket_total(self, engine, req: SizeRequest, lots: int) -> MarginResult:
        """SPAN total for the requested legs scaled to ``lots`` (units = lots*lotSize)."""
        orders = [
            Order(
                symbol=leg.symbol,
                instrument=self._leg_instrument(leg.optionType),
                transaction_type=leg.side,
                quantity=lots * leg.lotSize,
                expiry=leg.expiry,
                strike=float(leg.strike or 0.0),
            )
            for leg in req.legs
        ]
        return engine.basket(orders)

    @staticmethod
    def _leg_instrument(option_type: str) -> str:
        opt = (option_type or "").upper()
        if opt not in _VALID_TYPES:
            raise ApiError(
                400, "VALIDATION_FAILED",
                f"optionType must be one of {sorted(_VALID_TYPES)}",
                {"optionType": option_type},
            )
        return opt

    def size(self, req: SizeRequest) -> SizeResponse:
        if not req.legs:
            raise ApiError(400, "VALIDATION_FAILED", "legs must be non-empty")
        lot_size = req.legs[0].lotSize
        if lot_size <= 0:
            raise ApiError(400, "VALIDATION_FAILED", "lotSize must be positive")

        warnings: list[str] = []

        # Rail 0 (no-naked-risk): a hard stop is mandatory; reject outright if absent.
        if req.stop is None or req.stop <= 0:
            return self._rejected(req, "no stop loss supplied (no-naked-risk rail)")

        risk_per_unit = abs(req.entry - req.stop)
        if risk_per_unit <= 0:
            return self._rejected(req, "entry equals stop (zero risk-per-unit)")

        # Rail 1 (daily-loss): remaining room caps qty by risk-per-unit.
        room = req.dailyLossCap - req.dailyLossUsed
        if room <= 0:
            return self._rejected(req, "daily loss cap already reached")
        max_qty_by_loss = int((room / risk_per_unit).to_integral_value(rounding=ROUND_FLOOR))
        max_lots_by_loss = max_qty_by_loss // lot_size

        # Rail 2 (max-capital-%): largest lot count whose SPAN total <= capital budget.
        max_by_capital = req.capital * req.maxCapitalPct
        engine = self._engine()
        max_lots_by_margin = self._max_lots_within_margin(engine, req, max_by_capital)

        limiting_rail = "dailyLoss" if max_lots_by_loss <= max_lots_by_margin else "capitalPct"
        lots = min(max_lots_by_loss, max_lots_by_margin)
        if lots <= 0:
            reason = (
                "daily-loss room admits zero lots"
                if max_lots_by_loss <= max_lots_by_margin
                else "capital budget admits zero lots"
            )
            return self._rejected(req, reason, limiting_rail)

        qty = lots * lot_size
        result = self._basket_total(engine, req, lots)
        margin_required = _q2(Decimal(str(result.total)))
        risk_amount = _q2(risk_per_unit * Decimal(qty))
        target = self._target(req)

        if max_lots_by_loss < max_lots_by_margin:
            warnings.append("size clamped by remaining daily-loss room")
        elif max_lots_by_margin < max_lots_by_loss:
            warnings.append("size clamped by max-capital-% margin budget")

        return SizeResponse(
            lots=lots,
            qty=qty,
            marginRequired=margin_required,
            capitalUsed=margin_required,
            riskAmount=risk_amount,
            target=target,
            withinRails=True,
            warnings=warnings,
            limitingRail=limiting_rail,
        )

    def _max_lots_within_margin(self, engine, req: SizeRequest, budget: Decimal) -> int:
        """Largest lot count whose basket SPAN total <= ``budget`` (linear scan, cap 50).

        SPAN total is monotonic in lot count for a single-direction basket, so a
        forward scan from 1 lot suffices; capped at 50 lots to bound the loop on a
        huge capital budget (scalp sizing never needs more)."""
        best = 0
        for lots in range(1, 51):
            total = Decimal(str(self._basket_total(engine, req, lots).total))
            if total <= budget:
                best = lots
            else:
                break
        return best

    @staticmethod
    def _target(req: SizeRequest) -> Decimal:
        """RR target = entry +/- rrTarget * |entry-stop| in the trade's direction."""
        risk = abs(req.entry - req.stop)
        long_side = req.legs[0].side == "BUY"
        delta = req.rrTarget * risk
        return _q2(req.entry + delta if long_side else req.entry - delta)

    def _rejected(
        self, req: SizeRequest, reason: str, limiting_rail: str | None = None
    ) -> SizeResponse:
        """A within-rails:false, zero-lot response (target still computed when possible)."""
        try:
            target = self._target(req) if req.stop else Decimal("0")
        except Exception:  # noqa: BLE001 - defensive; a malformed rr never crashes sizing
            target = Decimal("0")
        return SizeResponse(
            lots=0,
            qty=0,
            marginRequired=Decimal("0"),
            capitalUsed=Decimal("0"),
            riskAmount=Decimal("0"),
            target=target,
            withinRails=False,
            warnings=[reason],
            limitingRail=limiting_rail,
        )
