"""Runtime configuration from the environment (CD-15 plain config, no framework magic)."""

from __future__ import annotations

import os
from dataclasses import dataclass


def _db_password() -> str:
    path = os.environ.get("ARTHA_DB_PASSWORD_FILE")
    if path and os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            return handle.read().strip()
    return os.environ.get("ARTHA_DB_PASSWORD", "")


@dataclass(frozen=True)
class Settings:
    """Resolved settings; ``load()`` reads the environment once at startup."""

    db_host: str
    db_port: int
    db_name: str
    db_user: str
    db_password: str
    redis_url: str
    strategy_signal_base: str
    backtest_base: str
    ntfy_url: str
    ntfy_topic: str
    evo_paper_cap: int

    @property
    def conninfo(self) -> str:
        """libpq conninfo targeting the ``backtest`` schema (the optimizer's owned schema)."""
        return (
            f"host={self.db_host} port={self.db_port} dbname={self.db_name} "
            f"user={self.db_user} password={self.db_password} options='-c search_path=backtest'"
        )

    @staticmethod
    def load() -> Settings:
        return Settings(
            db_host=os.environ.get("DB_HOST", "timescaledb"),
            db_port=int(os.environ.get("DB_PORT", "5432")),
            db_name=os.environ.get("DB_NAME", "artha"),
            db_user=os.environ.get("DB_USER", "artha"),
            db_password=_db_password(),
            redis_url=os.environ.get("REDIS_URL", "redis://redis:6379/0"),
            strategy_signal_base=os.environ.get(
                "STRATEGY_SIGNAL_BASE", "http://strategy-signal-service:8082"
            ),
            backtest_base=os.environ.get("BACKTEST_BASE", "http://backtest-service:8083"),
            # First-party ntfy (EVO E4 item 11): the SAME env names the Java services read
            # (ARTHA_NTFY_URL / ARTHA_NTFY_TOPIC). Blank topic → client no-ops (mock needs none).
            ntfy_url=os.environ.get("ARTHA_NTFY_URL", "https://ntfy.sh"),
            ntfy_topic=os.environ.get("ARTHA_NTFY_TOPIC", ""),
            # §1.4.3 safety invariant: <= 2 concurrent evo paper strategies per family book.
            evo_paper_cap=int(os.environ.get("ARTHA_EVO_PAPER_CAP", "2")),
        )
