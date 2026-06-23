"""Runtime configuration from the environment (CD-15 plain config, no framework magic)."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    """Resolved settings; ``load()`` reads the environment once at startup."""

    spn_dir: str

    @staticmethod
    def load() -> Settings:
        return Settings(
            # Read-only bind-mount of the daily host-fetched NSE .spn files (see §8.2).
            spn_dir=os.environ.get("SPN_DIR", "/spn"),
        )
