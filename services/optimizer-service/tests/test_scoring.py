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
    # rawObjective constant ⇒ plateauMargin constant (stability z=0); other components absent ⇒ z=0.
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
    assert comp["stability"] == 0.0  # plateauMargin constant, oosFoldStd absent
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
    assert gates["deflated_sharpe"] == "SKIPPED"  # no sharpe/trade count on a bare candidate
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


def test_regime_floor_negative_mean_fails_design_literal():
    # Design-literal §6.1: threshold = −0.5 × mean. A NEGATIVE mean flips the threshold positive
    # (here −0.5·(−0.1) = +0.05) and min ≤ mean by construction, so a negative-mean candidate can
    # never pass — faithful to the formula as written (documented behavior, not a bug).
    cand = _healthy(1) | {"regimeOosMin": -0.2, "regimeOosMean": -0.1}
    gates = scoring.score_cohort([cand], [])[0]["gates"]
    assert next(g for g in gates if g["id"] == "regime_floor")["status"] == "FAIL"


# --- boundary pins (≥ / ≤ directions) ---------------------------------------------------------

def test_fold_consistency_exact_boundary_passes():
    # exactly 3/5 = 0.60 positive folds → PASS (the gate is ≥, not >)
    cand = _healthy(1) | {"foldReturns": [0.1, 0.2, 0.3, -0.1, -0.2]}
    gates = scoring.score_cohort([cand], [])[0]["gates"]
    gate = next(g for g in gates if g["id"] == "fold_consistency")
    assert gate["value"] == 0.6
    assert gate["status"] == "PASS"


def test_drawdown_cap_exact_boundary_passes():
    # exactly 40.0 == the swing cap → PASS (the gate is ≤, not <)
    cand = _healthy(1) | {"maxDrawdown": 40.0}
    gates = scoring.score_cohort([cand], [])[0]["gates"]
    assert next(g for g in gates if g["id"] == "drawdown_cap")["status"] == "PASS"


# --- stability floor: multiplication form, sign- and direction-safe ---------------------------
# parameters=[] ⇒ every trial neighbors every other ⇒ 5 candidates give neighborCount 4 (gate
# assessable); plateauObjective = median of the whole cohort's rawObjectives.

def _stability_gate(cards, trial_number):
    card = next(c for c in cards if c["trialNumber"] == trial_number)
    return next(g for g in card["gates"] if g["id"] == "stability_floor")


def test_stability_negative_raw_plateau_better_but_below_bar_fails():
    # raw −1.0, cohort median −0.9: the plateau is BETTER than raw (−0.9 > −1.0) but does not clear
    # 0.8×raw = −0.8 (for raw < 0 the 0.8 multiple is a HIGHER bar) ⇒ FAIL. The old DIVISION form
    # inverted here: −0.9/−1.0 = 0.9 ≥ 0.8 would have (wrongly) PASSed.
    raws = [-1.0, -0.9, -0.9, -0.9, -0.9]
    cands = [{"trialNumber": i, "rawObjective": r} for i, r in enumerate(raws)]
    gate = _stability_gate(scoring.score_cohort(cands, []), 0)
    assert gate["status"] == "FAIL"
    assert "margin" in gate["note"]  # ratio undefined at raw ≤ 0 — the margin form is shown


def test_stability_negative_raw_positive_plateau_passes():
    # raw −1.0 but the neighborhood median is +0.2: 0.2 ≥ 0.8×(−1.0) = −0.8 ⇒ PASS. The old
    # DIVISION form inverted here too: 0.2/−1.0 = −0.2 < 0.8 would have (wrongly) FAILed.
    raws = [-1.0, 0.3, 0.3, 0.1, 0.2]
    cands = [{"trialNumber": i, "rawObjective": r} for i, r in enumerate(raws)]
    assert _stability_gate(scoring.score_cohort(cands, []), 0)["status"] == "PASS"


def test_stability_minimize_direction_normalizes_to_maximize_space():
    # A minimize sweep (e.g. maxDrawdown): values are negated ONCE into maximize space.
    # Cohort DDs [10, 5, 6, 7, 7.5], median 7.
    #  - candidate dd=10: −7 ≥ 0.8×(−10) = −8 ⇒ PASS (its neighborhood is better than itself).
    #  - candidate dd=5 (the lone best): −7 ≥ 0.8×(−5) = −4 ⇒ FAIL (a spike in minimize space —
    #    plateau doctrine: a lone winner surrounded by worse sinks).
    raws = [10.0, 5.0, 6.0, 7.0, 7.5]
    cands = [{"trialNumber": i, "rawObjective": r} for i, r in enumerate(raws)]
    cards = scoring.score_cohort(cands, [], direction="minimize")
    assert _stability_gate(cards, 0)["status"] == "PASS"
    assert _stability_gate(cards, 1)["status"] == "FAIL"


