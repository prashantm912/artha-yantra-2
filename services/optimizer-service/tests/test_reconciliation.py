"""EVO E3 item 7 — backtest-vs-live reconciliation computer (design §7 / §12 E3 item 7).

Layers, each deterministic (the drain thread runs against a fake whose ``job_status`` resolves the
re-sim on the first poll, so it always terminates):

* gap math   — annualize / returnGap / Jaccard overlap / entryPriceDelta / σ(fold returns) / gapZ
               (incl. the √t time normalization), hand-computed and pinned;
* verdict    — the §7.2 thresholds (ALIGNED / PENALIZED / DIVERGENT / INSUFFICIENT) + evidence
floor;
* pairing    — swing (symbol, IST entry DATE) + scalper (option leg) keys, incl. the #214 instant
trap;
* orchestrate— end-to-end create → drain → persist: ALIGNED / DIVERGENT+diagnosis / INSUFFICIENT
               (no fold history, floor unmet, re-sim failed); time-normalized gapZ; the funnel
               single-pick structural note; the 400 / 404 / 409 guards;
* REST       — POST 202 receipt + GET ?versionId= envelope.
"""

from __future__ import annotations

import math
import statistics
import time
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import reconciliation
from app.errors import ApiError, api_error_handler
from app.reconciliation import (
    ReconciliationService,
    _annualize,
    _dispatch_caveats,
    _entry_price_delta_bps,
    _evidence_floor_met,
    _fold_return_sigma,
    _gap_z,
    _is_single_pick,
    _ist_date,
    _jaccard,
    _mean_fold_test_days,
    _mode_for_style,
    _norm_symbol,
    _pair_trades,
    _return_gap_raw,
    _scalper_entry_ltp,
    _time_scale,
    _to_ist_iso,
    _verdict,
    _window_days,
)
from tests.fakes import FakeLiveRepo, FakeReconRepo

# ================================================================================================
# gap math (pure)
# ================================================================================================


def test_annualize_linear():
    # 5% over 30 days → 5 × 365/30 ≈ 60.83% annualized.
    assert _annualize(5.0, 30.0) == pytest.approx(5.0 * 365.0 / 30.0)
    assert _annualize(None, 30.0) is None
    assert _annualize(5.0, 0.0) is None


def test_return_gap_raw():
    assert _return_gap_raw(10.0, 12.0) == -2.0
    assert _return_gap_raw(None, 12.0) is None
    assert _return_gap_raw(10.0, None) is None


def test_jaccard_overlap():
    assert _jaccard({1, 2, 3}, {2, 3, 4}) == pytest.approx(2 / 4)  # |∩|=2, |∪|=4
    assert _jaccard({1, 2}, {1, 2}) == 1.0
    assert _jaccard(set(), set()) is None  # no basis, NOT a misleading 1.0
    assert _jaccard({1}, set()) == 0.0


def test_entry_price_delta_bps():
    # live 101 vs sim 100 → 100 bps; live 99 vs sim 100 → 100 bps; mean 100.
    assert _entry_price_delta_bps([(101.0, 100.0), (99.0, 100.0)]) == pytest.approx(100.0)
    assert _entry_price_delta_bps([(100.0, 0.0)]) is None  # zero sim price skipped
    assert _entry_price_delta_bps([]) is None


def test_fold_return_sigma():
    assert _fold_return_sigma([10.0, 12.0, 8.0]) == pytest.approx(statistics.pstdev([10.0, 12, 8]))
    assert _fold_return_sigma([10.0]) is None       # < 2 folds
    assert _fold_return_sigma(None) is None
    assert _fold_return_sigma([]) is None


def test_gap_z():
    assert _gap_z(-2.0, 1.6329931618554518) == pytest.approx(-2.0 / 1.6329931618554518)
    assert _gap_z(None, 1.5) is None
    assert _gap_z(-2.0, None) is None
    assert _gap_z(-2.0, 0.0) is None                # degenerate σ → no z


