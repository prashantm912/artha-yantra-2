"""The shared error envelope (COMMON §8.3): ``{code, message, details}``. FastAPI
maps :class:`ApiError` to it so optimizer-service speaks the same taxonomy as the
Java services (DATA_GAP, INVALID_PARAMETER_PATH, VALIDATION_FAILED, NOT_FOUND_*)."""

from __future__ import annotations

from typing import Any

from fastapi import Request
from fastapi.responses import JSONResponse


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
