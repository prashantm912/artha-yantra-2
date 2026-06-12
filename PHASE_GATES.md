# PHASE_GATES (A.15 / S5)

One page only: the **current-phase marker**, a **checkbox copy of the current
phase's acceptance criteria**, and a **"deferred" parking list**. The stage
files under `docs/design/` stay the single source of truth — this file is the
S5 **Friday gate ritual** input: walk the checklist **against the running mock
stack** at each phase boundary; an unchecked box extends the phase. No hard
calendar freeze dates; no on-push gate enforcement (the machine-checkable
subset is CI-enforced).

---

## Current phase

**Stage A — Foundations, Part 1 (design-reference sections A.1–A.17),
implemented section-per-commit on `feat/stage-a-foundations`.**

*(Adaptation note: this build walks Stage A by Part 1 section rather than by
Part 2 phase; Part 2 phases 1–8 map onto the same artifacts and will be walked
as the verification pass — see README design-authority note.)*

## Acceptance checklist (Part 1 sections)

- [x] A.9 — secrets hygiene: `.gitignore`, `.env.example`, secrets layout, gitleaks hook blocks a planted secret
- [x] A.12 — PR self-review checklist template
- [x] A.13 — golden-vector fixture-format freeze (`docs/golden-vectors.md`)
- [x] A.14 — `docs/dev-setup.md` tier table + port map (S6 corrections)
- [x] A.15 — this file: marker + checklist + parking list
- [x] A.16 — `docs/LEGAL.md` attribution record (A13) + A6 credentials record
- [x] A.1 — compose topology: pinned/healthchecked/capped timescaledb + redis, dev-tools profile, `ay` CLI, remote-access doc (Q3)
- [x] A.11 — db-backup sidecar: 00:30 IST `pg_dump -Fc` per schema, 14d+8w rotation, ntfy on failure
- [x] A.8 — Flyway one-shot init: admin + 3 per-service lineages from empty volume, idempotent
- [x] A.3 — Maven reactor + `common-web` (core / servlet adapter split)
- [x] A.4 — error-code taxonomy constants (COMMON §8.3 spellings)
- [x] A.5 — `market-calendar` (IST session, NSE 2026 holidays, Tuesday expiries)
- [x] A.6 — ECS JSON logging + `MaskingMessageConverter` (masking unit-tested)
- [x] A.2 — edge-gateway: Argon2id login, Redis sessions, route table, headers, rate limits, hash-password tool
- [x] A.7 — tick pipeline (mock feed → normalizer → Redis) + gateway STOMP WS bridge with 20 Hz conflation
- [x] A.10 — CI: ci-java + ci-migrations, gitleaks step in every workflow
- [ ] A.17 — Stage-A exit-gate checklist recorded below and walked against the running mock stack

## Parking list (deferred)

*(empty — items deferred out of a section land here with their target)*

- Stage B seeds (recorded in the stage file, not deferred work): instruments
  table, candles hypertable, Kite OAuth/AES-GCM token store, live ticker,
  options snapshots (Phases 9–17); Kite minute-depth probe (A3); NSE
  index-constituents CSV source verification (before Phase 22).
