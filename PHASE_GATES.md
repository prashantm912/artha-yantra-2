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

**Stage C — Strategy engine MVP (Phases 18–27) — IMPLEMENTATION + AUDIT COMPLETE
on `feat/stage-c-strategy-engine`, phase-per-commit; exit gate walked 2026-06-13
(record below); pending push/PR/CI/merge. Stage B merged to main 2026-06-13 via
PR #2; Stage A completed 2026-06-12. Earlier exit-gate records are below.**

*(How Stage C was walked: Phases 18–27 implemented phase-per-commit with unit +
Testcontainers ITs + Vitest, then a full-stack Playwright E2E that drove the live
MVP through a real browser for the first time — it exposed and fixed 9
integration gaps unit/IT coverage could not reach (SPA auth-gating made login
unreachable; the STOMP `Sec-WebSocket-Protocol` echo missing failed every
browser WS handshake; a strict CSP blocked PrimeNG inline styles; the auth probe
trusted any 200 and admitted anonymous users; a `+05:30` candle warm-up query
encoding 500'd, leaving the engine cold). Then an 18-agent adversarial
spec-vs-impl audit (8 reviewers, independent verification of every finding)
confirmed 1 CRITICAL + 8 MAJOR gaps — headline: `candles.1m.*` conflated with
latest-value-wins (a dropped bar = permanent series gap + a possibly-skipped
exit), `.nan`/`.inf` 500s, duplicate YAML keys defeating the checksum,
score-breakdown decimals as rounding JSON numbers, registry filter-after-
pagination, phantom ARCHIVE audit rows, and a wall-clock `generated_at` breaking
live↔replay determinism — all fixed and regression-tested in the audit commit.
strategy-schema + strategy-engine + both services green; Playwright E2E 7/7.)*

---

## Stage-C exit gate (plan §15.2 Phase-2 row — the MVP gate — walked 2026-06-13 against the running mock stack)

- [x] **Golden-vector tests pin determinism** — same YAML + same candles ⇒
      identical signals/scores/breakdowns. `GoldenDeterminismTest` 5/5 byte-
      matches the frozen fixtures across two runs; the `ScoreBreakdown` writer is
      byte-stable (now exact-decimal strings); the replay half lands Stage D. `[Phase 23]`
- [x] **Publishing a YAML strategy → a live signal pushed over gateway STOMP,
      visible in the browser.** The Playwright MVP test publishes a strategy via
      the API and sees a live `RELIANCE`/`ENTRY` row stream onto `/signals` with
      its reasoning breakdown — the MVP statement, driven end-to-end through a
      real browser. `[Phases 23+26]`
- [x] `strategy-schema/v1` **complete + frozen** — 31-fixture corpus green;
      `slippage_bps`, `fees{}`, `objective.fold_aggregation`, `walk_forward`,
      `scoring.{optional_min_score, optional_gate_margin}` and the A7 additions
      (`1w`, `risk.session.{pre_close_at, fill_timing, exit_intrabar}`, indicator
      `instrument` override, `universe.mode: futures_of_underlying` + `futures{}`)
      all present + validated; indicator-name enum stays advisory (Q2). The
      loader now rejects non-finite scalars and duplicate keys (audit). `[Phase 18]`
- [x] strategy-engine JAR — `IndicatorVectorTest` 19/19 (ta4j matches the
      committed reference vectors exactly), `CompositeScorerTest` 9/9 (the
      normative A1 composite + optional-activation truth table),
      `BreakdownContractTest` 5/5 (byte-stable `ScoreBreakdown`); JaCoCo BRANCH
      ≥ 70 %. `[Phases 19–20]`
- [x] Registry — immutable JSONB versions + SHA-256; full
      draft→published→archived with publish/rollback/diff/validate; every
      mutating call writes an audit row (append-only BY GRANT);
      `index_constituents`-universe publish guard (422
      `STRATEGY_UNIVERSE_UNSUPPORTED`). `RegistryLifecycleIntegrationTest` 12/12,
      incl. the audit's filter-then-paginate + archive-idempotency fixes. `[Phase 21]`
- [x] `marketdata.index_constituents` — append-only with point-in-time REST
      resolution (latest-on-or-before, audit-confirmed); mock fixture path green;
      live NSE fetcher gated on source verification; no cross-schema FK;
      survivorship-bias caveat documented. `[Phase 22]`
- [x] OpenAPI 3.1 specs for the three running services committed and **diff-
      gated** in CI; each `ContractCaptureTest` green; generated TS client
      compiles under `tsc --strict` (ci-contracts). `[Phase 24]`
- [x] Angular 21 SPA (zoneless, signals-first) served **through the gateway,
      same origin, zero CORS**; login round-trip works; initial bundle **457 KB
      raw / 109 KB transfer** (≤ 500 KB budget enforced); no Zone.js, no
      hardcoded `localhost`. SPA-shell auth + CSP relaxation fixed (E2E). `[Phases 25–26]`
- [x] `WsClientService` reconnects with backoff + jitter and re-syncs the REST
      snapshot; `/signals` renders the reasoning breakdown obeying
      `composite = Σ contributions / weightDenominator`. STOMP subprotocol echo
      fixed so the browser socket opens (E2E). `[Phase 26]`
- [x] **Playwright E2E 7/7 green** on the full mock stack: login (deep-link,
      cookie flags, wrong/right password, axe), the live-signals MVP + breakdown,
      signals-page axe, and the WS-reconnect chaos test; axe reports no
      violations on login/signals. `ci-e2e` runs the same suite on every PR
      (green-on-main lands at merge). `PHASE_GATES.md` mirrors this row. `[Phase 27]`

**Stage-end notes:** `strategy-schema/v1` (Phase 18) and the `ScoreBreakdown`
contract (Phase 20) **freeze here** — Stage D's FillSimulator/replay consume both
unchanged, and the replay half of the golden parity pair asserts byte-identity
against the live half frozen in Phase 23. **Open items carried forward:** NSE
index-constituents CSV source verification (before the Phase 22 live fetcher);
statutory fee-schedule values (pinned at Stage-D Phase 29). Neither blocks the
MVP demo on the mock stack. Owner action: mint a brand-new 2.0 Kite API
key/secret for live-mode (the Stage-C manual-testing guide's live appendix).

---

## Stage-B exit gate (plan §15.2 Phase-1 row — walked 2026-06-13 against the running mock stack; merged to main via PR #2)

*(Stage B walk: 13 phases phase-per-commit + a 39-agent audit — 3 CRITICAL + ~20
MAJOR fixed (post-close ticks re-opening the flushed close bar, continuous=1 on
per-contract FUT fetches, CONT via POST /candles/refresh); 164 market-data + 20
gateway tests green.)*

- [x] **Live tick reaches Redis < 50 ms after Kite delivery.** Measured live:
      tick generation → published-on-Redis = **3 ms** (mock feed, B-6
      pipeline, same-tick comparison of the embedded producer timestamp vs the
      `ticks:last-at` publish marker).
- [x] **Historical fetch fills gaps idempotently at ≤ 3 req/s.** Cold fetch =
      exactly one gateway call; warm read = zero gateway-port invocations
      (asserted); partial coverage fetches only the missing sub-range; 50-burst
      limiter test ≥ 15 s end-to-end and never > 3/s in any window.
- [x] **The same flows pass on the mock profile** — every Stage-B phase is
      mock-green with zero Kite credentials (the whole IT battery runs without
      any Kite material; live impls are WireMock-pinned).
- [x] **Snapshots accruing every 5 min in market hours** — the Phase-15
      scheduler is calendar-gated; the IT drives a market-hours clock and the
      off-hours degradation (stale:true, zeroed book, EOD OI) separately.
- [x] **Raw quote rows persist from the first market day with IV gated on S1**
      — every row carries LTP/bid/ask/spot/OI/oi_change + `forward_price` +
      `risk_free_rate`; the 490-vector golden suite (S1, above) gates the
      computed columns via `artha.options.iv-enabled`; null-IV rows persist
      with reason codes, never skipped.
- [x] **Contract canary runs on the first LIVE transition and surfaces on
      `/auth/kite/status`** — WireMock-verified both drift directions + the
      Redis daily-once marker; mock runs no canary by design. *(Result key is
      `kite:contract:check` — documented deviation, parking list.)*
- [x] **Contract-spec history accrues from the first sync** — FIRST_SEEN rows
      on sync, as-of resolution at change boundaries, `spec_asof_estimated`
      honesty flag (Phase 9A ITs).
- [x] **Front/next/far FUT + INDIA VIX pinned; term structure from ONE batched
      quote** — single-invocation assertion; basis fixture (120.0000 /
      0.0608 @ 30d); CONTANGO/BACKWARDATION by near→next slope; per-bar FUT OI
      through to the 1d cagg's `last(oi)`.
- [x] **Continuous futures stitch deterministically** — exact 150.0000 fixture
      gap, idempotent re-runs, `adjust=back|none` verified at the API,
      roll-day divergence caveat documented in the roller javadoc + stage doc.
- [x] **Corporate-action job detects the planted split and rebuilds** —
      uniform-ratio guard rejects single-anchor and non-uniform noise;
      re-backfill rides the rate-limited gateway; `fetched_at` bump asserted;
      byte-stable post-rebuild series.

**Stage-end deliverables roll-up:**

- [x] Daily Kite contract canary (S2B) — fixture-derived manifests, recursive
      field-set diff, first-party ntfy.
- [x] Greeks golden-vector suite + S1 gating of IV persistence; raw quotes
      captured from day one (S4).
- [x] `docs/retention.md` (A2 ≥5y floor, 50 GB review trigger) +
      `docs/runbook-notes.md` (A3 minute-depth probe, S2 Tuesday-expiry note).
- [x] ~~Leaked-credential tripwire~~ — dropped per A6; the live fail-fast
      (key + secret + master key files) remains and is tested.
- [x] 2026-06-12 feature-selection additions all landed: 9A spec history, 15A
      futures slice + INDIA VIX, 15B CONT + roll_events, 16A corporate
      actions, `candles_1w`, `oi_buildup`/`rs_rank`.

**Owner actions carried forward:** minute-depth probe in live mode (A3);
NSE constituents CSV source verification before Phase 22 (S8); branch
protection clicks. **Forward dependencies (by design):** `jobs:summary`
zeros until Phase 28; `rs_rank` universe = active equities until Phase 22;
1w/futures_of_underlying validate at the Phase 18 freeze.

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