def test_gap_z_time_normalization_90d_window_30d_folds():
    # The §7.2 √t model: σ is 30-day fold dispersion; a gap accrued over 90 days carries √3 more
    # noise. UNSCALED −2.5/1.6330 ≈ −1.5309 would read DIVERGENT; the corrected z rescales the gap
    # by √(30/90) → −2.5×0.57735/1.6330 ≈ −0.8839 — a true PENALIZED, not a false DIVERGENT.
    sigma = 1.6329931618554518
    unscaled = _gap_z(-2.5, sigma)
    corrected = _gap_z(-2.5, sigma, recon_days=90.0, fold_days=30.0)
    assert unscaled == pytest.approx(-1.5309, abs=1e-4)             # would cross the −1.5 gate
    assert corrected == pytest.approx(-2.5 * math.sqrt(30.0 / 90.0) / sigma)
    assert corrected == pytest.approx(-0.8839, abs=1e-4)            # stays PENALIZED
    assert corrected == pytest.approx(unscaled / math.sqrt(3.0))    # the exact √3 deflation


def test_gap_z_equal_durations_is_unchanged():
    sigma = 1.6329931618554518
    assert _gap_z(-2.5, sigma, recon_days=30.0, fold_days=30.0) == pytest.approx(-2.5 / sigma)


def test_gap_z_missing_duration_falls_back_to_scale_one():
    sigma = 1.6329931618554518
    assert _gap_z(-2.5, sigma, recon_days=90.0, fold_days=None) == pytest.approx(-2.5 / sigma)
    assert _gap_z(-2.5, sigma, recon_days=None, fold_days=30.0) == pytest.approx(-2.5 / sigma)
    assert _time_scale(0.0, 30.0) == 1.0            # a degenerate window never scales


def test_mean_fold_test_days():
    folds = [
        {"test": {"from": "2026-01-01T00:00:00+05:30", "to": "2026-01-31T00:00:00+05:30"}},  # 30 d
        {"test": {"from": "2026-02-01T00:00:00+05:30", "to": "2026-02-11T00:00:00+05:30"}},  # 10 d
        {"oosMetrics": {}},                         # no bounds — skipped
    ]
    assert _mean_fold_test_days(folds) == pytest.approx(20.0)
    assert _mean_fold_test_days([{"oosMetrics": {}}]) is None
    assert _mean_fold_test_days([]) is None


# ================================================================================================
# verdict + evidence floor (§7.2)
# ================================================================================================


def test_evidence_floor_met():
    assert _evidence_floor_met(20, 5.0) is True     # ≥ 20 paired trades
    assert _evidence_floor_met(3, 28.0) is True      # ≥ 4 weeks
    assert _evidence_floor_met(3, 27.0) is False     # neither arm


def test_verdict_thresholds():
    # aligned ≥ −0.5; penalized (−1.5, −0.5); divergent ≤ −1.5 with floor; else insufficient.
    assert _verdict(0.0, True, True) == "ALIGNED"
    assert _verdict(-0.5, True, True) == "ALIGNED"
    assert _verdict(-0.6, True, True) == "PENALIZED"
    assert _verdict(-1.49, True, True) == "PENALIZED"
    assert _verdict(-1.5, True, True) == "DIVERGENT"          # floor met
    assert _verdict(-3.0, False, True) == "INSUFFICIENT"      # ≤ −1.5 but floor UNMET
    assert _verdict(None, True, True) == "INSUFFICIENT"       # no σ
    assert _verdict(0.0, True, False) == "INSUFFICIENT"       # no live evidence


def test_diagnosis_flags_low_overlap_and_status():
    div = reconciliation._diagnosis(trade_set_overlap=0.2, slippage_realized=None)
    checklist = {item["id"]: item for item in div["checklist"]}
    assert checklist["trade_set_overlap"]["status"] == "auto"
    assert "UPSTREAM" in checklist["trade_set_overlap"]["note"]     # low overlap ⇒ upstream
    assert checklist["cost_slippage_realized_vs_modeled"]["status"] == "pending"  # P1-5 not landed
    assert checklist["flag_regime_changes"]["status"] == "pending"  # P1-7 not landed
    assert checklist["data_health_incidents"]["status"] == "manual"


def test_diagnosis_single_pick_suppresses_upstream_inference():
    # A funnel/screener single-pick sim vs the multi-name live book: overlap ≈ 0 STRUCTURALLY —
    # the UPSTREAM inference would be a false diagnosis and must be replaced by the structural note.
    div = reconciliation._diagnosis(trade_set_overlap=0.0, slippage_realized=None, single_pick=True)
    note = next(i for i in div["checklist"] if i["id"] == "trade_set_overlap")["note"]
    assert "UPSTREAM" not in note
    assert "STRUCTURAL" in note
    assert "does NOT imply upstream" in note


