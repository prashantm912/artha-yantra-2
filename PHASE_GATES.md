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

**Stage B — Market-data spine (Phases 9, 9A, 10–15, 15A, 15B, 16, 16A, 17) —
IN PROGRESS on `feat/stage-b-market-data-spine`, phase-per-commit.
(Stage A completed 2026-06-12; its exit-gate record is below.)**

*(How Stage A was walked: Part 1 sections A.1–A.17 implemented
section-per-commit; Part 2 Phases 1–8 then audited one-by-one against their
Deliverables/Tests/Acceptance — Phases 1, 2, 3, 5, 6, 7 + the COMMON
conventions sweep came back clean; Phase 4 was missing the lint pre-commit
hook entry (fixed) and Phase 8 had a real `GATEWAY_WS_FLUSH_HZ` binding bug
plus two missing IT cases (fixed, tested); Part 3 exit gate walked against
the running mock stack, below. CI red→green iterations: mvnw exec bit,
gitleaks-action→pinned CLI, drift-check pending-vs-checksum semantics.)*

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
- [x] A.17 — Stage-A exit-gate checklist recorded below and walked against the running mock stack

---

## Stage-A exit gate (plan §15.2 Phase-0 row — walked 2026-06-12)

**Deliverables present:**

- [x] Monorepo layout (COMMON §10.1); process docs committed (`README.md`, `PHASE_GATES.md`, `docs/golden-vectors.md`, `docs/remote-access.md`, `docs/dev-setup.md`, `docs/LEGAL.md`, PR template, 8-file design set under `docs/design/`).
- [x] Compose: timescaledb, redis, flyway-init, db-backup, edge-gateway, market-data-service + dev-tools profile — all with `mem_limit` + healthchecks + pinned tags + loopback binds. *(Remaining D7 app containers land in later stages.)*
- [x] Flyway 11 init job: 3 schemas + 3 roles + the single backtest→marketdata read-only grant from an empty volume (admin first), idempotent — `ay reset-db` twice green.
- [x] GitHub Actions `ci-java.yml` + `ci-migrations.yml` committed; gitleaks in every workflow; both Dockerfiles build locally; JaCoCo ≥60 % gates pass locally. *(First remote run + branch protection pend the first push — owner clicks protection in GitHub settings.)*
- [x] edge-gateway: Argon2id (m=19456/t=2/p=1) login + Spring Session Redis + route table + headers + 5/min login limit + 50 req/s valve.
- [x] Mock Kite feed (D13) publishing deterministic ticks; gateway WS bridge relays over STOMP-on-native-WS with 20 Hz conflation.
- [x] Review additions: PHASE_GATES (S5+P4), dev-setup tier table with S6 ports, LEGAL attribution record [A13], Tailscale-first remote-access doc (Q3). *(Day-zero rotation superseded by A6 — fresh keys, no tripwire.)*

**Acceptance (walked against the running stack, 2026-06-12):**

- [x] `ay up` green from a clean restart with **no Kite credentials** (9 healthy containers + flyway-init exit 0).
- [x] Login at `127.0.0.1:8080` works — 204 + HttpOnly SameSite=Strict cookie; authenticated session probe.
- [x] Service images build (edge-gateway, market-data-service) — CI image-build matrix mirrors the same Dockerfiles. *(CI runs on first push.)*
- [x] Mock ticks visible on Redis `ticks.*` (string decimals, `+05:30`, monotonic seq, deterministic seed) and **end-to-end via `e2e/tools/stomp-probe.mjs`** (10 frames).
- [x] Tier 2 verbatim: host-run market-data-service (`dev,mock`) connected to compose Redis published on loopback by `ay up dev-tools` — actuator health UP.

**Closed by the Part 2 verification pass (2026-06-12):** branch pushed; PR
[#1](https://github.com/prashantm912/artha-yantra-2/pull/1) opened; CI runs on
the PR; drift-check red path proven locally (edited applied migration →
checksum mismatch, exit 1); restore drill executed once via `ay restore`.

**Still owner-clickable:** branch protection on `main` (GitHub → Settings →
Branches); optional OneDrive sync of `./backups`; quarterly restore-drill
recurrence.

**Stage B parking list (seeds for the next branch):** instruments table +
candles hypertable + Kite OAuth/AES-GCM token store + live ticker + options
snapshots (Phases 9–17); Kite minute-depth probe (A3); NSE index-constituents
CSV source verification (before Phase 22); `tools/hash-password` may gain a
compose-escaped output mode (quality-of-life).

## Parking list (deferred)

*(empty — items deferred out of a section land here with their target)*

- Stage B seeds (recorded in the stage file, not deferred work): instruments
  table, candles hypertable, Kite OAuth/AES-GCM token store, live ticker,
  options snapshots (Phases 9–17); Kite minute-depth probe (A3); NSE
  index-constituents CSV source verification (before Phase 22).
