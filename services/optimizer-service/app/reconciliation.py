"""EVO E3 backtest-vs-live reconciliation computer (design §7 / roadmap §12 E3 item 7).

Purpose (design §7): for a strategy version that has accrued LIVE evidence, (a) fairly compare its
live evidence to a re-simulation of the EXACT version over the EXACT lived window, and (b) raise a
material-divergence tripwire (the §7.2 gate). This PR COMPUTES + PERSISTS the gap vector, gapZ,
verdict, and (when DIVERGENT) the diagnosis checklist. It does NOT wire the live-gap gate into
RobustScore — that is the SEPARATE later PR (§12 item 10); scoring.py is untouched here.

Mechanics (design §7.1, reusing the neighbor-probe / cost-stress drain idiom):
  1. POST validates the version (404) + window, refuses a duplicate (version, window) (409), reads
     the version's LIVE paper trades over the window, then submits a matched-window re-sim of the
     EXACT version through the job pipeline (``purpose: "reconcile"`` — free-form,
     StressGuard-exempt;
     for a SWING version the re-sim PINS ``session.fill_timing: at_close`` via the request's
     ``sessionOverrides`` block (§7.1.2) so the sim fills where live paper enters (the daily
     close), not the pipeline's NEXT_OPEN default; see ``create`` + ``_dispatch_caveats`` below).
  2. A background daemon POLLS the re-sim job to a terminal state (bounded — one dead run degrades
     to INSUFFICIENT, never a hang), reads the sim run's trades + return, PAIRS them against the
     live trades, computes the §7.1.3 gap vector + gapZ, derives the §7.2 verdict, and INSERTs ONE
     reconciliation row atomically (never a half-row on a crash; the UNIQUE(version, window) index
     is
     the hard backstop against a concurrent double-submit).

Degrade-honest (design §7.1.3, §13 rows 6/11): ``exitReasonDivergence`` needs audit P1-3
(candle-path
exit-reason attribution — not landed) → null + a caveat; ``slippageRealized`` needs P1-5 quote
capture
→ null + a caveat; ``entryPriceDelta`` for scalpers is the ``scalper_detail.option_ltp`` proxy until
P1-4/5. Every degradation is stamped, never silently zeroed.
"""

from __future__ import annotations

import logging
import math
import statistics
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.errors import ApiError

router = APIRouter(prefix="/api/v1/evolution")

_LOG = logging.getLogger(__name__)

_IST = timezone(timedelta(hours=5, minutes=30))

# §7.2 divergence-gate thresholds (gapZ = returnGap / σ(fold returns)).
_ALIGNED_MIN = -0.5      # gapZ ≥ −0.5 → aligned
_DIVERGENT_MAX = -1.5    # gapZ ≤ −1.5 (AND evidence floor) → DIVERGENT; between → PENALIZED

# §7.2 evidence floor for a DIVERGENT verdict: ≥ 20 paired trades OR ≥ 4 weeks lived.
_FLOOR_PAIRED_TRADES = 20
_FLOOR_WINDOW_DAYS = 28

# Re-sim drain bounds (mirror the cost-stress drain, #729): a single full-window re-run polled to a
# terminal state; a slow/dead run degrades to INSUFFICIENT, never a spinning daemon.
_POLL_INTERVAL_SECONDS = 3.0
_POLL_TIMEOUT_SECONDS = 1800.0
_TERMINAL_NO_RESULT = {"failed", "cancelled"}


# --- gap-vector math (pure) ---------------------------------------------------------------------


def _annualize(window_return: float | None, window_days: float) -> float | None:
    """Linear-annualize a fractional window return: ``r × 365 / days`` (design §7.1.3 "annualized").
    Linear (not compounded) is deliberate — compounding a possibly-NEGATIVE window return over a
    fraction-of-a-year power is undefined/complex, and the live and sim windows are IDENTICAL so the
    factor is common (it cancels in the gap's SIGN and in gapZ, which is computed on the RAW gap
    over
    the raw fold-return σ — same scale on both sides). ``None`` in / a zero window → ``None``."""
    if window_return is None or window_days <= 0:
        return None
    return window_return * 365.0 / window_days


def _return_gap_raw(live_return: float | None, sim_return: float | None) -> float | None:
    """The raw matched-window return gap: ``live − sim`` (both fractional returns over the IDENTICAL
    window). This is the scale gapZ divides by σ(raw fold returns) — kept raw so both sides of the
    z-score share one scale. ``None`` when either side is absent."""
    if live_return is None or sim_return is None:
        return None
    return live_return - sim_return


def _jaccard(live_keys: set[Any], sim_keys: set[Any]) -> float | None:
    """Trade-set overlap = Jaccard(|live ∩ sim| / |live ∪ sim|) on paired trade keys (design §7.1.3;
    swing target ≥ 0.9 — a low overlap means the INPUTS diverged, screener/CA/feed, before fills
    did).
    ``None`` when BOTH sets are empty (no basis — not a perfect-overlap 1.0, which would
    mislead)."""
    union = live_keys | sim_keys
    if not union:
        return None
    return len(live_keys & sim_keys) / len(union)