# ================================================================================================
# IST / window helpers (the #214 timestamp-key trap)
# ================================================================================================


def test_ist_date_keys_by_instant_not_offset():
    # 20:00 UTC on 06-01 IS 01:30 IST on 06-02 — the two representations of the SAME instant map to
    # the SAME IST date. Keying by the raw offset-bearing timestamp would split them (#214).
    assert _ist_date("2026-06-01T20:00:00+00:00") == "2026-06-02"
    assert _ist_date("2026-06-02T01:30:00+05:30") == "2026-06-02"
    assert _ist_date("2026-06-02") == "2026-06-02"           # a plain date
    assert _ist_date(None) is None


def test_to_ist_iso():
    assert _to_ist_iso("2026-06-01") == "2026-06-01T00:00:00+05:30"
    assert _to_ist_iso("2026-06-01T09:15:00+05:30") == "2026-06-01T09:15:00+05:30"


def test_window_days():
    days = _window_days("2026-06-01T00:00:00+05:30", "2026-07-01T00:00:00+05:30")
    assert days == pytest.approx(30.0)
    assert _window_days("bad", "worse") == 0.0


def test_mode_for_style_explicit_map():
    # every schema style maps EXPLICITLY (the binary is-swing split silently sent btst/positional/
    # expiry_day to scalper pairing): swing/positional → swing; intraday/btst/expiry_day → scalper.
    for style, expected in (("swing", "swing"), ("positional", "swing"),
                            ("intraday", "scalper"), ("btst", "scalper"),
                            ("expiry_day", "scalper")):
        mode, caveat = _mode_for_style({"risk": {"session": {"style": style}}})
        assert (mode, caveat) == (expected, None), style


def test_mode_for_style_unrecognized_degrades_with_caveat():
    mode, caveat = _mode_for_style({"risk": {"session": {"style": "weird_new_style"}}})
    assert mode == "scalper"
    assert caveat is not None and "not recognized" in caveat
    mode, caveat = _mode_for_style({})              # absent style — same guard
    assert mode == "scalper"
    assert caveat is not None


def test_is_single_pick_detects_dynamic_list_modes():
    assert _is_single_pick({"universe": {"mode": "manas_arora_funnel"}}) is True
    assert _is_single_pick({"universe": {"mode": "minervini_funnel"}}) is True
    assert _is_single_pick({"universe": {"mode": "futures_screener"}}) is True
    assert _is_single_pick({"universe": {"mode": "explicit"}}) is False
    assert _is_single_pick({}) is False


def test_scalper_entry_ltp_directional_and_straddle():
    assert _scalper_entry_ltp({"option_ltp": 42.5}) == 42.5
    assert _scalper_entry_ltp({"legs": [{"option_ltp": 30.0}, {"option_ltp": 31.0}]}) == 30.0
    assert _scalper_entry_ltp(None) is None
    assert _scalper_entry_ltp({}) is None


def test_norm_symbol():
    assert _norm_symbol(" nifty26jul24000ce ") == "NIFTY26JUL24000CE"
    assert _norm_symbol(None) == ""


# ================================================================================================
# pairing (§7.1.2)
# ================================================================================================


def _live(sym: str, opened: str, entry: float, pnl: float = 0.0,
          status: str = "CLOSED", detail: dict | None = None) -> dict[str, Any]:
    return {"tradingsymbol": sym, "side": "BUY", "avgEntryPrice": entry, "openedAt": opened,
            "closedAt": opened, "realizedPnl": pnl, "closeReason": "TARGET", "status": status,
            "scalperDetail": detail}


def _sim(sym: str, entry_ts: str, entry: float) -> dict[str, Any]:
    return {"tradingsymbol": sym, "entryTs": entry_ts, "entryPrice": entry}


def test_pair_trades_swing_by_symbol_and_ist_date():
    live = [_live("RELIANCE", "2026-06-01T15:20:00+05:30", 100.0),
            _live("TCS", "2026-06-02T15:20:00+05:30", 200.0)]
    sim = [_sim("RELIANCE", "2026-06-01T09:15:00+05:30", 101.0),      # same (sym, date) → paired
           _sim("INFY", "2026-06-03T09:15:00+05:30", 300.0)]           # unpaired
    inputs = _pair_trades(live, sim, "swing")
    assert inputs.paired_count == 1
    assert _jaccard(inputs.live_keys, inputs.sim_keys) == pytest.approx(1 / 3)  # 1 shared / 3 union
    # entry pair for RELIANCE: live 100 vs sim 101
    assert inputs.entry_pairs == [(100.0, 101.0)]


