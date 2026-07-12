"""EVO E5 — gate-candidate suggesters (design §5.1 / §5.1.2, roadmap §12 item 15).

The engine SUGGESTS structure-mutation candidates in the campaign report; it never invents
indicator math on its own (§5.1). Three suggesters, ranked by prior (§5.1):

  1. **rejection-forensics** (§5.1 #1): a rail that never blocked in the window is dead DOF →
     propose REMOVAL; a rail whose disable shows paired positive lift keeps blocking winners →
     propose RELAXATION.
  2. **regime-gate** (§5.1 #2): a cohort that loses ONLY in one regime (the design's DOWN_TURBULENT
     example) → suggest a RegimeLabeler entry-suppression for that regime. **EMIT-ONLY** — the
     frozen Stage-D design keeps regimes reported-not-optimized (RegimeLabel.java:6-7); the owner
     froze regimes on 2026-07-12 (revisit 2026-08-14). This suggester emits an owner-facing
     REVIEW_GATE proposal record and NOTHING ELSE: it wires no regime gate into scoring, the engine,
     or candidate dimensions. A regime gate needs a Stage-D design amendment the owner ratifies.
  3. **unused-indicator** (§5.1 #3): a LIB IndicatorBank / dot input not used by this strategy →
     suggest testing it as a gate/filter (e.g. an MA-slope filter for Manas, an IV-rank band for
     premium buys).

Every suggestion is a READ-ONLY analysis of the supplied forensics evidence and is persisted as a
REVIEW_GATE proposal (V011 evo_proposals, kind already legal; candidate_id NULL for these
cohort-level suggestions). NOTHING self-arms — a proposal is an inbox record the owner reviews. A
suggestion whose structure is already in the campaign GRAVEYARD is SUPPRESSED, never re-proposed
(§8.3, the "never re-proposed silently" guarantee).
"""

from __future__ import annotations

import statistics
from collections.abc import Callable
from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.ablation import structure_fingerprint
from app.errors import ApiError

router = APIRouter(prefix="/api/v1/evolution")

_REVIEW_GATE = "REVIEW_GATE"

# regime-gate suggester trigger: a MAJORITY of the analyzable cohort loses only in one regime, and
# at least this many candidates (a lone candidate is not a pattern). The design example is "3 of 5".
_REGIME_MIN_FRACTION = 0.5
_REGIME_MIN_COUNT = 2
# a candidate must cover ≥ this many regimes for "loses ONLY in R" to mean "wins elsewhere".
_REGIME_MIN_COVERED = 2

# The canonical regime vocabulary (design §4). NOTE the §13-row-20 latent platform bug: the
# optimizer/FE constants say BULL/RANGE/BEAR/CRASH, so `regimesCovered` can read empty on real folds
# — the suggester reads whatever labels the supplied folds carry (it does not depend on the enum).
_CANONICAL_REGIMES = ("UP_QUIET", "UP_TURBULENT", "DOWN_QUIET", "DOWN_TURBULENT")

# The §5.1.2 caveat every regime-gate suggestion MUST carry — it emits, it does not wire.
_REGIME_CAVEAT = (
    "REGIME GATE IS EMIT-ONLY (§5.1.2): the frozen Stage-D design keeps regimes reported-not-"
    "optimized (RegimeLabel.java:6-7; Stage-D S1A rejected regime-filter exprs). A regime gate "
    "is a NEW LIB gate type + a live-side regime feed + a design amendment the owner must ratify "
    "first. Owner decision 2026-07-12: regimes are FROZEN (revisit 2026-08-14). This suggestion "
    "WIRES NOTHING into scoring, the engine, or the candidate dimensions — it is an owner-facing "
    "proposal only."
)


# --- the three suggesters (pure) ----------------------------------------------------------------


