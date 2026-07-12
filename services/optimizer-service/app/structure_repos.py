"""Postgres access for the EVO E5 structure-experimentation tables (design §5 / §8.3, roadmap §12
items 14-15): ``evo_ablations`` (the pre-registered ablation protocol ledger) and ``evo_graveyard``
(the persistent rejection dedup index) — both V016, backtest schema. Also the thin READS the
ablation + suggester services need for validation (``evo_campaigns`` / ``evo_candidates``) and the
REVIEW_GATE writes onto the EXISTING ``evo_proposals`` table (the ablation IS-only rejection + the
§5.1.2 suggester suggestions land there — kind REVIEW_GATE is already in V011's CHECK).

Kept in its OWN module (not folded into repos.py's EvoRepo) so the E5 slice touches no existing
repo — a clean parallel-build lane. Thin psycopg wrapper, duck-typed like JobsRepo/EvoRepo so tests
substitute an in-memory fake.
"""

from __future__ import annotations

import json
from typing import Any

import psycopg


def _uuid(value: Any) -> str | None:
    """Stringify a psycopg uuid.UUID (or None) for the JSON envelope (mirrors repos._uuid)."""
    return str(value) if value is not None else None


def _ts(value: Any) -> str | None:
    """ISO-8601 a timestamptz (or None) for the envelope (mirrors repos._ts)."""
    return value.isoformat() if value is not None else None


def _jsonb(value: Any) -> str | None:
    """json.dumps a JSONB payload for an INSERT, or None -> SQL NULL (never the literal ``'null'``);
    mirrors repos._jsonb."""
    return json.dumps(value) if value is not None else None


_ABLATION_COLS = (
    "id, campaign_id, parent_candidate_id, mutation, fingerprint, status, verdict, evaluation, "
    "created_by, pre_registered_at, evaluated_at"
)


def _ablation_row(r: tuple) -> dict[str, Any]:
    return {
        "id": str(r[0]),
        "campaignId": str(r[1]),
        "parentCandidateId": _uuid(r[2]),
        "mutation": r[3],
        "fingerprint": r[4],
        "status": r[5],
        "verdict": r[6],
        "evaluation": r[7],
        "createdBy": r[8],
        "preRegisteredAt": _ts(r[9]),
        "evaluatedAt": _ts(r[10]),
    }


_GRAVEYARD_COLS = (
    "id, campaign_id, ablation_id, fingerprint, mutation, verdict, evidence, buried_at"
)


def _graveyard_row(r: tuple) -> dict[str, Any]:
    return {
        "id": str(r[0]),
        "campaignId": str(r[1]),
        "ablationId": _uuid(r[2]),
        "fingerprint": r[3],
        "mutation": r[4],
        "verdict": r[5],
        "evidence": r[6],
        "buriedAt": _ts(r[7]),
    }


_PROPOSAL_COLS = (
    "id, campaign_id, candidate_id, kind, evidence, status, actor, decided_at, expires_at, "
    "created_at"
)


def _proposal_row(r: tuple) -> dict[str, Any]:
    return {
        "id": str(r[0]),
        "campaignId": str(r[1]),
        "candidateId": _uuid(r[2]),
        "kind": r[3],
        "evidence": r[4],
        "status": r[5],
        "actor": r[6],
        "decidedAt": _ts(r[7]),
        "expiresAt": _ts(r[8]),
        "createdAt": _ts(r[9]),
    }


