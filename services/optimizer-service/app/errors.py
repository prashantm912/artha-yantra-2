"""The shared error envelope (COMMON §8.3): ``{code, message, details}``. FastAPI
maps :class:`ApiError` to it so optimizer-service speaks the same taxonomy as the
Java services (DATA_GAP, INVALID_PARAMETER_PATH, VALIDATION_FAILED, NOT_FOUND_*)."""

from __future__ import annotations

import logging
import uuid
from typing import Any

from fastapi import Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

log = logging.getLogger("optimizer")

#: The house correlation header (``ArthaHeaders.X_REQUEST_ID``). The gateway mints one UUID per
#: inbound request and forwards it upstream (RequestIdWebFilter, A.2.4), so a request that arrived
#: through the gateway carries one already.
REQUEST_ID_HEADER = "X-Request-Id"


class ApiError(Exception):
    """An error that renders to the shared envelope at a given HTTP status."""

    def __init__(
        self, status: int, code: str, message: str, details: dict[str, Any] | None = None
    ) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.details = details or {}


async def api_error_handler(_request: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status,
        content={"code": exc.code, "message": exc.message, "details": exc.details},
    )


async def invalid_path_handler(request: Request, exc: Exception) -> JSONResponse:
    """Maps a closed-grammar violation to 400 INVALID_PARAMETER_PATH (must be async so FastAPI
    awaits it)."""
    return await api_error_handler(
        request, ApiError(400, "INVALID_PARAMETER_PATH", str(exc))
    )


async def validation_error_handler(request: Request, exc: Exception) -> JSONResponse:
    """FastAPI's OWN transport validation (a pydantic body model, a typed path param) raises
    :class:`RequestValidationError`, whose stock body is ``{"detail": [...]}`` — not the shared
    envelope. Remap it so every optimizer error speaks one shape: 422 VALIDATION_FAILED, the
    offending field summarized in ``message`` (what the SPA toasts), the full per-field pydantic
    report preserved under ``details.errors``. The STATUS is unchanged (422 = transport
    validation); 400 stays the service's own semantic VALIDATION_FAILED."""
    errors = jsonable_encoder(exc.errors()) if isinstance(exc, RequestValidationError) else []
    summary = "; ".join(
        f"{'.'.join(str(part) for part in err.get('loc', []))}: {err.get('msg')}" for err in errors
    )
    return await api_error_handler(
        request,
        ApiError(
            422, "VALIDATION_FAILED", summary or "request validation failed", {"errors": errors}
        ),
    )


def _correlation_id(request: Request) -> str:
    """The gateway forwards one ``X-Request-Id`` per inbound request; a direct compose-internal
    call (or a test) has none, so mint one — a locally-generated id still links this response to
    its own log line, which is the whole point of putting it in the envelope."""
    return request.headers.get(REQUEST_ID_HEADER) or str(uuid.uuid4())


async def unhandled_error_handler(request: Request, exc: Exception) -> JSONResponse:
    """Last resort, mirroring the Java services' GlobalExceptionHandler (A.4): an unmapped
    exception stays a 500 — it IS unexpected — but renders the shared envelope instead of a bare,
    bodyless one, and is logged server-side WITH the traceback (never returned to the client). The
    correlation id appears in BOTH the log line and ``details.correlationId``, which is what lets
    an owner tie the 500 they saw to the log line explaining it."""
    correlation_id = _correlation_id(request)
    log.exception(
        "Unhandled exception on %s %s (correlationId=%s)",
        request.method,
        request.url.path,
        correlation_id,
    )
    return await api_error_handler(
        request,
        ApiError(500, "INTERNAL_ERROR", "Internal error", {"correlationId": correlation_id}),
    )