def suggest_regime_gates(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """§5.1 #2 (EMIT-ONLY). Over a cohort of scored candidates (each with per-regime OOS fold
    returns), find a regime that a MAJORITY of the cohort loses ONLY in — the design's
    DOWN_TURBULENT example ("3 of 5 lose only in DOWN_TURBULENT folds"). A candidate "loses only
    in R" iff it covers ≥ 2 regimes, R's mean OOS return < 0, and every OTHER covered regime's mean
    ≥ 0. Emits a RegimeLabeler R entry-suppression suggestion — carrying the §5.1.2 wires-nothing
    caveat — when the losing fraction clears the majority + min-count bar."""
    losers_by_regime: dict[str, list[str]] = {}
    analyzable = 0
    for cand in candidates:
        means = _regime_means(cand.get("regimeFoldReturns"))
        if len(means) < _REGIME_MIN_COVERED:
            continue
        analyzable += 1
        losing = [label for label, mean in means.items() if mean < 0]
        if len(losing) == 1:
            losers_by_regime.setdefault(losing[0], []).append(str(cand.get("candidateId")))
    if analyzable == 0:
        return []

    suggestions: list[dict[str, Any]] = []
    for regime in sorted(losers_by_regime):
        cids = losers_by_regime[regime]
        if len(cids) < _REGIME_MIN_COUNT or len(cids) / analyzable < _REGIME_MIN_FRACTION:
            continue
        mutation = {"op": "ADD_GATE", "gateType": "regime_suppression", "regime": regime}
        suggestions.append({
            "source": "regime_gate",
            "suggestion": (
                f"add a RegimeLabeler {regime} entry-suppression — {len(cids)} of {analyzable} "
                f"scored candidates lose only in {regime} folds"
            ),
            "mutation": mutation,
            "evidence": {
                "regime": regime,
                "losingCandidates": cids,
                "analyzableCohort": analyzable,
                "canonicalRegime": regime in _CANONICAL_REGIMES,
            },
            "caveats": [_REGIME_CAVEAT],
            "wires": False,
        })
    return suggestions


def suggest_rejection_forensics(rail_stats: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """§5.1 #1. Over per-rail forensics: a rail that never blocked (``blocks == 0``) is dead DOF →
    propose REMOVAL; a rail whose disable variant shows paired positive lift (``disableLift > 0``,
    p < 0.10 when supplied) keeps blocking winners → propose RELAXATION. Both are emit-only owner
    proposals; a relaxation still passes a paper A/B before any promotion (§5.2 LIVE_FIRST)."""
    suggestions: list[dict[str, Any]] = []
    for rail in rail_stats:
        name = rail.get("rail")
        if not name:
            continue
        blocks = rail.get("blocks")
        lift = rail.get("disableLift")
        lift_p = rail.get("disableLiftP")
        if blocks == 0:
            mutation = {"op": "REMOVE_GATE", "rail": str(name)}
            suggestions.append({
                "source": "rejection_forensics",
                "suggestion": (
                    f"rail '{name}' never blocked in the window — dead DOF; propose removing it "
                    "(§5.1 rejection forensics)"
                ),
                "mutation": mutation,
                "evidence": {"rail": name, "blocks": blocks, "reason": "dead_dof"},
                "caveats": [
                    "removal is a structure change — pre-register + paired-ablate it (§5.2) before "
                    "acting; this only SUGGESTS."
                ],
                "wires": False,
            })
        elif isinstance(lift, int | float) and lift > 0 and (lift_p is None or lift_p < 0.10):
            mutation = {"op": "RELAX_GATE", "rail": str(name)}
            suggestions.append({
                "source": "rejection_forensics",
                "suggestion": (
                    f"rail '{name}' shows paired positive disable-lift ({lift}) — it keeps "
                    "blocking winners; propose relaxation (§5.1 rejection forensics)"
                ),
                "mutation": mutation,
                "evidence": {"rail": name, "blocks": blocks, "disableLift": lift,
                             "disableLiftP": lift_p, "reason": "blocks_winners"},
                "caveats": [
                    "shadow-book paired evidence; a relaxation still passes a paper A/B before any "
                    "promotion (§5.2 LIVE_FIRST). This only SUGGESTS."
                ],
                "wires": False,
            })
    return suggestions


def suggest_unused_indicators(
    used_indicators: list[str], library_indicators: list[str]
) -> list[dict[str, Any]]:
    """§5.1 #3. Every LIB IndicatorBank / dot input NOT used by this strategy → suggest testing it
    as a gate/filter. Case-insensitive membership; the mutation's ``indicator`` is canonicalized
    (trim+lower) so the fingerprint is stable, the human text keeps the library's original label.
    The engine NEVER invents indicator math — it only proposes an EXISTING library indicator."""
    used = {_canon(i) for i in used_indicators}
    seen: set[str] = set()
    suggestions: list[dict[str, Any]] = []
    for indicator in library_indicators:
        canon = _canon(indicator)
        if not canon or canon in used or canon in seen:
            continue
        seen.add(canon)
        mutation = {"op": "ADD_INDICATOR", "indicator": canon}
        suggestions.append({
            "source": "unused_indicator",
            "suggestion": (
                f"the LIB indicator '{indicator}' is not used by this strategy — test adding it as "
                "a gate/filter (§5.1 existing indicator library)"
            ),
            "mutation": mutation,
            "evidence": {"indicator": indicator, "canonical": canon},
            "caveats": [
                "the engine never invents indicator math; this proposes testing an EXISTING LIB "
                "indicator via a pre-registered ablation (§5.2). This only SUGGESTS."
            ],
            "wires": False,
        })
    return suggestions


def _regime_means(regime_returns: Any) -> dict[str, float]:
    """The mean OOS return per regime label (skips empty labels). ``{}`` for absent/empty input."""
    if not isinstance(regime_returns, dict):
        return {}
    means: dict[str, float] = {}
    for label, returns in regime_returns.items():
        if isinstance(returns, list) and returns:
            try:
                means[str(label)] = statistics.fmean(float(v) for v in returns)
            except (TypeError, ValueError):
                continue
    return means


def _canon(value: Any) -> str:
    return str(value).strip().lower() if value is not None else ""


# --- typed response models ----------------------------------------------------------------------


class SuggestionModel(BaseModel):
    """One gate-candidate suggestion (persisted as a REVIEW_GATE proposal). ``wires`` is always
    False — suggesters SUGGEST; nothing self-arms. ``proposalId`` is the persisted evo_proposals
    row it minted/refreshed."""

    source: str
    suggestion: str
    mutation: dict[str, Any]
    fingerprint: str
    kind: str = _REVIEW_GATE
    evidence: dict[str, Any] | None = None
    caveats: list[str] = []
    wires: bool = False
    proposalId: str | None = None


class SuggestionsResponse(BaseModel):
    """A suggester pass over a campaign's forensics evidence: how many REVIEW_GATE proposals were
    newly minted vs refreshed in place (idempotent on fingerprint), how many suggestions were
    SUPPRESSED because their structure is already graveyarded (§8.3), and the surviving suggestions
    (each carrying its persisted proposalId)."""

    campaignId: str
    generated: int
    refreshed: int
    suppressed: int
    items: list[SuggestionModel]


# --- service ------------------------------------------------------------------------------------


class SuggesterService:
    """Runs the three gate-candidate suggesters over supplied forensics evidence, suppresses any
    suggestion whose structure is graveyarded, and persists the survivors as REVIEW_GATE proposals
    (idempotent on the mutation fingerprint). Collaborators injected (a StructureRepo factory) so
    tests drive it with an in-memory fake. NOTHING self-arms."""

    def __init__(self, repo_factory: Callable[[], Any]) -> None:
        self._repo_factory = repo_factory

    def generate(self, campaign_id: str, body: dict[str, Any]) -> SuggestionsResponse:
        """Run the suggesters and persist REVIEW_GATE proposals for the non-graveyarded survivors.
        Body: ``{candidates?, railStats?, usedIndicators?, libraryIndicators?}`` — each suggester
        runs over its slice (all optional; an absent slice yields no suggestions). 404 unknown
        campaign. Idempotent: one OPEN REVIEW_GATE per (campaign, fingerprint) — an existing one is
        REFRESHED, a graveyarded one is SUPPRESSED, a new one is minted."""
        candidates = body.get("candidates") or []
        rail_stats = body.get("railStats") or []
        used = body.get("usedIndicators") or []
        library = body.get("libraryIndicators") or []

        raw = (
            suggest_regime_gates(candidates)
            + suggest_rejection_forensics(rail_stats)
            + suggest_unused_indicators(used, library)
        )
        # Fingerprint + dedup within this pass (a slice could emit the same structure twice).
        deduped: dict[str, dict[str, Any]] = {}
        for suggestion in raw:
            fingerprint = structure_fingerprint(suggestion["mutation"])
            suggestion["fingerprint"] = fingerprint
            suggestion["kind"] = _REVIEW_GATE
            deduped.setdefault(fingerprint, suggestion)

        repo = self._repo_factory()
        generated = 0
        refreshed = 0
        suppressed = 0
        items: list[SuggestionModel] = []
        try:
            campaign = repo.get_campaign(campaign_id)
            if campaign is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            for fingerprint, suggestion in deduped.items():
                if repo.is_graveyarded(campaign_id, fingerprint):
                    suppressed += 1
                    continue
                evidence = {
                    "kind": _REVIEW_GATE,
                    "source": suggestion["source"],
                    "fingerprint": fingerprint,
                    "mutation": suggestion["mutation"],
                    "suggestion": suggestion["suggestion"],
                    "detail": suggestion.get("evidence"),
                    "caveats": suggestion["caveats"],
                    "family": campaign.get("family"),
                    "wires": False,
                }
                existing = repo.find_open_review_gate(campaign_id, fingerprint)
                if existing is not None:
                    repo.refresh_review_gate(existing["id"], evidence)
                    proposal_id = existing["id"]
                    refreshed += 1
                else:
                    row = repo.insert_review_gate(campaign_id, None, evidence)
                    proposal_id = row["id"]
                    generated += 1
                items.append(SuggestionModel(proposalId=proposal_id, **suggestion))
        finally:
            repo.close()

        return SuggestionsResponse(
            campaignId=campaign_id,
            generated=generated,
            refreshed=refreshed,
            suppressed=suppressed,
            items=items,
        )


def _service(request: Request) -> SuggesterService:
    return request.app.state.suggesters


@router.post("/campaigns/{campaign_id}/suggestions", response_model=SuggestionsResponse)
def generate_suggestions(
    campaign_id: str, body: dict[str, Any], request: Request
) -> SuggestionsResponse:
    """Run the §5.1.2 gate-candidate suggesters (rejection-forensics + regime-gate [emit-only] +
    unused-indicator) over supplied forensics evidence, suppress graveyarded structures, and persist
    the survivors as REVIEW_GATE proposals. Body ``{candidates?, railStats?, usedIndicators?,
    libraryIndicators?}``. 404 unknown campaign. NOTHING self-arms — proposals are inbox records the
    owner reviews; the regime gate wires nothing (regimes frozen, §5.1.2)."""
    return _service(request).generate(campaign_id, body)
