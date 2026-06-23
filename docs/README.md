# docs/ — what lives where

Documentation index for ArthaYantra. Every doc has one of four roles: **authority** (frozen,
governs the build), **active** (current operational / forward-work), **historical** (as-built
record, kept for provenance), **reference** (research the build draws on). The forward-work
authority is the OpenAlgo/React master plan; the design authority is `docs/design/`.

## Authority — frozen (do not change design decisions)
- `design/ARTHAYANTRA_2_COMMON_REFERENCE.md` — app-wide design reference (cited as COMMON §n).
- `design/ARTHAYANTRA_2_STAGE_{A..G}_*.md` — per-stage frozen design (the as-built spec).
- `design/DECISIONS_LOG.md` — dated ADRs / amendments.
- `superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md` — the **forward-work
  authority** (Phases 0–6); its §17 Errata + §18 Gap Addendum override §1–§16 on conflict.

## Active — forward-work plans (`superpowers/plans/`)
*(The master plan above is the authority; these are the open, non-merged plans.)*
- `2026-06-21-data-foundation-milestone.md` — value-verify every OI/data page in History mode on a
  REAL session vs oipulse (Phase-4 gate). GATED on the expired/OI backfill (now loading, #112–#116).
- `2026-06-20-scalper-manual-verification-checklist.md` — scalper verify+confirm panel; backend done,
  the React-UI contract (built once in Phase 4).
- `2026-06-24-data-ops-console-wave.md` — Data Ops Console (B1–B6) operator UI over the backfill;
  **MERGED #121** (deploy after the backfill finishes; kept active until deployed + value-verified).
- `superpowers/plans/archive/` — completed/merged plans (oipulse-parity, stage-g, eod-bhavcopy,
  phase3-scalper-track2, phase3.5 OI-analytics backlog + tier1, open-high per-strike).

## Active — operations & references (top level)
- `dev-setup.md`, `remote-access.md` — local/dev setup + remote access.
- `retention.md`, `runbook-notes.md` — data-retention policy + ops runbook notes.
- `golden-vectors.md` — golden-vector fixture-format freeze (the parity contract).
- `LEGAL.md` — attribution / licence record.
- `strategy-sources.md` — Siva scalper provenance manifest (strategy → source doc + last-ported commit).

## Active — manual-test guides (current convention = `manual-tests/`)
- `manual-tests/phase-0-openalgo-spine.md`, `phase-1-openalgo-routing.md`,
  `phase-2-scalp-indicators.md`, `phase-eod-bhavcopy.md` — per-phase mock-stack walks.
- `manual-tests/phase-3.5-oi-fidelity-and-strategies.md`, `phase-3.5b-open-high-per-strike.md` — scalper OI fidelity + #2 walks.
- `manual-tests/phase-4-expired-instruments-backfill.md` — the §5 expired-instruments backfill walk.
- `manual-tests/phase-4-wave1-*.md`, `phase-4-wave2-depth.md`, `phase-4-wave3-*.md` — the oipulse React page walks (W1/W2/W3).
- `manual-tests/data-foundation-activation.md`, `data-ops-console.md` — data-foundation activation + Data Ops Console walks.
- `manual-tests/archive/manual-testing-stage-{a..f}.md` — the legacy Stage A–G walks (historical).

## Active — consolidated deferred backlog
- `DEFERRED_BACKLOG.md` — single source of truth for all deferred/pending items Phases 0–6
  (last reconciled 2026-06-24); updated each phase; the forward-work authority for the next session.

## Historical — as-built record (`archive/`)
- `archive/ARTHAYANTRA_2_FEATURE_PROPOSALS.md` — the 2026-06-12 owner feature selection; the
  source of the `[FP-N]` tags cited in the design docs (superseded by amendments A7–A12).

## Reference — research the build draws on
- `oipulse-study/` — field study of oipulse.com (per-page design docs + API map + replication
  plan); the reference for the Phase-4 React OI pages. The 20 MB source manual PDF is **not in
  git** (local archive only, same pattern as the Siva decks); the derived `.md` study docs ARE versioned.

## Convention
- New design → a frozen `design/` stage file (rare; the platform is past initial design).
- New forward work → a dated plan in `superpowers/plans/`; move it to `plans/archive/` on merge.
- New manual-test walk → `manual-tests/<slug>.md`.
- Completed/superseded docs → the nearest `archive/` (never deleted — provenance matters).
- Big binaries (PDF decks/manuals) are gitignored; only the derived `.md` is versioned.
