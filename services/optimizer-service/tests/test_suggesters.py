"""EVO E5 — gate-candidate suggesters (design §5.1 / §5.1.2 / §12 item 15).

Covers the three pure suggesters (rejection-forensics, regime-gate [emit-only], unused-indicator)
and the service flow through a TestClient over SuggesterService + FakeStructureRepo: REVIEW_GATE
persistence, idempotency, and the graveyard SUPPRESSION that makes "never re-proposed silently"
real (§8.3). The DESIGN VERIFY GOAL (b) — the DOWN_TURBULENT-gate example reproduces as a suggester
OUTPUT on historical folds — is ``test_regime_gate_reproduces_down_turbulent_example`` and its API
twin below. The regime-gate suggester EMITs only: it wires nothing (regimes frozen, §5.1.2)."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import suggesters
from app.ablation import structure_fingerprint
from app.errors import ApiError, api_error_handler
from app.suggesters import (
    SuggesterService,
    suggest_regime_gates,
    suggest_rejection_forensics,
    suggest_unused_indicators,
)
from tests.structure_fakes import FakeStructureRepo

_CAMPAIGN_ID = "camp-1"
_DT_MUTATION = {"op": "ADD_GATE", "gateType": "regime_suppression", "regime": "DOWN_TURBULENT"}


def _campaign(campaign_id=_CAMPAIGN_ID):
    return {"id": campaign_id, "family": "manas-arora", "evidencePolicy": "SIM_FIRST"}


def _client(repo: FakeStructureRepo) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.suggesters = SuggesterService(repo_factory=lambda: repo)
    app.include_router(suggesters.router)
    return TestClient(app)


# --- regime-gate suggester (pure) — DESIGN VERIFY (b) -------------------------------------------


def _down_turbulent_cohort():
    """5 scored candidates; 3 lose ONLY in DOWN_TURBULENT (the design §9 example). c4 wins
    everywhere; c5 loses only in UP_QUIET."""
    return [
        {"candidateId": "c1", "regimeFoldReturns": {
            "UP_QUIET": [0.03, 0.04], "DOWN_TURBULENT": [-0.02, -0.03]}},
        {"candidateId": "c2", "regimeFoldReturns": {
            "UP_QUIET": [0.02, 0.01], "DOWN_QUIET": [0.01, 0.02],
            "DOWN_TURBULENT": [-0.01, -0.04]}},
        {"candidateId": "c3", "regimeFoldReturns": {
            "UP_QUIET": [0.05], "DOWN_TURBULENT": [-0.05, -0.02]}},
        {"candidateId": "c4", "regimeFoldReturns": {
            "UP_QUIET": [0.03, 0.04], "DOWN_TURBULENT": [0.01, 0.02]}},
        {"candidateId": "c5", "regimeFoldReturns": {
            "UP_QUIET": [-0.02, -0.01], "DOWN_TURBULENT": [0.03, 0.02]}},
    ]


def test_regime_gate_reproduces_down_turbulent_example():
    out = suggest_regime_gates(_down_turbulent_cohort())
    assert len(out) == 1
    suggestion = out[0]
    assert suggestion["source"] == "regime_gate"
    assert suggestion["mutation"] == _DT_MUTATION
    assert "3 of 5 scored candidates lose only in DOWN_TURBULENT" in suggestion["suggestion"]
    assert sorted(suggestion["evidence"]["losingCandidates"]) == ["c1", "c2", "c3"]
    # EMIT-ONLY: wires nothing, carries the §5.1.2 frozen-regime caveat
    assert suggestion["wires"] is False
    assert any("EMIT-ONLY" in c and "FROZEN" in c for c in suggestion["caveats"])


def test_regime_gate_below_majority_does_not_fire():
    # only 2 of 5 lose only in DOWN_TURBULENT → 0.4 < 0.5 majority → no suggestion.
    cohort = _down_turbulent_cohort()
    cohort[2]["regimeFoldReturns"] = {"UP_QUIET": [0.03, 0.04], "DOWN_TURBULENT": [0.05, 0.06]}
    assert suggest_regime_gates(cohort) == []


def test_regime_gate_ignores_single_regime_candidates():
    # a candidate covering ONE regime cannot "lose only there" (nowhere else to win) → not counted.
    cohort = [{"candidateId": "c1", "regimeFoldReturns": {"DOWN_TURBULENT": [-0.02, -0.03]}}]
    assert suggest_regime_gates(cohort) == []


# --- rejection-forensics suggester (pure) -------------------------------------------------------


def test_rejection_forensics_flags_dead_and_blocking_rails():
    out = suggest_rejection_forensics([
        {"rail": "volume_floor", "blocks": 0},                              # dead DOF → REMOVE
        {"rail": "iv_pair", "blocks": 12, "disableLift": 412.0, "disableLiftP": 0.04},  # → RELAX
        {"rail": "adx_gate", "blocks": 30},                                 # blocks, no lift → none
        {"rail": "oi_spurt", "blocks": 5, "disableLift": -10.0},            # negative lift → none
    ])
    ops = {s["mutation"]["op"]: s["mutation"]["rail"] for s in out}
    assert ops == {"REMOVE_GATE": "volume_floor", "RELAX_GATE": "iv_pair"}
    assert all(s["wires"] is False for s in out)


def test_rejection_forensics_respects_significance():
    # a positive disable-lift that is NOT significant (p ≥ 0.10) is not proposed for relaxation.
    out = suggest_rejection_forensics([
        {"rail": "iv_pair", "blocks": 12, "disableLift": 5.0, "disableLiftP": 0.5},
    ])
    assert out == []


# --- unused-indicator suggester (pure) ----------------------------------------------------------


def test_unused_indicator_proposes_library_minus_used_case_insensitively():
    out = suggest_unused_indicators(
        used_indicators=["EMA", "rsi"],
        library_indicators=["ema", "RSI", "ma_slope", "iv_rank", "ma_slope"],
    )
    indicators = sorted(s["mutation"]["indicator"] for s in out)
    # ema/rsi used (case-insensitive), ma_slope deduped
    assert indicators == ["iv_rank", "ma_slope"]
    assert all(s["source"] == "unused_indicator" and s["wires"] is False for s in out)


# --- service flow: persistence, suppression, idempotency ----------------------------------------


def _generate(client, body):
    return client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/suggestions", json=body)


def _full_body():
    return {
        "candidates": _down_turbulent_cohort(),
        "railStats": [{"rail": "volume_floor", "blocks": 0}],
        "usedIndicators": ["ema"],
        "libraryIndicators": ["ema", "ma_slope"],
    }


def test_generate_persists_review_gate_proposals():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    resp = _generate(_client(repo), _full_body())
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # regime DOWN_TURBULENT + REMOVE volume_floor + ADD ma_slope = 3 suggestions
    assert body["generated"] == 3 and body["suppressed"] == 0
    assert {s["source"] for s in body["items"]} == {
        "regime_gate", "rejection_forensics", "unused_indicator"
    }
    review = [p for p in repo.proposals if p["kind"] == "REVIEW_GATE"]
    assert len(review) == 3
    for p in review:
        assert p["status"] == "PENDING"
        assert p["candidateId"] is None            # cohort-level suggestion (V011)
        assert p["evidence"]["wires"] is False
        assert p["evidence"]["fingerprint"]


def test_generate_suppresses_graveyarded_structures():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    # bury the DOWN_TURBULENT regime structure first (as an ablation rejection would have)
    fp = structure_fingerprint(_DT_MUTATION)
    repo.bury(_CAMPAIGN_ID, None, fp, _DT_MUTATION, "REJECTED_NO_LIFT", None)

    body = _generate(_client(repo), _full_body()).json()
    # the regime suggestion is suppressed — never re-proposed silently (§8.3)
    assert body["suppressed"] == 1
    assert "regime_gate" not in {s["source"] for s in body["items"]}
    assert not any(
        (p["evidence"] or {}).get("fingerprint") == fp for p in repo.proposals
    )


def test_generate_is_idempotent_on_fingerprint():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    client = _client(repo)
    first = _generate(client, _full_body()).json()
    assert first["generated"] == 3 and first["refreshed"] == 0
    second = _generate(client, _full_body()).json()
    assert second["generated"] == 0 and second["refreshed"] == 3
    assert len([p for p in repo.proposals if p["kind"] == "REVIEW_GATE"]) == 3


def test_generate_unknown_campaign_is_404():
    resp = _generate(_client(FakeStructureRepo()), _full_body())
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_CAMPAIGN"