def test_pair_trades_scalper_uses_option_ltp_proxy():
    # scalper: live entry price = scalper_detail.option_ltp proxy (not avgEntryPrice).
    live = [_live("NIFTY26JUL24000CE", "2026-06-01T10:00:00+05:30", 55.0,
                  detail={"option_ltp": 50.0})]
    sim = [_sim("NIFTY26JUL24000CE", "2026-06-01T10:00:00+05:30", 48.0)]
    inputs = _pair_trades(live, sim, "scalper")
    assert inputs.paired_count == 1
    assert inputs.entry_pairs == [(50.0, 48.0)]      # option_ltp proxy, NOT the 55.0 fill


def test_dispatch_caveats_swing_flags_fill_timing():
    assert any("at_close" in c for c in _dispatch_caveats("swing"))
    assert any("STRUCTURAL" in c for c in _dispatch_caveats("scalper"))


# ================================================================================================
# orchestration fixtures
# ================================================================================================

_VERSION = "11111111-1111-1111-1111-111111111111"
_STRATEGY = "22222222-2222-2222-2222-222222222222"
_SWING_CONFIG = {"risk": {"session": {"style": "swing"}}}
_FUNNEL_CONFIG = {"risk": {"session": {"style": "swing"}},
                  "universe": {"mode": "manas_arora_funnel"}}
# Each fold's test window is 30 days (the √t normalization base T_fold); σ ≈ 1.633.
_FOLD_TEST_30D = {"from": "2026-01-01T00:00:00+05:30", "to": "2026-01-31T00:00:00+05:30"}
_WF_FOLDS = [{"oosMetrics": {"totalReturn": 10.0}, "test": dict(_FOLD_TEST_30D)},
             {"oosMetrics": {"totalReturn": 12.0}, "test": dict(_FOLD_TEST_30D)},
             {"oosMetrics": {"totalReturn": 8.0}, "test": dict(_FOLD_TEST_30D)}]
# Same returns, NO test bounds — the unscaled-gapZ + caveat path.
_WF_FOLDS_NO_BOUNDS = [{"oosMetrics": {"totalReturn": 10.0}},
                       {"oosMetrics": {"totalReturn": 12.0}},
                       {"oosMetrics": {"totalReturn": 8.0}}]
_SIGMA = statistics.pstdev([10.0, 12.0, 8.0])   # ≈ 1.633


class FakeReconBacktest:
    """Serves the reconcile re-sim (``run`` → ``job_status`` → ``results``/``trades``) and the
    version's walk-forward ``folds``. The re-sim resolves to ``completed`` (or a configured terminal
    state) on the first poll, so the drain always terminates."""

    def __init__(
        self,
        *,
        sim_total_return: float | None = None,
        sim_trades: list[dict[str, Any]] | None = None,
        folds: list[dict[str, Any]] | None = None,
        job_state: str = "completed",
        run_id: str = "sim-run-1",
    ) -> None:
        self.sim_total_return = sim_total_return
        self.sim_trades = sim_trades or []
        self._folds = folds if folds is not None else []
        self.job_state = job_state
        self.run_id = run_id
        self.runs_submitted: list[dict[str, Any]] = []

    def run(self, request: dict[str, Any]) -> dict[str, Any]:
        self.runs_submitted.append(request)
        return {"jobId": "sim-job-1", "status": "queued"}

    def job_status(self, job_id: str) -> dict[str, Any]:
        if self.job_state == "completed":
            return {"status": "completed", "resultRef": self.run_id}
        return {"status": self.job_state, "resultRef": None}

    def results(self, run_id: str) -> dict[str, Any]:
        return {"metrics": {"totalReturn": self.sim_total_return}}

    def trades(self, run_id: str, limit: int = 1000, offset: int = 0) -> list[dict[str, Any]]:
        return self.sim_trades

    def folds(self, run_id: str) -> list[dict[str, Any]]:
        return self._folds


def _svc(
    repo: FakeReconRepo, live: FakeLiveRepo, backtest: FakeReconBacktest
) -> ReconciliationService:
    return ReconciliationService(
        repo_factory=lambda: repo, live_factory=lambda: live, backtest_client=backtest,
        capital=1_000_000.0, poll_interval=0.0, poll_timeout=2.0,
    )


