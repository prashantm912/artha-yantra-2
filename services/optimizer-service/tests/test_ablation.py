"""EVO E5 — the pre-registered ablation protocol (design §5.2 / §12 item 14).

Covers the paired-evaluation math (deterministic Student-t p-value + the five §5.2 acceptance
gates), the verdict priority, and the API flow through a TestClient over AblationService +
FakeStructureRepo: pre-registration + idempotency, the IS-only auto-reject that buries the structure
AND records a REVIEW_GATE proposal (institutional memory), and the graveyard's "never re-proposed
silently" 409. The DESIGN VERIFY GOAL (a) — a known-noise indicator is rejected by the paired
evaluation — is ``test_known_noise_indicator_rejected_*`` below."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import ablation
from app.ablation import (
    AblationService,
    evaluate_ablation,
    paired_p_value,
    structure_fingerprint,
)
from app.errors import ApiError, api_error_handler
from tests.structure_fakes import FakeStructureRepo

_CAMPAIGN_ID = "camp-1"
_NOISE_GATE = {"op": "ADD_GATE", "gateType": "regime_suppression", "regime": "DOWN_TURBULENT"}


def _campaign(campaign_id=_CAMPAIGN_ID):
    return {"id": campaign_id, "family": "manas-arora", "evidencePolicy": "SIM_FIRST"}


def _client(repo: FakeStructureRepo) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.ablation = AblationService(repo_factory=lambda: repo)
    app.include_router(ablation.router)
    return TestClient(app)


# --- Student-t paired p-value (pure) ------------------------------------------------------------


def test_paired_p_value_significant_for_consistent_positive_lift():
    # a small, consistent positive lift is significant at p < 0.10 (one-sided).
    p = paired_p_value([0.02, 0.021, 0.019, 0.022, 0.02])
    assert p is not None and p < 0.10


def test_paired_p_value_not_significant_for_negative_lift():
    p = paired_p_value([-0.02, -0.01, -0.03, -0.02])
    assert p is not None and p > 0.90


def test_paired_p_value_none_below_two_folds():
    assert paired_p_value([]) is None
    assert paired_p_value([0.05]) is None


def test_paired_p_value_zero_variance_limits():
    assert paired_p_value([0.01, 0.01, 0.01]) == 0.0     # perfectly consistent positive → p 0
    assert paired_p_value([-0.01, -0.01]) == 1.0         # perfectly consistent negative → p 1
    assert paired_p_value([0.0, 0.0, 0.0]) == 0.5        # no lift → p 0.5


# --- fingerprint (pure) -------------------------------------------------------------------------


def test_fingerprint_is_key_order_independent():
    a = structure_fingerprint({"op": "ADD_GATE", "regime": "DOWN_TURBULENT", "gateType": "rs"})
    b = structure_fingerprint({"gateType": "rs", "op": "ADD_GATE", "regime": "DOWN_TURBULENT"})
    assert a == b


def test_fingerprint_distinguishes_structures():
    a = structure_fingerprint({"op": "ADD_GATE", "regime": "DOWN_TURBULENT"})
    b = structure_fingerprint({"op": "ADD_GATE", "regime": "UP_QUIET"})
    assert a != b


# --- evaluate_ablation verdicts (pure) ----------------------------------------------------------


def _acc_parent():
    return {
        "oosFoldReturns": [0.05, 0.06, 0.05, 0.055],
        "regimeFoldReturns": {"UP_QUIET": [0.05, 0.06], "DOWN_QUIET": [0.03, 0.04],
                              "DOWN_TURBULENT": [0.01, 0.02]},
        "tradeCount": 100, "robustScore": 1.0,
    }


def _acc_candidate():
    return {
        "oosFoldReturns": [0.07, 0.08, 0.075, 0.078],
        "regimeFoldReturns": {"UP_QUIET": [0.07, 0.08], "DOWN_QUIET": [0.05, 0.06],
                              "DOWN_TURBULENT": [0.03, 0.04]},
        "tradeCount": 90, "robustScore": 1.1,
    }


def test_evaluate_accepts_a_gate_that_pays_for_itself():
    verdict, evaluation = evaluate_ablation(_acc_parent(), _acc_candidate())
    assert verdict == "ACCEPTED"
    assert evaluation["oosLift"] > 0 and evaluation["pairedP"] < 0.10
    assert all(g["status"] != "FAIL" for g in evaluation["gates"])


def test_known_noise_indicator_rejected_no_lift():
    # DESIGN VERIFY (a): a noise gate whose per-fold OOS lift averages to ~0 is rejected — no edge.
    parent = {"oosFoldReturns": [0.05, 0.05, 0.05, 0.05]}
    candidate = {"oosFoldReturns": [0.05, 0.04, 0.06, 0.05]}  # diffs 0/-.01/+.01/0 → mean 0
    verdict, evaluation = evaluate_ablation(parent, candidate)
    assert verdict == "REJECTED_NO_LIFT"
    assert evaluation["oosLift"] <= 0 or evaluation["pairedP"] >= 0.10


def test_known_noise_indicator_rejected_is_only():
    # DESIGN VERIFY (a), the anti-snooping keystone: a noise gate that lifts IN-SAMPLE but NOT
    # out-of-sample is auto-rejected REJECTED_IS_ONLY.
    parent = {"oosFoldReturns": [0.05, 0.05, 0.05, 0.05], "isFoldReturns": [0.05, 0.05, 0.05, 0.05]}
    candidate = {"oosFoldReturns": [0.04, 0.05, 0.05, 0.04],   # OOS lift ≤ 0
                 "isFoldReturns": [0.09, 0.10, 0.09, 0.10]}    # IS lift > 0 (overfit)
    verdict, evaluation = evaluate_ablation(parent, candidate)
    assert verdict == "REJECTED_IS_ONLY"
    assert evaluation["isLift"] > 0 and evaluation["oosLift"] <= 0
    assert evaluation["isOnly"] is True


def test_evaluate_rejects_trade_starvation():
    parent = dict(_acc_parent(), tradeCount=100)
    candidate = dict(_acc_candidate(), tradeCount=50)  # 50 % retention < 70 %
    verdict, evaluation = evaluate_ablation(parent, candidate)
    assert verdict == "REJECTED_TRADE_STARVATION"
    assert evaluation["tradeRetention"] == 0.5


def test_evaluate_rejects_regime_dependence():
    # lift + retention pass, but the gate helps only in ONE regime (loses in the other two).
    parent = {"oosFoldReturns": [0.05, 0.06, 0.05, 0.06], "tradeCount": 100,
              "regimeFoldReturns": {"UP_QUIET": [0.05, 0.06], "DOWN_QUIET": [0.05, 0.06],
                                    "DOWN_TURBULENT": [0.05, 0.06]}}
    candidate = {"oosFoldReturns": [0.09, 0.10, 0.095, 0.098], "tradeCount": 95,
                 "regimeFoldReturns": {"UP_QUIET": [0.12, 0.13], "DOWN_QUIET": [0.02, 0.01],
                                       "DOWN_TURBULENT": [0.02, 0.01]}}
    verdict, evaluation = evaluate_ablation(parent, candidate)
    assert verdict == "REJECTED_REGIME"


def test_evaluate_rejects_when_dof_penalty_not_paid():
    # lift passes but the gated candidate's net RobustScore is below the parent's — the added DOF
    # was not paid for. (No regime/trade evidence → those gates SKIP, do not block.)
    parent = {"oosFoldReturns": [0.05, 0.05, 0.05, 0.05], "robustScore": 1.0}
    candidate = {"oosFoldReturns": [0.09, 0.10, 0.095, 0.098], "robustScore": 0.9}
    verdict, evaluation = evaluate_ablation(parent, candidate)
    assert verdict == "REJECTED_DOF"


def test_evaluate_skipped_gates_do_not_block_accept():
    # only OOS folds supplied — the regime/trade/DOF gates SKIP honestly and do NOT block ACCEPT.
    parent = {"oosFoldReturns": [0.05, 0.05, 0.05, 0.05]}
    candidate = {"oosFoldReturns": [0.09, 0.10, 0.095, 0.098]}
    verdict, evaluation = evaluate_ablation(parent, candidate)
    assert verdict == "ACCEPTED"
    skipped = {g["id"] for g in evaluation["gates"] if g["status"] == "SKIPPED"}
    assert skipped == {"regime_generalization", "trade_retention", "dof"}
    assert evaluation["caveats"]  # each SKIP records its reason


# --- registration API ---------------------------------------------------------------------------


def _register(client, mutation=None, parent=None):
    body = {"mutation": mutation or _NOISE_GATE}
    if parent is not None:
        body["parentCandidateId"] = parent
    return client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/ablations", json=body)


def test_register_pre_registers_and_lists():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    client = _client(repo)
    resp = _register(client)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["status"] == "PRE_REGISTERED"
    assert body["fingerprint"] == structure_fingerprint(_NOISE_GATE)
    assert body["verdict"] is None and body["preRegisteredAt"] is not None
    listed = client.get(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/ablations").json()["items"]
    assert len(listed) == 1 and listed[0]["id"] == body["id"]


def test_register_unknown_campaign_is_404():
    client = _client(FakeStructureRepo())
    assert _register(client).status_code == 404


def test_register_unknown_parent_is_404():
    repo = FakeStructureRepo(campaigns=[_campaign()], candidate_ids=set())
    resp = _register(_client(repo), parent="cand-x")
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_CANDIDATE"


def test_register_missing_mutation_is_400():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    resp = _client(repo).post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/ablations", json={})
    assert resp.status_code == 400


def test_register_duplicate_is_409():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    client = _client(repo)
    assert _register(client).status_code == 201
    dup = _register(client)
    assert dup.status_code == 409 and dup.json()["code"] == "CONFLICT_ABLATION_REGISTERED"


# --- evaluate API + graveyard + REVIEW_GATE -----------------------------------------------------


def _evaluate(client, ablation_id, parent, candidate):
    return client.post(
        f"/api/v1/evolution/ablations/{ablation_id}/evaluate",
        json={"parent": parent, "candidate": candidate},
    )


def test_evaluate_is_only_buries_and_records_review_gate_and_blocks_reproposal():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    client = _client(repo)
    ablation_id = _register(client).json()["id"]

    parent = {"oosFoldReturns": [0.05, 0.05, 0.05, 0.05], "isFoldReturns": [0.05, 0.05, 0.05, 0.05]}
    candidate = {"oosFoldReturns": [0.04, 0.05, 0.05, 0.04],
                 "isFoldReturns": [0.09, 0.10, 0.09, 0.10]}
    resp = _evaluate(client, ablation_id, parent, candidate)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["verdict"] == "REJECTED_IS_ONLY"
    assert body["status"] == "EVALUATED"
    assert body["buried"] is True
    assert body["reviewGateProposalId"] is not None

    # buried in the graveyard
    grave = client.get(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/graveyard").json()["items"]
    assert len(grave) == 1
    assert grave[0]["fingerprint"] == structure_fingerprint(_NOISE_GATE)
    assert grave[0]["verdict"] == "REJECTED_IS_ONLY"

    # a REVIEW_GATE proposal recorded it (institutional memory), wiring nothing
    review = [p for p in repo.proposals if p["kind"] == "REVIEW_GATE"]
    assert len(review) == 1
    assert review[0]["status"] == "PENDING"
    assert review[0]["evidence"]["wires"] is False
    assert review[0]["evidence"]["fingerprint"] == structure_fingerprint(_NOISE_GATE)

    # NEVER re-proposed silently — re-registering the same structure is refused
    reproposal = _register(client)
    assert reproposal.status_code == 409
    assert reproposal.json()["code"] == "CONFLICT_STRUCTURE_GRAVEYARDED"


def test_evaluate_accepted_is_not_buried():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    client = _client(repo)
    ablation_id = _register(client).json()["id"]
    resp = _evaluate(client, ablation_id, _acc_parent(), _acc_candidate())
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["verdict"] == "ACCEPTED" and body["buried"] is False
    assert body["reviewGateProposalId"] is None
    assert repo.graveyard == []
    assert [p for p in repo.proposals if p["kind"] == "REVIEW_GATE"] == []


def test_evaluate_already_evaluated_is_409():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    client = _client(repo)
    ablation_id = _register(client).json()["id"]
    _evaluate(client, ablation_id, _acc_parent(), _acc_candidate())
    again = _evaluate(client, ablation_id, _acc_parent(), _acc_candidate())
    assert again.status_code == 409
    assert again.json()["code"] == "ABLATION_ALREADY_EVALUATED"


def test_evaluate_unpaired_folds_is_422():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    client = _client(repo)
    ablation_id = _register(client).json()["id"]
    resp = _evaluate(
        client, ablation_id,
        {"oosFoldReturns": [0.05, 0.05, 0.05]}, {"oosFoldReturns": [0.06, 0.06]},
    )
    assert resp.status_code == 422 and resp.json()["code"] == "FOLDS_NOT_PAIRED"


def test_evaluate_unknown_ablation_is_404():
    repo = FakeStructureRepo(campaigns=[_campaign()])
    resp = _evaluate(_client(repo), "abl-none", _acc_parent(), _acc_candidate())
    assert resp.status_code == 404
