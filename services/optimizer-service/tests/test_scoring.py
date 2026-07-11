"""Unit tests for the pure §6 scoring lib. Every RobustScore assertion is hand-computed against a
tiny cohort (population z-scores), so a change in the weighting/normalization math trips a test with
an exact expected number — not an eyeballed range. No DB, no backtest client: it is pure."""

from app import scoring

# --- z-score primitive (population, cohort-as-population) --------------------------------------

def test_zscores_population_exact():
    # [10,20,30]: mean 20, pstdev = sqrt(200/3) = 8.164966; z = ∓1.224745 at the ends, 0 in middle
    zs = scoring._zscores([10.0, 20.0, 30.0])
    assert zs[1] == 0.0
    assert round(zs[0], 6) == -1.224745
    assert round(zs[2], 6) == 1.224745


def test_zscores_missing_stays_none_constant_and_singleton_are_zero():
    assert scoring._zscores([5.0, None, 5.0]) == [0.0, None, 0.0]  # None drops out; sd==0 → 0
    assert scoring._zscores([7.0]) == [0.0]  # <2 present → nothing to normalize against


# --- RobustScore composition ------------------------------------------------------------------

def test_robustscore_isolates_oos_return_component():
    # rawObjective constant ⇒ plateauRatio ≡ 1 (stability z=0); every other component absent ⇒ z=0.
    # So robustScore = 0.22 · z(oosReturn). z(30)= +1.224745 ⇒ 0.22·1.224745 = 0.269444 → 0.2694.
    cands = [
        {"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 10.0},
        {"trialNumber": 2, "rawObjective": 1.0, "oosReturn": 20.0},
        {"trialNumber": 3, "rawObjective": 1.0, "oosReturn": 30.0},
    ]
    cards = {c["trialNumber"]: c for c in scoring.score_cohort(cands, [])}
    assert cards[2]["robustScore"] == 0.0
    assert cards[3]["robustScore"] == 0.2694
    assert cards[1]["robustScore"] == -0.2694
    comp = {x["id"]: x["z"] for x in cards[3]["components"]}
    assert comp["oos_return"] == 1.2247
    assert comp["stability"] == 0.0  # plateauRatio constant, oosFoldStd absent
    assert comp["risk_adjusted"] == 0.0
    assert comp["cost_resilience"] == 0.0  # no stress runs (E2)
    assert comp["live_alignment"] == 0.0  # no live evidence


def test_component_averages_present_subsignal_zscores_with_sign():
    # drawdown_quality = mean of present sub-signal z's; here only negMaxDrawdown is present.
    # maxDD [10,20,30] → negMaxDD [-10,-20,-30]; worst DD (30) gets the LOWEST z (penalized).
    cands = [
        {"trialNumber": 1, "rawObjective": 1.0, "maxDrawdown": 10.0},
        {"trialNumber": 2, "rawObjective": 1.0, "maxDrawdown": 20.0},
        {"trialNumber": 3, "rawObjective": 1.0, "maxDrawdown": 30.0},
    ]
    cards = {c["trialNumber"]: c for c in scoring.score_cohort(cands, [])}
    dd = {t: next(x for x in c["components"] if x["id"] == "drawdown_quality")
          for t, c in cards.items()}
    assert dd[1]["z"] == 1.2247  # least drawdown → best
    assert dd[2]["z"] == 0.0
    assert dd[3]["z"] == -1.2247  # worst drawdown → penalized
    assert dd[3]["raw"] == {"negMaxDrawdown": -30.0}


def test_scorecard_is_deterministic():
    cands = [
        {"trialNumber": i, "rawObjective": 1.0, "oosReturn": 10.0 + i, "maxDrawdown": 15.0 + i,
         "sortino": 1.0 + i / 10, "regimesCovered": ["UP_QUIET", "DOWN_QUIET", "UP_TURBULENT"],
         "regimeOosMin": 0.3, "regimeOosMean": 0.6, "engineSha": "sha", "oosTradeCount": 70,
         "foldReturns": [0.1, -0.02, 0.2]}
        for i in range(3)
    ]
    assert scoring.score_cohort(cands, []) == scoring.score_cohort(cands, [])


# --- ranking + rankability --------------------------------------------------------------------

def test_rank_orders_by_robustscore_and_excludes_failed_gate():
    cands = [
        {"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 30.0},
        {"trialNumber": 2, "rawObjective": 1.0, "oosReturn": 20.0},
        {"trialNumber": 3, "rawObjective": 1.0, "oosReturn": -5.0},  # FAILs oos_sign ⇒ unrankable
    ]
    cards = {c["trialNumber"]: c for c in scoring.score_cohort(cands, [])}
    assert cards[1]["rank"] == 1
    assert cards[2]["rank"] == 2
    assert cards[3]["rank"] is None
    assert cards[3]["rankable"] is False
    oos_sign = next(g for g in cards[3]["gates"] if g["id"] == "oos_sign")
    assert oos_sign["status"] == "FAIL"


# --- gate statuses: degradations + passes -----------------------------------------------------

def test_gate_degradations_on_a_bare_candidate():
    cards = scoring.score_cohort([{"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 5.0}], [])
    gates = {g["id"]: g["status"] for g in cards[0]["gates"]}
    assert gates["oos_sign"] == "PASS"  # 5 > 0
    assert gates["evidence_floor"] == "UNKNOWN"  # no oosTradeCount
    assert gates["fold_consistency"] == "UNKNOWN"  # no foldReturns (full-window)
    assert gates["drawdown_cap"] == "UNKNOWN"  # no maxDrawdown
    assert gates["regime_floor"] == "UNKNOWN"  # regimesCovered empty (pre-#705 runs)
    assert gates["stability_floor"] == "SKIPPED"  # neighborCount 0 < 4
    assert gates["holdout"] == "SKIPPED"  # no holdout run linked in retro
    assert gates["comparability"] == "UNKNOWN"  # NULL engine SHA (pre-#703)
    assert gates["live_gap"] == "SKIPPED"  # no live evidence
    assert gates["deflated_sharpe"] == "NOT_IMPLEMENTED"  # DSR multiplicity gate is E2
    assert cards[0]["rankable"] is True  # no FAIL among the statuses


def _healthy(n: int) -> dict:
    return {
        "trialNumber": n, "rawObjective": 1.0, "oosReturn": 12.0,
        "foldReturns": [0.1, 0.2, 0.3, -0.05], "oosTradeCount": 80, "maxDrawdown": 20.0,
        "sortino": 1.5, "expectancy": 500.0, "regimeOosMin": 0.4, "regimeOosMean": 0.8,
        "regimesCovered": ["UP_QUIET", "DOWN_QUIET", "UP_TURBULENT"], "engineSha": "abc123",
    }


def test_all_gates_pass_on_a_healthy_cohort():
    # 5 mutual neighbors (parameters=[] ⇒ every trial neighbors every other) ⇒ neighborCount 4 ≥ 4.
    cards = scoring.score_cohort([_healthy(i) for i in range(5)], [])
    gates = {g["id"]: g for g in cards[0]["gates"]}
    assert gates["evidence_floor"]["status"] == "PASS"  # 80 ≥ 60
    assert gates["oos_sign"]["status"] == "PASS"
    assert gates["fold_consistency"]["status"] == "PASS"
    assert gates["fold_consistency"]["value"] == 0.75
    assert gates["drawdown_cap"]["status"] == "PASS"  # 20 ≤ 40
    assert gates["regime_floor"]["status"] == "PASS"  # 0.4 ≥ −0.4, 3 of 4 covered
    assert gates["stability_floor"]["status"] == "PASS"  # ratio 1.0 ≥ 0.8, 4 neighbors
    assert gates["comparability"]["status"] == "PASS"
    assert cards[0]["rankable"] is True


def test_regime_floor_fails_when_min_too_negative():
    # min −0.9 < −0.5 · mean(0.8) = −0.4 ⇒ FAIL even though 3 regimes are covered.
    cand = _healthy(1) | {"regimeOosMin": -0.9}
    gates = scoring.score_cohort([cand], [])[0]["gates"]
    gate = next(g for g in gates if g["id"] == "regime_floor")
    assert gate["status"] == "FAIL"


# --- penalties, flags, caveats ----------------------------------------------------------------

def test_caveat_penalty_and_regime_dependent_flag():
    cands = [
        {"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 10.0,
         "caveats": ["synthetic premium"], "oiGateCoverage": "30/45",  # 0.667 < 0.80 ⇒ +1
         "regimesCovered": ["UP_QUIET"]},  # 1 of 4 ⇒ REGIME_DEPENDENT
        {"trialNumber": 2, "rawObjective": 1.0, "oosReturn": 20.0},
    ]
    cards = {c["trialNumber"]: c for c in scoring.score_cohort(cands, [])}
    # 0.05 · (1 data caveat + 1 oiGateCoverage-below-floor) = 0.10
    assert cards[1]["penalties"] == {"dof": 0.0, "caveats": 0.1}
    assert "REGIME_DEPENDENT:UP_QUIET" in cards[1]["flags"]
    assert "synthetic premium" in cards[1]["caveats"]
    assert any("oiGateCoverage 30/45 below 80%" == c for c in cards[1]["caveats"])
    assert cards[2]["penalties"] == {"dof": 0.0, "caveats": 0.0}


def test_standing_e1_caveats_are_present():
    caveats = scoring.score_cohort(
        [{"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 5.0, "foldless": True}], []
    )[0]["caveats"]
    assert any("penalties.dof=0.0" in c for c in caveats)  # DOF deferred to E2
    assert any("cost_resilience: no stress runs" in c for c in caveats)  # cost_resilience
    assert any("live_alignment: no live evidence yet" in c for c in caveats)  # live_alignment
    assert any("full-window run" in c for c in caveats)  # foldless fallback flagged


def test_weights_are_echoed_and_overridable():
    override = dict(scoring.SIM_FIRST_WEIGHTS, oos_return=0.5)
    card = scoring.score_cohort(
        [{"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 5.0}], [], weights=override
    )[0]
    assert card["weights"]["oos_return"] == 0.5