def _entry_price_delta_bps(pairs: list[tuple[float, float]]) -> float | None:
    """Mean absolute entry-price delta in basis points over the paired trades: ``mean(|live − sim| /
    sim × 10_000)`` (design §7.1.3). For scalpers ``live`` is the ``scalper_detail.option_ltp``
    proxy
    until audit P1-4/5. ``None`` when no pair carries a usable (non-zero sim) price."""
    deltas = [
        abs(live - sim) / abs(sim) * 10_000.0
        for live, sim in pairs
        if sim not in (None, 0) and live is not None
    ]
    return statistics.fmean(deltas) if deltas else None


def _fold_return_sigma(fold_returns: list[float] | None) -> float | None:
    """σ of the version's per-fold OOS returns (design §7.2 "σ(fold returns)") — the population
    stdev
    over ≥ 2 folds. ``None`` (→ gap_z NULL → verdict INSUFFICIENT) when the version has < 2 folds of
    history in its sweep/run lineage."""
    if not fold_returns or len(fold_returns) < 2:
        return None
    return statistics.pstdev(fold_returns)


def _time_scale(recon_days: float | None, fold_days: float | None) -> float:
    """The √t time-normalization factor applied to the raw gap before z-scoring: √(T_fold/T_recon).
    Falls back to 1 (no scaling) when either duration is unknown or non-positive — the caller stamps
    a caveat so the un-normalized z is never silent."""
    if recon_days and fold_days and recon_days > 0 and fold_days > 0:
        return math.sqrt(fold_days / recon_days)
    return 1.0


def _gap_z(
    return_gap_raw: float | None,
    sigma: float | None,
    recon_days: float | None = None,
    fold_days: float | None = None,
) -> float | None:
    """gapZ = time-normalized returnGap / σ(fold returns) (design §7.2).

    Time normalization (the √t noise model): σ is the dispersion of FOLD-TEST-window returns (mean
    duration T_fold), while the raw gap accrued over the RECONCILE window (T_recon) — two unrelated
    durations. Under the diffusive model return noise scales ~ √t, so the raw gap is rescaled to the
    fold time-base by √(T_fold/T_recon) before dividing by σ. Without this a 90-day live window over
    30-day folds inflates |gapZ| by √3 (a true −0.9 PENALIZED reads −1.56 DIVERGENT) and the reverse
    deflates toward a false ALIGNED. T_recon == T_fold ⇒ factor 1 (identical to the unscaled z);
    either duration unknown ⇒ factor 1 + a caller-stamped caveat. ``None`` when the gap or σ is
    absent, or σ is zero (a degenerate all-identical fold history has no dispersion to z on)."""
    if return_gap_raw is None or sigma in (None, 0):
        return None
    return return_gap_raw * _time_scale(recon_days, fold_days) / sigma


def _mean_fold_test_days(folds: list[dict[str, Any]]) -> float | None:
    """The mean fold TEST-window duration in days — the T_fold of the §7.2 √t normalization. Each
    fold of the ``/folds`` payload carries its test bounds (``test: {from, to}`` —
    WalkForwardRunner.java:179 + window() at :246-251); folds missing/unparseable bounds are
    skipped. ``None`` when no fold carries usable bounds (→ scale 1 + caveat)."""
    days: list[float] = []
    for fold in folds:
        test = fold.get("test") or {}
        start, end = _parse_dt(test.get("from")), _parse_dt(test.get("to"))
        if start is not None and end is not None and end > start:
            days.append((end - start).total_seconds() / 86400.0)
    return statistics.fmean(days) if days else None


def _evidence_floor_met(paired_trades: int, window_days: float) -> bool:
    """§7.2 evidence floor: ≥ 20 paired trades OR ≥ 4 weeks lived — the bar a gapZ ≤ −1.5 must clear
    to harden from INSUFFICIENT into DIVERGENT."""
    return paired_trades >= _FLOOR_PAIRED_TRADES or window_days >= _FLOOR_WINDOW_DAYS


def _verdict(gap_z: float | None, evidence_floor_met: bool, has_live_evidence: bool) -> str:
    """The §7.2 divergence verdict:
      * no live evidence at all, or gap_z is None (no fold-return σ) → INSUFFICIENT;
      * gap_z ≥ −0.5                                                 → ALIGNED;
      * −1.5 < gap_z < −0.5                                          → PENALIZED;
      * gap_z ≤ −1.5 AND evidence floor met                         → DIVERGENT;
      * gap_z ≤ −1.5 but floor unmet                                → INSUFFICIENT.
    """
    if not has_live_evidence or gap_z is None:
        return "INSUFFICIENT"
    if gap_z >= _ALIGNED_MIN:
        return "ALIGNED"
    if gap_z > _DIVERGENT_MAX:
        return "PENALIZED"
    return "DIVERGENT" if evidence_floor_met else "INSUFFICIENT"