def _wait(cond, timeout: float = 3.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if cond():
            return
        time.sleep(0.01)
    raise AssertionError("reconciliation drain did not resolve in time")


def _swing_live_version(
    trades: list[dict[str, Any]], config: dict[str, Any] = _SWING_CONFIG
) -> FakeLiveRepo:
    return FakeLiveRepo(
        versions={_VERSION: {"strategyId": _STRATEGY, "version": "1.0.1", "config": config}},
        trades={_VERSION: trades},
    )


# ================================================================================================
# orchestration: end-to-end verdicts
# ================================================================================================


def test_reconcile_champion_vs_itself_is_aligned():
    # live return == sim return (10%) over a 30-day window → returnGap 0 → gapZ 0 → ALIGNED.
    live_trades = [_live("RELIANCE", "2026-06-01T15:20:00+05:30", 100.0, pnl=50_000.0),
                   _live("TCS", "2026-06-02T15:20:00+05:30", 200.0, pnl=50_000.0)]
    sim_trades = [_sim("RELIANCE", "2026-06-01T09:15:00+05:30", 100.0),
                  _sim("TCS", "2026-06-02T09:15:00+05:30", 200.0)]
    repo = FakeReconRepo(walkforward_run_ids={_VERSION: "wf-1"})
    live = _swing_live_version(live_trades)
    backtest = FakeReconBacktest(sim_total_return=10.0, sim_trades=sim_trades, folds=_WF_FOLDS)
    svc = _svc(repo, live, backtest)

    receipt = svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-07-01"})
    assert receipt["mode"] == "swing"
    assert receipt["liveTrades"] == 2
    _wait(lambda: len(repo.rows) == 1)
    row = repo.rows[0]
    assert row["verdict"] == "ALIGNED"
    assert row["gapZ"] == pytest.approx(0.0)
    assert row["gap"]["returnGap"] == pytest.approx(0.0)        # annualized 0 too
    assert row["gap"]["tradeSetOverlap"] == pytest.approx(1.0)
    assert row["pairedTrades"] == 2
    assert row["diagnosis"] is None                             # only DIVERGENT carries one
    assert row["simRunId"] == "sim-run-1"
    # meaning-of-paired_trades + the √t normalization record are on the row itself
    assert "first fill per (symbol, IST entry date)" in row["gap"]["pairedBasis"]
    assert row["gap"]["gapZTimeScale"]["factor"] == pytest.approx(1.0)  # 30d window == 30d folds
    # the re-sim was submitted with purpose:reconcile + the pinned semver + the common capital
    assert backtest.runs_submitted[0]["purpose"] == "reconcile"
    assert backtest.runs_submitted[0]["strategyVersion"] == "1.0.1"
    assert backtest.runs_submitted[0]["initialCapital"] == 1_000_000.0


def test_reconcile_divergent_explicit_universe_keeps_upstream_note():
    # EXPLICIT universe: live 0% vs sim 10% over 30 days (== the 30d fold base → scale 1) →
    # gapZ = −10/σ ≈ −6.1 ≤ −1.5; window ≥ 4 weeks → floor met → DIVERGENT. Disjoint trade sets →
    # LOW overlap → the legit upstream-divergence signal (screener/CA/feed) stays on the checklist.
    live_trades = [_live("ALPHA", "2026-06-01T15:20:00+05:30", 100.0, pnl=0.0),
                   _live("BETA", "2026-06-02T15:20:00+05:30", 100.0, pnl=0.0)]
    sim_trades = [_sim("GAMMA", "2026-06-10T09:15:00+05:30", 100.0)]
    repo = FakeReconRepo(walkforward_run_ids={_VERSION: "wf-1"})
    live = _swing_live_version(live_trades)
    backtest = FakeReconBacktest(sim_total_return=10.0, sim_trades=sim_trades, folds=_WF_FOLDS)
    svc = _svc(repo, live, backtest)

    svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-07-01"})
    _wait(lambda: len(repo.rows) == 1)
    row = repo.rows[0]
    assert row["verdict"] == "DIVERGENT"
    assert row["gapZ"] == pytest.approx(-10.0 / _SIGMA)
    assert row["evidenceFloorMet"] is True
    assert row["diagnosis"] is not None
    overlap_item = next(
        i for i in row["diagnosis"]["checklist"] if i["id"] == "trade_set_overlap"
    )
    assert overlap_item["value"] == 0.0                        # disjoint trade sets
    assert "UPSTREAM" in overlap_item["note"]


def test_reconcile_divergent_funnel_mode_replaces_upstream_with_structural_note():
    # FUNNEL universe (manas_arora_funnel): the re-sim signals on the TOP pinned pick only while
    # live papers the whole funnel → overlap ≈ 0 STRUCTURALLY. The diagnosis must NOT claim the
    # divergence is upstream, and the gap caveats must carry the single-pick distortion note.
    live_trades = [_live("ALPHA", "2026-06-01T15:20:00+05:30", 100.0, pnl=0.0),
                   _live("BETA", "2026-06-02T15:20:00+05:30", 100.0, pnl=0.0)]
    sim_trades = [_sim("GAMMA", "2026-06-10T09:15:00+05:30", 100.0)]
    repo = FakeReconRepo(walkforward_run_ids={_VERSION: "wf-1"})
    live = _swing_live_version(live_trades, config=_FUNNEL_CONFIG)
    backtest = FakeReconBacktest(sim_total_return=10.0, sim_trades=sim_trades, folds=_WF_FOLDS)
    svc = _svc(repo, live, backtest)

    receipt = svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-07-01"})
    assert any("single-pick sim vs multi-name live" in c for c in receipt["caveats"])
    _wait(lambda: len(repo.rows) == 1)
    row = repo.rows[0]
    assert row["verdict"] == "DIVERGENT"
    overlap_item = next(
        i for i in row["diagnosis"]["checklist"] if i["id"] == "trade_set_overlap"
    )
    assert "UPSTREAM" not in overlap_item["note"]              # the false inference is suppressed
    assert "STRUCTURAL" in overlap_item["note"]
    assert any("single-pick sim vs multi-name live" in c for c in row["gap"]["caveats"])


def test_reconcile_no_fold_history_is_insufficient():
    # no walk-forward run for the version → σ undefined → gapZ NULL → INSUFFICIENT.
    live_trades = [_live("ALPHA", "2026-06-01T15:20:00+05:30", 100.0, pnl=0.0)]
    sim_trades = [_sim("ALPHA", "2026-06-01T09:15:00+05:30", 100.0)]
    repo = FakeReconRepo(walkforward_run_ids={})               # no fold history
    live = _swing_live_version(live_trades)
    backtest = FakeReconBacktest(sim_total_return=10.0, sim_trades=sim_trades, folds=_WF_FOLDS)
    svc = _svc(repo, live, backtest)

    svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-07-01"})
    _wait(lambda: len(repo.rows) == 1)
    row = repo.rows[0]
    assert row["verdict"] == "INSUFFICIENT"
    assert row["gapZ"] is None
    assert any("no walk-forward fold history" in c for c in row["gap"]["caveats"])


def test_reconcile_floor_unmet_is_insufficient():
    # gapZ ≤ −1.5 but a 9-day window + < 20 paired trades → floor UNMET → INSUFFICIENT, not
    # DIVERGENT. The 9-day window over 30-day folds also exercises the √t UPSCALE direction:
    # gapZ = −10 × √(30/9) / σ ≈ −11.18 (a short window's gap carries LESS noise than the folds).
    live_trades = [_live("ALPHA", "2026-06-01T15:20:00+05:30", 100.0, pnl=0.0)]
    sim_trades = [_sim("ALPHA", "2026-06-01T09:15:00+05:30", 100.0)]
    repo = FakeReconRepo(walkforward_run_ids={_VERSION: "wf-1"})
    live = _swing_live_version(live_trades)
    backtest = FakeReconBacktest(sim_total_return=10.0, sim_trades=sim_trades, folds=_WF_FOLDS)
    svc = _svc(repo, live, backtest)

    svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-06-10"})  # 9 days
    _wait(lambda: len(repo.rows) == 1)
    row = repo.rows[0]
    assert row["gapZ"] == pytest.approx(-10.0 * math.sqrt(30.0 / 9.0) / _SIGMA)  # ≤ −1.5
    assert row["evidenceFloorMet"] is False
    assert row["verdict"] == "INSUFFICIENT"


def test_reconcile_gap_z_time_normalized_90d_window_30d_folds():
    # End-to-end √t normalization (the coordinator's concrete case): a 90-day live window over
    # 30-day folds. live 0% vs sim 10% → raw gap −10; corrected gapZ = −10×√(30/90)/σ ≈ −3.54
    # (UNSCALED would read −10/σ ≈ −6.12 — a √3 inflation). The scale record lands on the gap.
    live_trades = [_live("ALPHA", "2026-06-01T15:20:00+05:30", 100.0, pnl=0.0)]
    sim_trades = [_sim("ALPHA", "2026-06-01T09:15:00+05:30", 100.0)]
    repo = FakeReconRepo(walkforward_run_ids={_VERSION: "wf-1"})
    live = _swing_live_version(live_trades)
    backtest = FakeReconBacktest(sim_total_return=10.0, sim_trades=sim_trades, folds=_WF_FOLDS)
    svc = _svc(repo, live, backtest)

    svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-08-30"})  # 90 days
    _wait(lambda: len(repo.rows) == 1)
    row = repo.rows[0]
    expected = -10.0 * math.sqrt(30.0 / 90.0) / _SIGMA
    assert row["gapZ"] == pytest.approx(expected)
    assert row["gapZ"] != pytest.approx(-10.0 / _SIGMA)         # NOT the unscaled √3-inflated z
    scale = row["gap"]["gapZTimeScale"]
    assert scale["reconDays"] == pytest.approx(90.0)
    assert scale["foldTestDays"] == pytest.approx(30.0)
    assert scale["factor"] == pytest.approx(math.sqrt(30.0 / 90.0))


def test_reconcile_fold_bounds_missing_is_unscaled_with_caveat():
    # Folds carrying returns but NO test bounds → σ exists but T_fold is unknown → gapZ stays
    # UNSCALED (factor 1) and the row says so — never a silent un-normalized z.
    live_trades = [_live("ALPHA", "2026-06-01T15:20:00+05:30", 100.0, pnl=0.0)]
    sim_trades = [_sim("ALPHA", "2026-06-01T09:15:00+05:30", 100.0)]
    repo = FakeReconRepo(walkforward_run_ids={_VERSION: "wf-1"})
    live = _swing_live_version(live_trades)
    backtest = FakeReconBacktest(
        sim_total_return=10.0, sim_trades=sim_trades, folds=_WF_FOLDS_NO_BOUNDS
    )
    svc = _svc(repo, live, backtest)

    svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-08-30"})  # 90 days
    _wait(lambda: len(repo.rows) == 1)
    row = repo.rows[0]
    assert row["gapZ"] == pytest.approx(-10.0 / _SIGMA)         # scale 1 fallback
    assert row["gap"]["gapZTimeScale"]["foldTestDays"] is None
    assert row["gap"]["gapZTimeScale"]["factor"] == 1.0
    assert any("NOT time-normalized" in c for c in row["gap"]["caveats"])


def test_reconcile_failed_resim_persists_insufficient_row():
    # a failed re-sim yields no sim evidence → an INSUFFICIENT row (never a silent no-op).
    live_trades = [_live("ALPHA", "2026-06-01T15:20:00+05:30", 100.0, pnl=0.0)]
    repo = FakeReconRepo(walkforward_run_ids={_VERSION: "wf-1"})
    live = _swing_live_version(live_trades)
    backtest = FakeReconBacktest(job_state="failed", folds=_WF_FOLDS)
    svc = _svc(repo, live, backtest)

    svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-07-01"})
    _wait(lambda: len(repo.rows) == 1)
    row = repo.rows[0]
    assert row["verdict"] == "INSUFFICIENT"
    assert row["simRunId"] is None
    assert any("did not yield a completed run" in c for c in row["gap"]["caveats"])


def test_reconcile_swing_carries_fill_timing_caveat():
    live_trades = [_live("ALPHA", "2026-06-01T15:20:00+05:30", 100.0, pnl=50_000.0)]
    sim_trades = [_sim("ALPHA", "2026-06-01T09:15:00+05:30", 100.0)]
    repo = FakeReconRepo(walkforward_run_ids={_VERSION: "wf-1"})
    live = _swing_live_version(live_trades)
    backtest = FakeReconBacktest(sim_total_return=5.0, sim_trades=sim_trades, folds=_WF_FOLDS)
    svc = _svc(repo, live, backtest)
    svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-07-01"})
    _wait(lambda: len(repo.rows) == 1)
    assert any("at_close" in c for c in repo.rows[0]["gap"]["caveats"])


# ================================================================================================
# orchestration: guards
# ================================================================================================


def test_create_missing_field_is_400():
    svc = _svc(FakeReconRepo(), FakeLiveRepo(), FakeReconBacktest())
    for body in ({"from": "a", "to": "b"}, {"versionId": _VERSION, "to": "b"},
                 {"versionId": _VERSION, "from": "a"}):
        with pytest.raises(ApiError) as exc:
            svc.create(body)
        assert exc.value.status == 400


def test_create_unknown_version_is_404():
    svc = _svc(FakeReconRepo(), FakeLiveRepo(), FakeReconBacktest())
    with pytest.raises(ApiError) as exc:
        svc.create({"versionId": "nope", "from": "2026-06-01", "to": "2026-07-01"})
    assert exc.value.status == 404
    assert exc.value.code == "NOT_FOUND_VERSION"


def test_create_existing_reconciliation_is_409():
    repo = FakeReconRepo()
    repo.insert(
        version_id=_VERSION, strategy_id=_STRATEGY,
        window_from="2026-06-01T00:00:00+05:30", window_to="2026-07-01T00:00:00+05:30",
        sim_job_id=None, sim_run_id=None, gap={}, gap_z=None, paired_trades=0,
        evidence_floor_met=False, verdict="ALIGNED", diagnosis=None,
    )
    live = _swing_live_version([])
    svc = _svc(repo, live, FakeReconBacktest())
    with pytest.raises(ApiError) as exc:
        svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-07-01"})
    assert exc.value.status == 409
    assert exc.value.code == "CONFLICT_RECONCILIATION_EXISTS"


def test_create_in_flight_is_409():
    repo = FakeReconRepo()
    live = _swing_live_version([])
    svc = _svc(repo, live, FakeReconBacktest())
    assert svc._acquire((_VERSION, "2026-06-01T00:00:00+05:30", "2026-07-01T00:00:00+05:30"))
    with pytest.raises(ApiError) as exc:
        svc.create({"versionId": _VERSION, "from": "2026-06-01", "to": "2026-07-01"})
    assert exc.value.status == 409
    assert exc.value.code == "CONFLICT_RECONCILIATION_IN_FLIGHT"


# ================================================================================================
# REST surface
# ================================================================================================


def _client(repo: FakeReconRepo, live: FakeLiveRepo, backtest: FakeReconBacktest) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.reconciliation = _svc(repo, live, backtest)
    app.include_router(reconciliation.router)
    return TestClient(app)


def test_post_reconciliation_returns_202_receipt():
    live_trades = [_live("ALPHA", "2026-06-01T15:20:00+05:30", 100.0, pnl=50_000.0)]
    sim_trades = [_sim("ALPHA", "2026-06-01T09:15:00+05:30", 100.0)]
    repo = FakeReconRepo(walkforward_run_ids={_VERSION: "wf-1"})
    backtest = FakeReconBacktest(sim_total_return=5.0, sim_trades=sim_trades, folds=_WF_FOLDS)
    resp = _client(repo, _swing_live_version(live_trades), backtest).post(
        "/api/v1/evolution/reconciliations",
        json={"versionId": _VERSION, "from": "2026-06-01", "to": "2026-07-01"},
    )
    assert resp.status_code == 202, resp.text
    body = resp.json()
    assert body["status"] == "computing"
    assert body["mode"] == "swing"
    assert body["simJobId"] == "sim-job-1"
    _wait(lambda: len(repo.rows) == 1)


def test_get_reconciliations_envelope():
    repo = FakeReconRepo()
    repo.insert(
        version_id=_VERSION, strategy_id=_STRATEGY,
        window_from="2026-06-01T00:00:00+05:30", window_to="2026-07-01T00:00:00+05:30",
        sim_job_id="sim-job-1", sim_run_id="sim-run-1",
        gap={"returnGap": 1.2}, gap_z=0.3, paired_trades=5, evidence_floor_met=True,
        verdict="ALIGNED", diagnosis=None,
    )
    client = _client(repo, FakeLiveRepo(), FakeReconBacktest())
    resp = client.get("/api/v1/evolution/reconciliations", params={"versionId": _VERSION})
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["items"]) == 1
    assert body["items"][0]["verdict"] == "ALIGNED"
    assert body["items"][0]["gapZ"] == 0.3
    # a version with no reconciliations → clean empty envelope
    empty = client.get("/api/v1/evolution/reconciliations", params={"versionId": "other"})
    assert empty.json()["items"] == []
