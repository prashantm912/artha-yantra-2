"""The §8.4 margin REST surface under ``/api/v1/margin``.

``POST /``      -> raw SPAN margin for an arbitrary basket.
``POST /size``  -> rail-bounded position sizing (the S12 / order-widget caller).

Errors render through the shared ``{code,message,details}`` envelope (see
``app/errors.py``). Note (§8.4 #6): *no averaging losers* is a position-state rule
enforced by the caller (S12), not by this stateless endpoint."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request

from app.models import MarginRequest, MarginResponse, SizeRequest, SizeResponse

router = APIRouter(prefix="/api/v1/margin")


def _service(request: Request) -> Any:
    return request.app.state.margin


@router.post("")
def margin(body: MarginRequest, request: Request) -> MarginResponse:
    """Raw SPAN (span/exposure/total/hedgeBenefit) for a basket; 503 if no .spn loaded."""
    return _service(request).margin(body)


@router.post("/size")
def size(body: SizeRequest, request: Request) -> SizeResponse:
    """Rail-bounded lots/qty + margin + RR target for an order; never blocks (advisory)."""
    return _service(request).size(body)