class StructureRepo:
    """Read/write access to the E5 structure-experimentation model. The optimizer is the sole evo
    writer + single-process, so the read-then-write idempotency checks below have no competing
    writer; the DB UNIQUE indexes (V016) are the hard backstop."""

    def __init__(self, conn: psycopg.Connection) -> None:
        self._conn = conn

    def close(self) -> None:
        self._conn.close()

    # --- validation reads (evo_campaigns / evo_candidates) --------------------------------------

    def get_campaign(self, campaign_id: str) -> dict[str, Any] | None:
        """A campaign's id/family (the 404 guard + the family stamped on suggestions). None ->
        the service maps it to a 404."""
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT id, family, evidence_policy FROM evo_campaigns WHERE id=%s", (campaign_id,)
            )
            row = cur.fetchone()
        if row is None:
            return None
        return {"id": str(row[0]), "family": row[1], "evidencePolicy": row[2]}

    def candidate_exists(self, candidate_id: str) -> bool:
        """Whether a candidate id resolves (the parent-candidate 404 guard on pre-registration)."""
        with self._conn.cursor() as cur:
            cur.execute("SELECT 1 FROM evo_candidates WHERE id=%s", (candidate_id,))
            return cur.fetchone() is not None

    # --- evo_ablations --------------------------------------------------------------------------

    def find_ablation_by_key(
        self, campaign_id: str, parent_candidate_id: str | None, fingerprint: str
    ) -> dict[str, Any] | None:
        """The existing ablation for (campaign, parent, fingerprint), or None — the pre-registration
        idempotency check. The uq_evo_ablations_campaign_parent_fp index (V016, UNIQUE NULLS NOT
        DISTINCT) is the hard backstop INCLUDING NULL-parent campaign-level hypotheses; this read
        matches its semantics with IS NOT DISTINCT FROM."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_ABLATION_COLS} FROM evo_ablations "
                "WHERE campaign_id=%s AND parent_candidate_id IS NOT DISTINCT FROM %s "
                "AND fingerprint=%s",
                (campaign_id, parent_candidate_id, fingerprint),
            )
            row = cur.fetchone()
        return _ablation_row(row) if row is not None else None

    def insert_ablation(
        self,
        campaign_id: str,
        parent_candidate_id: str | None,
        mutation: dict[str, Any],
        fingerprint: str,
        created_by: str | None,
    ) -> dict[str, Any]:
        """Pre-register an ablation (status PRE_REGISTERED, pre_registered_at=now() from the DDL —
        the immutable §3.1 anti-snooping stamp). Returns the read-envelope row."""
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO evo_ablations "
                "(campaign_id, parent_candidate_id, mutation, fingerprint, created_by) "
                "VALUES (%s, %s, %s::jsonb, %s, %s) "
                f"RETURNING {_ABLATION_COLS}",
                (campaign_id, parent_candidate_id, json.dumps(mutation), fingerprint, created_by),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _ablation_row(row)

    def get_ablation(self, ablation_id: str) -> dict[str, Any] | None:
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_ABLATION_COLS} FROM evo_ablations WHERE id=%s", (ablation_id,)
            )
            row = cur.fetchone()
        return _ablation_row(row) if row is not None else None

    def list_ablations(
        self, campaign_id: str, limit: int, offset: int
    ) -> list[dict[str, Any]]:
        """A campaign's ablations, newest pre-registration first."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_ABLATION_COLS} FROM evo_ablations "
                "WHERE campaign_id=%s ORDER BY pre_registered_at DESC LIMIT %s OFFSET %s",
                (campaign_id, limit, offset),
            )
            rows = cur.fetchall()
        return [_ablation_row(r) for r in rows]

    def update_ablation_verdict(
        self, ablation_id: str, verdict: str, evaluation: dict[str, Any] | None
    ) -> dict[str, Any] | None:
        """Stamp the paired-evaluation verdict + evaluation JSONB, PRE_REGISTERED -> EVALUATED
        (evaluated_at=now()). Guarded to PRE_REGISTERED — a second evaluate matches nothing and
        returns None (the service maps it to a 409), so a verdict is written exactly once."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_ablations SET status='EVALUATED', verdict=%s, evaluation=%s::jsonb, "
                "evaluated_at=now() WHERE id=%s AND status='PRE_REGISTERED' "
                f"RETURNING {_ABLATION_COLS}",
                (verdict, _jsonb(evaluation), ablation_id),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _ablation_row(row) if row is not None else None

    # --- evo_graveyard --------------------------------------------------------------------------

    def is_graveyarded(self, campaign_id: str, fingerprint: str) -> bool:
        """Whether a structure fingerprint is already buried in this campaign — the suggesters'
        "never re-proposed silently" suppression check (§8.3)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM evo_graveyard WHERE campaign_id=%s AND fingerprint=%s",
                (campaign_id, fingerprint),
            )
            return cur.fetchone() is not None

    def bury(
        self,
        campaign_id: str,
        ablation_id: str | None,
        fingerprint: str,
        mutation: dict[str, Any],
        verdict: str,
        evidence: dict[str, Any] | None,
    ) -> bool:
        """Bury a rejected structure — idempotent via ON CONFLICT (campaign_id, fingerprint) DO
        NOTHING (the uq_evo_graveyard_campaign_fp index). Returns True iff a NEW grave was written
        (a re-bury of an already-buried fingerprint is a no-op)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO evo_graveyard "
                "(campaign_id, ablation_id, fingerprint, mutation, verdict, evidence) "
                "VALUES (%s, %s, %s, %s::jsonb, %s, %s::jsonb) "
                "ON CONFLICT (campaign_id, fingerprint) DO NOTHING RETURNING id",
                (campaign_id, ablation_id, fingerprint, json.dumps(mutation), verdict,
                 _jsonb(evidence)),
            )
            row = cur.fetchone()
        self._conn.commit()
        return row is not None

    def list_graveyard(
        self, campaign_id: str, limit: int, offset: int
    ) -> list[dict[str, Any]]:
        """A campaign's buried structures, newest burial first — the §8.3 graveyard read surface."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_GRAVEYARD_COLS} FROM evo_graveyard "
                "WHERE campaign_id=%s ORDER BY buried_at DESC LIMIT %s OFFSET %s",
                (campaign_id, limit, offset),
            )
            rows = cur.fetchall()
        return [_graveyard_row(r) for r in rows]

    # --- REVIEW_GATE proposals on the EXISTING evo_proposals table ------------------------------

    def find_open_review_gate(
        self, campaign_id: str, fingerprint: str
    ) -> dict[str, Any] | None:
        """The single OPEN (PENDING) REVIEW_GATE proposal for (campaign, fingerprint), or None — the
        campaign-level suggestion idempotency key (candidate_id is NULL for cohort-level
        suggestions, so find_open_proposal's candidate key does not apply; the fingerprint on the
        evidence card keys it instead). Newest first as the tiebreak."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_PROPOSAL_COLS} FROM evo_proposals "
                "WHERE campaign_id=%s AND kind='REVIEW_GATE' AND status='PENDING' "
                "AND evidence->>'fingerprint' = %s ORDER BY created_at DESC LIMIT 1",
                (campaign_id, fingerprint),
            )
            row = cur.fetchone()
        return _proposal_row(row) if row is not None else None

    def insert_review_gate(
        self,
        campaign_id: str,
        candidate_id: str | None,
        evidence: dict[str, Any] | None,
        expiry_days: int = 7,
    ) -> dict[str, Any]:
        """Insert a PENDING REVIEW_GATE proposal (§8.2 default 7-day expiry, DB-clock-anchored).
        ``candidate_id`` is the parent for an IS-only ablation rejection, NULL for a cohort-level
        suggester suggestion (V011: candidate_id NULL for campaign-level REVIEW_GATE)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO evo_proposals "
                "(campaign_id, candidate_id, kind, evidence, expires_at) "
                "VALUES (%s, %s, 'REVIEW_GATE', %s::jsonb, now() + (%s * interval '1 day')) "
                f"RETURNING {_PROPOSAL_COLS}",
                (campaign_id, candidate_id, _jsonb(evidence), expiry_days),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _proposal_row(row)

    def refresh_review_gate(
        self, proposal_id: str, evidence: dict[str, Any] | None
    ) -> dict[str, Any] | None:
        """Refresh an OPEN REVIEW_GATE proposal's evidence in place (idempotent regeneration — no
        duplicate row). Guarded to PENDING so a decided row is never silently overwritten."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_proposals SET evidence=%s::jsonb "
                f"WHERE id=%s AND status='PENDING' RETURNING {_PROPOSAL_COLS}",
                (_jsonb(evidence), proposal_id),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _proposal_row(row) if row is not None else None
