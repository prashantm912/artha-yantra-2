# Decisions log — dated amendments (COMMON §4.5)

Running record of dated ADR amendments that postdate the frozen design set. Each
entry is a decision made during implementation that the design authority
(COMMON_REFERENCE + stage files) references but could not pin ahead of time —
spike outputs, calibrated defaults, accepted deviations. Newest first.

---

## 2026-06-19 — OpenAlgo integration spine (master plan Phase 0)

**Context.** Master plan `docs/superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md`
Phase 0 (§2 appliance + §3 gateway + contract test) re-platforms the broker boundary around OpenAlgo.
Several plan assumptions had to be pinned against reality during implementation.

**Decisions.**

- **Image pin = DIGEST, not a tag.** `marketcalls/openalgo` on Docker Hub publishes only commit-hash
  tags + `latest` (NO semver), so the plan's "pin a release tag" is impossible. Pinned by digest
  `marketcalls/openalgo@sha256:b1bc2ec4fc40a0e32730bab9c4b9dd3a43daefee30453de46885544eab45fdd7`
  (pulled 2026-06-19; source tracks GitHub release `v2.0.1.3`). Bump = pull new digest → run
  `OpenAlgoWireContractTest` + `OpenAlgoContractCanary` → update digest in compose + `deploy/openalgo/README.md`.
- **OpenAlgo REST is POST-with-`apikey`-in-body, single-symbol.** Verified against the local source
  checkout (`C:\Trading\openalgo`, plan §19.1): `/quotes` DOES carry `oi` (always present, `0` for
  cash/index, real for F&O — `services/quotes_service.py` + `broker/*/api/data.py`); per-strike OI also
  rides `/optionchain` (`chain[].ce.oi`/`pe.oi`). `/history` `timestamp` is Unix epoch **seconds** as a
  JSON integer (`OpenAlgoCandle.timestamp` is `long`, NOT a String). Daily interval code is `D`.
  optionchain request uses `underlying` (not `symbol`) + a required `expiry_date` (DDMMMYY) — so its
  live canary OI-coverage probe is Phase 1 (§17.11, needs expiry resolution); the optionchain wire
  shape is guarded meanwhile by `OpenAlgoWireContractTest`. Healthcheck = `/health/status` (purpose-
  built, unauthenticated). Exchange codes (NSE/NFO/BSE/BFO/NSE_INDEX/BSE_INDEX) + index symbol remaps
  (NIFTY/BANKNIFTY/FINNIFTY/MIDCPNIFTY/SENSEX/BANKEX) match OpenAlgo's own loader byte-for-byte.
- **REST hand-rolled `RestClient`; SDK deferred to Phase 3 (WS + orders).** Correction: the
  `in.openalgo:openalgo:1.0.1` SDK's base URL IS settable (constructor/Builder — NOT pinned like Kite's),
  so WireMock-via-SDK was technically possible. We still hand-roll REST, but for PARITY with the
  `kite/wire` pattern — typed `@JsonIgnoreProperties` DTOs + off-critical-path `OpenAlgoContractCanary`
  drift detection, which the SDK's untyped `JsonObject` returns give up. No SDK dep in Phase 0 (no
  caller); when WS/orders land it still needs a mapping layer (SDK is untyped end-to-end).
- **Sandbox/analyzer (mock) mode is a RUNTIME toggle, not an env flag (§17.8).** OpenAlgo's only
  sandbox env var (`SANDBOX_DATABASE_URL`) just sets a DB path; the mode is set via the UI /
  `POST /api/v1/analyzer/toggle`. Mock⇒analyzer coupling therefore CANNOT be baked into the container
  via env and is deferred to the Phase-1 routing cutover (documented in `deploy/openalgo/README.md`);
  Phase 0 leaves it manual.
- **Appliance is OPT-IN (compose `profiles: [openalgo]`).** `ay up` uses `up -d --wait`; an
  unconfigured OpenAlgo would fail the wait and regress the green-boot entry gate. Nothing depends on
  `openalgo`, and the default source stays `kite`, so Phase 0 changes NO routing. Start with
  `ay up openalgo`. OpenAlgo's default `API_RATE_LIMIT` is 50/s (ours set conservatively to 5/s,
  MEASURE before the Phase-1 cutover, Risk R3).
- **Manual-verification fixes (ran the Phase-0 guide end-to-end).** Two appliance-config defects the
  contract tests can't catch surfaced only on a real `ay up openalgo`: (1) start.sh defaults
  `ENV_CONFIG_VERSION` to an OLD `1.0.4` when unset, and the app then HARD-REFUSES boot via an
  interactive `Continue anyway? (y/N)` prompt (no TTY → worker loops → :5000 never binds → healthcheck
  times out) — fixed by pinning `ENV_CONFIG_VERSION=1.0.7` in `.env.sample` (version-locked to the
  image digest; added to the bump runbook). (2) The `openalgo-publish` socat sidecar listened on
  container `5001` but published container `5000` (`127.0.0.1:5001:5000`), so the host port hit a dead
  port — fixed to `127.0.0.1:5001:5001` (matches the dev-tools sidecar pattern: host:listen:listen).
  After both fixes: `/health/status` returns 200 via the loopback publisher, loopback-only confirmed,
  the OpenAlgo API key mounts into market-data-service ONLY, and config survives a restart.
