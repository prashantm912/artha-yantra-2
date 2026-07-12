"""In-memory double for StructureRepo (EVO E5) — mirrors the camelCase envelopes the real repo
emits so the ablation + suggester services map them identically. Kept in its OWN test module (not
folded into tests/fakes.py) so the E5 slice touches no shared test file — a clean parallel lane."""

from __future__ import annotations

from typing import Any


class FakeStructureRepo:
    """In-memory ``evo_ablations`` + ``evo_graveyard`` + REVIEW_GATE ``evo_proposals``, plus the
    ``evo_campaigns`` / ``evo_candidates`` existence reads the services validate against."""

    def __init__(
        self,
        campaigns: list[dict[str, Any]] | None = None,
        candidate_ids: set[str] | None = None,
    ) -> None:
        self.campaigns = campaigns or []
        self.candidate_ids = candidate_ids or set()
        self.ablations: list[dict[str, Any]] = []
        self.graveyard: list[dict[str, Any]] = []
        self.proposals: list[dict[str, Any]] = []
        self._seq = 0

    # --- validation reads ------------------------------------------------------------------------

    def get_campaign(self, campaign_id: str) -> dict[str, Any] | None:
        return next((c for c in self.campaigns if c["id"] == campaign_id), None)

    def candidate_exists(self, candidate_id: str) -> bool:
        return candidate_id in self.candidate_ids

    # --- evo_ablations ---------------------------------------------------------------------------

    def find_ablation_by_key(
        self, campaign_id: str, parent_candidate_id: str | None, fingerprint: str
    ) -> dict[str, Any] | None:
        return next(
            (
                a for a in self.ablations
                if a["campaignId"] == campaign_id
                and a["parentCandidateId"] == parent_candidate_id
                and a["fingerprint"] == fingerprint
            ),
            None,
        )

    def insert_ablation(
        self,
        campaign_id: str,
        parent_candidate_id: str | None,
        mutation: dict[str, Any],
        fingerprint: str,
        created_by: str | None,
    ) -> dict[str, Any]:
        self._seq += 1
        row = {
            "id": f"abl-{self._seq}", "campaignId": campaign_id,
            "parentCandidateId": parent_candidate_id, "mutation": mutation,
            "fingerprint": fingerprint, "status": "PRE_REGISTERED", "verdict": None,
            "evaluation": None, "createdBy": created_by,
            "preRegisteredAt": "2026-07-12T00:00:00+00:00", "evaluatedAt": None,
        }
        self.ablations.append(row)
        return dict(row)

    def get_ablation(self, ablation_id: str) -> dict[str, Any] | None:
        found = next((a for a in self.ablations if a["id"] == ablation_id), None)
        return dict(found) if found is not None else None

    def list_ablations(
        self, campaign_id: str, limit: int, offset: int
    ) -> list[dict[str, Any]]:
        rows = [a for a in self.ablations if a["campaignId"] == campaign_id]
        rows.reverse()  # newest pre-registration first
        return [dict(r) for r in rows[offset:offset + limit]]

    def update_ablation_verdict(
        self, ablation_id: str, verdict: str, evaluation: dict[str, Any] | None
    ) -> dict[str, Any] | None:
        for a in self.ablations:
            if a["id"] == ablation_id and a["status"] == "PRE_REGISTERED":
                a.update(status="EVALUATED", verdict=verdict, evaluation=evaluation,
                         evaluatedAt="2026-07-12T01:00:00+00:00")
                return dict(a)
        return None  # unknown or already evaluated → the service maps to 409

    # --- evo_graveyard ---------------------------------------------------------------------------

    def is_graveyarded(self, campaign_id: str, fingerprint: str) -> bool:
        return any(
            g["campaignId"] == campaign_id and g["fingerprint"] == fingerprint
            for g in self.graveyard
        )

    def bury(
        self,
        campaign_id: str,
        ablation_id: str | None,
        fingerprint: str,
        mutation: dict[str, Any],
        verdict: str,
        evidence: dict[str, Any] | None,
    ) -> bool:
        if self.is_graveyarded(campaign_id, fingerprint):
            return False  # ON CONFLICT DO NOTHING — already buried
        self._seq += 1
        self.graveyard.append({
            "id": f"grave-{self._seq}", "campaignId": campaign_id, "ablationId": ablation_id,
            "fingerprint": fingerprint, "mutation": mutation, "verdict": verdict,
            "evidence": evidence, "buriedAt": "2026-07-12T01:00:00+00:00",
        })
        return True

    def list_graveyard(
        self, campaign_id: str, limit: int, offset: int
    ) -> list[dict[str, Any]]:
        rows = [g for g in self.graveyard if g["campaignId"] == campaign_id]
        rows.reverse()
        return [dict(r) for r in rows[offset:offset + limit]]

    # --- REVIEW_GATE proposals -------------------------------------------------------------------

    def find_open_review_gate(
        self, campaign_id: str, fingerprint: str
    ) -> dict[str, Any] | None:
        matches = [
            p for p in self.proposals
            if p["campaignId"] == campaign_id and p["kind"] == "REVIEW_GATE"
            and p["status"] == "PENDING"
            and (p.get("evidence") or {}).get("fingerprint") == fingerprint
        ]
        matches.sort(key=lambda p: p["_seq"], reverse=True)
        return _strip_seq(matches[0]) if matches else None

    def insert_review_gate(
        self,
        campaign_id: str,
        candidate_id: str | None,
        evidence: dict[str, Any] | None,
        expiry_days: int = 7,
    ) -> dict[str, Any]:
        self._seq += 1
        row = {
            "_seq": self._seq, "id": f"prop-{self._seq}", "campaignId": campaign_id,
            "candidateId": candidate_id, "kind": "REVIEW_GATE", "evidence": evidence,
            "status": "PENDING", "actor": None, "decidedAt": None,
            "expiresAt": "2026-07-19T00:00:00+00:00", "createdAt": "2026-07-12T00:00:00+00:00",
        }
        self.proposals.append(row)
        return _strip_seq(row)

    def refresh_review_gate(
        self, proposal_id: str, evidence: dict[str, Any] | None
    ) -> dict[str, Any] | None:
        for p in self.proposals:
            if p["id"] == proposal_id and p["status"] == "PENDING":
                p["evidence"] = evidence
                return _strip_seq(p)
        return None

    def close(self) -> None:
        pass


def _strip_seq(row: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in row.items() if k != "_seq"}