def _overlap_note(trade_set_overlap: float | None, single_pick: bool) -> str:
    """The §7.2.5 trade-set-overlap note. For a DYNAMIC-LIST (funnel/screener) universe the re-sim
    signals on the TOP pinned pick only (BacktestRunner.signalInstrument) while live papers the
    whole portfolio, so overlap ≈ 0 STRUCTURALLY — the low-overlap ⇒ UPSTREAM inference would be a
    false diagnosis and is suppressed. For explicit universes low overlap (< 0.5) stays the
    strongest automated signal that the divergence is upstream (screener/data), not execution."""
    if single_pick:
        return (
            "§7.2.5 trade-set overlap — STRUCTURAL for a dynamic-list (funnel/screener) universe: "
            "single-pick sim vs multi-name live portfolio; low overlap does NOT imply upstream "
            "divergence"
        )
    if trade_set_overlap is not None and trade_set_overlap < 0.5:
        return (
            "§7.2.5 trade-set overlap — LOW overlap ⇒ the divergence is UPSTREAM "
            "(screener/data planes, audit D1/D12), not execution"
        )
    return "§7.2.5 trade-set overlap adequate — divergence is not obviously upstream"


def _diagnosis(
    trade_set_overlap: float | None,
    slippage_realized: float | None,
    single_pick: bool = False,
) -> dict[str, Any]:
    """The §7.2.1-5 DIVERGENT diagnosis checklist. Each item declares whether it is ``auto``
    (computed
    from this reconciliation), ``pending`` (blocked on an un-landed Prompt-1 audit input), or
    ``manual`` (a human check — no automated source wired in this PR). Only ``tradeSetOverlap`` (#5)
    is auto today; the rest degrade honest (self-flagged in the receipt). The overlap note is
    universe-mode-aware (``_overlap_note``) — a funnel/screener single-pick sim must never read as
    an upstream-divergence finding."""
    return {
        "checklist": [
            {
                "id": "data_health_incidents",
                "status": "manual",
                "note": "§7.2.1 feed canaries / subscriber gaps / batch misses in the window — no "
                "automated marketdata-health query wired here; check /market/health/* manually",
            },
            {
                "id": "partial_bucket_canary",
                "status": "manual",
                "note": "§7.2.2 partial-bucket canary state (audit P0-1) — coarse-primary scalper "
                "live evidence is quarantined wholesale until fixed; verify the canary manually",
            },
            {
                "id": "flag_regime_changes",
                "status": "pending",
                "note": "§7.2.3 mid-window flag/regime changes — needs P1-7 flag snapshot (not "
                "landed); windows crossing a flag flip are discarded manually until then",
            },
            {
                "id": "cost_slippage_realized_vs_modeled",
                "status": "pending" if slippage_realized is None else "auto",
                "note": "§7.2.4 realized vs modeled slippage — needs audit P1-5 quote capture (not "
                "landed) → slippageRealized null"
                if slippage_realized is None
                else "§7.2.4 realized vs modeled slippage",
                "value": slippage_realized,
            },
            {
                "id": "trade_set_overlap",
                "status": "auto",
                "note": _overlap_note(trade_set_overlap, single_pick),
                "value": trade_set_overlap,
            },
        ],
    }


# --- IST / trade helpers (the #214 timestamp-key trap) ------------------------------------------


def _ist_date(raw: Any) -> str | None:
    """The IST CALENDAR DATE (``YYYY-MM-DD``) of a timestamp, keyed by INSTANT — the #214 trap: a
    cross-source ``OffsetDateTime`` map key silently misses when the two sides carry different UTC
    offsets (JDBC ``time_bucket`` returns +00, IST rows carry +05:30). Parsing to an aware datetime
    then converting to IST before taking ``.date()`` compares by the same instant on both sides. A
    naive (offset-less) timestamp is assumed already-IST (the paper/live rows are IST-local)."""
    dt = _parse_dt(raw)
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=_IST)
    return dt.astimezone(_IST).date().isoformat()


def _parse_dt(raw: Any) -> datetime | None:
    """Parse an ISO-8601 string (or pass a ``datetime`` through) to a datetime; ``None`` on junk."""
    if isinstance(raw, datetime):
        return raw
    if not isinstance(raw, str) or not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None


def _window_days(window_from: str, window_to: str) -> float:
    """The lived window's calendar span in days (the §7.2 ≥-4-weeks evidence-floor input + the
    annualization denominator). A malformed / inverted window → 0 (→ the floor's paired-trade arm
    decides + annualization degrades to None)."""
    start, end = _parse_dt(window_from), _parse_dt(window_to)
    if start is None or end is None:
        return 0.0
    if start.tzinfo is None:
        start = start.replace(tzinfo=_IST)
    if end.tzinfo is None:
        end = end.replace(tzinfo=_IST)
    return max((end - start).total_seconds() / 86400.0, 0.0)


def _to_ist_iso(raw: str) -> str:
    """Normalize a window bound (a plain date or an offset date-time) to an explicit IST (+05:30)
    ISO
    string — the ``window_from``/``window_to`` stored + used for the 409 idempotency check +
    passed as
    the re-sim window. A plain ``YYYY-MM-DD`` → midnight IST (matching JobsService.toDateTime); a
    naive
    date-time is assumed IST; an offset-bearing one is kept. Never a bare ``::date`` (the
    in-container
    UTC off-by-one trap)."""
    if len(raw) == 10:
        return raw + "T00:00:00+05:30"
    dt = _parse_dt(raw)
    if dt is None:
        raise ApiError(400, "VALIDATION_FAILED", f"unparseable window bound: {raw!r}")
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=_IST)
    return dt.isoformat()


