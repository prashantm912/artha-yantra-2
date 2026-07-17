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
*(The master plan above is the authority; the docs below are the current open forward-work plans.)*
- `2026-07-02-remaining-items.md` — the **single forward ledger of everything still open** across the
  whole platform: the current build frontier is the later increments of the two design programs (EVO
  E5/E6, INT I3-FE/I4) plus APP Phase 4 / FID Phase-2–4 remainder (§0 groups C/D), then the owner-gated
  items, next-session verifies, scheduled maintenance (CD-2 calendar refresh), the deferred-by-design
  list and the consolidated WON'T-DO record. **Read this FIRST when picking the next thing to do.**
  Supersedes the archived `2026-06-30-remaining-build-inventory.md`.
- `2026-06-30-live-signal-analysis-runbook.md` — the standing procedure for analysing ~1 month of
  live-paper scalper trades into the E9 band + per-scalper keep/cut/tune (counterfactual replay on real
  captured premium). Runs when the owner has gathered the data.
- `2026-07-03-10x-value-roadmap.md` — the 10x-value roadmap; remaining sub-items are data/owner-gated
  (F2 rollup, F3.2–.5 dots, F5 host).
- `2026-07-03-always-on-host-brief.md` — the F5 hardware decision brief (owner decision pending).
- `2026-07-10-strategy-evolution-engine-design.md` + `2026-07-10-intelligence-layer-design.md` — the
  two design authorities (slow research loop / fast decision loop), each consuming one 2026-07-10 audit
  as its fixed input: `docs/audits/2026-07-10-research-fidelity-audit.md` (evolution) and
  `docs/audits/2026-07-10-app-platform-audit.md` (intelligence). The shared E0/I1 build prerequisites
  shipped 2026-07-11 (#683–#717) and **both engines are now BUILT + LIVE — evolution
  through E6 (#792 / #793 / #787), intelligence through I3-FE (#789) (2026-07-11/12 waves, #720–#782)**; both audits carry §-appended
  fix-logs and the designs each carry a dated Status block. The remaining frontier (owner-gated **EVO E6-autonomy arming + INT I4**) is the ledger's §0 group C. Build gating: each doc's §13 + Status block + the ledger rows
  `evolution-engine` / `intelligence-layer`.
- `2026-07-12-minute-research-system-design.md` — a 14-feature minute-research gap analysis; **DORMANT /
  parked** (10 of 14 already built; excluded from the ledger §0 queue — the owner activates it once the
  current queue empties).
- `2026-07-16-engine-liveness-detector.md` — the F10 Part B engine-liveness detector design. Its **§3
  mechanism was INVALIDATED live 2026-07-17** (the "REST/JDBC can see bar flow" premise is false); §1/§2
  stay authoritative, and reviving Part B is an owner decision (a market-data bounded-recent-bar contract
  vs a cross-schema grant + ADR). Carries a STOP block up top.
- `superpowers/plans/archive/` — everything completed / decided / superseded, each with an ARCHIVED
  status banner. **2026-07-10 sweep added:** the full **Minervini SEPA set** (implementation plan +
  build-log + phase6/partial-close build-specs — all phases shipped+live #524–#563; only the
  forward-paper watch remains, tracked in the ledger), **manas-arora-and-book-separation-plan**
  (fully shipped #566–#575), **swing-doctrine-port-build-spec** (M31 shipped #655).
  **2026-07-18 sweep added:** **codex-review-harness-spike** (the Codex skill suite it specced shipped +
  is the live builder lane; authority = the `.claude/skills/codex*` skills + the `codex-builder-lane`
  memory topic).
  2026-07-02 sweep added: the **scalper-to-100 roadmap + `2026-06-27-backlog/` design
  streams** (build complete, #274–#404 arc), **remaining-build-inventory** (superseded), the two
  **2026-07-02 audits + findings register** (fix queues fully closed — UI #440–#475, codebase
  #407–#434), **data-foundation-milestone** (value-verify PASSED), **data-ops-console** (deployed),
  **upstox-live-migration** (W-U4 declined — stay Kite), **e8-e12-numbers** (epics closed),
  **pe-mirror** (executed STEP #381/#382), **FU1/FU2 follow-ups + audit summary** (built/declined).
  Earlier archive contents: oipulse-parity, stage-g, eod-bhavcopy, e1-market-movers-stockfut,
  phase3-scalper-track2, phase3.5 backlogs, open-high per-strike, scalper-manual-verification-checklist,
  frontend-revamp (#158–#177), scalper-tunable-infra (2b #220–#230), w3-engine-drift-impl (#251–#256),
  oip-ai-probability-spec (#255/#262).

## Active — per-session signal analysis (`signal-analysis/`)
- `signal-analysis/README.md` — the standing method + cadence for mining `strategy.signal_rejections`
  after every market session (open-ended dimension list, SQL toolkit, live in-session data-health +
  counterfactual procedure, data-model improvement backlog).
- `signal-analysis/YYYY-MM-DD-session-findings.md` — one immutable findings file per session (named by
  DATA date); periodic multi-session rollups consolidate them into tuning passes. First:
  `2026-07-02-session-findings.md` (volume-floor unpassable; composite capped ~0.765 by 5 dead dots).

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