def test_risk_adjusted_reads_sortino_only_never_sharpe():
    # One candidate carries ONLY sharpe: it must NOT leak into the sortino z-column (mixing metrics
    # biases fallback candidates) — its risk_adjusted degrades to z=0 with an empty raw.
    cands = [
        {"trialNumber": 1, "rawObjective": 1.0, "sortino": 1.0},
        {"trialNumber": 2, "rawObjective": 1.0, "sortino": 2.0},
        {"trialNumber": 3, "rawObjective": 1.0, "sharpe": 9.9},  # no sortino
    ]
    cards = {c["trialNumber"]: c for c in scoring.score_cohort(cands, [])}
    risk = {t: next(x for x in c["components"] if x["id"] == "risk_adjusted")
            for t, c in cards.items()}
    assert risk[3]["z"] == 0.0
    assert risk[3]["raw"] == {}
    # sortino z over [1.0, 2.0] only: mean 1.5, pstdev 0.5 → ±1.0 (9.9 never entered the column)
    assert risk[1]["z"] == -1.0
    assert risk[2]["z"] == 1.0


# --- deflated-Sharpe multiplicity gate (§4, E2) -----------------------------------------------
# deflatedSharpe = (S − S₀(N)) / se, gate > 0; se = √((1+0.5·S²)/(T−1)) (Lo/Bailey-LdP, IID-normal);
# S₀(N) = se·√(2·ln N), so the value reduces to S/se − √(2·ln N). Every number below is computed by
# hand from those two closed forms (T = OOS trade count, N = cohort/trial count).

def _dsr_cand(n: int, **over) -> dict:
    # A candidate that PASSES every OTHER hard gate, so rankability flips solely on deflated_sharpe.
    return {"trialNumber": n, "rawObjective": 1.0, "oosReturn": 12.0, "sharpe": 0.4,
            "oosTradeCount": 60, "maxDrawdown": 20.0, "regimeOosMin": 0.4, "regimeOosMean": 0.8,
            "regimesCovered": ["UP_QUIET", "DOWN_QUIET", "UP_TURBULENT"], "engineSha": "sha",
            "foldReturns": [0.1, 0.2, 0.3]} | over


def _dsr_gate(cards, trial_number=1):
    card = next(c for c in cards if c["trialNumber"] == trial_number)
    return next(g for g in card["gates"] if g["id"] == "deflated_sharpe")


def test_deflated_sharpe_skipped_without_sharpe_or_trades():
    # No sharpe → SKIPPED (never fabricated); the candidate stays rankable (SKIPPED never blocks).
    no_sharpe = scoring.score_cohort([_dsr_cand(1, sharpe=None)], [], n_trials=1000)[0]
    assert _dsr_gate([no_sharpe])["status"] == "SKIPPED"
    assert no_sharpe["rankable"] is True
    # A Sharpe SE needs T ≥ 2; a single-observation trade count is unassessable → SKIPPED.
    one_trade = scoring.score_cohort([_dsr_cand(1, oosTradeCount=1)], [], n_trials=1000)[0]
    assert _dsr_gate([one_trade])["status"] == "SKIPPED"


def test_deflated_sharpe_overfit_toy_sweep_rejected_at_large_N_passes_at_small_N():
    # THE §12 E2 verify: the SAME marginal candidate (Sharpe 0.4 over T=60 trades) is REJECTED when
    # the campaign ran 1000 trials but PASSES when it ran only 2 — the engine charges itself for
    # every look at the data. se = √((1+0.5·0.16)/59) = √0.0183051 = 0.1353.  S/se = 0.4/0.1353 =
    # 2.9565.  Bar √(2·ln N): N=1000 → 3.7169 ⇒ deflated 2.9565−3.7169 = −0.7604 (FAIL);
    # N=2 → 1.1774 ⇒ deflated 2.9565−1.1774 = 1.7791 (PASS).
    overfit = scoring.score_cohort([_dsr_cand(1)], [], n_trials=1000)[0]
    gate_hi = _dsr_gate([overfit])
    assert gate_hi["status"] == "FAIL"
    assert gate_hi["value"] == -0.7604
    assert "N=1000 trials" in gate_hi["note"]
    assert overfit["rankable"] is False   # the deflated-Sharpe FAIL is the SOLE disqualifier
    assert overfit["rank"] is None

    honest = scoring.score_cohort([_dsr_cand(1)], [], n_trials=2)[0]
    gate_lo = _dsr_gate([honest])
    assert gate_lo["status"] == "PASS"
    assert gate_lo["value"] == 1.7791
    assert honest["rankable"] is True
    assert honest["rank"] == 1


