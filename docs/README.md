# docs/ — what lives where

Documentation index for ArthaYantra. Every doc has one of four roles: **authority** (frozen,
governs the build), **active** (current operational / forward-work), **historical** (as-built
record, kept for provenance), **reference** (research the build draws on). The forward-work
authority is the OpenAlgo/React master plan; the design authority is `docs/design/`.

## Authority — frozen (do not change design decisions)
- `design/ARTHAYANTRA_2_COMMON_REFERENCE.md` — app-wide design reference (cited as COMMON §n).
- `design/ARTHAYANTRA_2_STAGE_{A..G}_*.md` — per-stage frozen design (the as-built spec).
- `design/DECISIONS_LOG.md` — dated ADRs / amendments.
- `adr/0001`…`adr/0003` — numbered architecture decision records (broker coupling / Upstox side-channel /
  scalper signal-strike-option three-way decoupling); cited as `ADR-000N` across plans + manual-tests.
- `superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md` — the **forward-work
  authority** (Phases 0–6); its §17 Errata + §18 Gap Addendum override §1–§16 on conflict.

## Active — forward-work plans (`superpowers/plans/`)
*(The master plan above is the authority; these are the only open plans after the 2026-07-02 archive sweep.)*
- `2026-07-02-remaining-items.md` — the **single forward ledger of everything still open** across the
  whole platform: 1 net-new build (Phase-5 Minervini screener), the owner-gated items, next-session
  verifies, scheduled maintenance (CD-2 calendar refresh), the deferred-by-design list and the
  consolidated WON'T-DO record. **Read this FIRST when picking the next thing to do.** Supersedes the
  archived `2026-06-30-remaining-build-inventory.md`.
- `2026-06-30-live-signal-analysis-runbook.md` — the standing procedure for analysing ~1 month of
  live-paper scalper trades into the E9 band + per-scalper keep/cut/tune (counterfactual replay on real
  captured premium). Runs when the owner has gathered the data.
- `superpowers/plans/archive/` — everything completed / decided / superseded, each with an ARCHIVED
  status banner. 2026-07-02 sweep added: the **scalper-to-100 roadmap + `2026-06-27-backlog/` design
  streams** (build complete, #274–#404 arc), **remaining-build-inventory** (superseded), the two
  **2026-07-02 audits + findings register** (fix queues fully closed — UI #440–#475, codebase
  #407–#434), **data-foundation-milestone** (value-verify PASSED), **data-ops-console** (deployed),
  **upstox-live-migration** (W-U4 declined — stay Kite), **e8-e12-numbers** (epics closed),
  **pe-mirror** (executed STEP #381/#382), **FU1/FU2 follow-ups + audit summary** (built/declined).
  Earlier archive contents: oipulse-parity, stage-g, eod-bhavcopy, e1-market-movers-stockfut,
  phase3-scalper-track2, phase3.5 backlogs, open-high per-strike, scalper-manual-verification-checklist,
  frontend-revamp (#158–#177), scalper-tunable-infra (2b #220–#230), w3-engine-drift-impl (#251–#256),
  oip-ai-probability-spec (#255/#262).

## Active — operations & references (top level)
- `dev-setup.md`, `remote-access.md` — local/dev setup + remote access.
- `retention.md`, `runbook-notes.md` — data-retention policy + ops runbook notes.
- `golden-vectors.md` — golden-vector fixture-format freeze (the parity contract).
- `symbol-normalization.md` — how instrument/contract naming is reconciled across Kite / Upstox / OpenAlgo
  (one canonical `(exchange, tradingsymbol)` key; per-source edge mappers; tuple-match + canaries).
- `LEGAL.md` — attribution / licence record.
- `strategy-sources.md` — Siva scalper provenance manifest (strategy → source doc + last-ported commit).

## Active — manual-test guides (current convention = `manual-tests/`)
- `manual-tests/phase-0-openalgo-spine.md`, `phase-1-openalgo-routing.md`,
  `phase-2-scalp-indicators.md`, `phase-eod-bhavcopy.md` — per-phase mock-stack walks.
- `manual-tests/phase-3.5-oi-fidelity-and-strategies.md`, `phase-3.5b-open-high-per-strike.md` — scalper OI fidelity + #2 walks.
- `manual-tests/phase-4-expired-instruments-backfill.md` — the §5 expired-instruments backfill walk.
- `manual-tests/phase-4-wave1-*.md`, `phase-4-wave2-depth.md`, `phase-4-wave3-*.md` — the oipulse React page walks (W1/W2/W3).
- `manual-tests/data-foundation-activation.md`, `data-ops-console.md` — data-foundation activation + Data Ops Console walks.
- `manual-tests/2b-e1-continuous-future-backfill.md`, `2b-e2b-strike-reference.md`, `2b-1-scalper-variants.md`
  — the 2b scalper-infra walks (continuous-future backfill, strike-reference spot, the 36-variant functional
  verify with the 36/36 backtest results).
- `manual-tests/archive/manual-testing-stage-{a..f}.md` — the legacy Stage A–G walks (historical).

## Active — consolidated deferred backlog
- `DEFERRED_BACKLOG.md` — the per-phase provenance ledger for deferred/pending items Phases 0–6
  (last reconciled 2026-07-02). The *current* open list lives in
  `superpowers/plans/2026-07-02-remaining-items.md`; this file keeps the dated history.

## Historical — as-built record (`archive/`)
- `archive/ARTHAYANTRA_2_FEATURE_PROPOSALS.md` — the 2026-06-12 owner feature selection; the
  source of the `[FP-N]` tags cited in the design docs (superseded by amendments A7–A12).
- `strategy-audit/` — **CLOSED** (2026-06-28): the bloated-consolidated scalper automation audit
  (570 rules / 424 gaps) + the S24 ratification chain (COMPARISON / RATIFICATION-PACK / GAP-DISPOSITION /
  W4 triage+impl). Superseded by the debloated operative doc + the scalper-to-100 roadmap; kept for the
  decision trail.

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
