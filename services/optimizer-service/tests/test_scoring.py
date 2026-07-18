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
    # gate-complete candidates (audit PF-01: partial-evidence candidates are no longer rankable) so
    # only the oos_sign FAIL, not a missing required gate, decides rankability.
    cands = [
        _healthy(1) | {"oosReturn": 30.0},
        _healthy(2) | {"oosReturn": 20.0},
        _healthy(3) | {"oosReturn": -5.0},  # FAILs oos_sign ⇒ unrankable
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
    card = scoring.score_cohort([{"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 5.0}], [])[0]
    gates = {g["id"]: g["status"] for g in card["gates"]}
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
    # DESCRIPTIVE rankable (permissive, unchanged): no hard-gate FAIL among the statuses → True.
    assert card["rankable"] is True
    # PROMOTION-ADMISSION stageReadiness (audit PF-01, fail-CLOSED): a bare candidate whose REQUIRED
    # SIM_FIRST gates could not run is NOT stage-ready (silently admitted under the old fail-open).
    sr = card["stageReadiness"]
    assert sr["ready"] is False
    assert sr["evidencePolicy"] == "SIM_FIRST" and sr["stage"] == "SCORED_TO_SURVIVOR"
    # every unevaluated REQUIRED SIM_FIRST gate is named (comparability/regime/fold/stability now
    # REQUIRED — #703/#705 closed); holdout + live_gap are the only excluded gates (post-select /
    # no-live), so they never appear.
    assert set(sr["blockedBy"]) == {
        "comparability:UNKNOWN", "deflated_sharpe:SKIPPED", "drawdown_cap:UNKNOWN",
        "evidence_floor:UNKNOWN", "fold_consistency:UNKNOWN", "regime_floor:UNKNOWN",
        "stability_floor:SKIPPED",
    }
    assert not any(g in b for b in sr["blockedBy"] for g in ("holdout", "live_gap"))


def _healthy(n: int) -> dict:
    return {
        "trialNumber": n, "rawObjective": 1.0, "oosReturn": 12.0,
        "foldReturns": [0.1, 0.2, 0.3, -0.05], "oosTradeCount": 80, "maxDrawdown": 20.0,
        "sortino": 1.5, "sharpe": 1.5, "expectancy": 500.0, "regimeOosMin": 0.4,
        "regimeOosMean": 0.8,
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
    # audit PF-01: every REQUIRED SIM_FIRST gate PASSes → stage-ready (promotable).
    sr = cards[0]["stageReadiness"]
    assert sr["ready"] is True and sr["blockedBy"] == []
    assert sr["evidencePolicy"] == "SIM_FIRST"
    assert set(sr["requiredGates"]) == {
        "evidence_floor", "oos_sign", "deflated_sharpe", "fold_consistency", "drawdown_cap",
        "regime_floor", "stability_floor", "comparability",
    }


def test_sim_first_survivor_requires_regime_and_comparability():
    # audit PF-01 finding #4: #703/#705 closed → comparability + regime_floor are now REQUIRED. A
    # candidate that clears every other bar but lacks an engine SHA (comparability UNKNOWN) is
    # rankable (descriptive, no FAIL) yet NOT stage-ready (fail-closed promotion admission).
    cohort = [_healthy(i) for i in range(5)]
    cohort[0]["engineSha"] = None  # comparability → UNKNOWN
    card = scoring.score_cohort(cohort, [])[0]
    assert card["rankable"] is True
    assert card["stageReadiness"]["ready"] is False
    assert "comparability:UNKNOWN" in card["stageReadiness"]["blockedBy"]


def test_comparability_fails_on_mismatched_sha_or_epoch():
    # audit PF-01 #4: comparability now CHECKS equality against the cohort comparator, not mere
    # presence (§1.3 refuses a cross-SHA / cross-data-epoch comparison). A differing SHA → FAIL
    # (blocks + drops the descriptive rankable too, since a FAIL is a real negative).
    cohort = [_healthy(i) for i in range(5)]        # comparator SHA = the first non-null, "abc123"
    cohort[1]["engineSha"] = "deadbeef"             # candidate 1 ran under a DIFFERENT engine
    card = next(c for c in scoring.score_cohort(cohort, []) if c["trialNumber"] == 1)
    gate = next(g for g in card["gates"] if g["id"] == "comparability")
    assert gate["status"] == "FAIL"
    assert card["rankable"] is False
    assert card["stageReadiness"]["ready"] is False
    assert "comparability:FAIL" in card["stageReadiness"]["blockedBy"]
    # a differing DATA EPOCH also FAILs (comparator epoch = the first non-null dataHash).
    cohort2 = [_healthy(i) | {"dataHash": "epoch-A"} for i in range(5)]
    cohort2[2]["dataHash"] = "epoch-B"
    card2 = next(c for c in scoring.score_cohort(cohort2, []) if c["trialNumber"] == 2)
    assert next(g for g in card2["gates"] if g["id"] == "comparability")["status"] == "FAIL"


def test_comparability_passes_when_cohort_shares_sha_and_epoch():
    # The healthy path: all trials of one sweep share the engine SHA + data epoch → comparability
    # PASSes for every candidate (the common, correct case).
    cohort = [_healthy(i) | {"dataHash": "epoch-1"} for i in range(5)]
    for card in scoring.score_cohort(cohort, []):
        assert next(g for g in card["gates"] if g["id"] == "comparability")["status"] == "PASS"


def test_live_first_sim_cohort_is_never_stage_ready():
    # audit PF-01 finding #5: a LIVE_FIRST campaign's sim cohort is functional-smoke only (§1.2) and
    # must NEVER become selectable. Even an all-gates-PASS healthy cohort is not stage-ready under
    # LIVE_FIRST — the SURVIVOR gate is live-evidence-led, not computed from sim. Descriptive
    # rankable is unaffected (still permissive).
    cards = scoring.score_cohort([_healthy(i) for i in range(5)], [], policy="LIVE_FIRST")
    assert cards[0]["rankable"] is True
    sr = cards[0]["stageReadiness"]
    assert sr["ready"] is False
    assert sr["evidencePolicy"] == "LIVE_FIRST"
    assert any("LIVE_FIRST" in b for b in sr["blockedBy"])
    # a LIVE_FIRST sim cohort names no SIM required gates (the live-evidence set is a flagged
    # follow-up, not computed here)
    assert sr["requiredGates"] == []


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
    # No sharpe → SKIPPED (never fabricated). DESCRIPTIVE rankable stays True (no hard FAIL), but
    # deflated_sharpe is a REQUIRED SURVIVOR gate (the overfitting guard), so a SKIPPED one BLOCKS
    # promotion — stageReadiness.ready is False (was fail-open "SKIPPED never blocks", the marquee
    # defect this closes).
    no_sharpe = scoring.score_cohort([_dsr_cand(1, sharpe=None)], [], n_trials=1000)[0]
    assert _dsr_gate([no_sharpe])["status"] == "SKIPPED"
    assert no_sharpe["rankable"] is True
    assert no_sharpe["stageReadiness"]["ready"] is False
    assert "deflated_sharpe:SKIPPED" in no_sharpe["stageReadiness"]["blockedBy"]
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


def test_deflated_sharpe_exactly_zero_fails_strict_boundary():
    # Equality-boundary pin: S=0, N=1 ⇒ S₀=0 ⇒ deflated = (0−0)/se = exactly 0.0 — the gate is
    # STRICT > 0, so 0.0 FAILs (a zero-edge candidate is never waved through on the boundary).
    card = scoring.score_cohort([_dsr_cand(1, sharpe=0.0, oosTradeCount=10)], [], n_trials=1)[0]
    gate = _dsr_gate([card])
    assert gate["value"] == 0.0
    assert gate["status"] == "FAIL"


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


# --- live-gap gate + live_alignment component + DIVERGENT checklist (§7.2, E3 §12 item 10) -----
# A candidate may carry a ``reconciliation`` row (the repo read-envelope shape — verdict / gapZ /
# gap.mode / pairedTrades / evidenceFloorMet / diagnosis / window), attached by the caller. scoring
# reads it PURELY: the live_alignment component (raw = min(0, gapZ + 0.5) = −live-gap magnitude),
# the live_gap hard gate (off the persisted verdict), and the DIVERGENT diagnosis checklist.

_WINDOW_FROM = "2026-06-01T00:00:00+05:30"
_WINDOW_TO = "2026-06-29T00:00:00+05:30"


def _recon(gap_z, verdict, *, mode="swing", floor=True, paired=25, diagnosis=None) -> dict:
    """A reconciliation row in the repo read-envelope shape — only the fields scoring reads."""
    return {
        "id": f"recon-{verdict}",
        "verdict": verdict,
        "gapZ": gap_z,
        "pairedTrades": paired,
        "evidenceFloorMet": floor,
        "windowFrom": _WINDOW_FROM,
        "windowTo": _WINDOW_TO,
        "gap": {"mode": mode},
        "diagnosis": diagnosis,
    }


def _live_gap(card) -> dict:
    return next(g for g in card["gates"] if g["id"] == "live_gap")


def _live_align(card) -> dict:
    return next(c for c in card["components"] if c["id"] == "live_alignment")


def test_live_alignment_direction_is_monotonic_and_gate_fails_divergent():
    # THE §12 item 10 SIGN test (guards the E1 plateau division/multiplication inversion class):
    # raw = min(0, gapZ + 0.5), so a HIGHER gapZ ⇒ HIGHER live_alignment ⇒ MORE reward. Over one
    # cohort at gapZ 0.0 / −1.0 / −2.0:  raw min(0,0.5)=0.0 ; min(0,−0.5)=−0.5 ; min(0,−1.5)=−1.5.
    aligned = _healthy(1) | {"reconciliation": _recon(0.0, "ALIGNED")}
    penalized = _healthy(2) | {"reconciliation": _recon(-1.0, "PENALIZED")}
    divergent = _healthy(3) | {"reconciliation": _recon(-2.0, "DIVERGENT")}
    cards = {c["trialNumber"]: c
             for c in scoring.score_cohort([aligned, penalized, divergent], [])}
    la = {t: _live_align(c) for t, c in cards.items()}
    # raw pinned EXACTLY (no float division) — the direction is unambiguous
    assert la[1]["raw"] == {"liveAlignment": 0.0}
    assert la[2]["raw"] == {"liveAlignment": -0.5}
    assert la[3]["raw"] == {"liveAlignment": -1.5}
    # z of the [0.0, −0.5, −1.5] column: mean −2/3, pstdev √(7/18) = 0.623610
    assert la[1]["z"] == 1.069     # aligned rewarded (POSITIVE z)
    assert la[2]["z"] == 0.2673
    assert la[3]["z"] == -1.3363   # divergent penalized (NEGATIVE z)
    assert la[1]["z"] > la[2]["z"] > la[3]["z"]   # MONOTONIC in gapZ — the inversion guard
    # +0.08·z(live_alignment) is the SOLE differentiator (every other component constant ⇒ z=0)
    assert cards[1]["robustScore"] == 0.0855      # 0.08 · 1.0690450
    # gate: not-divergent PASSES; DIVERGENT (floor met) hard-FAILS ⇒ unrankable
    assert _live_gap(cards[1])["status"] == "PASS"
    assert _live_gap(cards[2])["status"] == "PASS"
    assert _live_gap(cards[3])["status"] == "FAIL"
    assert cards[3]["rankable"] is False
    assert cards[1]["rankable"] is True and cards[2]["rankable"] is True


def test_champion_vs_itself_reads_aligned_gapz_near_zero():
    # §12 item 10 VERIFY: a champion reconciled against ITSELF over a clean window reads ALIGNED,
    # gapZ ≈ 0 → live_gap PASS, rankable. A single-candidate cohort ⇒ live_alignment z=0 (nothing to
    # normalize). Unit fixture: real known-clean live windows are scarce (design §12 item 10 note).
    card = scoring.score_cohort([_healthy(1) | {"reconciliation": _recon(0.0, "ALIGNED")}], [])[0]
    assert _live_gap(card)["status"] == "PASS"
    assert _live_gap(card)["value"] == 0.0
    assert card["rankable"] is True
    assert _live_align(card)["z"] == 0.0            # singleton cohort — nothing to normalize
    assert card["liveGap"]["verdict"] == "ALIGNED"
    assert card["liveGap"]["counted"] is True
    assert card["liveGap"]["gapZ"] == 0.0
    assert card["evidence"]["liveWindow"] == {"from": _WINDOW_FROM, "to": _WINDOW_TO}


def test_divergent_hard_gate_attaches_diagnosis_checklist():
    diag = {"checklist": [{"id": "trade_set_overlap", "status": "auto", "value": 0.3}]}
    card = scoring.score_cohort(
        [_healthy(1) | {"reconciliation": _recon(-2.0, "DIVERGENT", diagnosis=diag)}], []
    )[0]
    gate = _live_gap(card)
    assert gate["status"] == "FAIL"
    assert "DIVERGENT" in gate["note"]
    assert card["rankable"] is False
    assert card["rank"] is None
    assert card["liveGap"]["verdict"] == "DIVERGENT"
    assert card["liveGap"]["diagnosis"] == diag    # the §7.2.1-5 checklist, verbatim
    assert any("DIVERGENT" in c and "liveGap.diagnosis" in c for c in card["caveats"])


def test_penalized_verdict_passes_gate_but_penalizes_component():
    # −1.5 < gapZ < −0.5 ⇒ PENALIZED: the live_gap gate PASSES (not divergent) but the component
    # carries the proportional penalty raw = min(0, −1.0 + 0.5) = −0.5.
    cards = {c["trialNumber"]: c for c in scoring.score_cohort([
        _healthy(1) | {"reconciliation": _recon(-1.0, "PENALIZED")},
        _healthy(2) | {"reconciliation": _recon(0.0, "ALIGNED")},
    ], [])}
    assert _live_gap(cards[1])["status"] == "PASS"
    assert _live_align(cards[1])["raw"] == {"liveAlignment": -0.5}
    assert any("PENALIZED" in c for c in cards[1]["caveats"])


def test_insufficient_verdict_does_not_count_or_gate():
    # INSUFFICIENT (no live / no fold σ / floor unmet) → z=0 + live_gap SKIPPED (unchanged).
    card = scoring.score_cohort(
        [_healthy(1) | {"reconciliation": _recon(None, "INSUFFICIENT", floor=False)}], []
    )[0]
    assert _live_gap(card)["status"] == "SKIPPED"
    assert _live_align(card)["z"] == 0.0
    assert _live_align(card)["raw"] == {}
    assert card["rankable"] is True
    assert card["liveGap"]["verdict"] == "INSUFFICIENT"
    assert card["liveGap"]["counted"] is False
    assert "diagnosis" not in card["liveGap"]


def test_scalper_raw_reconciliation_is_whitelisted_never_gated():
    # §7.2 final para: a scalper raw backtest-vs-live divergence is STRUCTURAL by design — stamped,
    # NEVER counted or gated (only shadow-vs-paper may gate scalpers). Even a −2.0 DIVERGENT row
    # leaves the gate SKIPPED (not FAIL) and the candidate rankable.
    diag = {"checklist": [{"id": "trade_set_overlap", "status": "auto"}]}
    card = scoring.score_cohort([
        _healthy(1) | {"reconciliation": _recon(-2.0, "DIVERGENT", mode="scalper", diagnosis=diag)}
    ], [])[0]
    gate = _live_gap(card)
    assert gate["status"] == "SKIPPED"
    assert "whitelist" in gate["note"]
    assert card["rankable"] is True                # NOT hard-failed — whitelisted
    assert _live_align(card)["z"] == 0.0           # not counted into the component
    assert card["liveGap"]["whitelisted"] is True
    assert card["liveGap"]["counted"] is False
    assert "diagnosis" not in card["liveGap"]      # not a promotion-blocking finding
    assert any("whitelisted" in c for c in card["caveats"])


def test_no_reconciliation_is_byte_identical_pre_item10():
    # A candidate with NO reconciliation: live_gap SKIPPED with the ORIGINAL note, live_align z=0
    # with the standing caveat, liveGap None, evidence.liveWindow None — pre-item-10 behaviour.
    card = scoring.score_cohort([_healthy(1)], [])[0]
    gate = _live_gap(card)
    assert gate["status"] == "SKIPPED"
    assert gate["value"] is None
    assert "no live evidence on a sim sweep" in gate["note"]
    assert card["liveGap"] is None
    assert card["evidence"]["liveWindow"] is None
    assert _live_align(card)["z"] == 0.0
    assert any("live_alignment: no live evidence yet" in c for c in card["caveats"])


def test_mixed_cohort_notes_the_candidate_without_a_reconciliation():
    # Some candidates live, some sim-only: the standing "no live evidence yet" caveat fires for
    # NEITHER (the column is non-empty), and the sim-only candidate gets a per-candidate drop-out
    # note instead (mirrors the cost_resilience per-candidate pattern).
    cards = {c["trialNumber"]: c for c in scoring.score_cohort([
        _healthy(1) | {"reconciliation": _recon(0.0, "ALIGNED")},
        _healthy(2),  # no reconciliation
    ], [])}
    assert not any("no live evidence yet" in c for c in cards[1]["caveats"])
    assert not any("no live evidence yet" in c for c in cards[2]["caveats"])
    assert any("no live reconciliation for this candidate" in c for c in cards[2]["caveats"])