- **Config is FILE-BASED (mount a complete `/app/.env`), NOT compose env vars.** Bringing the broker
  up live surfaced three more defects, all one root cause — OpenAlgo is file-config-native and
  start.sh's env→file heredoc is lossy: (4) `CSP_UPGRADE_INSECURE_REQUESTS` defaults TRUE → the
  browser upgrades same-origin POSTs (setup/login) to https → TLS on the plain socat port →
  `ERR_CONNECTION_CLOSED`; (5) `FERNET_SALT` is auto-rotated by `env_check` into the container's
  `/app/.env` and lost on recreate → stored ciphertext won't decrypt → hard boot refusal; (6) the
  heredoc omits required keys (rate limits, logging, `VALID_BROKERS`) that the app hard-checks. Fix:
  `ay` seeds `deploy/openalgo/.env` from the **pinned image's own `/app/.sample.env`** (every key
  present, version-locked), bakes the ArthaYantra overrides (`HOST_SERVER`/`REDIRECT_URL` = the
  loopback publisher `:5001`; `FLASK_HOST_IP`/`WEBSOCKET_HOST` = `0.0.0.0` so socat reaches the
  bind; `CSP_UPGRADE_INSECURE_REQUESTS=FALSE`), generates `APP_KEY`/`API_KEY_PEPPER`/`FERNET_SALT`,
  and (single-owner) fills `BROKER_API_KEY/SECRET` from the existing `deploy/secrets/kite_*`. That
  file is mounted AS `/app/.env` (compose `volumes:`, NOT `env_file:` — it is OpenAlgo's native
  `KEY = 'value'` dotenv format), so start.sh skips generation and every secret persists verbatim
  across recreates (no rotation). Live bring-up verified end-to-end: Zerodha logged in via the UI;
  `POST /api/v1/quotes` (RELIANCE/NIFTY) and `/api/v1/history` (daily) return live data whose wire
  shape matches the `OpenAlgoQuote`/`OpenAlgoCandle` DTOs byte-for-byte — `oi` present, `timestamp`
  an integer epoch-second — confirming the §17.2 source-verification corrections against the REAL feed.
- **Kite app repurposed to OpenAlgo (single Kite session).** Zerodha issues one access_token + one
  registered redirect per Connect app; per owner decision the single app's redirect is pointed at
  OpenAlgo (`:5001/zerodha/callback`) and OpenAlgo holds the session. ArthaYantra's own Kite-direct
  path stays the never-deleted fallback (directive 6f/6g) and reads via OpenAlgo after the Phase-1
  cutover. Running both Kite-direct sessions in parallel would need a second Connect app.

**No schema change** (capture path untouched; Flyway heads V017/V008/V005 confirmed). Branch
`feat/openalgo-spine`. Phase 1 (§4 routing + OI-coverage canary) flips `source.*` and enables the
contract canary against the live appliance (§17.11 entry gate).

---

## 2026-06-13 — S3 pruner-calibration defaults (Stage D, Phase 33/34 entry gate)

**Context.** §D.13 mandates the fold-fed `MedianPruner` calibration be RUN and its
outputs recorded as a dated amendment before sweeps ship — or pruning stays
disabled (PHASE_GATES.md gate). The spike
(`services/optimizer-service/spikes/s3_pruner_calibration.py`, pure-Python,
deterministic, no backtest-service dependency) models walk-forward OOS folds with
a per-fold regime drift (fold 0 = benign trending regime that flatters every
trial — the early-regime-bias trap) plus trade-count-dependent Sharpe noise
(~1/√trades — the prune-on-noise hazard), then sweeps the pruner knobs measuring
false-prune of near-optimal trials vs true-prune of poor ones, and checks
TPE/NSGA-II convergence.

**Findings (seeded run).**

- Pruner knob sweep (score = true_prune − false_prune; higher better):

  | n_startup_trials | n_warmup_folds | false_prune | true_prune | score |
  |---|---|---|---|---|
  | 5 | 3 | 0.00 | 0.90 | 0.90 |
  | 5 | 2 | 0.06 | 0.86 | 0.80 |
  | 10 | 3 | 0.03 | 0.80 | 0.77 |
  | 5 | 1 | 0.19 | 0.94 | 0.75 |

  Warm-up of **3 folds** eliminates false-pruning of near-optimal trials (0.00)
  while keeping true-prune high (0.90); `n_warmup_folds=1` prunes ~19% of
  near-optimal trials on the benign opening regime alone — the early-regime bias
  §D.13 warns about, confirmed.
- Sampler convergence (|best − known optimum| @ 150 trials): **tpe 0.0096**,
  grid 0.0386, random 0.1168. NSGA-II returns a 5-point Pareto front @ 200.
  TPE/NSGA-II converge usefully under the chosen pruner settings.

**Decision — recorded pruner defaults (configured in Phase 34).**

| Setting | Value |
|---|---|
| `n_startup_trials` | **5** |
| `n_warmup_folds` (MedianPruner `n_warmup_steps`) | **3** |
| `n_min_trials` | **2** |
| default `max_trials` | **150** (tpe) / **200** (nsga2) |
| default sampler / pruner | TPE (constant-liar) + fold-fed MedianPruner |

Pruning is **enabled** in Phase 34 with these defaults (the gate's "or pruning
explicitly disabled" branch is not taken). Pruning is fed by **OOS fold medians**
only (guard 2), never train/OOS divergence (the rejected S1B design).
