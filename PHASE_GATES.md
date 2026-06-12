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

## S1 gate — Black-76 golden-vector acceptance (Phase 14, walked 2026-06-13)

The formal S1 record (B-10 / B-15): the Phase 15 snapshot job may enable its
computed IV/Greeks columns **only while this suite stays green**; raw-quote
capture is never blocked by it.

- [x] Grid covered: F/K 0.85–1.15, T ∈ {0.5, 2, 7, 30, 90} d, σ 8–60 %, CE+PE —
      **490 committed py_vollib vectors** (offline generator, A4 exception;
      never generated at test runtime).
- [x] Greeks vs reference: relative error ≤ 1e-6 across all vectors; absolute
      ≤ 1e-9 where |reference| < 1e-3 (far-OTM gamma/vega corners included).
- [x] IV solver round-trip: reprice |Black76(IV) − market price| ≤ ₹0.01 for
      every vector carrying ≥ 1 tick of time value (324/490; the 0.5 d/2 d
      far-OTM remainder has no recoverable vol by construction).
- [x] Expiry-day: T from 5 minutes to 0 returns finite greeks via the
      documented `T_MIN` clamp (5 calendar minutes, ACT/365).
- [x] Edge corpus: at/below-discounted-intrinsic and zero-quote inputs → null
      IV + reason code (`BELOW_INTRINSIC` / `ZERO_QUOTE` / `NO_CONVERGENCE`),
      never NaN/Infinity.
- [x] Model is Black-76 **on the forward** (PCP → monthly-futures-LTP →
      `S·e^{rT}` precedence implemented and tested); no Black-Scholes-on-spot
      shortcut anywhere.
- [x] Deterministic across runs (same inputs ⇒ identical `BigDecimal` outputs).
- [ ] Market sanity (informational, non-gating): solved IV within ±2 vol points
      of the NSE chain page for liquid ATM±2 strikes on one live capture —
      pends the first live-mode session with real Kite credentials.

## Parking list (deferred)

**From the Stage-B audit (2026-06-13)** — accepted deviations + deferred work,
each with its target:

- **B-9 binary-frame guard production wiring** — `KiteBinaryFrameParser` is
  fixture-pinned and registry-driven, but javakiteconnect's `KiteTicker`
  exposes no raw-frame hook, so the guard cannot intercept live frames through
  the SDK. Production coverage today = the daily contract canary + the
  fixture-pinned envelope tests + no-tick alerting. Full wiring requires
  replacing the SDK socket with a first-party WS client (revisit when Kite
  changes its wire format or at the Stage-C hardening pass).
- **`instruments.exchange_token` population** — column exists, never written
  (dump record drops it). Wire through `InstrumentRecord` + both dump parsers
  when anything consumes it (nothing in Stages B–D does).
- **Canary result Redis key** — result lands in `kite:contract:check` (JSON) +
  `GET /auth/kite/status`, not embedded in the plain-string
  `kite:session:status` the spec names (would break that key's existing
  readers). Documented deviation.
- **Recorded Kite binary-frame capture** — the mixed-frame fixture is
  synthesized from the documented envelope; commit one real capture during the
  first live session (closes the shared-misreading risk).
- **`candles_1h` IST alignment** — hourly cagg buckets align to UTC hours
  (= :30 IST boundaries). Deciding to re-anchor means dropping/recreating the
  cagg; revisit before the Stage-E chart page consumes 1h.
- **`kite.rateBudget` on system status** — field present, null until
  market-data-service publishes a budget key (limiter metrics exist; producer
  pends Stage C status work).
- **~5k-row mock dump fixture** — CD-14 names ~5k rows; the frozen fixture is
  ~1.1k. The ≤5 s sync budget is asserted at the committed size; regenerate at
  5k only as a deliberate fixture-freeze event.

*(other items deferred out of a section land here with their target)*

- Stage B seeds (recorded in the stage file, not deferred work): instruments
  table, candles hypertable, Kite OAuth/AES-GCM token store, live ticker,
  options snapshots (Phases 9–17); Kite minute-depth probe (A3); NSE
  index-constituents CSV source verification (before Phase 22).