def _num(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _norm_symbol(symbol: Any) -> str:
    """A stable trade-key symbol: trimmed + upper-cased. Live (paper_positions.tradingsymbol) and
    sim
    (backtest_trades.tradingsymbol) are both Kite-grammar tradingsymbols, so an exact normalized
    match
    is the pairing key; a low overlap is itself the §7.1.3 signal that the INPUTS diverged."""
    return str(symbol).strip().upper() if symbol is not None else ""


# Pairing mode per risk.session.style (the strategy-schema-v1 enum, made EXPLICIT — a binary
# is-swing split silently sent btst/positional/expiry_day to scalper pairing). swing/positional
# hold multi-day and pair like daily equity entries (symbol, IST entry date, avg_entry_price);
# intraday/btst/expiry_day are the option-leg families (scalper_detail carries the leg).
_STYLE_MODES = {
    "swing": "swing",
    "positional": "swing",
    "intraday": "scalper",
    "btst": "scalper",
    "expiry_day": "scalper",
}

# Dynamic-list universe modes whose backtest signals on the TOP pinned pick only
# (BacktestRunner.signalInstrument) while live papers the whole funnel/screen — the single-pick
# structural distortion (§13 row 8).
_SINGLE_PICK_MODES = {"manas_arora_funnel", "minervini_funnel", "futures_screener"}

_SINGLE_PICK_CAVEAT = (
    "single-pick sim vs multi-name live portfolio (dynamic-list universe): the re-sim signals on "
    "the TOP pinned pick only, so tradeSetOverlap and returnGap are structurally distorted; low "
    "overlap does NOT imply upstream divergence"
)


def _mode_for_style(config: dict[str, Any]) -> tuple[str, str | None]:
    """The pairing mode for the version's ``risk.session.style`` (schema enum, ``_STYLE_MODES``) +
    an honesty caveat when the style is unrecognized/absent — such a config degrades to scalper
    (option-leg) pairing WITH the caveat, never a silent guess."""
    style = ((config.get("risk") or {}).get("session") or {}).get("style")
    mode = _STYLE_MODES.get(style)
    if mode is not None:
        return mode, None
    return "scalper", (
        f"session style {style!r} is not recognized by the pairing model "
        f"(known: {sorted(_STYLE_MODES)}) — degrading to scalper (option-leg) pairing"
    )


def _is_single_pick(config: dict[str, Any]) -> bool:
    """True for a dynamic-list universe (``universe.mode`` in ``_SINGLE_PICK_MODES``) — the re-sim
    is a single-pick replay, so overlap/returnGap are structurally distorted vs the live book."""
    return ((config.get("universe") or {}).get("mode")) in _SINGLE_PICK_MODES


def _scalper_entry_ltp(scalper_detail: dict[str, Any] | None) -> float | None:
    """The scalper option leg's entry-premium proxy: ``scalper_detail.option_ltp`` (the LTP at
    signal
    firing — SignalEngine.scalperDetailJson, V009 side-channel), the design §7.1.3
    ``entryPriceDelta``
    proxy until audit P1-4/5. For a NEUTRAL straddle the first leg's ``option_ltp`` stands in."""
    if not isinstance(scalper_detail, dict):
        return None
    ltp = _num(scalper_detail.get("option_ltp"))
    if ltp is not None:
        return ltp
    legs = scalper_detail.get("legs")
    if isinstance(legs, list):
        for leg in legs:
            if isinstance(leg, dict):
                ltp = _num(leg.get("option_ltp"))
                if ltp is not None:
                    return ltp
    return None


# --- trade pairing (the §7.1.2 pairing keys) ----------------------------------------------------


def _live_key_price(trade: dict[str, Any], mode: str) -> tuple[Any, float | None]:
    """A live paper trade's pairing key + entry price. SWING pairs by (symbol, IST entry DATE) on
    the
    equity, entry = the FillSimulator-stamped ``avgEntryPrice`` (§7.1.2). SCALPER pairs by the
    tradeable OPTION contract (paper_positions.tradingsymbol IS the option) + IST entry date,
    entry =
    the ``scalper_detail.option_ltp`` proxy (§7.1.3, falling back to avgEntryPrice)."""
    key = (_norm_symbol(trade.get("tradingsymbol")), _ist_date(trade.get("openedAt")))
    if mode == "scalper":
        price = _scalper_entry_ltp(trade.get("scalperDetail"))
        if price is None:
            price = _num(trade.get("avgEntryPrice"))
        return key, price
    return key, _num(trade.get("avgEntryPrice"))


def _sim_key_price(trade: dict[str, Any], mode: str) -> tuple[Any, float | None]:
    """A sim (re-run) trade's pairing key + entry price, keyed identically to the live side:
    (symbol,
    IST entry DATE) on the sim trade's tradingsymbol (the equity for swing, the option contract
    for a
    premium-replay scalper leg), entry = the sim fill ``entryPrice``. Keyed by the entry INSTANT's
    IST
    date, never the raw offset-bearing timestamp (the #214 cross-source timestamp-key trap)."""
    key = (_norm_symbol(trade.get("tradingsymbol")), _ist_date(trade.get("entryTs")))
    return key, _num(trade.get("entryPrice"))


@dataclass
class _GapInputs:
    """The paired-trade products the gap vector consumes: the live/sim key SETS (Jaccard overlap),
    the count of PAIRED KEYS (the §7.2 evidence-floor input), and the (live, sim) entry-price pairs
    for the entryPriceDelta."""

    live_keys: set[Any] = field(default_factory=set)
    sim_keys: set[Any] = field(default_factory=set)
    entry_pairs: list[tuple[float, float]] = field(default_factory=list)

    @property
    def paired_count(self) -> int:
        """UNIQUE paired keys — first fill per (symbol, IST entry date), NOT per-fill multiplicity
        (a key with several fills counts once). Persisted as ``paired_trades`` and stamped as the
        gap's ``pairedBasis`` so the column's meaning is on the record."""
        return len(self.live_keys & self.sim_keys)


def _pair_trades(
    live_trades: list[dict[str, Any]], sim_trades: list[dict[str, Any]], mode: str
) -> _GapInputs:
    """Pair live↔sim trades by the §7.1.2 key (mode-dependent). First-wins per key on each side for
    the entry-price pair (a key with multiple trades takes the first — the entryPriceDelta is a
    per-key comparison, not per-fill). Returns the key sets + the paired entry-price pairs."""
    live_by_key: dict[Any, float | None] = {}
    for t in live_trades:
        key, price = _live_key_price(t, mode)
        live_by_key.setdefault(key, price)
    sim_by_key: dict[Any, float | None] = {}
    for t in sim_trades:
        key, price = _sim_key_price(t, mode)
        sim_by_key.setdefault(key, price)
    inputs = _GapInputs(live_keys=set(live_by_key), sim_keys=set(sim_by_key))
    for key in inputs.live_keys & inputs.sim_keys:
        live_p, sim_p = live_by_key[key], sim_by_key[key]
        if live_p is not None and sim_p is not None:
            inputs.entry_pairs.append((live_p, sim_p))
    return inputs


def _live_return_pct(live_trades: list[dict[str, Any]], capital: float) -> tuple[float, int]:
    """The live window return as a PERCENT on the common re-sim notional (design §7.1.3): Σ realized
    P&L of the window's paper positions ÷ ``capital`` × 100 (the SAME base the re-sim ran on, so
    live
    and sim returns share a scale — see the §13-row-8 portfolio-effect caveat: a funnel swing sim
    signals on the TOP pick while live papers many names). Returns ``(return_pct, open_count)`` —
    ``open_count`` positions have no realized P&L yet (contribute 0), flagged as a caveat when >
    0."""
    total_pnl = 0.0
    open_count = 0
    for t in live_trades:
        pnl = _num(t.get("realizedPnl"))
        if pnl is not None:
            total_pnl += pnl
        if t.get("status") != "CLOSED":
            open_count += 1
    return (total_pnl / capital * 100.0 if capital else 0.0), open_count


# --- orchestration models -----------------------------------------------------------------------


class ReconciliationModel(BaseModel):
    """One persisted reconciliation (``reconciliations`` row, V012) — the GET ?versionId= shape."""

    id: str
    versionId: str | None = None
    strategyId: str | None = None
    windowFrom: str | None = None
    windowTo: str | None = None
    simJobId: str | None = None
    simRunId: str | None = None
    gap: dict[str, Any] | None = None
    gapZ: float | None = None
    pairedTrades: int
    evidenceFloorMet: bool
    verdict: str
    diagnosis: dict[str, Any] | None = None
    createdAt: str | None = None


class ReconciliationListResponse(BaseModel):
    items: list[ReconciliationModel]


class ReconciliationReceipt(BaseModel):
    """The 202 receipt for a dispatched reconciliation (design §7.1): the matched-window re-sim was
    submitted (``simJobId``); the gap row lands on the async drain's completion and is read back via
    GET ?versionId=. ``caveats`` surfaces the honest degradations known at dispatch (the scalper
    §7.2 structural divergence, the funnel single-pick portfolio-effect)."""

    versionId: str
    windowFrom: str
    windowTo: str
    simJobId: str | None = None
    mode: str
    liveTrades: int
    status: str = "computing"
    caveats: list[str] = []


@dataclass(frozen=True)
class _ReconContext:
    """The frozen context a dispatched reconciliation's drain needs to compute + persist the row.
    ``single_pick`` marks a dynamic-list (funnel/screener) universe whose re-sim replays the TOP
    pinned pick only — it steers the diagnosis note away from the false UPSTREAM inference."""

    version_id: str
    strategy_id: str | None
    window_from: str
    window_to: str
    sim_job_id: str | None
    mode: str
    single_pick: bool
    live_trades: list[dict[str, Any]]
    dispatch_caveats: list[str]


class ReconciliationService:
    """Computes + persists one backtest-vs-live reconciliation per (version, window) (design §7).
    Collaborators injected (the reconciliation repo factory, the live-evidence repo factory, the
    backtest REST client) so tests drive it with in-memory fakes. The re-sim is submitted through
    the
    HTTP job pipeline (BacktestClient.run — universe pinning + version resolve happen there); a
    background drain polls it to a terminal state and INSERTs the row. The in-flight guard is an
    in-memory (version, window) registry (single-process optimizer) backed by the DB UNIQUE
    index."""

    def __init__(
        self,
        *,
        repo_factory: Callable[[], Any],
        live_factory: Callable[[], Any],
        backtest_client: Any,
        capital: float = 1_000_000.0,
        poll_interval: float = _POLL_INTERVAL_SECONDS,
        poll_timeout: float = _POLL_TIMEOUT_SECONDS,
    ) -> None:
        self._repo_factory = repo_factory
        self._live_factory = live_factory
        self._backtest = backtest_client
        self._capital = capital
        self._poll_interval = poll_interval
        self._poll_timeout = poll_timeout
        self._in_flight: set[tuple[str, str, str]] = set()
        self._lock = threading.Lock()

    # --- public entry ---------------------------------------------------------------------------

    def create(self, body: dict[str, Any]) -> dict[str, Any]:
        """Validate + dispatch a reconciliation (design §7.1). 400 on a missing field; 404 unknown
        version; 409 if a reconciliation for (version, window) already exists OR one is in flight.
        Reads the version's live paper trades, submits the matched-window re-sim (``purpose:
        reconcile``), and returns a 202 receipt — the gap row lands on the drain's completion."""
        version_id = body.get("versionId")
        raw_from = body.get("from")
        raw_to = body.get("to")
        for name, value in (("versionId", version_id), ("from", raw_from), ("to", raw_to)):
            if not value:
                raise ApiError(400, "VALIDATION_FAILED", f"missing required field: {name}")
        window_from = _to_ist_iso(raw_from)
        window_to = _to_ist_iso(raw_to)

        live = self._live_factory()
        try:
            resolved = live.resolve_version(version_id)
        finally:
            live.close()
        if resolved is None:
            raise ApiError(404, "NOT_FOUND_VERSION", f"no strategy version {version_id}")
        strategy_id = resolved.get("strategyId")
        semver = resolved.get("version")
        config = resolved.get("config") or {}
        mode, style_caveat = _mode_for_style(config)
        single_pick = _is_single_pick(config)

        repo = self._repo_factory()
        try:
            if repo.get_by_version_window(version_id, window_from, window_to) is not None:
                raise ApiError(
                    409,
                    "CONFLICT_RECONCILIATION_EXISTS",
                    f"a reconciliation for version {version_id} over "
                    f"[{window_from}, {window_to}) already exists",
                )
        finally:
            repo.close()

        key = (version_id, window_from, window_to)
        if not self._acquire(key):
            raise ApiError(
                409,
                "CONFLICT_RECONCILIATION_IN_FLIGHT",
                f"a reconciliation for version {version_id} over that window is already computing",
            )
        try:
            live = self._live_factory()
            try:
                live_trades = live.paper_trades_for_version(version_id, window_from, window_to)
            finally:
                live.close()

            caveats = _dispatch_caveats(mode)
            if style_caveat:
                caveats.append(style_caveat)
            if single_pick:
                caveats.append(_SINGLE_PICK_CAVEAT)
            request = {
                "strategyId": strategy_id,
                "strategyVersion": semver,
                "from": window_from,
                "to": window_to,
                "purpose": "reconcile",
                "initialCapital": self._capital,
            }
            # EVO §7.1.2: a swing re-sim PINS session.fill_timing to at_close (BacktestRunRequest's
            # sessionOverrides, validated strict server-side) so the sim fills where live paper
            # enters — the daily close (SwingBatchEngine) — not the job-pipeline swing default
            # (next_open); without the pin, entry deltas would carry fill-timing noise, not
            # evidence. Scalper re-sims are NOT pinned: their reconciliation runs shadow-vs-paper
            # (§7.2), so the raw-candle fill-timing knob does not apply.
            if mode == "swing":
                request["sessionOverrides"] = {"fillTiming": "at_close"}
            job = self._backtest.run(request)
            sim_job_id = (job or {}).get("jobId")

            context = _ReconContext(
                version_id=version_id,
                strategy_id=strategy_id,
                window_from=window_from,
                window_to=window_to,
                sim_job_id=sim_job_id,
                mode=mode,
                single_pick=single_pick,
                live_trades=live_trades,
                dispatch_caveats=caveats,
            )
            self._start_drain(context)
        except Exception:
            self._release(key)
            raise
        return {
            "versionId": version_id,
            "windowFrom": window_from,
            "windowTo": window_to,
            "simJobId": sim_job_id,
            "mode": mode,
            "liveTrades": len(live_trades),
            "status": "computing",
            "caveats": caveats,
        }

    def list_for_version(self, version_id: str, limit: int, offset: int) -> dict[str, Any]:
        repo = self._repo_factory()
        try:
            rows = repo.list_by_version(version_id, limit, offset)
        finally:
            repo.close()
        return {"items": rows}

    # --- async drain ----------------------------------------------------------------------------

    def _start_drain(self, context: _ReconContext) -> None:
        threading.Thread(
            target=self._drain,
            kwargs={"context": context},
            daemon=True,
            name=f"reconcile-{context.version_id[:8]}",
        ).start()

    def _drain(self, *, context: _ReconContext) -> None:
        """Poll the re-sim to a terminal state, compute the gap, and INSERT the row (fresh
        per-thread
        repo, like the stress drain). The in-flight guard releases in a finally so a crash never
        wedges the (version, window) permanently; a failed/timed-out re-sim persists an INSUFFICIENT
        row (never a silent no-op — the record honestly says 'we tried, the re-sim did not yield
        sim evidence')."""
        try:
            run_id, sim_failed = self._await_run(context.sim_job_id)
            self._compute_and_persist(context, run_id, sim_failed)
        except Exception:  # noqa: BLE001 - never crash the daemon thread; the guard still releases
            _LOG.exception("reconciliation drain for version %s failed", context.version_id)
        finally:
            self._release((context.version_id, context.window_from, context.window_to))

    def _await_run(self, sim_job_id: str | None) -> tuple[str | None, bool]:
        """Poll the re-sim job to a terminal state (bounded by ``_poll_timeout`` — a slow/dead run
        degrades, never hangs). Returns ``(run_id, sim_failed)``: the completed run's ``resultRef``,
        or ``(None, True)`` for a failed / cancelled / timed-out / never-dispatched re-sim."""
        if not sim_job_id:
            return None, True
        deadline = time.monotonic() + self._poll_timeout
        while time.monotonic() < deadline:
            status = self._safe_job_status(sim_job_id)
            if status is not None:
                state = status.get("status")
                if state == "completed":
                    return status.get("resultRef"), False
                if state in _TERMINAL_NO_RESULT:
                    return None, True
            time.sleep(self._poll_interval)
        return None, True  # timed out → treat as no sim evidence

    def _safe_job_status(self, job_id: str) -> dict[str, Any] | None:
        try:
            return self._backtest.job_status(job_id)
        except httpx.HTTPError:
            return None

    def _compute_and_persist(
        self, context: _ReconContext, run_id: str | None, sim_failed: bool
    ) -> None:
        """Assemble the §7.1.3 gap vector, derive the §7.2 verdict + diagnosis, and INSERT the
        row."""
        caveats = list(context.dispatch_caveats)
        window_days = _window_days(context.window_from, context.window_to)
        live_return, open_count = _live_return_pct(context.live_trades, self._capital)
        has_live = len(context.live_trades) > 0
        if open_count:
            caveats.append(
                f"{open_count} live position(s) still OPEN in the window — their P&L is unrealized "
                "and contributes 0 to the live return"
            )

        sim_return: float | None = None
        sim_trades: list[dict[str, Any]] = []
        if sim_failed or run_id is None:
            caveats.append(
                "reconcile re-sim did not yield a completed run (failed / cancelled / timed out) — "
                "no sim evidence; verdict INSUFFICIENT"
            )
        else:
            try:
                results = self._backtest.results(run_id) or {}
                sim_return = _num((results.get("metrics") or {}).get("totalReturn"))
                sim_trades = self._backtest.trades(run_id) or []
            except httpx.HTTPError as exc:
                caveats.append(f"sim run read failed ({exc}); verdict INSUFFICIENT")

        inputs = _pair_trades(context.live_trades, sim_trades, context.mode)
        overlap = _jaccard(inputs.live_keys, inputs.sim_keys)
        entry_delta = _entry_price_delta_bps(inputs.entry_pairs)
        return_gap_raw = _return_gap_raw(live_return if has_live else None, sim_return)
        return_gap_ann = _annualize(return_gap_raw, window_days)

        sigma, fold_days = self._fold_evidence(context.version_id)
        if sigma is None:
            caveats.append(
                "no walk-forward fold history for this version — σ(fold returns) is undefined, so "
                "gapZ is NULL and the verdict is INSUFFICIENT (§7.2)"
            )
        elif fold_days is None:
            caveats.append(
                "fold test-window bounds unavailable — gapZ is NOT time-normalized (scale 1); "
                "reconcile-window vs fold-window durations may differ (√t model, see _gap_z)"
            )
        gap_z = _gap_z(return_gap_raw, sigma, window_days, fold_days)

        paired_trades = inputs.paired_count
        evidence_floor_met = _evidence_floor_met(paired_trades, window_days)
        verdict = _verdict(gap_z, evidence_floor_met, has_live and not sim_failed)

        # gap vector (§7.1.3): returnGap is annualized (the §10 presentation value); returnGapRaw is
        # the gapZ numerator before √t rescaling (gapZTimeScale records the durations + factor).
        # exitReasonDivergence needs P1-3 (candle exit reasons); slippageRealized needs P1-5 (quote
        # capture) — both null + caveat. pairedBasis pins what paired_trades counts.
        gap = {
            "returnGap": return_gap_ann,
            "returnGapRaw": return_gap_raw,
            "liveReturn": live_return if has_live else None,
            "simReturn": sim_return,
            "tradeSetOverlap": overlap,
            "entryPriceDeltaBps": entry_delta,      # scalper: scalper_detail.option_ltp proxy
            "exitReasonDivergence": None,
            "slippageRealized": None,
            "mode": context.mode,
            "pairedBasis": "unique paired keys (first fill per (symbol, IST entry date))",
            "gapZTimeScale": {
                "reconDays": window_days or None,
                "foldTestDays": fold_days,
                "factor": _time_scale(window_days, fold_days),
            },
            "caveats": caveats,
        }
        diagnosis = (
            _diagnosis(overlap, slippage_realized=None, single_pick=context.single_pick)
            if verdict == "DIVERGENT"
            else None
        )

        repo = self._repo_factory()
        try:
            repo.insert(
                version_id=context.version_id,
                strategy_id=context.strategy_id,
                window_from=context.window_from,
                window_to=context.window_to,
                sim_job_id=context.sim_job_id,
                sim_run_id=run_id,
                gap=gap,
                gap_z=gap_z,
                paired_trades=paired_trades,
                evidence_floor_met=evidence_floor_met,
                verdict=verdict,
                diagnosis=diagnosis,
            )
        finally:
            repo.close()

    def _fold_evidence(self, version_id: str) -> tuple[float | None, float | None]:
        """The version's fold-history evidence for the §7.2 z-score: ``(σ, T_fold)`` — σ of the
        per-fold OOS ``totalReturn`` (population stdev over ≥ 2 folds) and the mean fold TEST-window
        duration in days (each fold's ``test: {from, to}`` bounds — the √t normalization base).
        Finds the version's most recent fold run (own schema), reads the fold array via the proven
        REST shape. ``(None, None)`` (→ gapZ NULL → INSUFFICIENT) when the version has no fold
        history or the fetch fails; ``(σ, None)`` when folds carry returns but no usable bounds
        (→ unscaled gapZ + caveat)."""
        repo = self._repo_factory()
        try:
            run_id = repo.latest_walkforward_run_id(version_id)
        finally:
            repo.close()
        if run_id is None:
            return None, None
        try:
            folds = self._backtest.folds(run_id) or []
        except httpx.HTTPError:
            return None, None
        returns = [
            v for v in (_num((f.get("oosMetrics") or {}).get("totalReturn")) for f in folds)
            if v is not None
        ]
        return _fold_return_sigma(returns), _mean_fold_test_days(folds)

    # --- in-flight guard ------------------------------------------------------------------------

    def _acquire(self, key: tuple[str, str, str]) -> bool:
        with self._lock:
            if key in self._in_flight:
                return False
            self._in_flight.add(key)
            return True

    def _release(self, key: tuple[str, str, str]) -> None:
        with self._lock:
            self._in_flight.discard(key)


def _dispatch_caveats(mode: str) -> list[str]:
    """The honest degradations known at dispatch (design "degrade honest"). SWING: NO fill-timing
    degradation — the re-sim now PINS ``session.fill_timing: at_close`` via the request's
    ``sessionOverrides`` (§7.1.2), so a swing re-sim fills at the daily close matching live paper
    (SwingBatchEngine) rather than the pipeline's NEXT_OPEN default; the run is provenance-stamped
    (``fillTimingOverride`` on the run metrics) so a reader can see it was pinned. SCALPER: the §7.2
    whitelist — raw backtest-vs-live is a STRUCTURAL divergence for gate-armed intraday options
    (OI/Dow/IV muted on derived history → a scalper backtest arms ~0 trades); shadow-vs-paper is the
    real plane, so a scalper reconciliation reads as a data-fidelity artifact, not a strategy
    verdict."""
    if mode == "swing":
        return []
    return [
        "scalper raw backtest-vs-live is a §7.2 STRUCTURAL divergence (gate-armed intraday: "
        "OI/Dow/IV muted on derived history → ~0 armed trades); judge on shadow-vs-paper not raw"
    ]


def _service(request: Request) -> ReconciliationService:
    return request.app.state.reconciliation


@router.post("/reconciliations", response_model=ReconciliationReceipt, status_code=202)
def create_reconciliation(body: dict[str, Any], request: Request) -> dict[str, Any]:
    """Compute a backtest-vs-live reconciliation for a version over a lived window (design §7.1).
    Body ``{versionId, from, to}``. Submits the matched-window re-sim (``purpose: reconcile``) and
    returns a 202 receipt; the gap row lands asynchronously (GET ?versionId=). 400 missing field;
    404 unknown version; 409 if a reconciliation for (version, window) exists or is in flight."""
    return _service(request).create(body)


@router.get("/reconciliations", response_model=ReconciliationListResponse)
def list_reconciliations(
    request: Request, versionId: str, limit: int = 50, offset: int = 0
) -> dict[str, Any]:
    """A version's reconciliations, newest-first (design §7.1.4 / §11 endpoint list) — the typed
    ``{items: [...]}`` envelope the FE backtest-vs-paper view renders."""
    bounded_limit = min(max(limit, 1), 200)
    return _service(request).list_for_version(versionId, bounded_limit, max(offset, 0))
