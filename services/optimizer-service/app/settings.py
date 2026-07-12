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
    stress_max_concurrent_jobs: int
    scheduler_enabled: bool
    scheduler_interval_seconds: int

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
            # Cost-stress dispatch reservation: max stress BACKTEST runs in flight at once, so a
            # plateau round can't flood the interactive worker pool (composes with backtest-service
            # B16). Default 2 leaves headroom on any pool with 3+ workers.
            stress_max_concurrent_jobs=int(
                os.environ.get("ARTHA_STRESS_MAX_CONCURRENT_JOBS", "2")
            ),
            # E6 item 16 autonomy scheduler — DEFAULT OFF. When false (the default), NO background
            # driver starts: campaigns advance ONLY via the owner-triggered POST /scheduler/tick.
            # Arming is owner-gated (a live .env flip → the compose passthrough of the SAME name,
            # ARTHA_EVO_SCHEDULER_ENABLED). NOTHING self-arms/self-publishes even when true — the
            # scheduler advances research (sweeps/scoring/proposals) only (§1.4.1).
            scheduler_enabled=_env_bool("ARTHA_EVO_SCHEDULER_ENABLED", default=False),
            # The background driver's poll cadence (only read when scheduler_enabled). Each tick
            # advances every ACTIVE campaign by at most one step; per-campaign generation cadence is
            # a separate budget knob (budget.cadenceSeconds).
            scheduler_interval_seconds=int(
                os.environ.get("ARTHA_EVO_SCHEDULER_INTERVAL_SECONDS", "300")
            ),
        )


def _env_bool(name: str, *, default: bool) -> bool:
    """Parse a boolean env flag ('1'/'true'/'yes'/'on', case-insensitive → True). An unset/blank var
    falls to ``default`` — the autonomy flag defaults OFF, so a missing passthrough stays dormant,
    never accidentally armed."""
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}
