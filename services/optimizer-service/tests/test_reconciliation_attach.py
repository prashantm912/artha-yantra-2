"""EVO E4 item-10 closure — the reconciliation ATTACH hook (design §7.1.4 / §12 item 10).

``evolution.attach_reconciliations`` resolves the latest applicable reconciliation per (version,
window) and attaches it as the ``reconciliation`` dict ``scoring.score_cohort`` already consumes.
These tests prove: the attach reads the LATEST (newest-first) row; a candidate WITHOUT a versionId
is untouched (byte-identical to the pre-item-10 retro path); and — end-to-end — a cohort carrying an
attached DIVERGENT reconciliation HARD-FAILS that candidate's live_gap gate (the #738 gate fires),
while an ALIGNED one PASSes and a version-less candidate stays SKIPPED."""

from app import scoring
from app.evolution import attach_reconciliations
from tests.fakes import FakeReconRepo


def _cand(trial: int, version_id: str | None = None) -> dict:
    """A minimal scoring-cohort candidate bag (the _assemble output shape) + an optional lived
    versionId — enough metrics that the non-live gates assess, so live_gap is the decisive one."""
    return {
        "trialNumber": trial, "runId": f"run-{trial}", "params": {"period": 15 + trial},
        "rawObjective": 1.0, "oosReturn": 5.0, "oosFoldStd": 1.0, "foldReturns": [4.0, 6.0],
        "oosTradeCount": 80, "sortino": 1.2, "sharpe": 1.0, "maxDrawdown": 10.0,
        "ddDurationBars": 3, "totalReturn": 5.0, "recoveryFactor": 0.5, "regimeOosMin": 1.0,
        "regimeOosMean": 2.0, "regimesCovered": ["UP_QUIET", "DOWN_QUIET", "UP_TURBULENT"],
        "expectancy": 100.0, "turnover": None, "tradeFrequency": None, "engineSha": "sha-1",
        "dataHash": "h1", "caveats": [], "oiGateCoverage": None, "structureGateCount": 0,
        "holdoutRunId": None, "foldless": False, "versionId": version_id,
    }


def _insert_recon(repo, version_id, verdict, gap_z, *, mode="swing", floor=True, diagnosis=None):
    return repo.insert(
        version_id=version_id, strategy_id="s1",
        window_from="2026-06-01T00:00:00+05:30", window_to="2026-06-30T00:00:00+05:30",
        sim_job_id="j1", sim_run_id="r1", gap={"mode": mode, "returnGap": -0.2}, gap_z=gap_z,
        paired_trades=25, evidence_floor_met=floor, verdict=verdict, diagnosis=diagnosis,
    )


def _gate(card: dict, gate_id: str) -> str:
    return next(g["status"] for g in card["gates"] if g["id"] == gate_id)


# --- the pure attach ----------------------------------------------------------------------------

def test_attach_sets_latest_recon_for_versioned_candidate_only():
    repo = FakeReconRepo()
    _insert_recon(repo, "ver-1", "ALIGNED", 0.2)
    candidates = [_cand(0, version_id="ver-1"), _cand(1, version_id=None)]
    attach_reconciliations(candidates, repo)
    assert candidates[0]["reconciliation"]["verdict"] == "ALIGNED"
    # the version-less candidate is untouched — no reconciliation key at all (byte-identical retro)
    assert "reconciliation" not in candidates[1]


def test_attach_picks_newest_window_per_7_1_4():
    # §7.1.4 "latest applicable (version, window)": an OLDER ALIGNED then a NEWER DIVERGENT — the
    # newest wins (FakeReconRepo.list_by_version is created_at DESC, mirroring the real repo).
    repo = FakeReconRepo()
    _insert_recon(repo, "ver-1", "ALIGNED", 0.3)         # older
    _insert_recon(repo, "ver-1", "DIVERGENT", -2.0,
                  diagnosis={"checklist": [{"id": "trade_set_overlap", "status": "auto"}]})  # newer
    candidates = [_cand(0, version_id="ver-1")]
    attach_reconciliations(candidates, repo)
    assert candidates[0]["reconciliation"]["verdict"] == "DIVERGENT"


def test_attach_versioned_candidate_with_no_recon_gets_none():
    repo = FakeReconRepo()  # no rows
    candidates = [_cand(0, version_id="ver-none")]
    attach_reconciliations(candidates, repo)
    assert candidates[0]["reconciliation"] is None


# --- end-to-end: attach → score → the #738 live_gap gate ----------------------------------------

def test_divergent_recon_hard_fails_candidate_end_to_end():
    repo = FakeReconRepo()
    _insert_recon(repo, "ver-div", "DIVERGENT", -2.0,
                  diagnosis={"checklist": [{"id": "trade_set_overlap", "status": "auto",
                                            "value": 0.4}]})
    _insert_recon(repo, "ver-aln", "ALIGNED", 0.4)
    candidates = [
        _cand(0, version_id="ver-div"),
        _cand(1, version_id="ver-aln"),
        _cand(2, version_id=None),
    ]
    attach_reconciliations(candidates, repo)
    cards = scoring.score_cohort(candidates, parameters=[], direction="maximize")

    div, aln, none_card = cards[0], cards[1], cards[2]

    # the DIVERGENT candidate: live_gap hard-FAILs, so it is NOT rankable (#738 fires end-to-end)
    assert _gate(div, "live_gap") == "FAIL"
    assert div["rankable"] is False
    assert div["liveGap"]["verdict"] == "DIVERGENT"
    assert div["liveGap"]["counted"] is True
    assert div["liveGap"]["diagnosis"]["checklist"][0]["id"] == "trade_set_overlap"

    # the ALIGNED candidate PASSes the gate and stays rankable
    assert _gate(aln, "live_gap") == "PASS"
    assert aln["rankable"] is True

    # the version-less candidate has no live evidence → SKIPPED (pre-item-10 behaviour preserved)
    assert _gate(none_card, "live_gap") == "SKIPPED"
    assert none_card["liveGap"] is None


def test_scoring_byte_identical_without_versionids():
    # No candidate carries a versionId → attach is a no-op → scoring is unchanged from the
    # pre-item-10 path (the retro read's default state).
    repo = FakeReconRepo()
    plain = [_cand(0), _cand(1)]
    attached = [_cand(0), _cand(1)]
    attach_reconciliations(attached, repo)
    assert scoring.score_cohort(plain, parameters=[]) == \
        scoring.score_cohort(attached, parameters=[])