def test_deflated_sharpe_single_trial_has_no_multiplicity_haircut():
    # N clamps to ≥ 1; N=1 ⇒ ln 1 = 0 ⇒ S₀ = 0 ⇒ deflated = S/se = 2.9565 (> 0, PASS): one untried
    # candidate is gated on Sharpe sign alone (there is no multiplicity to correct for a lone look).
    card = scoring.score_cohort([_dsr_cand(1)], [], n_trials=1)[0]
    assert _dsr_gate([card])["value"] == 2.9565
    assert _dsr_gate([card])["status"] == "PASS"


def test_deflated_sharpe_negative_sharpe_fails_any_N():
    # A negative Sharpe can never clear a non-negative bar → FAIL regardless of N.
    card = scoring.score_cohort([_dsr_cand(1, sharpe=-0.3, oosTradeCount=100)], [], n_trials=10)[0]
    assert _dsr_gate([card])["status"] == "FAIL"


# --- DOF penalties (§6.2, E2) ------------------------------------------------------------------
# dof = 0.03 per tuned param over 4 + 0.06 per structure gate; activeParams = len(parameters).

def _params(k: int) -> list[dict]:
    return [{"path": f"p{i}"} for i in range(k)]


def test_dof_penalty_charges_tuned_params_over_four():
    cand = {"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 10.0}
    # 4 tuned params → free (0.03·max(0,4−4)=0); 5 → 0.03; 7 → 0.03·3 = 0.09.
    assert scoring.score_cohort([cand], _params(4))[0]["penalties"]["dof"] == 0.0
    assert scoring.score_cohort([cand], _params(5))[0]["penalties"]["dof"] == 0.03
    assert scoring.score_cohort([cand], _params(7))[0]["penalties"]["dof"] == 0.09


def test_dof_penalty_charges_structure_gates():
    # 4 params (no param charge) + 2 structure gates → 0.06·2 = 0.12.
    cand = {"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 10.0, "structureGateCount": 2}
    assert scoring.score_cohort([cand], _params(4))[0]["penalties"]["dof"] == 0.12


def test_dof_penalty_subtracts_from_robustscore():
    # Single candidate ⇒ every component z = 0 ⇒ robustScore = 0 − dof − caveats.  7 params → −0.09.
    cand = {"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 10.0}
    assert scoring.score_cohort([cand], _params(7))[0]["robustScore"] == -0.09


def test_dof_source_is_unified_with_explainability_activedof():
    # DOF penalty and the explainability activeDOF/12 term MUST read the same tuned-param count
    # (len(parameters)) so they can't drift. 12 params ⇒ explainability 0.3·(1−12/12) = 0.0 (raw),
    # and DOF penalty 0.03·(12−4) = 0.24 — both driven by the identical param count.
    cand = {"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 10.0}
    card = scoring.score_cohort([cand], _params(12))[0]
    assert card["penalties"]["dof"] == 0.24
    explain = next(c for c in card["components"] if c["id"] == "explainability")
    assert explain["raw"]["explainability"] == 0.0  # activeDOF 12 zeroes the E1 explainability term


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
    assert any("penalties.dof charges tuned params over 4" in c for c in caveats)  # DOF is E2-live
    assert any("cost_resilience: no stress runs" in c for c in caveats)  # cost_resilience
    assert any("live_alignment: no live evidence yet" in c for c in caveats)  # live_alignment
    assert any("full-window run" in c for c in caveats)  # foldless fallback flagged


def test_weights_are_echoed_and_overridable():
    override = dict(scoring.SIM_FIRST_WEIGHTS, oos_return=0.5)
    card = scoring.score_cohort(
        [{"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 5.0}], [], weights=override
    )[0]
    assert card["weights"]["oos_return"] == 0.5
