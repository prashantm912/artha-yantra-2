# ArthaYantra — OpenAlgo Integration, marketcalls Repos, React Migration & Strategy Master Plan

> Status: PLAN (no code changed). Authored 2026-06-19. Source of truth for the OpenAlgo/React/strategy integration work.
> A new session should read CLAUDE.md + this file before implementing any phase. Phases, dependencies and verify gates are in the final section.
> **AUTHORITY NOTE:** §17 (Errata & Addendum) and §18 (Gap Addendum) were added after review passes and are AUTHORITATIVE and co-equal — where any body section (§1–§16) conflicts with §17 or §18, the addenda win. §17 fixes 29 internal defects; §18 closes 7 goal-vs-plan gaps. Read BOTH before implementing any phase.

## Table of Contents

1. Overview, Target Architecture & License-Compliance Framework
2. OpenAlgo Appliance — Docker Compose Service & Pinning
3. OpenAlgoGateway Adapter (OpenAlgo-Java SDK behind domain ports)
4. Market-Data Routing Migration (Kite-direct -> OpenAlgo, incl OI capture)
5. Historical Data Backfill — ExpiryTrack (intraday OI) + openchart (daily)
6. opengreeks -> black76-math Port (higher-order Greeks)
7. Technical-Indicator Engine Port (VWAP/Supertrend/VWMA/RSI/PSAR)
8. marginism SPAN-Margin Appliance & Position Sizing
9. openscreener Fundamentals Appliance (OPTIONAL — Minervini filter)
10. React Migration Master Plan (Angular 21 -> React 19)
11. oipulse OI-Analysis Pages in React
12. Track 2 — Siva Options Scalper Implementation (12 sub-strategies)
13. Track 1 — Minervini-India Momentum Screener
14. Backtest & Forward-Test Strategy
15. Dev & Agentic Tooling (optional installs)
16. Sequencing, Phases, Milestones, Dependencies & Risk Register
17. **Errata & Addendum (authoritative corrections from the review pass)**
18. **Gap Addendum (authoritative — 7 goal-vs-plan gaps from the cross-check pass)**

---

## 1. Overview, Target Architecture & License-Compliance Framework

### 1a. What this plan delivers

This plan re-platforms ArthaYantra's data and execution layer around **OpenAlgo as a swappable broker boundary** and rebuilds the front end in **React**, while preserving the existing Java analytics/backtest moat and its irreplaceable TimescaleDB capture archive. Concretely it delivers: (1) an **`OpenAlgoGateway`** implementing the existing domain ports (`QuoteGateway`, `HistoricalCandleGateway`, plus a new option-chain/greeks port) behind the same anti-corruption pattern the `kite/wire/` package already uses, with a per-capability config flag selecting `kite` vs `openalgo` as the source — so the broker becomes swappable (Kite → any of 30+ brokers) with zero ArthaYantra code change (§2, §3); (2) **historical intraday-OI backfill** via the ExpiryTrack and openchart appliances exporting Parquet that we ingest into `marketdata.options_chain_snapshots` / `marketdata.candles`, finally solving the intraday-OI-history backtest crux (§4, §5); (3) **ported math/indicators** — opengreeks higher-order greeks into `libs/black76-math`, pyindicators formulas into the scalp signal engine — keeping the entire deterministic backtest replay path network-hop-free (§6, §7); (4) the **Siva Options Scalper** (12 sub-strategies, built first) atop the ~80%-complete oipulse OI analytics spine, then the **Minervini SEPA daily screener** for Indian equities (built second) (§9, §10, §11); (5) a **React 19 + Vite + Tailwind + shadcn/ui + Lightweight-Charts** front end that re-creates both the existing Angular cockpit and the oipulse OI-analysis pages, importing only MIT React pieces (openalgo-heatmap) (§8); and (6) a **marginism** SPAN-margin appliance for position sizing (§12). The owner runs everything single-tenant on one Windows 11 box under the existing Dockerized, loopback-only, `ay`-CLI-driven stack. **(§18.2: long-term investing is the explicit future Track 3 — `long_term` preset + fundamentals, no code now. §18.3: live systematic scalping requires an always-on host — laptop-only ⇒ discretionary-manual only.)**

### 1b. Target architecture

```
                                  ┌────────────────────────── HOST: Windows 11 / Docker Desktop (WSL2) ──────────────────────────┐
                                  │                  Everything loopback-only; phone via Tailscale-serve (COMMON §3)             │
  ┌─────────────┐                 │                                                                                              │
  │  Browser /  │   127.0.0.1     │   ┌──────────────────┐      same-origin, zero-CORS catch-all route                          │
  │  Tailscale  │────8080─────────┼──▶│  edge-gateway     │◀─── serves React SPA static bundle (replaces frontend-ui Angular)    │
  └─────────────┘   (ONLY pub'd   │   │ (Spring Cloud GW) │      §8                                                              │
                     app port)    │   │  loopback only    │                                                                      │
                                  │   └───────┬───────────┘                                                                      │
                                  │           │  REST + WS (decimal-as-JSON-string; {items:[]} envelopes)                        │
                                  │   ┌───────┴───────────────────────────────────────────────────────────────────┐            │
                                  │   │            JAVA SERVICES (Spring Boot 3 / Modulith, Java 21)                 │            │
                                  │   │                                                                              │            │
                                  │   │  market-data-service:8081   strategy-signal-service:8082                     │            │
                                  │   │   - QuoteGateway  ───┐        - scalp signal engine (ported indicators §7)   │            │
                                  │   │   - CandleReader     │        - Siva 12 sub-strategies §11                    │            │
                                  │   │   - OI analytics ────┤        backtest-service:8083                          │            │
                                  │   │     (oipulse spine,  │         - deterministic replay (greeks IN-PROCESS,    │            │
                                  │   │      §9 already built)│           NO network hop) §5, §6                      │            │
                                  │   │   - OpenAlgoGateway ─┤        optimizer-service:8084 (Python/FastAPI/Optuna)  │            │
                                  │   │     (NEW port impl)  │                                                        │            │
                                  │   └──────────┬───────────┴───────────────┬──────────────────────────────────────┘            │
                                  │              │ writes (capture is        │ reads marketdata READ-ONLY (CD-1 grant, D10)      │
                                  │              │ UNCONDITIONAL)            │                                                   │
                                  │   ┌──────────▼───────────────────────────▼────────────────┐                                  │
                                  │   │   TimescaleDB 2.17.2-pg17  ── THE CAPTURE MOAT          │                                  │
                                  │   │   live→artha / mock→artha_mock ; 4 Flyway lineages      │                                  │
                                  │   │   schemas: admin · marketdata · strategy · backtest     │                                  │
                                  │   │   candles · options_chain_snapshots · futures_oi_*      │                                  │
                                  │   │   nse_eod_bhavcopy · fii_dii · participant_oi · iv_*    │                                  │
                                  │   └────▲───────────────▲──────────────▲────────────────────┘                                  │
                                  │        │ ingest Parquet│ ingest Parquet│ live snapshots (3-min REST)                          │
                                  │        │ (intraday OI) │ (daily OHLCV) │                                                      │
                                  │   ┌────┴─────┐   ┌─────┴──────┐        │                                                      │
                                  │   │ExpiryTrack│  │ openchart  │        │   ── ANTI-CORRUPTION BOUNDARY (process edge) ──       │
                                  │   │ APPLIANCE │  │ APPLIANCE  │   ┌────┴──────────────────┐                                  │
                                  │   │ AGPL,     │  │ MIT, py    │   │  OpenAlgo APPLIANCE     │   unified REST /api/v1/ + WS    │
                                  │   │ standalone│  │ daily+intra│   │  AGPL, UNMODIFIED,      │──────────────┐                  │
                                  │   │ DuckDB→   │  │ OHLCV      │   │  pinned release tag      │              │ broker SWAP     │
                                  │   │ Parquet   │  │ (no OI)    │   │  (Flask, sandbox paper) │              ▼ point            │
                                  │   └──────────┘   └────────────┘   └──────────┬──────────────┘     ┌─────────────────┐         │
                                  │   ┌──────────┐   ┌────────────┐              │ quotes/depth/        │  BROKER         │         │
                                  │   │marginism │   │openscreener│              │ history/optionchain/ │  (Kite / Upstox │         │
                                  │   │APPLIANCE │   │APPLIANCE   │              │ optiongreeks/orders  │   / 30+ via     │─────────┼──▶ NSE/NFO/BSE
                                  │   │MIT, SPAN │   │MIT, OPTIONAL│             ▼                      │   OpenAlgo)     │         │
                                  │   │.spn calc │   │fundamentals│      OpenAlgo-Java SDK              └─────────────────┘         │
                                  │   └──────────┘   └────────────┘    (in.openalgo:openalgo:1.0.1)                                │
                                  └──────────────────────────────────────────────────────────────────────────────────────────────┘
```

Data-flow summary into the TimescaleDB moat:
- **Live source (real-time)**: market-data-service pulls quotes / per-strike OI / candles / option-chain through `OpenAlgoGateway` (HTTP to the OpenAlgo container; SDK `in.openalgo:openalgo:1.0.1`) **or** Kite-direct (existing `kite/live/`), selected by config flag — then **writes to TimescaleDB itself**. OpenAlgo is the *source*, never the store (§2, decision 2).
- **Historical backfill (one-shot / scheduled)**: ExpiryTrack (intraday OHLCV+OI for expired F&O) and openchart (200+ days daily OHLCV for the equity universe) run as standalone appliances, export **Parquet**, which an ingest job loads into `marketdata.options_chain_snapshots` and `marketdata.candles` (§4, §5).
- **Side appliances**: marginism (SPAN margin from exchange `.spn` files) and openscreener (Screener.in fundamentals, optional) are queried by Java services over their own thin HTTP/file boundaries (§12, §10).

### 1c. The anti-corruption-boundary principle

**OpenAlgo (and every appliance) sits behind a process edge; ArthaYantra owns analytics, backtest, and the store.** The repo already practises this for Kite: the `kite/wire/` package mirrors *every* documented Kite REST field as `@JsonIgnoreProperties(ignoreUnknown=true)` records (`KiteQuote`, `KiteHistoricalResponse`, `KiteInstrument`, `KiteSession`, …), the live gateways `body(KiteXxx.class)` then **map to domain port records** (`QuoteGateway.Quote`, `HistoricalCandleGateway.Candle`), and drift is caught *off the critical path* by `ContractCanary` (against `kite-contract-manifest.json`) + `KiteWireContractTest` — **never** by a live deserialization failure (`kite/wire/package-info.java`, the owner directive of 2026-06-15). The OpenAlgo integration mirrors this exactly (§2): a new `openalgo/wire/` package of unknown-property-tolerant DTOs, an `OpenAlgoGateway` that maps wire → the **existing** domain records (`QuoteGateway.Quote`, `CandleReader`'s `EngineCandle`-shaped rows, a new chain/greeks port), a contract test, and a canary. The boundary guarantees three things:

1. **Broker swappability** — strategies, the engine, and the UI depend only on domain records (`Quote`, `EngineCandle`, the OI analytics enums like `OiInterpretation`), never on a broker's wire shape; swapping Kite→Upstox is an OpenAlgo config change.
2. **Capture is unconditional and ours** — the irreplaceable archive (`options_chain_snapshots`, "the platform's only irreplaceable dataset", per `V006`) is written by ArthaYantra into its own TimescaleDB; OpenAlgo going down loses *future* ticks, never history.
3. **Determinism is preserved** — the backtest replay path reads candles via `backtest/replay/CandleReader` **directly from `marketdata` read-only (D10), never over REST** (its own Javadoc), and greeks/indicators are *ported into Java* (§5/§6) so a network hop can never enter a deterministic replay and break golden-vector parity (`GoldenDeterminismTest` / `BacktestParityTest`).

Latency caveat (decision 2): the OpenAlgo hop is fine for 1-min/3-min OI snapshots but **must be measured for scalp order execution**; and the unified API may flatten Kite-specific fields (e.g. 20-level depth) — both are flagged in §2.

### 1d. License-compliance framework

**Filter:** MIT → port source or import binaries freely, **keep the copyright notice** (record attributions in `docs/LEGAL.md`, which already logs lightweight-charts/Apache-2.0). AGPL-3.0 → run **STANDALONE behind a process boundary**, consume only its **output data or network API**, **NEVER merge its source** into any ArthaYantra module — AGPL's network-use copyleft would infect ArthaYantra the moment a second user touches it over the network.

| Repo / artifact | License | Integration form | Plan § | AGPL-containment rule (AGPL items only) |
|---|---|---|---|---|
| **OpenAlgo** (Python/Flask platform, 30+ brokers, unified REST+WS, sandbox paper) | **AGPL-3.0** | **Appliance** (own Docker container, unmodified, pinned tag) | §2, §3 | Run as a separate container; ArthaYantra talks to it **only** over its `/api/v1/` HTTP + WS. NEVER fork/patch its source, NEVER copy its frontend. Compose service kept distinct from `arthayantra/*` images. |
| **OpenAlgo-Java SDK** `in.openalgo:openalgo:1.0.1` (Maven Central) | **MIT** | **Import** (Maven dep in market-data-service) | §2 | — (MIT client of the AGPL appliance; linking the MIT SDK does not infect us) |
| **ExpiryTrack** (Python; expired-F&O 1-min OHLCV+OI → DuckDB → Parquet) | **AGPL-3.0** | **Appliance** (standalone, run on demand) | §4 | Consume its **Parquet output** only; ingest data into TimescaleDB. NEVER import its code into any Java/Python ArthaYantra module. |
| **openchart** (Python; free NSE/NFO daily+intraday OHLCV, no OI, no auth) | **MIT** | **Appliance** (standalone backfill) | §4 | — |
| **opengreeks** (Rust core + Python; Black-76/BS/BSM + higher-order greeks) | **MIT** | **Port** (closed-form math → `libs/black76-math`) | §6 | — (keep MIT notice; copy formulas, not a service call) |
| **pyindicators / openalgo-indicator-skills** (Python; 100+ indicators) | **MIT** | **Port** (VWAP, Supertrend, VWMA, RSI, PSAR → Java) | §7 | — |
| **openalgo-heatmap** (TS/React; zero-dep layout+color core) | **MIT** | **Import** (zero-dep core into React UI) | §8 | — |
| **marginism** (pure Python; offline SPAN from `.spn` files) | **MIT** | **Appliance** (small Python SPAN service) | §12 | — |
| **openscreener** (Python Playwright Screener.in scraper) | **MIT** | **Appliance** (OPTIONAL — nice-to-have, not blocking) | §10 | — |
| **raptorbt** (Rust options backtester) | MIT | **DEFER** — future cross-check oracle; do NOT replace the Java engine | — | — |
| openalgo-skills / -indicator-skills / -execution-skills, openalgo-mcp, openalgo-claude-plugin, zerodha-api-docs | mixed | **Reference** (optional dev tooling only) | — | — |
| dhan-20depth / order-flow-chart | — | **Reference** (optional scalp-microstructure) | §11 | — |
| fastscalper-tauri | — | **Reference** (fast-order-entry UX) | §8 | — |
| fyers-scanner / TradingView-Screener | — | **Reference** (scanner patterns) | §10 | — |
| ExpiryFlow (no OI), Algomirror, claude-tradingview-mcp-trading (crypto) | — | **SKIP** | — | — |

### 1e. Glossary

- **OI (Open Interest)** — count of outstanding (unsettled) option/future contracts at a strike/series. Captured per-strike in `marketdata.options_chain_snapshots.oi` (BIGINT) and per-future in `marketdata.futures_oi_snapshots`; the `candles` hypertable also carries an `oi` column (nullable; null for indices).
- **Trending-OI** — OI change tracked over rolling lookbacks (5 / 15 / 30 / 60 / 120 / 240-min) to read directional pressure; served by `OiTrendingService` / the `trending` endpoint and enumerated by `OiInterval` (§9, §11).
- **OI quadrants — LB / SC / SB / LU** — the oipulse 4-state OI interpretation (price direction × OI direction), already encoded as `OiInterpretation` (`services/market-data-service/.../options/OiInterpretation.java`): **LB** = LONG_BUILDUP (price↑, OI↑), **SB** = SHORT_BUILDUP (price↓, OI↑), **SC** = SHORT_COVERING (price↑, OI↓), **LU** = LONG_UNWINDING (price↓, OI↓). Boundary convention: delta == 0 counts as the "up" side.
- **Max Pain** — the strike at which the aggregate value of in-the-money options is minimized (where most option buyers lose); computed by `MaxPainCalculator` (§9).
- **PCR (Put-Call Ratio)** — put OI ÷ call OI; history served by `PcrHistoryService` (§9).
- **VCP (Volatility Contraction Pattern)** — Minervini's pre-breakout base of successively tighter price pullbacks on declining volume. NO repo implements it — custom/future; owner accepts manual chart-reading until automated (§11, Track 1).
- **RS rank (Relative Strength rank)** — a stock's price-momentum percentile vs the index (NIFTY) universe; a Trend-Template gate (≥ 70) and a screener output column (§11, Track 1).
- **Trend Template** — Minervini's 8-gate daily filter (price > 150- & 200-day MA; 150 > 200; 200-day rising ≥ 1mo; 50 > 150 & 200; price > 50-day; price ≥ 25% above 52-wk low; price within ~25% of 52-wk high; RS rank ≥ 70). Stage-2 status falls out of it. Data flows from the existing `nse_eod_bhavcopy` capture (~3.2k symbols/day) + openchart 200-day backfill for the MAs (§4, §11).
- **Anti-corruption layer (ACL)** — a translation boundary (`*/wire/` DTOs → domain records) isolating ArthaYantra's model from an external system's wire shape; the existing `kite/wire/` pattern, mirrored for OpenAlgo (§1c, §2).
- **Appliance** — an external program run as its own pinned, unmodified Docker container/process; ArthaYantra consumes only its output data or network API across a process edge (the AGPL-containment mechanism for OpenAlgo and ExpiryTrack).
- **Capture moat** — ArthaYantra's owned, unconditional, no-retention-floor TimescaleDB archive (especially `options_chain_snapshots`, "the platform's only irreplaceable dataset", `V006`); the defensible asset that survives any broker/appliance swap.
- **Source vs store** — OpenAlgo and the backfill appliances are *sources* (where data originates); TimescaleDB is the *store* (system of record). ArthaYantra always owns the store (§1c, decision 2).
- **Domain port** — a broker-agnostic Java interface the rest of the system depends on (`QuoteGateway`, `HistoricalCandleGateway`, `CandleReader`); concrete impls (`Live*Gateway` for Kite, `OpenAlgoGateway` for OpenAlgo) plug in behind it (§2).

---

Implementation-anchor notes for downstream sections (grounded in the repo):
- **Source-selection config flag** should extend the existing convention: live Kite config uses `@ConfigurationProperties(prefix = "artha.kite")` (`kite/live/KiteHttpProperties.java`); add a sibling `artha.source.*` (or `artha.openalgo.*`) prefix with a per-capability selector. Mock-vs-live remains `SPRING_PROFILES_ACTIVE`, orthogonal to the new source flag.
- **OpenAlgo capture write** needs a Flyway change: `marketdata.candles.source` has a CHECK constraint `source IN ('KITE','TICK_AGG','MOCK','BACKFILL')` (`V003`). Adding `OPENALGO` (and likely `EXPIRYTRACK`/`OPENCHART` for Parquet ingest) requires a **new suffix-versioned migration** (e.g. `V018__candles_source_openalgo.sql`) — applied migrations are checksum-locked; never edit `V003` in place (CLAUDE.md / new-migration skill).
- The new option-chain/greeks domain port is **net-new** (no existing `OptionChainGateway`); model it on `QuoteGateway.Quote` (which already carries `oi`) and the `options_chain_snapshots` column set (ltp/bid/ask/volume/oi/iv/delta/gamma/theta/vega/rho/forward_price/risk_free_rate).
- Build any touched service with the **full reactor + `-am`** (`./mvnw -pl services/market-data-service -am package -DskipTests`); a new market-data Maven dep (`in.openalgo:openalgo:1.0.1`) rides the existing fat-JAR build. Adding a new CI service shard is unnecessary (OpenAlgoGateway lives in market-data-service, already a CI shard).
- React SPA replaces the `frontend-ui` Angular container but keeps the same compose contract: internal-only, served by the gateway's same-origin catch-all (`deploy/docker-compose.yml` `frontend-ui`), `/healthz` → `ok`.

---


## 2. OpenAlgo Appliance — Docker Compose Service & Pinning

### 2.0 Scope & dependencies

This section adds **OpenAlgo as a pinned, unmodified Docker Compose service** inside the existing `deploy/docker-compose.yml` stack, wires it into the `ay.ps1` profile model, and locks the AGPL boundary so OpenAlgo's source never enters the ArthaYantra tree. It produces **no Java code** — that is Section 3 (OpenAlgoGateway behind `QuoteGateway` / `CandleReader`, contract test). This section only stands the appliance up and exposes it on the internal compose network at `http://openalgo:5000` so Section 3's gateway has a target.

- **Depends on Section 3 (S3)** for the `OpenAlgoWireContractTest` / `OpenAlgoContractCanary` invoked by the update procedure (step 11 below).
- **Consumed by** the OpenAlgoGateway (Section 3, in `market-data-service`) and indirectly by the scalp signal engine (Section 11/Track 2) and Minervini screener (Section 11/Track 1) via that gateway.
- **AGPL filter (LOCKED, 2026-06-19):** OpenAlgo is AGPL-3.0. It runs as a **separate process behind an anti-corruption HTTP boundary**, image **UNMODIFIED**, pinned to a release tag. We consume only its `/api/v1/` REST + WebSocket output. We **NEVER** `git clone` its source into this repo, never fork-and-edit, never `COPY` its frontend. The copyleft network-use clause is contained because nothing of OpenAlgo's code is linked into or distributed with ArthaYantra.

### 2.1 Current-stack facts this section builds on (verified)

Read from `deploy/docker-compose.yml`, `ay.ps1`, `.env.example`, `deploy/secrets/README.md`:

- Compose file: `deploy/docker-compose.yml`, project `name: arthayantra`. **One** compose file; activation tiers are compose **profiles** (`obs`, `dev-tools`); default profile = core + app services. Mock vs live is orthogonal (`SPRING_PROFILES_ACTIVE` in `.env`).
- Every service has: a **pinned image tag** (e.g. `timescale/timescaledb:2.17.2-pg17`, `redis:7.4-alpine`, `wiremock/wiremock:3.9.2`), a `mem_limit`, a `healthcheck`, `restart: unless-stopped`, and `logging: *default-logging` (`x-logging` anchor, json-file 10m×3).
- **Only the edge-gateway publishes a port** (`127.0.0.1:8080:8080`). Internal services (8081–8084) are **never published** in the default profile; the `dev-tools` profile forwards them on loopback via `alpine/socat` containers (`mds-publish`, `sss-publish`, `redis-publish`). TimescaleDB publishes `127.0.0.1:5432` for dev tooling only. **All publishes are hardcoded `127.0.0.1` (loopback), never `0.0.0.0`.**
- Secrets are **file-mounted** under `deploy/secrets/` (gitignored except `.gitkeep`/`README.md`), declared in the top-level `secrets:` block, mounted into the **one** service that needs them. `kite_api_key`/`kite_api_secret`/`artha_master_key` mount into **market-data-service ONLY** (CLAUDE.md: "the token and the credentials never reach any other container"; `docker inspect` must show no secret values). Owner password hash is the only secret that is an env var (`ARTHA_OWNER_PASSWORD_HASH` on edge-gateway).
- `ay.ps1` ALWAYS calls compose as `docker compose -f deploy/docker-compose.yml --env-file .env …` (the `Invoke-Compose` helper). `Set-ProfileEnv` reads `SPRING_PROFILES_ACTIVE` from `.env` and exports `ARTHA_DB_NAME`/`ARTHA_REDIS_DB` (live→`artha`/`0`, mock→`artha_mock`/`1`) so compose interpolates them. `Initialize-LocalConfig` (on `up`/`reset-db`) creates `.env` from `.env.example` and generates/creates the secret files.
- Verbs: `up [obs] [dev-tools]`, `down`, `logs <svc>`, `status`, `backup`, `restore`, `reset-db`. `up` uses `--wait` (gates on healthchecks).
- **Gotcha (CLAUDE.md):** never invoke `docker compose` without `--env-file .env` — compose resolves `.env` relative to `deploy/` and silently blanks vars. Always go through `ay`. Mock and live share ONE Postgres instance + Redis but separate DBs; `reset-db` wipes BOTH (shared volume).

### 2.2 Design decisions for the OpenAlgo service

| Aspect | Decision | Rationale |
|---|---|---|
| Image vs build | **Image, pulled by digest-pinned tag** — `marketcalls/openalgo:v1.0.0.x` (VERIFY exact Docker Hub repo + latest release tag at implementation time; OpenAlgo publishes images on Docker Hub and ships a root `Dockerfile`). **Never `latest`.** | AGPL stays contained in an UNMODIFIED upstream image. No `build:` block ⇒ no local Dockerfile ⇒ no temptation to modify. |
| Service name | `openalgo` (container `ay-openalgo`) | Section 3's gateway targets `http://openalgo:5000` on the compose network. |
| Port | Internal `5000` (OpenAlgo's default Flask port). **NOT published** in the default profile. A `dev-tools`-profile `openalgo-publish` socat forwarder mirrors `mds-publish` for browser/manual access on `127.0.0.1:5001` (avoid clashing with 5000 if the host runs anything). | Matches the "only edge-gateway is published" rule; only `market-data-service` reaches it internally. |
| Healthcheck | Hit OpenAlgo's HTTP root / a known light endpoint with `wget`/`curl`. OpenAlgo images are Python-slim and ship `curl` (VERIFY). Use `curl -fsS http://127.0.0.1:5000/` and grep for a 200, with `start_period: 60s` (Flask + gunicorn cold start). | Same pattern as edge-gateway's actuator healthcheck. |
| Restart | `restart: unless-stopped` | Stack convention. |
| Volumes | Named volume `openalgo-data:/app/db` (its 4 SQLite DBs + sandbox/analyzer DBs live under `db/` in the OpenAlgo app dir — VERIFY exact path in the pinned release; OpenAlgo uses `DATABASE_URL=sqlite:///db/openalgo.db` and additional `logs.db`, `latency.db`, sandbox db). Bind-mount `./openalgo/.env:/app/.env:ro` for its config. | OpenAlgo's SQLite persistence must survive container recreation. DuckDB is **ExpiryTrack's** store (Section 3 backfill), not OpenAlgo's — do not conflate. |
| Resource limit | `mem_limit: 2048m` | Per locked decision (~2GB). Python + WS + SQLite. |
| Network | Default compose network only; no `ports:` in default profile. | Not publicly reachable; only intra-stack DNS `openalgo:5000`. |
| Logging | `logging: *default-logging` | Stack convention. |

### 2.3 OpenAlgo's own config & secrets (kept OUT of the gateway secrets)

OpenAlgo is configured via **its own `.env`** (it ships `.env.sample`). Its keys include (VERIFY against the pinned release `.env.sample`): `BROKER_API_KEY`, `BROKER_API_SECRET`, `REDIRECT_URL`, `APP_KEY` (Flask session key), `API_KEY_PEPPER`, `DATABASE_URL`, `HOST_SERVER`, `FLASK_HOST_IP`, `FLASK_PORT`, `WEBSOCKET_*`, and broker selection (`BROKER` / `VALID_BROKERS`). Critically, OpenAlgo issues **its own API key** (generated in its UI / via `API_KEY_PEPPER`-derived hashing) that the **OpenAlgo-Java SDK** (Section 3) authenticates with — this is distinct from the upstream broker (Kite) credentials.

**Secret placement — deliberately separate from ArthaYantra's `deploy/secrets/`:**

- OpenAlgo's broker keys + its own app keys live in **`deploy/openalgo/.env`** (a NEW gitignored path), NOT in `deploy/secrets/` and NOT in ArthaYantra's root `.env`. This keeps the broker credentials and OpenAlgo's session/app keys out of the gateway/market-data secret surface — they belong to the appliance.
- The **only value the Java side needs** is OpenAlgo's API key (to authenticate the SDK). Map that ONE value into `market-data-service` as a **new Docker secret file** `deploy/secrets/openalgo_api_key`, mounted into market-data-service only (mirroring `kite_api_key`). Section 3's `OpenAlgoHttpProperties` reads it from `/run/secrets/openalgo_api_key` (mirror `KiteHttpProperties.apiKeyFile`). The OpenAlgo host URL is plain config, not a secret: `artha.openalgo.base-url=http://openalgo:5000` (Section 3).
- Add `deploy/openalgo/` and `deploy/openalgo/.env` to `.gitignore` (commit only `deploy/openalgo/.env.sample` mirror + a README). Add `openalgo_api_key` to the gitignore allow/deny pattern already covering `deploy/secrets/*`.

### 2.4 Files to CREATE / MODIFY

**MODIFY `deploy/docker-compose.yml`** — add the service (place after `market-data-service`, before `strategy-signal-service`):

```yaml
  # ---- OpenAlgo broker-abstraction appliance (Section 1/2; AGPL — UNMODIFIED) -
  # Pinned upstream image, never built/forked here. Source: Kite (and any of 30+
  # brokers) -> OpenAlgo unified /api/v1 + WebSocket -> consumed by the Java
  # OpenAlgoGateway (Section 3) behind QuoteGateway/CandleReader. OpenAlgo is the
  # SOURCE, not the store: capture still writes ArthaYantra's TimescaleDB.
  # Internal :5000 only — never published in the default profile (loopback socat
  # forwarder under dev-tools, like mds-publish). Its broker secrets + own app/API
  # key live in deploy/openalgo/.env, NOT in deploy/secrets/ (kept off the gateway
  # secret surface). The Java side gets ONLY OpenAlgo's API key via a secret file.
  openalgo:
    image: marketcalls/openalgo:v1.0.0.x   # VERIFY repo+tag; PIN, never :latest
    container_name: ay-openalgo
    env_file:
      - ./openalgo/.env                     # appliance config (gitignored)
    volumes:
      - openalgo-data:/app/db               # VERIFY path: 4 SQLite DBs + sandbox
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://127.0.0.1:5000/ >/dev/null || exit 1"]
      interval: 15s
      retries: 8
      start_period: 60s
    mem_limit: 2048m
    restart: unless-stopped
    logging: *default-logging
```

Add the named volume:

```yaml
volumes:
  timescale-data:
  openalgo-data:        # OpenAlgo SQLite persistence (Section 2)
```

Add a `dev-tools`-profile loopback forwarder (after `sss-publish`):

```yaml
  openalgo-publish:
    image: alpine/socat:1.8.0.3
    container_name: ay-openalgo-publish
    profiles: [dev-tools]
    command: ["TCP-LISTEN:5001,fork,reuseaddr", "TCP:openalgo:5000"]
    ports:
      - "127.0.0.1:5001:5000"   # browse OpenAlgo UI on loopback during dev
    depends_on:
      openalgo:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "socat", "-V"]
      interval: 30s
      retries: 3
    mem_limit: 16m
    restart: unless-stopped
    logging: *default-logging
```

**MODIFY `market-data-service`** (in the same compose file) — make the gateway reach OpenAlgo and mount only its API key:

```yaml
    environment:
      # ... existing keys ...
      ARTHA_OPENALGO_BASE_URL: http://openalgo:5000        # Section 3 reads this
    secrets: [postgres_password, kite_api_key, kite_api_secret, artha_master_key, openalgo_api_key]
    depends_on:
      # ... existing ...
      openalgo:
        condition: service_healthy        # gateway can reach it at boot
```

> Note (CLAUDE.md image-build-context gotcha): market-data-service builds with **repo-root context + `-f services/market-data-service/Dockerfile`** (it COPYs `deploy/dev-certs/`). Adding env/secrets to it changes nothing about that; just keep CI image-build context in lockstep.

**MODIFY top-level `secrets:` block** — declare the OpenAlgo API-key secret:

```yaml
secrets:
  # ... existing ...
  openalgo_api_key:
    file: ./secrets/openalgo_api_key      # OpenAlgo's OWN api key (NOT broker's)
```

**CREATE `deploy/openalgo/.env.sample`** — mirror of OpenAlgo's upstream sample, with ArthaYantra-appropriate defaults (VERIFY full key list against the pinned release):

```dotenv
# OpenAlgo appliance config (Section 2). Copy to deploy/openalgo/.env (gitignored).
# AGPL appliance — these are OpenAlgo's keys, kept OFF ArthaYantra's secret surface.
BROKER=zerodha
BROKER_API_KEY=            # the SAME brand-new Kite key (live); blank in mock/sandbox
BROKER_API_SECRET=
REDIRECT_URL=http://127.0.0.1:5001/zerodha/callback   # via dev-tools forwarder
APP_KEY=                   # Flask session key (generate 32+ random hex)
API_KEY_PEPPER=            # pepper for OpenAlgo's API-key hashing
DATABASE_URL=sqlite:///db/openalgo.db
FLASK_HOST_IP=0.0.0.0      # inside the container only; not host-published
FLASK_PORT=5000
HOST_SERVER=http://127.0.0.1:5000
# Sandbox/analyzer (paper) mode — OpenAlgo's built-in simulated trading:
# enables order/positions without hitting the real broker (mirrors ArthaYantra mock).
```

**CREATE `deploy/openalgo/README.md`** — how to fill `.env`, where the OpenAlgo API key comes from (generated in OpenAlgo's UI after first boot), and the rule: **never edit the image; config only lives here.**

**CREATE `deploy/secrets/openalgo_api_key`** placeholder — empty, generated by `ay` (see 2.5). Update `deploy/secrets/README.md` table with a new row:

| File | Contents | Consumed by |
|---|---|---|
| `openalgo_api_key` | OpenAlgo's own generated API key | market-data-service only (live mode; Section 3 SDK auth) |

**MODIFY `.gitignore`** — add:

```gitignore
# OpenAlgo appliance config (AGPL appliance — its keys, not ours)
deploy/openalgo/.env
!deploy/openalgo/.env.sample
!deploy/openalgo/README.md
```
(`deploy/secrets/openalgo_api_key` is already covered by the existing `deploy/secrets/*` ignore.)

**MODIFY `ay.ps1`** — extend `Initialize-LocalConfig` to (a) create `deploy/openalgo/.env` from the sample, (b) create the empty `openalgo_api_key` placeholder (so compose can mount it; mock never reads it, live fails fast until filled). Mirror the existing `kite_api_key` placeholder loop:

```powershell
    # OpenAlgo appliance config + its API-key placeholder (Section 2)
    $oaEnv = Join-Path $RepoRoot 'deploy\openalgo\.env'
    if (-not (Test-Path $oaEnv)) {
        $oaDir = Split-Path $oaEnv
        if (-not (Test-Path $oaDir)) { New-Item -ItemType Directory -Path $oaDir | Out-Null }
        Copy-Item (Join-Path $RepoRoot 'deploy\openalgo\.env.sample') $oaEnv
        Write-Host '[ay] created deploy/openalgo/.env from sample (fill broker keys for live)'
    }
    # add 'openalgo_api_key' to the existing placeholder loop:
    foreach ($name in 'kite_api_key', 'kite_api_secret', 'openalgo_api_key') { ... }
```

> The OpenAlgo appliance is the SAME in mock and live compose-wise (one definition). It is **NOT** gated behind `ARTHA_DB_NAME`/`ARTHA_REDIS_DB` — those are ArthaYantra's Postgres/Redis split. OpenAlgo uses its OWN SQLite; profile isolation is handled by running OpenAlgo in its **sandbox/analyzer (paper) mode under mock** (no real broker) and **live broker mode under live**. Drive that distinction from `deploy/openalgo/.env` (sandbox on/off), set by the owner — do NOT couple it to `SPRING_PROFILES_ACTIVE` in v1 (keep it simple; a future enhancement could template two `.env` files). (VERIFY OpenAlgo's exact sandbox-mode toggle key in the pinned release.)

### 2.5 Vendor-mirror fallback (upstream deletion / hotfix)

The locked rule is **fork-as-MIRROR, never modify**. To survive upstream image/repo deletion or a needed hotfix:

1. Create a **mirror fork** of the OpenAlgo repo under the owner's GitHub org (e.g. `arthayantra-mirror/openalgo`) — a pristine mirror, **no commits of our own on `main`/release branches**.
2. Mirror the **image** to a registry we control: `docker pull marketcalls/openalgo:<tag>` → `docker tag` → push to GHCR (`ghcr.io/<owner>/openalgo:<tag>`). Record the **image digest** (`docker inspect --format '{{index .RepoDigests 0}}'`) in `deploy/openalgo/README.md`.
3. In compose, optionally pin by **digest** (`image: ghcr.io/<owner>/openalgo@sha256:…`) for immutability; the readme documents both the upstream tag and the mirror digest.
4. **Hotfix path (last resort, still AGPL-clean):** if a fix is unavoidable, branch the mirror, apply the minimal patch, build the image **in the mirror repo's own CI** (its AGPL source stays in the mirror, never in ArthaYantra), publish a new mirror tag, and bump the ArthaYantra compose `image:` to it. ArthaYantra still consumes only the image — no AGPL source ever lands in this repo.

### 2.6 Update procedure (bump pinned tag → contract test)

1. Read the upstream OpenAlgo CHANGELOG for the new tag; check for `/api/v1/` shape changes (esp. `quotes`, `depth`, `history`, `optionchain`, `optiongreeks`).
2. Edit `deploy/docker-compose.yml` `openalgo.image:` to the new pinned tag (and mirror digest if used).
3. `./ay.ps1 up` (or `docker compose … pull openalgo && up -d openalgo` via `ay`-style invocation) to recreate only `openalgo`.
4. Run **Section 3's `OpenAlgoWireContractTest` + `OpenAlgoContractCanary`** (the analog of `KiteWireContractTest` / `ContractCanary` in market-data-service) against the new image. Green ⇒ accept the bump; red ⇒ the unified API drifted (a field renamed/removed/retyped — e.g. the noted risk that the unified shape flattens Kite-specific 20-level depth) → fix the Section-3 wire DTOs/mapping, never patch OpenAlgo.
5. Mirror the new image to GHCR (2.5 step 2) and update the digest in the readme.

### 2.7 Numbered build steps with VERIFY checks

1. **Identify the pinned image.** Confirm the OpenAlgo Docker Hub repo + a concrete release tag (NOT `latest`) and grab its `.env.sample` + the SQLite/DuckDB paths from the pinned release.
   **VERIFY:** `docker pull <repo>:<tag>` succeeds; `docker run --rm <repo>:<tag> ls -la /app/db` shows the SQLite DB dir (record the exact path for the volume mount).
2. **Create config scaffolding.** Add `deploy/openalgo/.env.sample` + `README.md`; add `deploy/secrets/openalgo_api_key` row to `deploy/secrets/README.md`; update `.gitignore`.
   **VERIFY:** `git status` shows `.env.sample`/README tracked and `deploy/openalgo/.env` ignored once created.
3. **Edit compose.** Add the `openalgo` service, the `openalgo-data` volume, the `openalgo_api_key` secret, the `openalgo-publish` (dev-tools) forwarder, and the `market-data-service` `ARTHA_OPENALGO_BASE_URL` env + `openalgo_api_key` secret + `depends_on: openalgo`.
   **VERIFY:** `docker compose -f deploy/docker-compose.yml --env-file .env config` (run through an `ay`-style invocation with `--env-file`) renders with no errors and shows `ay-openalgo` mem_limit 2048m, no `ports:` outside dev-tools.
4. **Extend `ay.ps1`.** Add the `deploy/openalgo/.env` copy + `openalgo_api_key` placeholder to `Initialize-LocalConfig`.
   **VERIFY:** delete `.env`/placeholders locally, run `.\ay.ps1 up`; `deploy/openalgo/.env` and an empty `deploy/secrets/openalgo_api_key` are created automatically.
5. **Boot the appliance (mock).** `.\ay.ps1 up` (sandbox mode in `deploy/openalgo/.env`).
   **VERIFY:** `.\ay.ps1 status` shows `ay-openalgo` healthy within `start_period`; `docker inspect ay-openalgo --format '{{.HostConfig.Memory}}'` ≈ 2147483648; `docker inspect ay-openalgo --format '{{json .NetworkSettings.Ports}}'` shows no host bindings (not published).
6. **Confirm internal reachability from market-data-service.** `docker exec ay-market-data-service sh -c 'wget -qO- http://openalgo:5000/ | head -c 200'` (or `curl`).
   **VERIFY:** returns OpenAlgo's HTTP response (UI/redirect), proving the gateway's future target resolves on the compose network. Confirm it is NOT reachable from the host on 5000: `Invoke-WebRequest http://127.0.0.1:5000/` fails (no publish) unless `dev-tools` is up.
7. **Confirm the `/api/v1` surface (post-config).** With a valid OpenAlgo API key in `deploy/openalgo/.env`/secret, from inside the network: `docker exec ay-market-data-service sh -c 'curl -fsS -X POST http://openalgo:5000/api/v1/quotes -H "Content-Type: application/json" -d "{\"apikey\":\"...\",\"symbol\":\"NIFTY\",\"exchange\":\"NSE_INDEX\"}"'` (VERIFY exact request body shape against the pinned release).
   **VERIFY:** a JSON quote/payload comes back (200). This is the smoke test the Section-3 gateway/contract test will formalize.
8. **Secret isolation check.** `docker inspect ay-edge-gateway` and any non-market-data service.
   **VERIFY:** none contain `openalgo_api_key` or broker secrets; only `ay-market-data-service` mounts `/run/secrets/openalgo_api_key`. OpenAlgo's broker secrets exist only inside `ay-openalgo` (from its `env_file`), nowhere else.
9. **Volume persistence.** `docker compose … restart openalgo` (via `ay`); re-check health and that OpenAlgo's settings/API key survive.
   **VERIFY:** OpenAlgo retains its generated API key / config across recreate (proves `openalgo-data` mount is correct). Note: `ay reset-db` does NOT touch `openalgo-data` (it only `down -v`s the shared timescale volume) — confirm `openalgo-data` survives `reset-db` or document if intentionally wiped.
10. **Mirror the image (2.5).** Push to GHCR, record digest in `deploy/openalgo/README.md`.
    **VERIFY:** `docker pull ghcr.io/<owner>/openalgo:<tag>` succeeds from a clean cache.
11. **Wire the contract test (handoff to Section 3).** Once Section 3 lands `OpenAlgoWireContractTest`/`OpenAlgoContractCanary`, the update procedure (2.6) gates every tag bump on it.
    **VERIFY:** the contract test passes against the pinned image in CI's market-data shard.

### 2.8 Gotchas (from CLAUDE.md + this stack)

- **`--env-file .env` is mandatory** — only reach compose through `ay.ps1`; a raw `docker compose` blanks `ARTHA_DB_NAME`/etc. and would also mis-resolve the OpenAlgo `env_file` relative path.
- **PIN the tag, never `latest`** — every other container in the file is pinned; an unpinned OpenAlgo would silently drift its API and break Section 3's gateway with no contract-test trip until a tag bump runs the test.
- **Loopback only** — the appliance must have **no** default-profile `ports:`. Exposing OpenAlgo's UI/API publicly would put broker order-execution one port-scan away.
- **AGPL** — `image:` only, no `build:`, no source clone. If a `build:` block ever appears for OpenAlgo, the boundary is breached.
- **`ay reset-db` shares ONE volume** for Postgres; OpenAlgo's `openalgo-data` is independent — decide explicitly whether `reset-db` should also reset OpenAlgo (recommend: leave OpenAlgo state intact; document it).
- **Latency caveat (locked decision):** the extra hop is fine for 1-min OI snapshots but **MEASURE** for scalp execution — Section 3 owns the measurement; this section just notes the appliance adds one in-network hop (`market-data-service → openalgo → broker`).

---


## 3. OpenAlgoGateway Adapter (OpenAlgo-Java SDK behind domain ports)

### 3.0 Goal and key architectural fact

> **§18.5 (AUTHORITATIVE):** §3 and §4 are ONE deliverable, not two — a single OpenAlgo gateway + wire package + config + contract test in `services/market-data-service/.../marketdata/openalgo/` (§17.2 namespace). §3 = the Phase-0 spine (default source stays Kite); §4 = the Phase-1 routing flag. Build ONE `OpenAlgoQuoteGateway`/etc.; discard §4's duplicate `kite/openalgo` path and the `artha.md.source.*` prefix.

Make the broker/market-data **source swappable** (Kite-direct ⇆ OpenAlgo) with zero change to any consumer, by adding a *third* set of implementations of the existing domain ports — alongside the live Kite REST impls (`kite/live/`) and the mock impls (`mockfeed/`). A per-capability config flag selects which impl Spring binds. The OpenAlgo appliance (Section S2 — the pinned AGPL container) is the SOURCE; **capture still writes to ArthaYantra TimescaleDB unchanged**.

**Load-bearing fact (verified):** the entire OI/options/futures capture pipeline depends only on the `QuoteGateway` *port*, not on Kite:
- `services/market-data-service/.../options/OptionsSnapshotService.java` (`@Scheduled` snapshotter) → `OptionsChainService` → `QuoteGateway.quotes(...)` + `InstrumentRepository`.
- `services/market-data-service/.../futures/FuturesOiSnapshotService.java` (`@Profile("live")`) → `QuoteGateway.quotes(...)`.
- `OptionsSnapshotService.snapshotNow()` calls `repository.insertAll(rows)` (the Timescale write) — it never references Kite. `OptionsChainService` computes IV/Greeks itself via `libs/black76-math` (`Black76`/`IvSolver`).

Therefore **binding an `OpenAlgoQuoteGateway` as the `QuoteGateway` bean automatically reroutes ALL OI/options/futures capture through OpenAlgo with no change to the snapshot services or the Timescale write layer.** This is the core of the swap. OpenAlgo's optionchain does NOT need to supply IV/Greeks/oi_change — ArthaYantra computes those (confirmed: OpenAlgo optionchain returns `oi`, `ltp`, `bid`, `ask`, `volume` per CE/PE but no `iv`/`delta`/`oi_change`).

The three domain ports to implement (all in package `in.arthayantra.marketdata.kite`):
- `QuoteGateway` — `Map<InstrumentKey,Quote> quotes(Collection<InstrumentKey>)`. `Quote(key, lastPrice, bid, ask, volume, oi, Ohlc, timestamp)`; `Ohlc(open,high,low,close)`. All money fields `BigDecimal`, `volume`/`oi` are `Long`.
- `HistoricalCandleGateway` — `List<Candle> fetch(InstrumentKey, String interval, Instant from, Instant to)`. `Candle(key, interval, OffsetDateTime bucketStart, open, high, low, close, long volume, Long oi)`.
- `InstrumentDumpGateway` — `List<InstrumentRecord> fetchDump()`. `InstrumentRecord(long instrumentToken, exchange, tradingsymbol, name, instrumentType, segment, LocalDate expiry, BigDecimal strike, int lotSize, BigDecimal tickSize)`.

Optional 4th surface: a NEW `OptionChainGateway` port (OI directly from OpenAlgo's `optionchain` — see §3.6) — only if we want to bypass per-strike `quotes()` fan-out. **Default plan keeps `quotes()` fan-out** so `OptionsChainService` (IV/Greeks/forward/PCR logic) is unchanged; §3.6 is an optimization noted but not built first.

`InstrumentKey(exchange, tradingsymbol)` is the stable identity; `.canonical()` = `EXCHANGE:TRADINGSYMBOL`. Numeric tokens are session-scoped wire details — OpenAlgo is symbol-addressed (no tokens), which simplifies us.

> **Dependency on S2:** the OpenAlgo appliance container (pinned `in.openalgo:openalgo` Flask image, base URL e.g. `http://openalgo:5000`, its `apikey`) is provisioned by **Section S2 (OpenAlgo appliance integration)**. This section consumes S2's `baseUrl` + `apikey` config; it does NOT stand up the container. If S2 is not yet done, the contract test (§3.5) and unit tests still run against a stubbed base URL.

### 3.1 Dependency (step a)

Add to `services/market-data-service/pom.xml` (the correct module — it owns every Kite gateway, the wire DTOs, the contract canary, and the `QuoteGateway`/`HistoricalCandleGateway`/`InstrumentDumpGateway` ports; verified). It builds with the reactor via `./mvnw -pl services/market-data-service -am package -DskipTests`.

Coordinates verified on Maven Central: `in.openalgo:openalgo:1.0.1` (MIT, Java 11/17/21). Add a property near the other version pins (`kiteconnect.version`, `resilience4j.version`):

```xml
<properties>
  ...
  <openalgo-sdk.version>1.0.1</openalgo-sdk.version>
</properties>
```
```xml
<!-- OpenAlgo-Java SDK (MIT) — swappable broker/market-data SOURCE behind the
     domain ports (mirror of the kite/wire ACL pattern). The SDK returns Gson
     JsonObject; we map to domain port records and never let its shape leak. -->
<dependency>
  <groupId>in.openalgo</groupId>
  <artifactId>openalgo</artifactId>
  <version>${openalgo-sdk.version}</version>
</dependency>
```

**Gotcha (CLAUDE.md COMMON §3 / this POM):** the module already pins Gson 2.13.2 and excludes javakiteconnect's bundled Gson. The OpenAlgo SDK also returns Gson `JsonObject`. After adding the dependency, run `./mvnw -pl services/market-data-service -am dependency:tree | grep -i gson` and confirm only **one** Gson (2.13.2) resolves; if the SDK drags a conflicting Gson, add an `<exclusion>` for `com.google.code.gson:gson` exactly as the kiteconnect dependency does. **Keep the MIT LICENSE/NOTICE** — record the SDK's copyright in `services/market-data-service/THIRD-PARTY-NOTICES` (create if absent) per the project license filter.

**VERIFY step a:** `./mvnw -pl services/market-data-service -am dependency:resolve | grep openalgo` shows `in.openalgo:openalgo:jar:1.0.1`; `dependency:tree` shows a single Gson 2.13.2; module still compiles.

### 3.2 Anti-corruption wire layer (step b)

Mirror the `kite/wire/` pattern exactly (one record per OpenAlgo response, every documented field, `@JsonIgnoreProperties(ignoreUnknown=true)`, snake_case → camelCase via `@JsonProperty`, DTOs free of domain imports, gateway binds-then-maps). Because the SDK hands back a Gson `JsonObject` rather than typed POJOs, **re-serialize to a String and deserialize with the shared Jackson `ObjectMapper`** into our own wire DTOs (this matches how `LiveQuoteGateway` does `objectMapper.readValue(body, KiteQuoteResponse.class)` and is consistent with the canary, which deliberately bypasses Gson→POJO mapping). The SDK call gives us a `JsonObject` for free, but we do not trust its typing.

**New package** `services/market-data-service/src/main/java/in/arthayantra/marketdata/openalgo/wire/` with a `package-info.java` modeled on `kite/wire/package-info.java` (state: full-mirror, accept-liberally, bind-then-map, drift caught off the critical path by `OpenAlgoContractCanary` + `OpenAlgoWireContractTest`).

Files to CREATE (each `@JsonIgnoreProperties(ignoreUnknown=true)`):

| File | Mirrors OpenAlgo response | Key fields (`@JsonProperty`) |
|---|---|---|
| `OpenAlgoQuoteResponse.java` | `quotes(symbol,exchange)` | `status`, `data` → `OpenAlgoQuote` |
| `OpenAlgoQuote.java` | quote `data` block | `ltp`(BigDecimal), `bid`(BigDecimal), `ask`(BigDecimal), `open`,`high`,`low`,`prev_close`(BigDecimal), `volume`(Long), `oi`(BigDecimal — narrow to Long) |
| `OpenAlgoDepthResponse.java` / `OpenAlgoDepth.java` | `depth(...)` | `ltp`,`open`,`high`,`low`,`volume`,`totalbuyqty`,`totalsellqty`, `bids[]`,`asks[]` → `Level(price,quantity)` |
| `OpenAlgoHistoryResponse.java` | `history(...)` | `status`, `data` → `List<OpenAlgoCandle>` |
| `OpenAlgoCandle.java` | one OHLCV row | `timestamp`(Long epoch or String — VERIFY), `open`,`high`,`low`,`close`(BigDecimal), `volume`(long), `oi`(Long, nullable — present for F&O) |
| `OpenAlgoOptionChainResponse.java` | `optionchain(...)` | `status`, `underlying`, `underlying_ltp`(BigDecimal), `expiry_date`(String DDMMMYY), `atm_strike`(BigDecimal), `chain`→`List<OpenAlgoChainRow>` |
| `OpenAlgoChainRow.java` | one chain row | `strike`(BigDecimal), `ce`→`OpenAlgoLeg`, `pe`→`OpenAlgoLeg` |
| `OpenAlgoLeg.java` | per-strike CE/PE | `symbol`, `label`, `ltp`, `bid`, `ask`, `open`, `high`, `low`, `prev_close`(BigDecimal), `volume`(Long), **`oi`(BigDecimal → Long — the per-strike OI, confirmed present)**, `lotsize`(int), `tick_size`(BigDecimal) |
| `OpenAlgoGreeksResponse.java` | `optiongreeks(...)` | `symbol`, `spot_price`, `option_price`, `implied_volatility`(BigDecimal), `days_to_expiry`, `greeks`→`Greeks(delta,gamma,theta,vega)` |
| `OpenAlgoExpiryResponse.java` | `expiry(...)` | `status`, `data`→`List<String>` (expiry dates DDMMMYY) |
| `OpenAlgoInstrument.java` (+ `OpenAlgoSymbolResponse.java`) | symbol/search | `symbol`, `brsymbol`/`brexchange`, `exchange`, `instrumenttype`, `expiry`, `strike`, `lotsize`, `tick_size`, `token` (VERIFY field names against `/api/v1/search` or `/symbol` — OpenAlgo's instrument master shape differs from Kite's CSV) |

**Decimal handling (matches the Kite pattern):** every price/strike/IV/greek is `BigDecimal` in the wire DTO (Jackson reads JSON numbers into `BigDecimal` losslessly), narrowed to `Long` only for whole-number `oi`/`volume` exactly as `KiteQuote.oi()` (a documented `float64`) is narrowed via `quote.oi().longValue()` in `LiveQuoteGateway.toDomain`. Money continues to cross OUR wire as JSON strings (Jackson emits `BigDecimal` as string; unchanged — no consumer change).

**VERIFY step b:** `OpenAlgoWireContractTest` (§3.5) round-trips a full fixture into each record with non-null assertions, and an extra-fields fixture proves `ignoreUnknown` never throws.

### 3.3 Gateway implementations (step b cont.)

**New package** `services/market-data-service/src/main/java/in/arthayantra/marketdata/openalgo/`. Each gateway takes the shared `RestClient.Builder`, the OpenAlgo `baseUrl`, the `apikey`, the `KiteCallExecutor` (reused as the generic resilience stack — see §3.7), and the shared `ObjectMapper`. **Use the SDK's `OpenAlgo` client for the HTTP call, then re-serialize→Jackson-deserialize** (do NOT introduce a second HTTP client; if the SDK's pinned base URL turns out to be unstubbable for WireMock — the same problem that forced hand-rolled `RestClient` for Kite — fall back to a hand-rolled `RestClient` against OpenAlgo's documented `/api/v1/*` endpoints. **VERIFY the SDK exposes a host-URL constructor arg** — docs show `new OpenAlgo(apiKey, "http://127.0.0.1:5000")`, so WireMock CAN stand in; prefer the SDK).

Files to CREATE:

1. **`OpenAlgoQuoteGateway.java implements QuoteGateway`** — for each `InstrumentKey`, split `canonical()` into `(exchange, tradingsymbol)`, call `client.quotes(symbol, exchange)` (one call per instrument — OpenAlgo quotes is single-symbol per the SDK signature, unlike Kite's batched `/quote?i=...`; this is the documented extra-hop/latency cost). Map: `lastPrice←ltp`, `bid←bid`, `ask←ask`, `volume←volume`, `oi←oi.longValue()`, `Ohlc(open,high,low,prev_close)` (Kite's `ohlc.close` is the **previous** day's close — OpenAlgo's `prev_close` matches that semantic exactly; map `prev_close→Ohlc.close`), `timestamp←OffsetDateTime.now(ZoneOffset.UTC)` (mirror `LiveQuoteGateway`, which stamps fetch time since the staleness guard in `OptionsChainService` needs a real quote age). Exchange mapping: ArthaYantra `InstrumentKey.exchange` is Kite-style (`NSE`,`NFO`,`BSE`,`BFO`,`NSE INDICES`); OpenAlgo wants `NSE`/`NFO`/`BFO`/`NSE_INDEX`/`BSE_INDEX` — add a small `OpenAlgoExchange.map(String)` translator (esp. `"NSE"`+index underlying → `NSE_INDEX`, the only non-trivial case).
2. **`OpenAlgoHistoricalCandleGateway.java implements HistoricalCandleGateway`** — map ArthaYantra interval (`"1m"`,`"1d"`) → OpenAlgo interval (`1m`→`"1m"`, `1d`→`"D"`; OpenAlgo also supports `3m/5m/10m/15m/30m/1h` which we DON'T fetch on-demand today but the scalp engine may later — VERIFY the literal strings against OpenAlgo `history` interval enum). `from_date`/`to_date` format per OpenAlgo (likely `YYYY-MM-DD` — VERIFY; convert `Instant`→IST date via `in.arthayantra.common.web.time.Ist.ZONE` as `LiveHistoricalCandleGateway` does). Map each row → `Candle(key, interval, bucketStart, open, high, low, close, volume, oi)`; `bucketStart` from OpenAlgo `timestamp` (epoch→`OffsetDateTime` in IST — VERIFY epoch unit). OpenAlgo is symbol-addressed so **no `InstrumentTokenResolver` lookup is needed** (drop that dependency vs the Kite impl).
3. **`OpenAlgoInstrumentDumpGateway.java implements InstrumentDumpGateway`** — `fetchDump()` from OpenAlgo's symbol master (`/api/v1/search` or its instrument download — VERIFY endpoint; OpenAlgo keeps a master DB). Map to `InstrumentRecord(instrumentToken=0 or OpenAlgo token, exchange, tradingsymbol, name, instrumentType, segment, expiry, strike, lotSize, tickSize)`. **Note (VERIFY):** if OpenAlgo exposes no full dump, KEEP `LiveInstrumentDumpGateway` (Kite) as the dump source even when quotes/history route through OpenAlgo — this is exactly why the flag is **per-capability** (§3.4). Instrument identity stays Kite-derived; OpenAlgo brokers map symbols to it.

Mapper helpers live in a package-private `OpenAlgoMappers` class (static `toDomain` methods), keeping the gateways thin like `LiveQuoteGateway.toDomain`.

**VERIFY step b-cont:** `OpenAlgoQuoteGatewayWireMockTest`, `OpenAlgoHistoricalCandleGatewayWireMockTest` (mirror the existing Kite WireMock tests under `src/test/.../kite/live/`) stub OpenAlgo `/api/v1/quotes` etc. and assert the mapped domain records.

### 3.4 Config flag + Spring wiring (step c)

Per-capability source selector. Add to `application.yml` (under the existing `artha:` tree; the `live` profile block already configures `artha.options`/`artha.futures`):

```yaml
artha:
  marketdata:
    source:
      quotes:      ${ARTHA_MD_SOURCE_QUOTES:kite}       # kite | openalgo
      history:     ${ARTHA_MD_SOURCE_HISTORY:kite}      # kite | openalgo
      instruments: ${ARTHA_MD_SOURCE_INSTRUMENTS:kite}  # kite | openalgo
  openalgo:
    base-url:  ${OPENALGO_BASE_URL:http://openalgo:5000}     # S2 appliance
    api-key-file: ${OPENALGO_API_KEY_FILE:/run/secrets/openalgo_api_key}
    quote-timeout-ms: 3000
    history-timeout-ms: 10000
```

**New file** `services/market-data-service/.../openalgo/OpenAlgoProperties.java` (`@ConfigurationProperties("artha.openalgo")`, record mirroring `KiteHttpProperties`: `baseUrl`, `apiKeyFile`, with a `resolveApiKey()` reading the secret file). Register in a new `@Configuration`.

**Selection mechanism — preserve the existing one-bean-per-port invariant.** Today exactly one impl per port binds, by profile: `MockKitePorts` (`@Profile("mock")`) and `LiveKiteConfig` (`@Profile("live")`) each declare `@Bean QuoteGateway`/`HistoricalCandleGateway`/`InstrumentDumpGateway`. The cleanest non-invasive change: **move the live bean definitions to be `@ConditionalOnProperty`-gated** so Kite-source and OpenAlgo-source are mutually exclusive per capability.

Create `services/market-data-service/.../openalgo/OpenAlgoConfig.java`:

```java
@Configuration
@Profile("live")
@EnableConfigurationProperties(OpenAlgoProperties.class)
public class OpenAlgoConfig {

  @Bean
  @ConditionalOnProperty(name = "artha.marketdata.source.quotes", havingValue = "openalgo")
  public QuoteGateway openAlgoQuoteGateway(
      RestClient.Builder builder, OpenAlgoProperties props,
      KiteCallExecutor executor, ObjectMapper objectMapper) {
    return new OpenAlgoQuoteGateway(builder, props.baseUrl(),
        props.resolveApiKey(), executor, objectMapper);
  }
  // …openAlgoHistoricalCandleGateway gated on …source.history==openalgo
  // …openAlgoInstrumentDumpGateway gated on …source.instruments==openalgo
}
```

Then in `LiveKiteConfig` add the **inverse** guard to the three existing live `@Bean`s so only one wins:
```java
@Bean
@ConditionalOnProperty(name = "artha.marketdata.source.quotes",
    havingValue = "kite", matchIfMissing = true)   // default = kite
public QuoteGateway liveQuoteGateway(...) { ... }   // unchanged body
```
(same for `liveHistoricalCandleGateway` → `…source.history`, `liveInstrumentDumpGateway` → `…source.instruments`). `matchIfMissing=true` preserves today's Kite-direct default. **`MockKitePorts` is untouched** — mock never uses OpenAlgo.

**Gotcha (CLAUDE.md — rebuild ONE service):** these are live-only beans; mock builds/tests stay credential-free. After editing, rebuild via `docker compose -f deploy/docker-compose.yml --env-file .env build market-data-service && up -d market-data-service` (or the `build-service` skill) with `ARTHA_DB_NAME=artha`/`ARTHA_REDIS_DB=0` set so the other services don't drift.

**VERIFY step c:** boot live with `ARTHA_MD_SOURCE_QUOTES=openalgo` and confirm via actuator/`/conditions` (or a `@SpringBootTest` slice) that exactly one `QuoteGateway` bean exists and it is `OpenAlgoQuoteGateway`; with the flag unset it is `LiveQuoteGateway`. A `PortBindingTest`-style test (one already exists: `services/market-data-service/src/test/.../PortBindingTest.java`) asserting single-bean-per-port under each flag combo.

### 3.5 Contract test + canary (step d)

Two artifacts mirroring the Kite pair (`KiteWireContractTest` + `ContractCanary`/`kite-contract-manifest.json`):

1. **`OpenAlgoWireContractTest.java`** under `src/test/.../openalgo/wire/` — modeled byte-for-byte on `KiteWireContractTest`: one `@Test` per response (quote, depth, history-with-oi, history-without-oi, optionchain, optiongreeks, expiry, instrument). Each feeds a FULL JSON fixture (every documented field) and asserts each mapped component is non-null / decodes to the right type; one extra-field fixture per record proves `ignoreUnknown` never throws (the "Kite additions never crash live" guarantee). Critically, assert the **per-strike `chain[].ce.oi` / `chain[].pe.oi`** map to non-null `Long` (the confirmed OI field — this is the OI-suite spine in Track 2 / Section on Siva strategies).

2. **`OpenAlgoContractCanary.java`** under `src/main/.../openalgo/canary/` + committed `src/main/resources/openalgo-contract-manifest.json` — modeled on `ContractCanary`: 3–4 direct `RestClient` probes against the live OpenAlgo appliance (`quotes`, `history`, `optionchain`, `funds`/`ping`) with the stored apikey, recursive field-set/type diff vs the manifest (sentinels for CONSUMED fields only — `ltp`,`bid`,`ask`,`oi`,`volume`,`open`,`high`,`low`,`close`,`timestamp`,`strike`,`chain[].ce.oi`,`chain[].pe.oi` — not a full mirror), MISSING/TYPE → ntfy critical, NEW → warning, idempotent via a Redis daily-once marker, trading-day-gated. Wire it in `OpenAlgoConfig` only when any `…source.*==openalgo`. Reuse `NtfyClient`, `MarketCalendar`, `KiteCallExecutor` (rename its `MISC` family use, or add an `OPENALGO` family in §3.7). Increments a new `ay_openalgo_contract_drift_total` counter.

**Gotcha (CLAUDE.md — contracts):** these are *Kite-vs-OpenAlgo wire* contract tests (internal), independent of the springdoc `ContractCaptureTest` snapshot. We add no new public `@*Mapping` paths or query params in the default plan, so `/v3/api-docs` does NOT drift and `contracts/gen/*.d.ts` need no regen. (If §3.6's `OptionChainGateway` later adds a new endpoint, re-capture with `-Dcontracts.capture=true` and regen TS.)

**Gotcha (CLAUDE.md — `*IntegrationTest`/`*Test` only):** name the WireMock/contract tests `*Test` (no failsafe plugin; `*IT` is silently skipped). The OpenAlgo canary appears in the **`market-data` CI shard** (`.github/workflows/ci-java.yml`) automatically since it lives in this service — no new matrix shard needed.

**VERIFY step d:** `./mvnw -pl services/market-data-service -am test -Dtest=OpenAlgoWireContractTest` green; `OpenAlgoContractCanary.runNow()` against a WireMock OpenAlgo returns zero MISSING/TYPE drift for the seed manifest.

### 3.6 Optional: direct `OptionChainGateway` (note, not first build)

The default reuses `QuoteGateway.quotes()` fan-out so `OptionsChainService` (IV/Greeks/forward/PCR) is untouched. OpenAlgo's `optionchain` returns the whole chain (all strikes' `oi`/`ltp`/`bid`/`ask`/`volume`) in ONE call — far fewer hops than per-strike `quotes()`. If latency proves a problem for the 1-min snapshot, add a new port `OptionChainGateway.chain(underlying, exchange, expiry, strikeCount)` returning raw legs, with an OpenAlgo impl and a Kite impl (Kite builds it from instrument master + batched quote — i.e. the current `OptionsChainService` path). This is a future optimization; do NOT build it before the basic swap works. OpenAlgo's chain still lacks IV/Greeks/oi_change, so `OptionsChainService.computeLeg` (Black-76 solver) and `OptionsSnapshotService.addRow` (oi_change diff) remain the authority.

### 3.7 Error handling, rate limiter/batching, timeouts (step e)

- **Resilience:** reuse `KiteCallExecutor` (it's a generic `Retry(RateLimiter(CircuitBreaker(call)))` keyed by a `Family` enum; the only Kite-specific bit is `KiteRateLimitedException`/`Retry-After`). Add `OPENALGO("openalgo")` to `KiteCallExecutor.Family` (and rename the class's javadoc to "broker REST" — minimal). Add resilience4j instances in `application.yml`: an `openalgo` rate limiter (start conservative — OpenAlgo proxies the broker, so the effective budget is the broker's; for Kite-behind-OpenAlgo that's ~3/s historical, ~1/s quote — set `limit-for-period: 3, limit-refresh-period: 1s` for quotes since OpenAlgo quotes is single-symbol and the chain fan-out is many calls; MEASURE) and use the `kite-rest` breaker or add an `openalgo-rest` breaker mirroring it.
- **Batching analog:** Kite batches up to `KITE_QUOTE_BATCH_SIZE` (250) symbols per `/quote` call. **OpenAlgo `quotes()` is single-symbol** (SDK signature `quotes(symbol, exchange)`), so a chain refresh that was ~2–4 Kite calls becomes N calls (one per strike). Mitigations, in order: (1) run them on the service's already-enabled **virtual threads** (`spring.threads.virtual.enabled: true` — confirmed in `application.yml`) with a bounded concurrency, under the limiter; (2) prefer §3.6's single `optionchain` call for the chain capture path. Document the extra-hop latency cost (fine for 1-min OI snapshots; **MEASURE for scalp execution** per locked decision 2).
- **Errors:** map OpenAlgo non-success (`status != "success"` / HTTP 4xx/5xx) to the existing `ApiException` codes used by `LiveQuoteGateway`: missing/expired OpenAlgo auth → `401 KITE_TOKEN_EXPIRED` (or add `OPENALGO_AUTH` to `ErrorCodes`), parse failure → `502 KITE_UPSTREAM_ERROR` (or `UPSTREAM_ERROR`), local limiter saturation → `429 RATE_LIMIT_LOCAL`, breaker open → `503` (all already thrown by `KiteCallExecutor`). The unified API may flatten broker-specific fields (e.g. Kite 20-level depth → OpenAlgo's `bids[]`/`asks[]`) — depth beyond level-0 may be unavailable; `OpenAlgoQuoteGateway` uses only best bid/ask (level 0), matching `LiveQuoteGateway.firstPrice`. Mark deeper depth "(VERIFY)".
- **Timeouts:** set per-call timeouts on the `RestClient.Builder` (quote 3s, history 10s) via `OpenAlgoProperties`; OpenAlgo adds a hop over the Kite-direct path.

**VERIFY step e:** unit test that a 429 from a WireMock OpenAlgo triggers retry/`RATE_LIMIT_LOCAL`; a malformed body → `502`; a missing apikey → `401`.

### 3.8 Capture unchanged (step f) — no work, assert it

No change to `OptionsSnapshotService`, `FuturesOiSnapshotService`, `OptionsSnapshotRepository.insertAll`, the Timescale tables, or any Flyway lineage. OpenAlgo is SOURCE; the existing snapshot services keep writing rows. **No new migration.** The only thing that changes is which `QuoteGateway`/`HistoricalCandleGateway` bean those services receive (via §3.4).

**VERIFY step f:** with `ARTHA_MD_SOURCE_QUOTES=openalgo` live against the S2 appliance, the existing `GET /api/v1/market/options/oi-analysis` (+ `chain`, `chain/history`) return data and a row count appears in `marketdata.options_snapshot` (table name VERIFY via `OptionsSnapshotRepository`) — proving capture flows through OpenAlgo into Timescale unchanged.

### 3.9 Numbered build order with verify gates

1. **POM dep** (§3.1). VERIFY: `dependency:tree` single Gson; module compiles.
2. **Wire DTOs + `package-info`** (§3.2). VERIFY: `OpenAlgoWireContractTest` round-trips full fixtures + ignores unknowns (esp. per-strike `oi`).
3. **`OpenAlgoProperties` + secret wiring** (§3.4). VERIFY: `resolveApiKey()` reads the secret file; `@SpringBootTest` live slice binds props.
4. **`KiteCallExecutor.Family.OPENALGO` + resilience4j yml** (§3.7). VERIFY: limiter/breaker instances register (actuator metrics).
5. **`OpenAlgoMappers` + `OpenAlgoQuoteGateway`** (§3.3). VERIFY: `OpenAlgoQuoteGatewayWireMockTest` maps `ltp/bid/ask/volume/oi/ohlc`.
6. **`OpenAlgoHistoricalCandleGateway`** (§3.3). VERIFY: WireMock test maps OHLCV+OI, interval/date formats correct.
7. **`OpenAlgoInstrumentDumpGateway`** (§3.3) — or decide to keep Kite dump. VERIFY: dump maps, or documented decision.
8. **`OpenAlgoConfig` + `@ConditionalOnProperty` guards on `LiveKiteConfig`** (§3.4). VERIFY: `PortBindingTest`-style single-bean-per-port under each flag value.
9. **`OpenAlgoContractCanary` + manifest** (§3.5). VERIFY: canary green vs WireMock; drift fixture fires ntfy.
10. **Live smoke** against S2 appliance (§3.8): boot `live` with `ARTHA_MD_SOURCE_QUOTES=openalgo`, `GET /api/v1/market/options/chain?underlying=NIFTY%2050`, confirm rows land in Timescale and OI/PCR populate. VERIFY: non-empty chain + snapshot row count increments. **Latency check:** time a full chain capture pass through OpenAlgo vs Kite-direct (decision-2 latency caveat).

### 3.10 Cross-section dependencies & gotchas
- **S2 (OpenAlgo appliance)** provides the container, `OPENALGO_BASE_URL`, and the apikey secret consumed here. Hard dependency for live smoke (step 10) and the canary; unit/contract/WireMock tests do not need it.
- **Section: Siva Options Scalper (Track 2)** consumes the per-strike `oi` this gateway sources — the optionchain OI mapping (§3.2/§3.6) is the data spine for Trending-OI/quadrants/sentiment. No change needed there; it reads the existing OI endpoints which now ride OpenAlgo.
- **Section: black76-math port** — Greeks/IV stay in `OptionsChainService` (`libs/black76-math`), NOT OpenAlgo's `optiongreeks` (we mirror the DTO for the contract test only; we do not depend on its numbers in the deterministic path). Keep it that way (decision 5: greeks stay in the no-network replay path).
- **CLAUDE.md gotchas:** build with `-pl services/market-data-service -am` (never bare `-pl`); name tests `*Test`/`*IntegrationTest`; live beans only — mock stays credential-free; `--env-file .env` for any compose; live DB=`artha`/redis0; keep MIT notice for the SDK.

---


## 4. Market-Data Routing Migration (Kite-direct -> OpenAlgo, incl OI capture)

> **Depends on Section 3 (S3 — OpenAlgo appliance + config source flag).** This section assumes S3 has (a) stood up the pinned, unmodified OpenAlgo container behind the anti-corruption boundary, (b) put the OpenAlgo base URL + API key into market-data-service config/secrets, and (c) defined the **per-capability source flag** namespace. Where this section references that flag it uses `artha.md.source.*` (S3 owns the final key names — reconcile if S3 chose differently). Nothing here modifies the OpenAlgo container or imports its AGPL source: market-data-service is a **REST client** of OpenAlgo's `/api/v1/` only.

### 4.1 What exists today (grounded in the repo)

market-data-service binds **five Kite ports** (A.7a), wired live in `services/market-data-service/.../kite/live/LiveKiteConfig.java` under `@Profile("live")`:

| Port (interface) | Live impl | Wire DTO(s) |
|---|---|---|
| `kite/QuoteGateway.java` (`quotes(Collection<InstrumentKey>) -> Map<InstrumentKey,Quote>`) | `kite/live/LiveQuoteGateway.java` — `GET /quote?i=EXCH:SYM&...`, batched `<=` `artha.kite.quote-batch-size` (250), QUOTE limiter (1/s) | `kite/wire/KiteQuote.java`, `KiteQuoteResponse.java` |
| `kite/HistoricalCandleGateway.java` (`fetch(key,interval,from,to) -> List<Candle>`) | `kite/live/LiveHistoricalCandleGateway.java` — `GET /instruments/historical/{token}/{interval}?oi=1&continuous=...`, HISTORICAL limiter (3/s), only `1m`/`1d` | `kite/wire/KiteCandle.java`, `KiteHistoricalResponse.java` |
| `kite/InstrumentDumpGateway.java` (`fetchDump() -> List<InstrumentRecord>`) | `kite/live/LiveInstrumentDumpGateway.java` — instruments CSV | `kite/wire/KiteInstrument.java` |
| `kite/SessionGateway.java` | `LiveKiteConfig.liveSessionGateway` | `KiteSession*` |
| `kite/MarketFeed.java` (WS ticker) | `kite/ticker/LiveTickerFeed.java` (javakiteconnect SDK `KiteTicker`) | binary frames |

**Consumers of the ports (the things that must keep working through the cutover):**
- `options/OptionsChainService.java` — calls `quoteGateway.quotes(...)` for the underlying spot quote and the full strike list, computes IV/Greeks via `libs/black76-math`, returns a `Chain`. **This is the per-strike OI source.**
- `options/OptionsSnapshotService.java` — `@Scheduled(fixedDelayString = "${artha.options.snapshot-interval-ms:300000}")` (live profile overrides to **60000 = 1-min**, `application.yml` `on-profile: live`), calendar-gated; calls `chainService.chain(...)`, then `OptionsSnapshotRepository.insertAll(...)` -> `marketdata.options_chain_snapshots`. Tracks per-leg `oi_change` in an in-memory `previousOi` map.
- `futures/FuturesOiSnapshotService.java` (`@Profile("live")`) — `@Scheduled(...:180000)` (live overrides to 60000); resolves front/next/far FUT via `kite/FuturesContractSource.java`, one batched `quoteGateway.quotes(keys)` call, writes `marketdata.futures_oi_snapshots` via `FuturesOiSnapshotRepository`.
- `candles/CandleQueryService.java` — cache-first OHLCV: `ensureCoverage(...)` calls `gateway.fetch(...)` (the `HistoricalCandleGateway`) for missing pages, upserts to `marketdata.candles`, refreshes caggs. `fetchSource = environment.matchesProfiles("live") ? "KITE" : "MOCK"` is stamped into the `source` column.
- `kite/ticker/*` (WS), `instruments/InstrumentSyncService.java` (dump), `futures/FuturesTermStructureService.java`.

**The TimescaleDB write path is downstream of every port and DOES NOT CHANGE.** The migration swaps *only* the implementation behind `QuoteGateway` / `HistoricalCandleGateway`; `OptionsChainService`, `OptionsSnapshotService`, `FuturesOiSnapshotService`, `CandleQueryService`, `CandleReader` (`backtest-service/.../replay/CandleReader.java`), the repositories, and the schema are untouched. OpenAlgo is **SOURCE, not store** — capture still writes to `marketdata.options_chain_snapshots` / `futures_oi_snapshots` / `candles`.

**The source-flag toggle already has precedent**: `CandleQueryService` reads `environment.matchesProfiles("live")`. The cutover generalizes this into an explicit per-capability flag rather than a hard profile check.

### 4.2 The anti-corruption shape (mirror `kite/wire`)

Create a sibling package `kite/openalgo/` (peer of `kite/live/`) holding the OpenAlgo REST client + full-mirror DTOs + domain mappers — identical discipline to `kite/wire/package-info.java`:

- **One DTO record per OpenAlgo endpoint**, every documented field mirrored, each `@JsonIgnoreProperties(ignoreUnknown = true)`. OpenAlgo's unified API may add fields per broker; this guarantees an additive change can never crash capture.
- **Hand-rolled `RestClient`** (same reason as Kite: testability via WireMock, configurable base URL), NOT the `in.openalgo:openalgo:1.0.1` SDK for the REST capture path. Rationale: the live REST path already uses `RestClient` + `KiteCallExecutor` (rate-limit/retry/breaker family) and a WireMock-able `baseUrl`; the SDK would bypass `KiteCallExecutor` and pin its own URL. **Use the `in.openalgo:openalgo:1.0.1` SDK only for the WS streaming path** if/when we replace the Kite WS ticker (a later capability, lower priority — see 4.3 order).
- DTOs map to the **existing domain port records** `QuoteGateway.Quote` / `HistoricalCandleGateway.Candle` — no new domain types.

**Files to CREATE:**
```
services/market-data-service/src/main/java/in/arthayantra/marketdata/kite/openalgo/
  package-info.java                 # the convention doc (mirror kite/wire/package-info.java)
  OpenAlgoHttpProperties.java       # @ConfigurationProperties(prefix="artha.openalgo")
  OpenAlgoClient.java               # thin RestClient wrapper: postForString(path, bodyMap)
  OpenAlgoQuoteGateway.java         # implements QuoteGateway
  OpenAlgoHistoricalCandleGateway.java  # implements HistoricalCandleGateway
  OpenAlgoOptionChainGateway.java   # NEW port (see 4.5) — per-strike chain incl OI
  wire/OaQuoteResponse.java         # {status, data:{ltp,bid,ask,open,high,low,oi,prev_close,volume}}
  wire/OaHistoryResponse.java       # {status, data:[{timestamp(epoch),open,high,low,close,oi,volume}]}
  wire/OaOptionChainResponse.java   # {status, underlying, underlying_ltp, expiry_date, atm_strike, chain:[{strike, ce:{...}, pe:{...}}]}
  wire/OaDepthResponse.java         # {status, data:{depth:{buy:[{price,quantity,orders}], sell:[...]}}}
OpenAlgoConfig.java                 # @Configuration @Profile("live") @EnableConfigurationProperties — conditional beans (see 4.4)
```
**Files to CREATE (tests):**
```
services/market-data-service/src/test/java/.../kite/openalgo/
  OpenAlgoQuoteGatewayTest.java          # WireMock, mirror LiveQuoteGatewayTest
  OpenAlgoHistoricalCandleGatewayTest.java
  OpenAlgoOptionChainGatewayTest.java
  OpenAlgoWireContractTest.java          # mirror KiteWireContractTest — DTO field coverage vs committed fixture
services/market-data-service/src/main/resources/openalgo-contract-manifest.json  # mirror kite-contract-manifest.json
```
**Files to MODIFY:** `LiveKiteConfig.java` (or new `OpenAlgoConfig.java`) for `@Conditional` bean selection; `application.yml` (`artha.md.source.*` defaults + `artha.openalgo.*`); `pom.xml` only if adding the SDK for WS later.

> **Gotcha (CLAUDE.md):** OpenAlgo emits JSON **numbers** for prices (`"ltp": 265.93`). Jackson binds those to `BigDecimal` fine, but on our OWN wire (the gateway endpoints) decimals must cross as **strings** — that is already handled downstream by the shared `ObjectMapper` config + `core/decimal`; capture writes `BigDecimal` to NUMERIC columns, so no change. Just bind OpenAlgo numbers to `BigDecimal` in the DTOs (never `double`).

### 4.3 Capability inventory + migration order (lowest-risk first)

| # | Capability | Today (Kite-direct) | OpenAlgo source | Risk | Order |
|---|---|---|---|---|---|
| A | **Historical candles** (incl OI for F&O) | `LiveHistoricalCandleGateway` `GET /quote.../historical` | `POST /api/v1/history` (`oi`, `volume`, epoch `timestamp`; intervals `1m/3m/5m/10m/15m/30m/1h/D`) | LOW — backfill path, not latency-critical, idempotent upsert, easy A/B | **1st** |
| B | **Instruments / symbols** | `LiveInstrumentDumpGateway` CSV | OpenAlgo `symbol`/`search` APIs | MED — symbol-format mapping (see 4.6) | **defer / keep Kite** unless broker swap forces it |
| C | **Batch + live quotes** (spot, FUT) | `LiveQuoteGateway` `GET /quote` (batched 250) | `POST /api/v1/quotes` (per-symbol) | MED — **no batch endpoint**; one POST per symbol (mitigate via virtual-thread fan-out + 50/s budget) | **2nd** |
| D | **Option chain + per-strike OI** | `OptionsChainService` over `QuoteGateway.quotes(strikeList)` | `POST /api/v1/optionchain` (whole chain in ONE call, per-strike `oi`) | MED — chain shape differs; OI-zero risk on index (see 4.6) | **3rd** |
| E | **Futures OI** | `FuturesOiSnapshotService` over `QuoteGateway.quotes(futKeys)` | `POST /api/v1/quotes` per FUT (rides capability C once C lands) | LOW once C done | **4th** |
| F | **WS live ticker** | `LiveTickerFeed` (javakiteconnect SDK) | OpenAlgo WebSocket (use `in.openalgo:openalgo` SDK) | HIGH — scalp execution latency; depth flattening | **LAST / separate task** |

**Rationale for the order:** A is a backfill path where a wrong value is caught by re-fetch and the write is idempotent (`ON CONFLICT DO NOTHING`/upsert) — safest place to prove the DTO+mapper+contract-test machinery. C unlocks D and E. D is the irreplaceable dataset (`options_chain_snapshots`, amendment A2 >=5y floor) — migrate it only after C is proven on a real session. F (WS, scalp execution) is highest-risk for latency and is explicitly a later, measured task, not part of this section's core cutover.

### 4.4 How the source flag from S3 flips each capability

Generalize the existing `environment.matchesProfiles("live")` switch into a per-capability flag. **Config keys (`application.yml`):**
```yaml
artha:
  md:
    source:
      historical: KITE      # KITE | OPENALGO  (capability A)
      quotes:     KITE      #                  (capability C)
      optionchain: KITE     #                  (capability D — falls back to per-strike quotes if KITE)
      instruments: KITE     #                  (capability B)
      ticker:      KITE     #                  (capability F)
  openalgo:
    base-url: ${ARTHA_OPENALGO_URL:http://openalgo:5000}
    api-key:  # via secret file, mirror artha.kite.api-key-file pattern
    api-key-file: ${ARTHA_OPENALGO_API_KEY_FILE:/run/secrets/openalgo_api_key}
    quote-fanout-concurrency: 16
    history-max-days-per-page: 30   # OpenAlgo intraday range cap (verified: 30d/request)
```

**Bean selection** — make the live impl picked by the flag, defaulting to Kite. Use `@ConditionalOnProperty` so the Kite impl stays the fallback and is never deleted (owner directive 6f/6g):

```java
// in LiveKiteConfig (or new OpenAlgoConfig), live profile
@Bean
@ConditionalOnProperty(name = "artha.md.source.historical", havingValue = "OPENALGO")
HistoricalCandleGateway openAlgoHistoricalCandleGateway(...) { return new OpenAlgoHistoricalCandleGateway(...); }

@Bean
@ConditionalOnProperty(name = "artha.md.source.historical", havingValue = "KITE", matchIfMissing = true)
HistoricalCandleGateway liveHistoricalCandleGateway(...) { /* existing body unchanged */ }
```
Same `@ConditionalOnProperty` pair for `QuoteGateway` (`artha.md.source.quotes`). Both `@Bean` methods return the same interface, so exactly one binds and **every consumer is unchanged** — `OptionsChainService`, `CandleQueryService`, `FuturesOiSnapshotService` inject the interface. The flag flips at restart (config is static); a flag change = one-service redeploy per CLAUDE.md ("Rebuild + redeploy ONE service": set `ARTHA_DB_NAME`/`ARTHA_REDIS_DB` to the live values, `docker compose ... up -d market-data-service`).

> A flag flip is restart-scoped, not hot. That is acceptable and intentional — it keeps the selection deterministic and avoids a runtime mutable switch in the capture path.

### 4.5 OI capture specifics (capability D)

OpenAlgo `POST /api/v1/optionchain` returns the **whole chain in one call** with per-strike `ce`/`pe` objects each carrying `oi`, `bid`, `ask`, `ltp`, `volume`, `open/high/low/prev_close`, `lotsize`, `tick_size`, plus `underlying_ltp` and `atm_strike`. This is a structurally better fit than today's path (which fans the strike list into one big `quotes()` batch).

**Two integration options — choose B for the cleanest mapping to the existing snapshot:**

- **Option A (minimal):** keep `OptionsChainService` as-is, only swap its injected `QuoteGateway` to `OpenAlgoQuoteGateway` (one POST per strike). Simple but N POSTs per pass; under the 50/s budget a NIFTY full chain (~100+ strikes x CE/PE) blows the per-second budget and adds latency.
- **Option B (recommended):** add a new domain port `OptionChainGateway` so the whole chain arrives in ONE OpenAlgo call, and have `OptionsChainService` source raw legs from it when `artha.md.source.optionchain=OPENALGO`, falling back to the per-strike `QuoteGateway` path (Kite) otherwise. IV/Greeks/forward/PCR math in `OptionsChainService` stays identical — only the raw-leg acquisition changes.

  New port: `kite/OptionChainGateway.java`
  ```java
  public interface OptionChainGateway {
    record Leg(String tradingsymbol, BigDecimal ltp, BigDecimal bid, BigDecimal ask,
               Long volume, Long oi, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal prevClose) {}
    record StrikeRow(BigDecimal strike, Leg ce, Leg pe) {}
    record RawChain(String underlying, LocalDate expiry, BigDecimal underlyingLtp,
                    BigDecimal atmStrike, List<StrikeRow> rows) {}
    RawChain chain(String underlying, LocalDate expiry);   // null legs are first-class
  }
  ```
  `OpenAlgoOptionChainGateway` implements it; `OptionsChainService.chain(...)` branches: when the flag is OPENALGO, take `underlying_ltp` as `spot` (it currently sources spot from the underlying `quotes()` call — `underlying_ltp` replaces that exactly), map each `ce`/`pe` to its existing `computeLeg` inputs, run the unchanged IV/forward/PCR pipeline, return the same `Chain`. **No snapshot-writer or schema change.**

**Shape mapping (OpenAlgo chain -> `options_chain_snapshots`):**
| OpenAlgo field | Domain `Leg` | Snapshot column |
|---|---|---|
| `chain[].strike` | `StrikeRow.strike` | `strike NUMERIC(12,2)` |
| `chain[].ce` / `chain[].pe` | `optionType = "CE"/"PE"` | `option_type CHECK IN ('CE','PE')` |
| `ce.symbol` | `Leg.tradingsymbol` | `tradingsymbol` |
| `ce.oi` | `Leg.oi` (Long) | `oi BIGINT` |
| `ce.bid/ask/ltp/volume` | `Leg.bid/ask/ltp/volume` | `bid/ask/ltp/volume` |
| `underlying_ltp` | `Chain.spot` | `spot_price` |
| (computed) | iv/greeks/forward/pcr/oi_change | unchanged |

`(strike, optionType)` maps 1:1 to the snapshot PK `(ts, underlying, expiry, strike, option_type)`. `oi_change` is still computed in `OptionsSnapshotService.addRow` from the in-memory `previousOi` map — **unchanged**, because it derives from successive snapshot OI values regardless of source.

**Cadence is preserved.** The `@Scheduled` cadence lives in `OptionsSnapshotService`/`FuturesOiSnapshotService`, NOT in the gateway — swapping the source does not touch it. Live profile = 1-min (`snapshot-interval-ms: 60000`). OpenAlgo's general data-API rate limit is **50/s** (vs Kite's 1/s quote), so a 1-min full-chain pass via the single `optionchain` call is comfortably within budget — strictly easier than the current per-strike Kite batch.

> **VERIFY at cutover:** OpenAlgo expiry param is `DDMMMYY` (e.g. `30DEC25`); `OptionsChainService` works in `LocalDate`. The mapper must format `expiry` -> `DDMMMYY` on request and parse `expiry_date` back. Underlying naming differs too: OpenAlgo wants `underlying="NIFTY", exchange="NSE_INDEX"`, whereas our `InstrumentKey` is `("NSE","NIFTY 50")`. Add a symbol-map (see 4.6).

### 4.6 Field-coverage risks + gap detection

1. **`oi: 0` on index option chains (HIGH — verified in OpenAlgo docs).** The official `optionchain` example for NIFTY shows `"oi": 0` on every leg. OI presence depends on the **underlying broker** behind OpenAlgo and on index-vs-stock-options handling. This is the single biggest risk because OI capture is the whole point.
   - **Detect:** a startup/daily **OI-coverage canary** — fetch one in-horizon NIFTY chain via OpenAlgo and assert `sum(oi) > 0` across legs; if zero, fire ntfy critical (reuse `alerts/NtfyClient.java`) and **do not flip** `artha.md.source.optionchain` to OPENALGO. Add this assertion to `OpenAlgoWireContractTest` against a fixture AND as a runtime probe.
   - **Handle:** keep `artha.md.source.optionchain=KITE` (Kite quote `oi` is proven, `KiteQuote.oi`) until OI is confirmed non-zero on the chosen OpenAlgo broker + plan.
2. **No batch quote endpoint (MED).** Kite `/quote` takes up to 250 instruments per call; OpenAlgo `/quotes` is one symbol per POST. `OpenAlgoQuoteGateway.quotes(keys)` must fan out — use virtual threads (already enabled: `spring.threads.virtual.enabled: true`) bounded by `artha.openalgo.quote-fanout-concurrency`, honoring 50/s. Measure (4.7); for the futures snapshot (~19 underlyings x 3 contracts = ~57 POSTs/pass at 1-min) this is fine.
3. **20-level depth flattening (MED, scalp only).** Kite quote `depth` carries 5 buy + 5 sell; OpenAlgo WS depth (Mode 3) is `depth_level: 5` with a `broker_supported` flag. Today `LiveQuoteGateway.toDomain` only ever reads `depth.buy[0]`/`sell[0]` (best bid/ask) — so the snapshot/chain path is **immune** to depth-level differences. Only a future order-microstructure/scalp feature needs >5 levels; flag as a known limitation for capability F, not a blocker for A–E.
4. **Exact OI semantics (MED).** Kite quote `oi` is `float64` narrowed to `long` (`KiteQuote.oi.longValue()`); OpenAlgo `oi` is documented `integer`. Map to `Long` directly. **VERIFY** OpenAlgo OI is total contract OI (not lots) for the chosen broker — compare against Kite for the same symbol/timestamp during the A/B (4.7).
5. **Quote `oi` only on F&O.** Both venues return null/absent OI for indices/equities; existing null-handling in `QuoteGateway.Quote` (nullable `oi`) covers it — no change.
6. **Timestamp form.** OpenAlgo history `timestamp` is **Unix epoch seconds**; Kite historical is `+0530` strings. `OpenAlgoHistoricalCandleGateway` maps epoch -> `OffsetDateTime.ofInstant(Instant.ofEpochSecond(ts), Ist.ZONE)` so buckets land on the same IST offset `CandleReader`/golden fixtures expect (`EngineSeries.IST`). **VERIFY** bucket alignment in the A/B.
7. **Symbol/exchange mapping (MED).** OpenAlgo uses `NSE_INDEX`/`NFO`/`BFO` and underlying tokens like `NIFTY` (not `NIFTY 50`). Build a small bidirectional `OpenAlgoSymbols` mapper (underlying alias + exchange-code map + `DDMMMYY` expiry formatter). Drive it from the existing instrument master where possible; hardcode the handful of index aliases (`NIFTY 50`->`NIFTY`/`NSE_INDEX`, `NIFTY BANK`->`BANKNIFTY`, `SENSEX`->`SENSEX`/`BSE_INDEX`).

**Drift detection (off the critical path):** `OpenAlgoWireContractTest` (CI) = DTO-field-coverage vs committed WireMock fixture (mirror `KiteWireContractTest`). Optionally extend the daily `ContractCanary` pattern with an OpenAlgo probe against `openalgo-contract-manifest.json` (recursive field-set/type diff -> ntfy), but a CI contract test plus the OI-coverage runtime canary are the minimum.

### 4.7 Latency + rate-limit + measurement plan

- **Extra network hop:** ArthaYantra -> OpenAlgo -> broker. For 1-min OI snapshots and backfill (A, D, E) this is negligible. **Scalp execution (F) is the only latency-sensitive path** and is a separate, measured task — do NOT route order execution / live tick consumption through OpenAlgo on the strength of this section alone.
- **Rate limits:** OpenAlgo data APIs 50/s (configurable `API_RATE_LIMIT`); intraday history capped at 30 days/request -> page in `<=30d` windows (`artha.openalgo.history-max-days-per-page`), mirroring the existing `GapDetector.pages(...)` <=60d Kite paging. Reuse `KiteCallExecutor`-style 429 + `Retry-After` handling (OpenAlgo also returns `429` + `Retry-After`); a dedicated `openalgo` resilience4j family in `application.yml` (limit 50/s) is cleaner than reusing the Kite families.
- **Measurement plan (capability by capability):**
  1. Add Micrometer timers in each OpenAlgo gateway (`ay_openalgo_history_duration_seconds`, `ay_openalgo_optionchain_duration_seconds`, `ay_openalgo_quotes_duration_seconds`) — mirror `ay_options_snapshot_duration_seconds` already in `OptionsSnapshotService`.
  2. **A/B parity script (verify):** for the same symbol/window, fetch via Kite-direct and via OpenAlgo, diff OHLCV/OI bar-by-bar and chain leg-by-leg. PowerShell + `Invoke-WebRequest -UseBasicParsing` against both `GET /api/v1/market/candles` (KITE flag) and the OpenAlgo container directly, or a one-off JUnit `*Test`. Acceptance: prices identical to tick size, OI within rounding, bucket timestamps identical on IST.
  3. p50/p99 per capability under live load; for scalp (F) set an explicit latency budget before considering it.

### 4.8 Rollback path

The `@ConditionalOnProperty` pair keeps **both** impls compiled and wired-by-flag. Rollback for any capability = set `artha.md.source.<cap>=KITE` (or delete the override; `matchIfMissing=true` makes KITE the default) and redeploy market-data-service only. No code revert, no data migration: TimescaleDB rows already written by either source are identical-shape (the `source` column on `candles` records `KITE`/`OPENALGO`/`BACKFILL` provenance — **add `OPENALGO` as an allowed value**; for snapshots, optionally add a nullable `source TEXT` provenance column via a new suffix-versioned migration — see 4.9). **Never delete `LiveQuoteGateway`/`LiveHistoricalCandleGateway`** (owner directive). The OI-coverage canary (4.6) gates the forward flip so a bad OpenAlgo OI feed never silently corrupts the irreplaceable snapshot.

### 4.9 Optional schema touch (provenance only)

`marketdata.candles.source` already exists (`CandleQueryService` writes `"KITE"`/`"MOCK"`/`"BACKFILL"`). For audit, add `OPENALGO` as a value. **`candles.source` is CHECK-constrained** (`V003__candles_hypertable.sql`: `source IN ('KITE','TICK_AGG','MOCK','BACKFILL')`) — NOT free-text — so adding `OPENALGO` (and `EXPIRYTRACK`/`OPENCHART`) **REQUIRES a new suffix-versioned migration (V018; see §17.1/§17.4), mandatory before any OpenAlgo capture insert or it 500s.** For snapshots, a nullable provenance column is optional:
```sql
-- deploy/flyway/marketdata/V0NN__snapshot_source_provenance.sql  (NEW, never edit V006/V011 — checksum-locked)
ALTER TABLE options_chain_snapshots ADD COLUMN source TEXT;   -- nullable: pre-cutover rows stay valid
ALTER TABLE futures_oi_snapshots    ADD COLUMN source TEXT;
```
Use the `new-migration` skill; applied migrations are checksum-locked (CLAUDE.md). This is OPTIONAL — only do it if the owner wants per-row source provenance; the snapshot machinery does not require it.

### 4.10 Numbered steps (with VERIFY per step)

1. **Scaffold the anti-corruption package.** Create `kite/openalgo/package-info.java`, `OpenAlgoHttpProperties`, `OpenAlgoClient`, and the `wire/Oa*Response` DTOs (full-mirror, `@JsonIgnoreProperties`). **VERIFY:** `./mvnw -pl services/market-data-service -am package -DskipTests` compiles; `OpenAlgoWireContractTest` (WireMock fixture from real OpenAlgo JSON) passes asserting every documented field binds.
2. **Capability A — historical.** Implement `OpenAlgoHistoricalCandleGateway implements HistoricalCandleGateway` (`POST /api/v1/history`, epoch->IST, <=30d paging, `oi`/`volume`). Add the `@ConditionalOnProperty` bean pair. **VERIFY:** unit/WireMock test green; flip `artha.md.source.historical=OPENALGO` in a scratch live boot, run the A/B parity script for `NIFTY 50` 1m over a recent day vs KITE — bars match; confirm rows land in `marketdata.candles` with the expected `source`.
3. **Capability C — quotes.** Implement `OpenAlgoQuoteGateway implements QuoteGateway` (fan-out POSTs, virtual threads, 50/s, 429/Retry-After). Add bean pair on `artha.md.source.quotes`. **VERIFY:** WireMock test; live A/B on `NSE:RELIANCE` + a FUT — `lastPrice`/`bid`/`ask`/`volume`/`oi` match Kite within tick/rounding.
4. **Capability D — option chain + OI.** Add `OptionChainGateway` port + `OpenAlgoOptionChainGateway`; branch `OptionsChainService.chain(...)` on `artha.md.source.optionchain`. Add the **OI-coverage canary** (4.6). **VERIFY:** the unchanged IV/Greeks/PCR pipeline still passes existing `OptionsChainService`/snapshot tests; with the flag ON in live, run one `OptionsSnapshotService.snapshotNow("NIFTY 50", <expiry>)`, confirm `options_chain_snapshots` gains rows with non-null `oi` for the pass, `(strike,option_type)` complete, `oi_change` populated on the 2nd pass; OI-canary green.
5. **Capability E — futures OI.** No new code beyond C (it rides `QuoteGateway`); just confirm `FuturesOiSnapshotService` works under `artha.md.source.quotes=OPENALGO`. **VERIFY:** one `snapshotNow()`, rows in `futures_oi_snapshots` with `oi`/`oi_change`/day OHLC; A/B vs Kite for `NIFTY 50` front FUT.
6. **Rollback drill.** Flip each capability flag back to KITE, redeploy market-data-service. **VERIFY:** capture continues, rows still land, no exceptions; both impls present in the jar (grep `LiveQuoteGateway` in the running container is unnecessary — the bean wiring test suffices).
7. **(Optional) provenance migration** (4.9) via `new-migration` skill. **VERIFY:** `flyway validate` clean; new column nullable; pre-existing rows unaffected.
8. **Defer F (WS ticker)** to a separate, latency-budgeted task using the `in.openalgo:openalgo:1.0.1` SDK; do not route scalp execution through OpenAlgo until measured.

**Build/CI gotchas (CLAUDE.md):** build with the full reactor + `-am` (never bare `-pl` on a leaf). Integration tests must be `*IntegrationTest`/`*Test` (no failsafe; `*IT` silently skipped). market-data-service is a CI shard; new test classes ride the existing shard. ITs share the singleton Timescale+Redis with no per-method cleanup — give any new IT unique slugs/timestamps. Keep the Bash cwd at repo root (the `guard-paths.py` hook resolves relative to cwd). If `mvnw` can't fetch Maven on this box (TLS-intercepting AV), use the `build-service` skill.

---


## 5. Historical Data Backfill — ExpiryTrack (intraday OI) + openchart (daily)

> **Status of the existing pipeline (read first).** `tools/historical-import/` already
> contains a production loader (`ingest.py`, `test_ingest.py`, `README.md`,
> `DATA_SOURCES.md`, `IMPORT_PLAN.md`, `run_full_load.ps1`) that bulk-loads CSV archives
> into `marketdata.candles` / `iv_history` / `fundamentals`. **Crucially, on 2026-06-19
> the OPTION scope was DROPPED** — mid-backfill the uncompressed `candles` table hit 277 GB
> and filled the C: drive, so all option OHLCV (`source='BACKFILL'`, NFO/BFO) was
> truncate-deleted (277 GB → 1.1 GB) and is never re-loaded; `equity/minute` (the ~2B-row
> giant) was also dropped. This left **intraday option/futures OI history unsolved for
> backtest** — exactly the crux this section fixes. The previously-loaded CSV options were
> raw OHLCV+OI bars in `candles`, NOT in the snapshot tables the oipulse OI engine actually
> reads (`options_chain_snapshots` / `futures_oi_snapshots`). This section routes ExpiryTrack
> output into the **snapshot tables** so the existing OI analytics
> (`OptionsSnapshotReader`/`FuturesSnapshotReader`) replay it directly, and avoids the
> disk blow-up by per-strike snapshot rows (not full candle bars) + immediate compression.

### 5.0 What the readers expect (the ingest target contract)

Grounded in the actual schema and readers:

- **`marketdata.options_chain_snapshots`** (`deploy/flyway/marketdata/V006__options_chain_snapshots.sql`):
  hypertable on `ts TIMESTAMPTZ`, 1-day chunks, compressed after 7d, **no retention** (≥5y floor).
  PK `(ts, underlying, expiry, strike, option_type)`. Columns:
  `ts, underlying, expiry DATE, strike NUMERIC(12,2), option_type ('CE'|'PE'), tradingsymbol,
  ltp, bid, ask, volume BIGINT, oi BIGINT, spot_price, iv, delta, gamma, theta, vega, rho,
  iv_reason, price_source, forward_price, risk_free_rate`. Each row is a **point-in-time
  snapshot** of one strike.
- **`marketdata.futures_oi_snapshots`** (`V011` + `V015__futures_oi_snapshots_ohlc.sql`):
  hypertable on `ts`, PK `(ts, underlying, tradingsymbol)`. Columns:
  `ts, underlying, tradingsymbol, expiry DATE, ltp, volume BIGINT, oi BIGINT, oi_change BIGINT,
  day_open, day_high, day_low, prev_close`.
- **Reader semantics** (`services/market-data-service/.../options/analytics/OptionsSnapshotReader.java`
  and `futures/analytics/FuturesSnapshotReader.java`): every read does
  `public.time_bucket(INTERVAL '<n> minutes', ts, 'Asia/Kolkata')` + `public.last(<col>, ts)`
  GROUP BY bucket+strike. So **each snapshot row's `oi` is the OI value AT that `ts`** (a level,
  not a delta), and `last()` picks the newest row within a downsample bucket. `OiInterval`
  (`options/OiInterval.java`) supports `1m,3m,5m,15m,30m,60m`.
  - **Implication for ExpiryTrack mapping:** ExpiryTrack stores per-1-min-BAR OHLCV+OI where the
    bar's OI is the open-interest as of that bar's close. That maps **directly** to one snapshot
    row per (bar-ts, strike): `ts` = bar timestamp (IST→UTC), `oi` = bar OI level, `ltp` =
    bar close, `volume` = bar volume. No re-bucketing needed at ingest — the reader buckets at
    query time. Store at the **native 1-min cadence**; the readers downsample to
    5/15/30/60/120/240-min as the Siva strategies require.
  - `futures_oi_snapshots.oi_change` is a stored per-bar delta in the live capture. ExpiryTrack
    gives OI levels; compute `oi_change = oi(bar) − oi(prev bar)` for the same contract during
    ingest (NULL on the first bar of a contract's history). `day_open/high/low/prev_close` are
    NULLable (forward-only per V015) — leave them NULL for backfill; the `/eod` rollup
    (`FuturesSnapshotReader.eod`) falls back to `last(ltp)` for close and tolerates NULL day-range.
- **Daily candle consumers diverge (critical for openchart):**
  - The Minervini screener `ScreenerService` (`screener/ScreenerService.java`, `VIEWS` map
    line ~50) reads the **`candles_1d` continuous aggregate** (rolled from 1m bars per
    `V004__candles_continuous_aggregates.sql`).
  - The backtest `CandleReader.readDailyWithWarmup` (`services/backtest-service/.../replay/CandleReader.java`)
    reads **native `marketdata.candles` at `interval='1d'`**.
  - CLAUDE.md states these two diverge for 1d. **openchart daily backfill must write native
    `candles` rows with `interval='1d', source='BACKFILL'`** (satisfies `readDailyWithWarmup`
    directly). The `candles_1d` cagg is built `FROM candles WHERE "interval"='1m'` — so native
    1d backfill rows do **NOT** flow into `candles_1d`. The Minervini screener path needs a
    reconciliation (§5.B.4).
  - `nse_eod_bhavcopy` (`V014`, written by `BhavcopyFetcher`/`NseEodBhavcopyRepository`) is the
    **ongoing** EOD capture (~3.2k symbols/day, OHLCV + delivery). openchart is **one-time
    history only** — it backfills the 200+ day MA warm-up that bhavcopy hasn't accumulated yet.
    The two are reconciled in §5.B.4.

### 5.A — ExpiryTrack appliance (intraday OI history) → snapshot tables

**License/containment:** ExpiryTrack is AGPL-3.0 (Python). Run it **STANDALONE in its own
container, UNMODIFIED, pinned to a release tag**. We consume only its **output data** (DuckDB
→ Parquet) — never its source. Data is not license-encumbered; AGPL stays contained because no
ArthaYantra code is merged with or linked to ExpiryTrack code. (Same boundary as decision 1/3.)

**Why Upstox, not Kite, here (decisive — see §21):** Kite Connect **cannot** serve historical data for
**expired** F&O contracts (instrument tokens drop from the master and get reused after expiry), so it can
NEVER produce this backfill dataset. Upstox uniquely exposes expired-contract 1-min OHLCV+OI history —
that is the entire reason Upstox Plus was bought. This source is *mandatory*, not a preference.

**Prereqs (owner action):** Upstox Plus paid plan is **PURCHASED/ACTIVE** (confirmed 2026-06-21; decision 3). ExpiryTrack needs Upstox
API credentials (API key + secret + redirect URI registered in the Upstox developer console, then
a daily access-token via the Upstox login flow). Store the token in
`deploy/secrets/upstox_access_token` (mirrors the existing `deploy/secrets/postgres_password`
convention used by `run_full_load.ps1`). **(VERIFY)** ExpiryTrack's exact config keys and CLI from
its README at pin time — the steps below assume its documented "capture expired F&O → DuckDB →
export Parquet" flow.

#### 5.A.1 Files to CREATE

```
tools/expirytrack-import/
  Dockerfile                 # pins ExpiryTrack to a release tag (e.g. v<X.Y.Z>), AGPL standalone
  README.md                  # run + containment notes
  config.example.yaml        # Upstox creds path, instruments (NIFTY/BANKNIFTY/SENSEX + F&O stocks),
                             #   expiry-date range to backfill, output Parquet dir
  ingest_oi.py               # Parquet -> options_chain_snapshots / futures_oi_snapshots
  manifest_oi.sqlite         # (runtime, gitignored) per-Parquet-file fingerprint manifest
  test_ingest_oi.py          # pure-logic tests (column mapping, IST->UTC, oi_change calc, dedup)
  requirements.txt           # psycopg[binary]>=3.1, pyarrow (or duckdb), tqdm
```

A compose service stanza is added to `deploy/docker-compose.yml` under the existing
`profiles: [dev-tools]` pattern (so it never starts with the main stack), pinned by `image:`/tag,
NOT a `build:` of repo source:

```yaml
  expirytrack:
    image: <expirytrack-org>/expirytrack:v<X.Y.Z>   # pinned tag, AGPL standalone appliance
    profiles: [backfill]
    env_file: [../.env]                              # CLAUDE.md: NEVER raw compose without --env-file
    volumes:
      - ../tools/expirytrack-import/config.yaml:/app/config.yaml:ro
      - expirytrack_out:/app/output                  # DuckDB + exported Parquet land here
      - ../deploy/secrets:/run/secrets:ro
    # runs its capture+export then exits; NOT a long-running service
```

> Gotcha (CLAUDE.md / Docker): always drive compose via the `ay`/`ay.ps1` CLI or pass
> `--env-file .env` explicitly; a bare `docker compose` blanks vars. Use a NEW compose profile
> (`backfill`) so the appliance is opt-in and never co-starts with live services.

#### 5.A.2 Column mapping — ExpiryTrack Parquet → ArthaYantra snapshot tables

ExpiryTrack stores **1-min OHLCV+OI bars for expired F&O**. Per its schema **(VERIFY exact
Parquet column names at pin time)**, the expected fields and mapping:

**Options (CE/PE) → `marketdata.options_chain_snapshots`:**

| ExpiryTrack field (expected) | ArthaYantra column | Transform |
|---|---|---|
| `timestamp` / `ts` (bar time, IST) | `ts` (TIMESTAMPTZ) | parse IST → UTC (`zoneinfo` IST→UTC; reuse `ingest.py` `_to_utc`) |
| `underlying` / `name` (e.g. `NIFTY`) | `underlying` | uppercase; map index aliases via `INDEX_MAP` style |
| `expiry` (date) | `expiry` (DATE) | as-is |
| `strike` | `strike` NUMERIC(12,2) | as-is |
| `option_type` / `instrument_type` (`CE`/`PE`) | `option_type` | CHECK in ('CE','PE') |
| derived (build Kite-style) | `tradingsymbol` | reuse `ingest.py` `parse_option()` naming: `{SYM}{yy}{MON}{strike}{CE\|PE}` |
| `close` (bar close) | `ltp` | as-is (the snapshot's point-in-time price) |
| `oi` (bar OI level) | `oi` BIGINT | as-is; 0 → NULL (matches `parse_candle_rows` convention) |
| `volume` | `volume` BIGINT | as-is |
| (none) | `spot_price` | NULL (ExpiryTrack has no spot per-strike; spot comes from index OI series) |
| (none) | `bid/ask/iv/delta/gamma/theta/vega/rho/iv_reason/price_source/forward_price/risk_free_rate` | NULL — greeks are a deterministic backtest-replay concern (decision 5: opengreeks ported into `libs/black76-math`, computed at replay, NOT stored from backfill) |

**Futures (FUT) → `marketdata.futures_oi_snapshots`:**

| ExpiryTrack field | ArthaYantra column | Transform |
|---|---|---|
| `timestamp` (IST) | `ts` | IST→UTC |
| `underlying` | `underlying` | uppercase |
| derived | `tradingsymbol` | `parse_option()` FUT naming: `{SYM}{yy}{MON}FUT` |
| `expiry` | `expiry` DATE | as-is |
| `close` | `ltp` | as-is |
| `volume` | `volume` | as-is |
| `oi` | `oi` BIGINT | as-is |
| computed | `oi_change` BIGINT | `oi(bar) − oi(prev bar)` per contract, ordered by `ts`; NULL on first bar |
| (none) | `day_open/day_high/day_low/prev_close` | NULL (forward-only per V015) |

**Identity gotchas to reconcile (flagged):**
- **Instrument identity:** ExpiryTrack is keyed by Upstox instrument keys; ArthaYantra is keyed by
  Kite-style `tradingsymbol`. Build the Kite symbol deterministically from
  `(underlying, expiry, strike, option_type)` using `ingest.py`'s existing `parse_option()` logic
  (already handles BSE→BFO/BSE exchange split via `BSE_UNDERLYINGS = {SENSEX, BANKEX}`). Reuse it
  verbatim — do not re-derive.
- **TZ:** ExpiryTrack bar times are IST. The snapshot tables store UTC `TIMESTAMPTZ`; the readers
  re-localize with `time_bucket(..., 'Asia/Kolkata')`. Convert IST→UTC at ingest (reuse
  `ingest.py` `_to_utc` / `IST = ZoneInfo("Asia/Kolkata")`).
- **OI basis:** ExpiryTrack OI is **per-bar level** = exactly what the snapshot reader's
  `last(oi, ts)` expects. Do NOT store deltas in `options_chain_snapshots.oi`. Only
  `futures_oi_snapshots.oi_change` is a delta and is computed at ingest.
- **Snapshot cadence:** live capture writes a chain snapshot every ~3 min (per the scalping memo).
  ExpiryTrack gives 1-min. **Down-sample to 1-min native is fine** — finer than live, and the
  reader buckets at query time. Do NOT try to match the live 3-min cadence.

#### 5.A.3 Staging table — NO new Flyway migration required

The target tables already exist (`V006`, `V011`, `V015`) with the right columns and `source`-free
PKs, and admin `V001` already grants `ay_backtest` SELECT on all `marketdata` tables (so backtest
reads the backfilled OI for free). **No schema change is needed.** Two notes:

- `options_chain_snapshots` has **no `source` column** (unlike `candles`/`iv_history`). Backfilled
  rows are indistinguishable from live by column — acceptable, since live and backfill never
  overlap in time (live capture started 2026-06-15; ExpiryTrack covers EXPIRED contracts before
  that). If provenance tagging is later wanted, that is a **new suffix-versioned migration**
  (`marketdata/V018__...`), never an in-place edit (CLAUDE.md Flyway lock). Default: do not add it.
- For COPY-then-upsert throughput, the ingest uses a **TEMP staging table** created per worker
  connection (mirror `ingest.py`'s `_STAGE_DDL` / `ON COMMIT PRESERVE ROWS` pattern), not a
  persistent DDL object — so still no migration.

#### 5.A.4 Idempotent / dedup ingest (`ingest_oi.py`)

Reuse the proven mechanics from `tools/historical-import/ingest.py`:
- **Manifest** (`manifest_oi.sqlite`): fingerprint each Parquet file by `(size, mtime_ns[, sha256])`;
  re-runs load only NEW/MODIFIED files (copy the `manifest`/`classify_changes` block).
- **Per-worker persistent psycopg connection**, `SET synchronous_commit = off`, COPY into a TEMP
  stage, then flush with `ON CONFLICT ... DO UPDATE`:

```sql
-- options flush (PK = ts, underlying, expiry, strike, option_type)
INSERT INTO marketdata.options_chain_snapshots
  (ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, volume, oi)
SELECT ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, volume, oi
FROM _oi_stage
ON CONFLICT (ts, underlying, expiry, strike, option_type) DO UPDATE SET
  ltp = EXCLUDED.ltp, volume = EXCLUDED.volume, oi = EXCLUDED.oi,
  tradingsymbol = EXCLUDED.tradingsymbol;

-- futures flush (PK = ts, underlying, tradingsymbol)
INSERT INTO marketdata.futures_oi_snapshots
  (ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change)
SELECT ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change
FROM _futoi_stage
ON CONFLICT (ts, underlying, tradingsymbol) DO UPDATE SET
  expiry = EXCLUDED.expiry, ltp = EXCLUDED.ltp, volume = EXCLUDED.volume,
  oi = EXCLUDED.oi, oi_change = EXCLUDED.oi_change;
```

- **Dedup within a file by PK** before COPY (last-wins), mirroring `parse_candle_rows`'s
  `by_bucket` dict — guards against repeated bar timestamps.
- **DSN gotcha (from README):** always `postgresql://artha:<pw>@127.0.0.1:5432/artha` — `localhost`
  resolves to IPv6 `::1` first and libpq stalls ~130s.
- **DB selection:** live = `artha`, mock rehearsal = `artha_mock` (separate `--manifest`).
- **Bulk-load tuning (from README §Speed tips):** disable the `options_chain_snapshots` /
  `futures_oi_snapshots` compression policies before a big load, compress chunks after — this is
  the disk-blow-up guard. Per-strike snapshot rows are far smaller than full OHLC candle bars, and
  immediate post-load compression (segmentby `underlying, expiry, option_type`) keeps the footprint
  bounded (the 277 GB incident was uncompressed full-candle options in `candles`, a different table).

#### 5.A.5 Run commands

```powershell
# 1. (one-time) build/pull the pinned appliance, run its capture+export
ay backfill expirytrack            # OR: docker compose -f deploy/docker-compose.yml --env-file .env --profile backfill run --rm expirytrack
# -> writes DuckDB + Parquet under the expirytrack_out volume

# 2. ingest the exported Parquet into the snapshot tables (live DB)
$pw  = (Get-Content deploy/secrets/postgres_password -Raw).Trim()
$dsn = "postgresql://artha:$pw@127.0.0.1:5432/artha"
python tools/expirytrack-import/ingest_oi.py `
  --root <expirytrack_out>/parquet --dsn $dsn `
  --manifest tools/expirytrack-import/manifest_oi.sqlite --workers 8

# dry-run (parse + map only, no DB):
python tools/expirytrack-import/ingest_oi.py --root <...>/parquet --dry-run
```

### 5.B — openchart appliance (free NSE daily OHLCV history) → native `candles` 1d

**License:** openchart is MIT (Python) — port/import freely, keep the copyright notice. It needs
**no broker auth** and returns daily + intraday NSE/NFO OHLCV (**no OI**). Use it for the **one-time
200+ day daily history** that the Minervini moving averages need (price>150&200-day MA, 200-day
rising ≥1mo, RS-rank 252-day lookback). Ongoing daily data continues via the existing bhavcopy
capture — openchart fills the pre-history gap only.

#### 5.B.1 Files to CREATE

```
tools/openchart-import/
  README.md                  # run + reconciliation-with-bhavcopy notes (MIT notice preserved)
  fetch_daily.py             # uses openchart lib: for each universe symbol, pull N days 1d OHLCV -> CSV/Parquet
  ingest_daily.py            # CSV/Parquet -> marketdata.candles (interval='1d', source='BACKFILL') + refresh candles_1d
  universe.txt               # NSE equity universe (the F&O+liquid set the screener ranks)
  manifest_daily.sqlite      # (runtime) fingerprint manifest
  requirements.txt           # openchart, psycopg[binary]>=3.1, pyarrow, tqdm
  test_ingest_daily.py
```

Optionally a `profiles: [backfill]` compose stanza like §5.A.1, OR just run `fetch_daily.py`
locally (openchart is pure Python, no creds, no AGPL — a sidecar container is not strictly needed).
Local run is simpler; recommend that.

#### 5.B.2 Mapping → `marketdata.candles` (NO migration; table exists per `V003`)

`candles` PK `(exchange, tradingsymbol, "interval", bucket)`, `source` CHECK allows `'BACKFILL'`,
NUMERIC(18,4) prices, `oi BIGINT` nullable.

| openchart field | candles column | Transform |
|---|---|---|
| date (IST trading day) | `bucket` TIMESTAMPTZ | IST midnight → store as the IST-day bucket. The `candles_1d` cagg uses `time_bucket('1 day', bucket, 'Asia/Kolkata')`, so write `bucket` = IST 00:00 of the trade day converted to UTC (i.e. `YYYY-MM-DD 00:00+05:30`) — matches the existing daily-candle convention used by `readDailyWithWarmup`. |
| symbol | `tradingsymbol` | NSE symbol uppercased |
| (fixed) | `exchange` | `'NSE'` |
| (fixed) | `"interval"` | `'1d'` |
| open/high/low/close | `open/high/low/close` | Decimal, no float |
| volume | `volume` BIGINT | as-is |
| (none — equity) | `oi` | NULL |
| (fixed) | `source` | `'BACKFILL'` |

**Reuse `ingest.py`'s `_UPSERT_INSTRUMENT` + candle COPY/upsert verbatim** — its
`parse_equity()` (strip leading `NNNN_`, uppercase) and `_STAGE_FLUSH`
(`ON CONFLICT (...) DO UPDATE`, `oi = COALESCE(EXCLUDED.oi, candles.oi)`) already do exactly this.
In fact, **openchart-fetched daily CSVs in `equity/day/<SYM>.csv` form can be fed straight into the
existing `tools/historical-import/ingest.py` with `--skip-kinds OPTION`** (it already classifies
`equity` daily → `candles@1d`). A separate `ingest_daily.py` is only needed if openchart's native
output columns differ; prefer reusing `ingest.py` and adding only a thin openchart→CSV `fetch`
step.

#### 5.B.3 Idempotency

Same manifest + `ON CONFLICT DO UPDATE` model as §5.A. Re-runs reload only changed files. A symbol
re-fetched with a longer window upserts cleanly (existing days update, new days insert). Reuse
`ingest.py --load-mode upsert` (incremental-safe) for top-ups, `--load-mode replace` (bucket-range
DELETE + direct COPY) for the first virgin backfill.

#### 5.B.4 Reconcile openchart (one-time) with bhavcopy (ongoing) — REQUIRED

The two daily-candle consumers diverge (confirmed in §5.0):

1. **`CandleReader.readDailyWithWarmup` (backtest)** reads native `candles@1d` → openchart rows
   land there directly. ✅ No extra step.
2. **`ScreenerService` (Minervini) reads the `candles_1d` cagg**, which is materialized
   `FROM candles WHERE "interval"='1m'`. Native 1d backfill rows do **NOT** flow into it. Two
   options — pick one and document it:
   - **(Recommended) Refresh is a no-op for native-1d; instead point the screener path / a thin
     view at native `candles@1d`.** The cleanest in-scope fix: after openchart ingest, the screener
     still won't see the rows via `candles_1d`. So make the screener also see backfilled history by
     reconciling at the data layer — **(VERIFY current behavior:** confirm whether `candles_1d`
     already contains the universe from accrued 1m data; if the universe has no 1m history,
     `candles_1d` is empty for it and the screener returns nothing regardless).
   - **Simpler operational answer:** the Minervini screener's MA gates need 200+ daily closes. Those
     come from native `candles@1d` (openchart) + ongoing `nse_eod_bhavcopy`. If `ScreenerService`
     reads `candles_1d` and that cagg lacks the universe, the screener should read native
     `candles@1d` (the same source `readDailyWithWarmup` uses) for the equity universe. This is a
     **dependency on the S13 screener section** — flag there that the Minervini daily screener must
     read native `candles@1d` (or `nse_eod_bhavcopy`), NOT the 1m-rolled `candles_1d` cagg, because
     backfilled and bhavcopy history never pass through 1m.
3. **Overlap dedup:** openchart history and bhavcopy ongoing capture may both produce the same
   `(NSE, SYM, 1d, day)` once dates overlap. `candles` upsert keys on the PK
   `(exchange, tradingsymbol, "interval", bucket)` so the later write wins idempotently. Bhavcopy
   writes to `nse_eod_bhavcopy` (a different table) — openchart writes to `candles@1d`. They do NOT
   collide; they are parallel daily stores. **The screener must read ONE of them consistently** —
   resolve in S13.

> Open question to resolve in S13 (screener) section: standardize the Minervini daily screener on
> **native `candles@1d`** as the single daily source (openchart history + a small adapter that also
> upserts ongoing bhavcopy EOD into `candles@1d`), OR have it read `nse_eod_bhavcopy` directly and
> backfill openchart history INTO `nse_eod_bhavcopy` instead of `candles`. Either is viable; the
> first keeps backtest + screener on one table. **This section assumes native `candles@1d`.**

#### 5.B.5 Run commands

```powershell
# fetch ~250 trading days of daily OHLCV for the universe -> CSV under equity/day/<SYM>.csv shape
python tools/openchart-import/fetch_daily.py --universe tools/openchart-import/universe.txt `
  --days 280 --out D:/market-import/openchart/equity/day

# ingest via the EXISTING loader (classifies equity-day -> candles@1d, source=BACKFILL)
$pw  = (Get-Content deploy/secrets/postgres_password -Raw).Trim()
python tools/historical-import/ingest.py `
  --root D:/market-import/openchart --dsn "postgresql://artha:$pw@127.0.0.1:5432/artha" `
  --manifest tools/openchart-import/manifest_daily.sqlite --load-mode replace --workers 8 --skip-kinds OPTION
```

### 5.C — Verify checks (per step)

Numbered, with a VERIFY per step. In-container SQL (CLAUDE.md): DB is `artha` (live) / `artha_mock`
(mock); connect via `docker exec` or `127.0.0.1:5432`.

1. **Apply schema preflight.** No new migrations needed (snapshot + candles tables exist). VERIFY:
   `\d marketdata.options_chain_snapshots` shows PK `(ts, underlying, expiry, strike, option_type)`;
   `\d marketdata.futures_oi_snapshots` shows `oi_change`, `day_open..prev_close`.
2. **ExpiryTrack capture+export.** VERIFY: Parquet files exist under the output volume; spot-check
   one with `pyarrow` — has `timestamp/underlying/expiry/strike/option_type/oi/close/volume`.
3. **OI ingest dry-run.** `ingest_oi.py --dry-run`. VERIFY: classification + mapping report shows
   nonzero OPTION + FUT rows, no parse errors; sample tradingsymbol matches Kite form
   (`NIFTY24DEC24500CE`).
4. **OI ingest (live).** VERIFY row counts + a spot symbol/day:
   ```sql
   SELECT count(*), count(DISTINCT underlying), min(ts), max(ts) FROM marketdata.options_chain_snapshots;
   SELECT count(*), count(DISTINCT tradingsymbol) FROM marketdata.futures_oi_snapshots;
   -- spot-check one expiry's chain on one day, downsampled like the reader does:
   SELECT public.time_bucket(INTERVAL '3 minutes', ts, 'Asia/Kolkata') b, strike, option_type,
          public.last(oi, ts) oi
   FROM marketdata.options_chain_snapshots
   WHERE underlying='NIFTY' AND expiry='2024-12-26' AND ts::date='2024-12-20'
   GROUP BY b, strike, option_type ORDER BY b LIMIT 20;
   ```
5. **OI reader replay.** VERIFY through the API (PowerShell `Invoke-WebRequest -UseBasicParsing`,
   login → seed XSRF — CLAUDE.md): `GET /api/v1/market/options/oi-analysis?...&date=2024-12-20`
   returns historical OI rows (the `date`-scoped reader path), confirming
   `OptionsSnapshotReader.latest(...,date)` finds backfilled data. Likewise
   `GET /api/v1/market/futures/oi-analysis?date=...`.
6. **`futures_oi_snapshots.oi_change` correctness.** VERIFY a contract's first bar has
   `oi_change IS NULL` and subsequent bars equal the level diff:
   ```sql
   SELECT ts, oi, oi_change FROM marketdata.futures_oi_snapshots
   WHERE tradingsymbol='NIFTY24DECFUT' ORDER BY ts LIMIT 5;
   ```
7. **openchart fetch + ingest.** VERIFY:
   ```sql
   SELECT tradingsymbol, count(*), min(bucket), max(bucket)
   FROM marketdata.candles WHERE "interval"='1d' AND source='BACKFILL'
   GROUP BY tradingsymbol ORDER BY count(*) DESC LIMIT 20;   -- expect >=200 rows/symbol
   ```
   Spot-check one symbol's close on a known day vs a public source (e.g. RELIANCE on a fixed date).
8. **Screener/backtest unblock.** VERIFY `CandleReader.readDailyWithWarmup('NSE','RELIANCE',...,
   200)` returns ≥200 warm-up bars (backtest path); and the Minervini daily screener returns
   candidates (depends on S13 resolving §5.B.4 — flag if empty because it still reads the empty
   `candles_1d` cagg).
9. **Compression re-enable.** VERIFY after load: compression policies for
   `options_chain_snapshots` / `futures_oi_snapshots` are re-scheduled and old chunks compressed
   (`SELECT compress_chunk(c) FROM show_chunks('marketdata.options_chain_snapshots', older_than => INTERVAL '7 days') c;`)
   — guards the disk-blow-up that killed the original options backfill.
10. **Idempotency.** Re-run both ingests. VERIFY the manifest reports `unchanged` for all files
    (no reload) and row counts are unchanged.

### 5.D — Dependencies & gotchas

- **Unblocks S12 (backtest data)** — backfilled `options_chain_snapshots` /
  `futures_oi_snapshots` are the intraday-OI history the Siva options scalper backtest needs
  (Track 2 OI inputs: Trending-OI 5/15/30/60/120/240-min, OI quadrants, futures OI). Greeks/IV in
  the replay path come from the ported opengreeks math (decision 5), computed at replay — NOT from
  these backfilled rows (they intentionally leave `iv/delta/...` NULL).
- **Unblocks S13 (Minervini screener history)** — openchart provides the 200+ day daily MA
  warm-up. **Hard dependency:** S13 must standardize on **native `candles@1d`** (not the
  1m-rolled `candles_1d` cagg) as the Minervini daily source, per §5.B.4.
- **Reuse, don't rewrite:** `tools/historical-import/ingest.py` already implements the manifest,
  per-worker COPY/upsert, IST→UTC, instrument naming (`parse_option`/`parse_equity`), and the
  `127.0.0.1` DSN fix. The new `ingest_oi.py` copies its manifest/worker skeleton with snapshot-table
  flush SQL; openchart can reuse `ingest.py` directly for the candle path.
- **CLAUDE.md gotchas:** (a) applied migrations are checksum-locked — none are edited here; if a
  `source` provenance column is ever added it is a NEW `marketdata/V018__...`. (b) compose only via
  `ay`/`--env-file .env`; new `profiles: [backfill]` keeps the AGPL appliance opt-in. (c) AGPL
  containment: ExpiryTrack runs as a pinned UNMODIFIED container; ArthaYantra consumes only its
  Parquet output. (d) Keep the Bash cwd at repo root (guard-paths hook) when editing under
  `tools/`. (e) `*.json eol=lf` is irrelevant here (CSV/Parquet/SQLite).

---


## 6. opengreeks -> black76-math Port (higher-order Greeks)

### 6.0 Context and goal

`libs/black76-math` is the dependency-free Black-76-on-the-forward pricing + IV library (Stage D Phase 30A / ADR A10), hoisted out of `market-data-service`'s Phase-14 Greeks engine so live Greeks and the backtest synthetic-premium replay share ONE implementation. Today it computes **price + five first-order Greeks (delta, gamma, theta, vega, rho)** and an IV solver. This section ports the **validated higher-order (second- and third-order) closed-form Greeks** from `opengreeks` (MIT; Rust core + Python API; claims ~1e-13 vs autodiff) into Java, so the Siva scalper strike-selection engine (Section S12) and the React Greeks display (Section S11) can read delta-buckets, vanna/charm/vomma, etc. — all computed **in-process, in the deterministic replay path, with no network hop**.

**Verified current state (do NOT re-derive — read these files):**
- `libs/black76-math/src/main/java/in/arthayantra/black76/Black76.java` — `final` class, `private` ctor. Package `in.arthayantra.black76`. Holds: `enum OptionType{CE,PE}`; `public static final double T_MIN = 5.0/(365.0*24.0*60.0)` (5-calendar-minute T→0 clamp, ≈9.5e-6 yr); `record Greeks(BigDecimal price, delta, gamma, theta, vega, rho)`; `public static double price(OptionType,double f,double k,double t,double r,double sigma)`; `public static Greeks greeks(OptionType,f,k,t,r,sigma)`; package-private `static double rawVega(...)` (the Newton denominator, dPrice/dSigma per 1.0 of vol — distinct from the per-point reporting vega); package-private `static double cdf(double)` / `static double pdf(double)` delegating to a single shared `org.apache.commons.math3.distribution.NormalDistribution NORMAL = new NormalDistribution(null,0,1)`.
- **Pinned conventions (frozen by goldens, amendment A4):** Black-76 on the FORWARD (never BS-on-spot); ACT/365 year fractions; **theta per CALENDAR DAY** (`/365`), **vega per 1 VOL POINT** (`/100`), **rho per 1% rate** (`/100`, and in Black-76 `rho = -T·V/100` because only the discount factor sees `r`). `BigDecimal` at the API surface via `BigDecimal.valueOf(double)`; doubles internally.
- `libs/black76-math/src/main/java/in/arthayantra/black76/IvSolver.java` — bracketed Newton–Raphson + bisection fallback; returns `record IvResult(BigDecimal iv, Reason reason)` with `enum Reason{OK,BELOW_INTRINSIC,ZERO_QUOTE,NO_CONVERGENCE}`; NEVER NaN/Infinity. **Untouched by this section.**
- `libs/black76-math/pom.xml` — artifact `in.arthayantra:black76-math:2.0.0-SNAPSHOT`; only runtime dep is `org.apache.commons:commons-math3:3.6.1`; test deps `junit-jupiter`, `assertj-core`, `jackson-databind`.
- **Tests / golden gate (THE S1 GATE, B-10):** `libs/black76-math/src/test/java/.../Black76GoldenVectorTest.java` loads `src/test/resources/black76-golden-vectors.json` (`fixtureFormat:1`, ≥490 vectors), asserts price + 5 Greeks within **relative ≤ 1e-6, or absolute ≤ 1e-9 where |ref| < 1e-3**, plus an IV round-trip ≤ ₹0.01 and a determinism check. The fixture is generated **offline only** by `tools/greeks-vectors/generate.py` (py_vollib analytical Greeks oracle; A4-sanctioned non-runtime Python; values serialized via Python `repr`, 17 sig-digits, as JSON strings). README at `tools/greeks-vectors/README.md`.
- **A SECOND copy of the fixture exists** at `services/market-data-service/src/test/resources/black76-golden-vectors.json` (the generator's `OUT` path writes there; the lib's copy is a duplicate). Both must stay in lockstep — see step 7.

**Verified consumers (greeks() / price()):**
- `services/market-data-service/.../options/OptionsChainService.java` `computeLeg(...)` (≈ lines 269–294) — solves IV then calls `Black76.greeks(...)`, persists `iv,delta,gamma,theta,vega,rho` (each `scale6(...)`). Persistence schema `deploy/flyway/marketdata/V006__options_chain_snapshots.sql` has exactly those columns at `NUMERIC(12,6)` plus `iv_reason`, `forward_price`, `risk_free_rate`. **No higher-order columns today.**
- `services/market-data-service/.../mockfeed/MockQuoteGateway.java` — uses `Black76.price(...)` for synthetic fair value (no greeks() call).
- `services/backtest-service/.../replay/options/SyntheticPremium.java` — uses `Black76.price(...)` only, in the deterministic `SYNTHETIC_B76` replay path. Confirms greeks compute IN-process (no network hop) — the property this section must preserve.
- Edge corpus test `services/market-data-service/.../options/Black76EdgeCorpusTest.java` exercises `greeks()`/`IvSolver` at T→0.

**Verified: NO higher-order Greeks exist anywhere in Java today** (`vanna|charm|vomma|speed|zomma|color|veta|ultima` matches are only in `docs/oipulse-study/*` study notes, never code). **No license-header / spotless plugin** is configured in `pom.xml` (only `maven-checkstyle-plugin` 3.6.0) — so MIT attribution rides in a Javadoc/`NOTICE` comment, not an enforced header.

---

### 6.1 (a) Gap analysis — have vs. add

| Greek | Order | In black76-math today | Action |
|---|---|---|---|
| price | 0 | ✅ `Black76.price` | keep |
| delta (∂V/∂F) | 1 | ✅ | keep; expose raw `cdf(d1)`-based form for reuse |
| gamma (∂²V/∂F²) | 2 | ✅ (already 2nd-order, kept among "first-order" Greeks) | keep |
| theta (∂V/∂t, /day) | 1 | ✅ | keep |
| vega (∂V/∂σ, /point) | 1 | ✅ | keep |
| rho (∂V/∂r, /1%) | 1 | ✅ | keep |
| IV | — | ✅ `IvSolver` | keep, untouched |
| **vanna** (∂²V/∂F∂σ = ∂vega/∂F) | 2 | ❌ | **ADD** |
| **charm** (∂²V/∂F∂t = ∂delta/∂t) | 2 | ❌ | **ADD** |
| **vomma / volga** (∂²V/∂σ²) | 2 | ❌ | **ADD** |
| **veta** (∂vega/∂t) | 2 | ❌ | **ADD** |
| **speed** (∂³V/∂F³ = ∂gamma/∂F) | 3 | ❌ | **ADD** |
| **zomma** (∂gamma/∂σ) | 3 | ❌ | **ADD** |
| **color** (∂gamma/∂t) | 3 | ❌ | **ADD** |
| **ultima** (∂vomma/∂σ) | 3 | ❌ | **ADD** |
| **dual-delta** (∂V/∂K) | 1 (strike) | ❌ | **ADD** (Section S12 strike-selection nicety) |
| **dual-gamma** (∂²V/∂K²) | 2 (strike) | ❌ | **ADD** |

**Model scope:** Owner's primary track is INDEX options (NIFTY/BANKNIFTY/SENSEX) → **Black-76 on the forward is the only model needed now**. opengreeks also ships Black-Scholes (BSM-on-spot) variants; the equity-options (stock) use-case (Track 1 future) is NOT in scope here — leave a documented seam (§6.3) but do NOT build BSM yet (Working principle #2: no speculative code). Mark BSM port **(VERIFY scope with owner before building)**.

**Convention pin for the new Greeks** (must match the goldens' reporting units or the S1 grid extension will mis-compare):
- Spot/forward-bumped Greeks (vanna, vomma, veta, speed, zomma, color, ultima): report in the **analytical units py_vollib's `py_vollib.black.greeks.numerical` / known references use** — i.e. vega-family Greeks per **vol point** where they differentiate vega, theta-family per **calendar day** where they differentiate theta. Concretely (this is the convention the golden generator must encode, §6.4): **vanna scaled /100** (per vol point, since it is ∂vega/∂F and vega is /100), **vomma /100·/100 i.e. /10000** wait — pin exactly to py_vollib/the chosen oracle and let the oracle define units; do NOT hand-pick scalings. The Java code computes the raw analytical value and applies the SAME scalar the oracle applies. **Document each unit in the Javadoc** exactly as `Black76.java` lines 54/70–74 already do for theta/vega/rho.

---

### 6.2 (b) Exact new methods/classes + signatures

Two files touched in the lib. Keep all internals `double`; cross the API as `BigDecimal` via `BigDecimal.valueOf(double)` (the documented chosen-by-default rounding, same as today). Reuse the existing shared `NORMAL`, `cdf`, `pdf`.

**MODIFY `libs/black76-math/src/main/java/in/arthayantra/black76/Black76.java`:**

1. Add a higher-order record (separate from the frozen `Greeks` record so existing callers/persistence are untouched):
```java
/** Second- and third-order Greeks (ported from opengreeks, MIT). Black-76 on the forward;
 *  units documented per field. Computed on demand — NOT persisted by V006. */
public record HigherOrderGreeks(
    BigDecimal vanna,   // ∂vega/∂F  (per vol point)
    BigDecimal charm,   // ∂delta/∂t (per calendar day)
    BigDecimal vomma,   // ∂vega/∂σ  (volga, per vol point)
    BigDecimal veta,    // ∂vega/∂t  (per calendar day)
    BigDecimal speed,   // ∂gamma/∂F
    BigDecimal zomma,   // ∂gamma/∂σ (per vol point)
    BigDecimal color,   // ∂gamma/∂t (per calendar day)
    BigDecimal ultima,  // ∂vomma/∂σ (per vol point)
    BigDecimal dualDelta,  // ∂V/∂K
    BigDecimal dualGamma) {} // ∂²V/∂K²
```
2. Add the public entry point, mirroring `greeks(...)`'s signature exactly:
```java
public static HigherOrderGreeks higherOrderGreeks(
    OptionType type, double f, double k, double t, double r, double sigma) { ... }
```
   Inside, compute once: `tt = max(t, T_MIN)`, `df = exp(-r*tt)`, `sqrtT = sqrt(tt)`, `d1`, `d2 = d1 - sigma*sqrtT`, `pdfD1 = pdf(d1)` (reuse the existing formulas verbatim so d1/d2 are bit-identical to `greeks()`), then the closed forms ported from opengreeks (`src/.../greeks.rs` / its Python wrapper — port the **formulae**, re-implement in Java, do NOT shell out):
   - `vanna = -df * pdfD1 * d2 / sigma` (then apply the vol-point scalar per §6.1 pin)
   - `vomma = vega_raw * d1 * d2 / sigma` (volga)
   - `charm`, `veta`, `speed`, `zomma`, `color`, `ultima`, `dualDelta`, `dualGamma` — port each closed form from opengreeks, keeping the `df` discount-factor handling correct for **Black-76 on the forward** (opengreeks' BS forms carry `q`/`r` on the spot term; in Black-76 the forward is already the carry-adjusted underlying and only `df=e^{-rT}` multiplies — adapt accordingly, exactly as the existing `theta`/`rho` already do).
3. **Promote two existing private statics to package-private reuse** so the new method shares them and there is no second normal-dist instance: `rawVega` is already package-private; `cdf`/`pdf` already package-private — no change needed.
4. Add a **single guard** consistent with the lib's NaN-safety contract: if `sigma <= 0` the higher-order forms divide by zero. Match `Black76.price`'s existing behavior (it does NOT guard — `SyntheticPremiumTest` documents that `sigma=0` → NaN → `BigDecimal.valueOf(NaN)` throws). **Decision: keep identical** — callers (S12) only pass solver-derived `sigma > 0`. Document it in the Javadoc. (Working principle #2: no error handling for impossible cases.)

**No change to `IvSolver.java`.** No change to the `Greeks` record (frozen, persisted by V006).

**MIT attribution (§6.5):** top-of-file Javadoc note in `Black76.java` near `higherOrderGreeks` + an entry in a repo-root `NOTICE` (create if absent) crediting opengreeks (MIT) for the higher-order formulae.

---

### 6.3 BSM/BSM-on-spot seam (future stock options) — DEFERRED

opengreeks ships Black-Scholes + BSM-with-dividend variants. Track 1 (Minervini) is equities-momentum **screener-only** (no options) per the locked decision, so **do not port BSM now**. Leave the seam: the `OptionType` enum and method shapes are model-agnostic; a future `BlackScholes.java` sibling in the same package would mirror `Black76` with a spot+`q` signature. Mark **(VERIFY)** — only build when a stock-options strategy is actually specified.

---

### 6.4 (c) Numerical-accuracy targets + how to test

**Targets (match the existing S1 gate exactly):** relative ≤ **1e-6**, or absolute ≤ **1e-9** where |reference| < 1e-3. opengreeks claims ~1e-13 vs autodiff; py_vollib's `numerical` greeks (finite-difference) are the practical oracle and agree with analytical to well within 1e-6 across the grid — so 1e-6 is the right, achievable bar (matching how the five existing Greeks are pinned).

**Oracle choice — two independent references, both offline:**
- **Primary:** extend `tools/greeks-vectors/generate.py` to also emit higher-order columns using **`py_vollib.black.greeks.numerical`** finite-difference greeks for the directly-supported ones, and for the rest (vanna/vomma/charm/etc.) a small **autodiff or central-difference** computation on `py_vollib.black.black` (e.g. `jax`/`numdifftools`, dev-machine only — this is the A4-sanctioned non-runtime exception, README already documents the boundary). This is the authoritative oracle.
- **Cross-check (optional, recommended):** run opengreeks' own Python API over the same grid and assert the two oracles agree to 1e-6 before committing the fixture — proves we ported the right formula, not just a self-consistent one. Document in `tools/greeks-vectors/README.md`.

**Fixture extension (bump `fixtureFormat` to 2):** add `vanna,charm,vomma,veta,speed,zomma,color,ultima,dual_delta,dual_gamma` keys to each of the 490 vectors (same B-10 grid: F/K∈{0.85..1.15}@F=22000, T∈{0.5,2,7,30,90}d, σ∈{8..60}%, CE+PE; r=6.5%). Keep all existing keys byte-identical so the five current Greeks never re-compare-differently. `repr()` 17-sig-digit string serialization, `encoding="ascii"`, unchanged.

**Test extension** — `Black76GoldenVectorTest.java`:
- Extend `record Vector` with the 10 new fields; parse them in `loadFixture`.
- Update `assertThat(root.path("fixtureFormat").asInt()).isEqualTo(2)`.
- Add `@Test void higherOrderGreeksMatchOracleAcrossTheGrid()` calling `Black76.higherOrderGreeks(...)` and `assertWithinTolerance(...)` (reuse the existing helper) for each of the 10.
- The existing `priceAndAllGreeksMatchPyVollibAcrossTheGrid`, `ivRoundTripRepricesWithinOnePaisa`, `solverIsDeterministicAcrossRuns` stay AS-IS and MUST stay green (regression guard that the port didn't disturb d1/d2).

**NIFTY chain spot-check (manual / IT):** add to `Black76EdgeCorpusTest` (or a new `HigherOrderGreeksSpotCheckTest`) a finite-difference self-consistency assertion at a realistic ATM NIFTY point (e.g. F=22000, K=22000, T=7/365, σ=0.16, r=0.065): assert `vanna ≈ (vega(F+ΔF) - vega(F-ΔF))/(2ΔF)`, `vomma ≈ (vega(σ+Δσ)-vega(σ-Δσ))/(2Δσ)`, `speed ≈ (gamma(F+ΔF)-gamma(F-ΔF))/(2ΔF)` etc. to ~1e-4 (FD is coarse) — a cheap, oracle-free guard that survives even if the fixture is regenerated. Also assert all 10 are finite at `T=T_MIN` (the expiry-day clamp path), mirroring `expiryDayTimeToZeroStaysFiniteViaTheClamp`.

---

### 6.5 (d) PARITY-SAFETY (CRITICAL — CLAUDE.md guardrail)

The port itself is parity-safe because greeks compute **in-process** in the same Java lib both live and replay consume — no network hop, no wall-clock, no randomness (`Black76.higherOrderGreeks` is a pure function of its args, like `price`/`greeks`).

**The risk is downstream** when S12/S11 attach a higher-order Greek to a `SignalEvent` or `Trade`. Per CLAUDE.md and the verified pattern in `libs/strategy-engine/.../golden/GoldenSignalsJson.java`:
- `GoldenSignalsJson.write()` is **FROZEN** — it serializes only `timestamp/exchange/tradingsymbol/direction/composite/breakdown`. The `SignalEvent` record already carries `stopLoss`/`takeProfit` as a **pure side-channel** (lines 19–34: "`write` never serializes them, so the frozen golden vectors stay byte-identical").
- Therefore: any new Greek field on `SignalEvent`/`Trade` (e.g. an entry-time `delta` used for strike selection, or a displayed `vanna`) MUST (1) be added to the record as a side-channel field, (2) NOT be serialized by `GoldenSignalsJson.write` (no edit to that method), (3) be **computed AT ENTRY deterministically** (the same way `ExitEvaluator.entryLevels` computes stopLoss/takeProfit at entry, NOT per-run) so both deterministic replays compute the identical value and parity holds.
- **Verify with:** `BacktestParityTest` (`services/backtest-service/.../replay/BacktestParityTest.java`) + `GoldenDeterminismTest` must stay green. Golden vectors stay byte-identical.
- **Dependency note:** the actual `SignalEvent`/`Trade` field additions belong to **Section S12 (strike selection) and Section S11 (greeks display)** — this section only delivers the math + the rule. S12/S11 MUST follow the side-channel pattern above; flag it in their sections.

---

### 6.6 (e) MIT attribution

opengreeks is MIT → port/re-implement the formulae freely, keep the copyright notice. Concretely: (1) Javadoc note on `HigherOrderGreeks`/`higherOrderGreeks` — `// Higher-order Greek closed forms ported from opengreeks (MIT, https://github.com/<...>/opengreeks); see NOTICE.`; (2) create/append repo-root `NOTICE` with the opengreeks MIT copyright line. No code is copied verbatim (Rust→Java reimplementation), but attribution is still required and cheap.

---

### 6.7 Numbered steps (each with a VERIFY)

1. **Read & confirm.** Open `Black76.java`, `Black76GoldenVectorTest.java`, `tools/greeks-vectors/generate.py`, `tools/greeks-vectors/README.md`, `GoldenSignalsJson.java`. VERIFY: you can name the d1/d2 formula, the theta/vega/rho unit scalars, and the side-channel rule before writing code.
2. **Port the math.** Add `HigherOrderGreeks` record + `higherOrderGreeks(...)` to `Black76.java`, reusing existing `cdf`/`pdf`/`rawVega` and recomputing d1/d2 with the identical expressions. VERIFY: `./mvnw -pl libs/black76-math -am compile` (build the lib **with the reactor + `-am`**, never a bare `-pl` per CLAUDE.md) succeeds; checkstyle clean.
3. **Extend the oracle.** Update `tools/greeks-vectors/generate.py` to emit the 10 new keys (py_vollib numerical + autodiff/central-difference cross-check), bump `fixtureFormat` to 2, update the `grid`/`oracle` metadata strings. Run on a dev machine (`pip install py_vollib`; the README's A4 boundary). VERIFY: it writes a fixture with ≥490 vectors each carrying all 16 numeric keys; optionally diff opengreeks-Python vs py_vollib oracle ≤ 1e-6.
4. **Copy the fixture to BOTH locations.** The generator's `OUT` writes `services/market-data-service/src/test/resources/black76-golden-vectors.json`; copy the identical bytes to `libs/black76-math/src/test/resources/black76-golden-vectors.json`. (`.gitattributes` pins `*.json eol=lf` — after adding/regenerating, `git add --renormalize` so byte-identical tests don't fail on CRLF.) VERIFY: both files are byte-identical (`git diff --no-index` shows nothing) and LF-terminated.
5. **Extend the golden test.** Update `record Vector`, `loadFixture`, `fixtureFormat==2` assertion, add `higherOrderGreeksMatchOracleAcrossTheGrid`. VERIFY: `./mvnw -pl libs/black76-math -am test` — all four tests green (the three pre-existing + the new one), tolerances met.
6. **Add the spot-check.** Finite-difference self-consistency test at an ATM NIFTY point + finiteness at `T_MIN`. VERIFY: green; vanna/vomma/speed within ~1e-4 of central differences.
7. **Guard the duplicate fixture.** Confirm `market-data-service`'s test suite still passes against the bumped fixture (it reads the same file via `Black76EdgeCorpusTest`/`OptionsChainIntegrationTest`, which only touch the original 6 columns — should be unaffected). VERIFY: `./mvnw -pl services/market-data-service -am test`.
8. **Attribution.** Add the MIT Javadoc note + repo-root `NOTICE` entry. VERIFY: present; checkstyle still clean.
9. **Full-reactor sanity + CI shard.** `libs/black76-math` rides upstream via `-am` in ≥1 CI shard (it is a dep of `market-data-service` and `backtest-service`, both sharded in `.github/workflows/ci-java.yml`), so no new matrix shard is needed. VERIFY: `./mvnw -pl services/backtest-service -am verify` and `-pl services/market-data-service -am verify` both green locally (the JaCoCo ≥60% gate binds per-module; the lib has no service gate but its tests run via the reactor).

---

### 6.8 Files to create / modify (summary)

**Modify:**
- `libs/black76-math/src/main/java/in/arthayantra/black76/Black76.java` — add `HigherOrderGreeks` record + `higherOrderGreeks(...)`.
- `libs/black76-math/src/test/java/in/arthayantra/black76/Black76GoldenVectorTest.java` — extend `Vector`, `fixtureFormat==2`, new test.
- `libs/black76-math/src/test/resources/black76-golden-vectors.json` — regenerated (format 2).
- `services/market-data-service/src/test/resources/black76-golden-vectors.json` — regenerated (kept byte-identical to the lib copy).
- `tools/greeks-vectors/generate.py` — emit 10 new keys + bump format.
- `tools/greeks-vectors/README.md` — document new columns, oracle, cross-check.

**Create:**
- `libs/black76-math/src/test/java/in/arthayantra/black76/HigherOrderGreeksSpotCheckTest.java` (or fold into `Black76EdgeCorpusTest`'s neighbour) — FD self-consistency + T_MIN finiteness.
- repo-root `NOTICE` (if absent) — opengreeks MIT attribution.

**Do NOT touch:** `IvSolver.java`, the `Greeks` record, `GoldenSignalsJson.write`, `V006__options_chain_snapshots.sql` (no new persisted columns — higher-order Greeks are computed on demand for S12/S11; if the owner later wants them archived, that is a NEW suffix-versioned Flyway migration per CLAUDE.md, **never an in-place edit** of the checksum-locked V006).

**Consumers (other sections):** **S12** (Siva scalper) reads `delta` for delta-0.6–0.7 strike selection and may read vanna/vomma for IV-regime gating — it calls `Black76.greeks(...)`/`higherOrderGreeks(...)` directly in-process; any Greek persisted to a `SignalEvent`/`Trade` MUST ride the side-channel + compute-at-entry rule (§6.5). **S11** (React Greeks display) reads them off the options-chain API / a new endpoint — display-only, no parity impact. Both depend on this section's math being merged and S1-gate-green first.

---


## 7. Technical-Indicator Engine Port (VWAP/Supertrend/VWMA/RSI/PSAR)

### 7.0 Context — what already exists (read this first)

ArthaYantra **already has a deterministic Java indicator engine**. Do **not** build a new one or pull in pyindicators at runtime — extend the existing engine. The relevant code (all under `libs/strategy-engine/`, the single shared JAR embedded in **both** `strategy-signal-service` (live) and `backtest-service` (replay) per ADR D6/D7) is:

- **`libs/strategy-engine/src/main/java/in/arthayantra/strategyengine/indicators/`**
  - `EngineIndicator.java` — the interface every indicator implements: `BigDecimal valueAt(int index)` (null while warming up / inputs unavailable — a null can never silently become zero in a composite) and `int unstableBars()`. Output scale is `SCALE = 8` dp.
  - `IndicatorRegistry.java` — the **id → (Definition, Factory)** map. `Definition(String id, String description, Set<String> params, boolean requiresContext)`. `create(name, EngineSeries series, EngineSeries context, Map<String,Object> params)` is the single construction entry point; `exists(name)` is the server-side save/publish existence check; `knownNames()` is the advisory enum source. **This is where every new indicator registers.** Currently registered: `EMA, SMA, RSI, VWAP, ADX, MACD_HIST, SUPERTREND, VOLUME_RATIO, OI_CHANGE_PCT, ATR, ORB_HIGH, ORB_LOW, PREV_DAY_HIGH, PREV_DAY_LOW, PREV_DAY_CLOSE, DAY_HIGH, DAY_LOW, GAP_PCT, RS_VS_INDEX, VIX_LEVEL`.
  - `Ta4jIndicators.java` — ta4j 0.22.0 wrappers (`Num`/`DecimalNum`-32 in, boundary-rounded `BigDecimal` out via `wrap(...)`). **RSI and SuperTrend already exist here**: `rsi(series, period)` wraps `org.ta4j.core.indicators.RSIIndicator`; `supertrendDirection(series, period, multiplier)` wraps `org.ta4j.core.indicators.supertrend.SuperTrendIndicator` and returns **+1 uptrend / -1 downtrend** (not the band level).
  - `SessionIndicators.java` — hand-rolled session/IST family. **VWAP already exists**: `sessionVwap(series)` is cumulative session VWAP = Σ(typical·vol)/Σvol from `series.sessionStart(index)`, typical = (h+l+c)/3, IST-session-anchored. Also `volumeRatio`, `oiChangePct`, `openingRange`, `previousDay`, `dayExtreme`, `gapPct`, `rsVsIndex`, `contextLevel`.
  - `EngineMath.java` — `MC = MathContext(32, HALF_UP)`, `HUNDRED`, `round(v)` → `setScale(8, HALF_UP)`. **All new math MUST use `EngineMath.MC` and end at `EngineMath.round(...)`.**
- **`series/EngineSeries.java`** — wraps a ta4j `BarSeries` (DecimalNum precision 32) + a per-bar OI column + **IST session geometry**: `sessionStart(i)`, `previousSessionEnd(i)`, `indexAtOrBefore(instant)`, `candle(i)`, `oiAt(i)`, static `sessionDate(candle)` (uses `IST = ZoneOffset.ofHoursMinutes(5,30)`). `intervalDuration(String)` maps the interval string → `Duration`.
- **`series/EngineCandle.java`** — `record EngineCandle(OffsetDateTime bucketStart, BigDecimal open, high, low, close, long volume, BigDecimal oi)` (+ a no-OI convenience ctor).
- **`eval/IndicatorBank.java`** — builds all indicator instances for one (strategy, instrument), alias-addressable, with multi-timeframe + A7 context-override resolution. `valueAt(alias, primaryIndex)` / `previousValueAt(...)` map a coarser-timeframe indicator to its **last COMPLETED bar at the primary bar's close** via `mappedIndex(...)` (a half-built bucket never leaks — identical rule in live and replay). `builtin(name, idx)` exposes `close`/`volume`/`vwap`.

**Determinism / parity machinery (already frozen — your new indicators ride it):**
- `tools/indicator-vectors/generate_vectors.py` — the **A4-exception reference-vector generator**: runs ONCE, mirrors the engine's arithmetic exactly (Python `Decimal` prec 32, `ROUND_HALF_UP`, boundary-rounded to 8 dp), writes CSVs to `libs/strategy-engine/src/test/resources/vectors/`. Header documents the exact ta4j recurrences mirrored.
- `libs/strategy-engine/src/test/java/.../VectorFixtures.java` — **bar-for-bar Java recreation of the same synthetic series** (2 IST sessions 2026-02-03/04, 40×1m bars/day, integer-cents formulas identical to the Python). The CSVs hold EXPECTED outputs only.
- `libs/strategy-engine/src/test/java/.../IndicatorVectorTest.java` — `@TestFactory` asserting every registry indicator matches its committed vector **exactly** (decimal-string compare at 8 dp; blank rows assert null). Add one `Vector(...)` row per new vector-pinnable indicator.
- `SupertrendBehaviorTest.java` — SUPERTREND is deliberately **NOT** vector-pinned (ta4j-internal band ratchet); it is behavior-tested (sustained rise = +1, fall = -1, V-shape flips) and frozen end-to-end by the Phase 23 goldens. **PSAR has the same recurrence-ratchet character → behavior-test, not vector-pin (see 7.4).**
- `golden/GoldenSignalsJson.java` (FROZEN `write()`) + `GoldenDeterminismTest.java` — end-to-end byte-identical signal goldens. Adding indicators does not touch the frozen writer; a strategy YAML that *uses* a new indicator gets a new golden fixture (see §S12 plan).

**The advisory indicator enum is in `libs/strategy-schema/src/main/resources/strategy-schema/strategy-schema-v1.json`** at `$defs.indicatorName.anyOf[0].enum` (line ~207). **This is an APPLIED, checksum-locked Flyway-adjacent contract but the schema file itself is NOT a migration** — it is the JSON Schema resource. Because the schema's second `anyOf` branch is the open pattern `^[A-Z][A-Z0-9_]*$`, an unknown name is a **registry warning at save / refusal at publish, NEVER a schema violation** (Q2/A5). So editing the advisory enum is OPTIONAL-but-recommended for editor autocomplete; it is **not** load-bearing and does **not** drift the springdoc contract.

### 7.1 What the Siva scalper needs vs. what exists (gap table)

| Indicator (Siva params) | Status | Action |
|---|---|---|
| **VWAP** (session-anchored, IST) | ✅ EXISTS (`VWAP`, `SessionIndicators.sessionVwap`) | none — verify 3m support (§7.5) |
| **Supertrend(10,2)** | ✅ EXISTS (`SUPERTREND`, default period 10 / mult 3) — pass `multiplier:2` | none — params already supported |
| **RSI14 (80:20 bands)** | ✅ EXISTS (`RSI`) — the 80/20 bands are **gate thresholds in the strategy YAML**, not an indicator param | none — encode bands in S12 strategy gates |
| **VWMA(20)** | ❌ MISSING | **CREATE** `VWMA` (§7.3) |
| **PSAR(0.02,0.2)** | ❌ MISSING (ta4j has `ParabolicSarIndicator`) | **CREATE** `PSAR` (§7.4) |
| **Advance/Decline breadth** | ⚠️ EOD-only service (`BreadthService`, NSE EQ bhavcopy) — NOT an engine indicator, not intraday | **CREATE** `ADVANCE_DECLINE_RATIO` engine helper backed by a breadth context-series (§7.6) |
| **Futures basis** (futures − spot) | ❌ MISSING | **CREATE** `BASIS` / `BASIS_PCT` context-mechanism indicator (§7.7) |
| **3m execution + 15m/60m bias** | ⚠️ `3m` is NOT in the engine interval vocab (only 1m/5m/15m/1h/1d/1w) | **ADD `3m`** across interval maps + schema enum (§7.5) |

VWAP, Supertrend, and RSI are **done** — only VWMA, PSAR, advance/decline, basis, and the `3m` interval are new work.

### 7.2 Where the new code lives

All new indicators register in the **existing** `IndicatorRegistry` and live in the **existing** packages — no new package needed:
- Pure-formula indicators on the close/OHLCV → add a `Ta4jIndicators.vwma(...)` / `Ta4jIndicators.psar(...)` static (wrap ta4j) **or** a hand-rolled method in `SessionIndicators` when ta4j lacks it.
- Cross-instrument indicators (basis, advance/decline) → `requiresContext()=true`, evaluate ON the signal series AGAINST a context series, exactly like the existing `rsVsIndex` / `contextLevel` (so `IndicatorBank.build` and `SignalEngine.reload` already subscribe the context symbol — see `IndicatorBank` lines 60-79 and `SignalEngine.reload` lines 190-201, which subscribe `spec.instrument()` context symbols as **series inputs only, never signal emitters**).

Output is always a single `BigDecimal` per bar through `EngineIndicator.valueAt`. No new record types are needed.

### 7.3 VWMA(20) — Volume-Weighted Moving Average

ta4j 0.22 has no VWMA. **Hand-roll it in `SessionIndicators`** (rolling window, NOT session-cumulative — VWMA(20) means the last 20 bars regardless of session, matching pyindicators/TradingView `vwma = sma(close*volume, n) / sma(volume, n)`):

```java
// SessionIndicators.java — new method
/** Volume-weighted MA: sum(close*vol, n) / sum(vol, n) over the trailing n bars. */
static EngineIndicator vwma(EngineSeries series, int period) {
  return indicator(
      period - 1,        // unstable: needs `period` bars (index >= period-1)
      series,
      index -> {
        BigDecimal pv = BigDecimal.ZERO;
        BigDecimal vol = BigDecimal.ZERO;
        for (int i = index - period + 1; i <= index; i++) {
          EngineCandle c = series.candle(i);
          BigDecimal v = BigDecimal.valueOf(c.volume());
          pv = pv.add(c.close().multiply(v, EngineMath.MC));
          vol = vol.add(v);
        }
        return vol.signum() == 0 ? null : pv.divide(vol, EngineMath.MC);
      });
}
```
Register in `IndicatorRegistry` static block:
```java
register(
    new Definition("VWMA", "Volume-weighted moving average of close", Set.of("period"), false),
    (s, c, p) -> SessionIndicators.vwma(s, requirePositive(p, "period", 20)));
```
**Vector-pin it** (deterministic, no ratchet): add a `VWMA_period20` writer to `generate_vectors.py` and a `Vector("VWMA_period20","VWMA",Map.of("period",20),false)` row to `IndicatorVectorTest`. Reference Python:
```python
vwma = []
for i in range(N):
    if i < 19:
        vwma.append(None); continue
    win = bars[i-19:i+1]
    pv = sum(b["c"]*b["v"] for b in win); vol = sum(b["v"] for b in win)
    vwma.append(pv/vol)
write("VWMA_period20", [(i, vwma[i]) for i in range(N)])
```

### 7.4 PSAR(0.02, 0.2) — Parabolic SAR

ta4j 0.22 ships `org.ta4j.core.indicators.ParabolicSarIndicator(BarSeries, Num aF, Num maxA)` (or `(series)` for defaults 0.02/0.2 — **VERIFY the exact ctor arity/types in ta4j 0.22.0** before wiring). Wrap it in `Ta4jIndicators`:

```java
static EngineIndicator psar(EngineSeries series, BigDecimal step, BigDecimal max) {
  var num = series.barSeries().numFactory();
  ParabolicSarIndicator psar =
      new ParabolicSarIndicator(series.barSeries(), num.numOf(step), num.numOf(max));
  int unstable = Math.max(2, psar.getCountOfUnstableBars());
  return wrap(psar, unstable);     // reuse the existing wrap(...) — boundary-rounds to 8dp, NaN→null
}
```
Register:
```java
register(
    new Definition("PSAR", "Parabolic SAR stop-and-reverse level", Set.of("step", "max"), false),
    (s, c, p) -> Ta4jIndicators.psar(
        s, p.decimalValue("step", new BigDecimal("0.02")), p.decimalValue("max", new BigDecimal("0.2"))));
```
**PSAR returns a price level** (not ±1). Scalper logic ("price above PSAR = long bias") is encoded as a `close > PSAR` gate in the S12 strategy YAML, not in the indicator.

**Do NOT vector-pin PSAR** — like Supertrend it has a stateful acceleration-factor ratchet that is ta4j-internal; mirroring it bit-for-bit in Python is brittle. Instead **behavior-test it** in a new `PsarBehaviorTest.java` (mirror `SupertrendBehaviorTest`): sustained rise → PSAR stays *below* close and rises; sustained fall → PSAR stays *above* close; a V-shape flips side; warm-up returns null. Determinism (the parity guarantee S12 needs) is then proven end-to-end by the Phase-23 golden harness when an S12 strategy YAML references `PSAR`. Add a one-line note to the `generate_vectors.py` header documenting why PSAR (like SUPERTREND) is behavior-tested, not vector-pinned.

### 7.5 Add the `3m` interval (the scalper's execution timeframe)

The Siva scalper executes on **3m** candles. `3m` is honored by the OI/options query layer (`OiInterval.parse("3m")`) but **NOT** by the engine interval vocabulary. Three places hard-code the interval switch and one schema enum:

1. **`libs/strategy-engine/.../series/EngineSeries.java` `intervalDuration(String)`** — add `case "3m" -> Duration.ofMinutes(3);`.
2. **`libs/strategy-engine/.../eval/IndicatorBank.java` `intervalOf(String)`** — add `case "3m" -> Duration.ofMinutes(3);` (so multi-timeframe mapping 3m↔15m/60m works).
3. **`services/strategy-signal-service/.../signals/SignalEngine.java` `intervalDuration(String)`** (lines ~660-667) — add `case "3m" -> Duration.ofMinutes(3);` so `evaluateCoarsePrimary` computes the right bucket boundary for a 3m primary.
4. **`libs/strategy-schema/src/main/resources/strategy-schema/strategy-schema-v1.json`** `$defs.interval.enum` (line ~53) — add `"3m"`: `["1m","3m","5m","15m","1h","1d","1w"]`.

**Critical dependency — depends on §S5/§S6 (market-data candle aggregates).** The engine reads coarser series from the **`candles_<iv>` continuous aggregates** (`CandleReader.read()` per CLAUDE.md). There is currently **no `candles_3m` cagg** (caggs are 5m/15m/1h/1d/1w). For 3m coarse-primary live evaluation and 3m backtest replay, the market-data section **must add a `candles_3m` continuous aggregate** (new suffix-versioned Flyway migration in the `marketdata` lineage, `time_bucket('3 minutes', bucket, origin/timezone 'Asia/Kolkata')` — match the existing caggs' IST origin so 3m buckets align to 09:15 IST session open). **If 3m is built only from live 1m bars in `LiveSeriesStore` (resampling), confirm the same 3m buckets are reproducible in replay (VERIFY with §S5 owner).** Note this cross-dependency explicitly in the plan; the indicator port itself is interval-agnostic once the maps accept `3m`, but it cannot run on 3m until the candle spine serves 3m.

**IST bucketing is already correct**: `EngineSeries` derives sessions from `sessionDate(candle)` using `IST = +05:30` off bar timestamps (never UTC/calendar days), and VWAP/ORB/PREV_DAY anchor to `sessionStart`. This aligns with the platform's `time_bucket(..., 'Asia/Kolkata')` origin. No change needed to session logic — just the interval duration maps above.

### 7.6 Advance/Decline breadth as an engine input

`BreadthService` (market-data) computes advance/decline counts from the **EOD** `nse_eod_bhavcopy` (V014), exposed at `GET /api/v1/market/breadth` — **EOD only, not intraday, and not an engine indicator**. The Siva scalper wants intraday breadth as a confluence gate.

**Plan (two-part, lower priority than VWMA/PSAR):**
- **Data (depends on §S5/market-data):** capture an intraday advance/decline ratio as a synthetic 1m/3m **context series** under a reserved tradingsymbol, e.g. `NSE:ADRATIO` (close = advances/declines or net A−D), written to TimescaleDB by the market-data breadth capture so the engine reads it via `CandleReader` like any other series. (VERIFY whether intraday breadth is even capturable from the OpenAlgo/Kite feed — Siva uses a vendor breadth feed; if unavailable intraday, mark this **deferred/optional** and keep breadth EOD-only for the Minervini screener, §S? Track-1.)
- **Engine:** register `ADVANCE_DECLINE_RATIO` as a **context-mechanism indicator** (`requiresContext()=true`) whose value = `contextLevel(series, context)` (reuse the existing `SessionIndicators.contextLevel` — it just returns the context-series close at-or-before the primary bar). No new math; the breadth number IS the context close. The strategy YAML attaches it with an indicator-level `instrument: {exchange: NSE, tradingsymbol: ADRATIO}` override, exactly as `VIX_LEVEL` does.

This keeps breadth out of the deterministic math path (it's just a series read) — parity holds trivially.

### 7.7 Futures-basis helper

Siva uses futures OI/basis as confluence. Add a **context-mechanism** indicator `BASIS_PCT` = `(spot − futures) / futures × 100` (sign per Siva's convention — **VERIFY direction with the source docs in `C:\Trading\ArthaYantra\StockMarketStrategyTraining`**), evaluated on the signal (spot/index) series against the **front-month futures context series**. Mirror `rsVsIndex`'s shape (own series + context series, time-aligned via `context.indexAtOrBefore(primaryBucketStart)`):

```java
static EngineIndicator basisPct(EngineSeries spot, EngineSeries futures) {
  return indicator(0, spot, index -> {
    int fi = futures.indexAtOrBefore(spot.candle(index).bucketStart().toInstant());
    if (fi < 0) return null;
    BigDecimal f = futures.candle(fi).close();
    BigDecimal s = spot.candle(index).close();
    if (f.signum() == 0) return null;
    return s.subtract(f).divide(f, EngineMath.MC).multiply(EngineMath.HUNDRED, EngineMath.MC);
  });
}
```
Register with `requiresContext()=true`, params `Set.of()`. The front-month context symbol is resolved the same way `SignalEngine.resolveUniverse` already handles `futures_of_underlying` (front_month + roll) — reuse `FuturesUniverseResolver`; the strategy attaches the futures instrument via an indicator-level `instrument` override. **Vector-pin BASIS_PCT** (deterministic, no ratchet) using the existing context-series fixture — add a `BASIS_PCT` writer to `generate_vectors.py` and a `Vector(...,context=true)` row.

### 7.8 Determinism & parity rules (apply to every new indicator)

1. **Compute-at-entry, never per-run random.** Every value is a pure function of the candle series + params; no `now()`, no RNG, no mutable static state. (Side-channel exit levels are computed at entry per the frozen `GoldenSignalsJson` contract — see CLAUDE.md "Extend engine records parity-safely".)
2. **Same JAR, both paths.** `strategy-signal-service` (live) and `backtest-service` (replay) embed the identical `strategy-engine` JAR; an indicator computed once is correct in both. **Build with the full reactor + `-am`** (`./mvnw -pl libs/strategy-engine -am package` then the two services) — never a bare `-pl` on the leaf lib (CLAUDE.md: a bare `-pl` install embeds a stale lib in the compose fat JAR).
3. **All arithmetic through `EngineMath.MC` (precision 32, HALF_UP), all outputs through `EngineMath.round(...)` (8 dp).** ta4j-wrapped indicators inherit this via `Ta4jIndicators.wrap(...)`. Hand-rolled ones must call `EngineMath.round` at the boundary (the `SessionIndicators.indicator(...)` helper already does).
4. **Null-on-warm-up, never zero.** Return `null` (not 0) while `index < unstableBars()` or inputs are missing — the engine refuses to score a null; it never silently becomes 0.
5. **Vector-pin the closed-form ones, behavior-test the ratchets.** VWMA, BASIS_PCT, ADVANCE_DECLINE_RATIO → reference vectors (Python mirror in `generate_vectors.py` + Java fixture recreate + `IndicatorVectorTest` row). PSAR → `PsarBehaviorTest` only (acceleration-factor ratchet, ta4j-internal), frozen end-to-end by the S12 golden YAMLs.
6. **Strategy bands/thresholds are YAML gates, not indicator params.** RSI 80/20, "price > VWAP", "close < PSAR", VWMA crossovers, volume gates (≥50K BN / ≥125K N) all live in the S12 strategy gate/scoring nodes — the indicator just emits the raw number. The `requiresContext()` registry flag is the only behavioral switch the engine itself carries.

### 7.9 Files to CREATE / MODIFY

**Modify:**
- `libs/strategy-engine/.../indicators/Ta4jIndicators.java` — add `psar(...)`.
- `libs/strategy-engine/.../indicators/SessionIndicators.java` — add `vwma(...)`, `basisPct(...)`; reuse `contextLevel` for advance/decline.
- `libs/strategy-engine/.../indicators/IndicatorRegistry.java` — register `VWMA`, `PSAR`, `BASIS_PCT`, `ADVANCE_DECLINE_RATIO`.
- `libs/strategy-engine/.../series/EngineSeries.java` — `intervalDuration` add `3m`.
- `libs/strategy-engine/.../eval/IndicatorBank.java` — `intervalOf` add `3m`.
- `services/strategy-signal-service/.../signals/SignalEngine.java` — `intervalDuration` add `3m`.
- `libs/strategy-schema/src/main/resources/strategy-schema/strategy-schema-v1.json` — `$defs.interval.enum` add `"3m"`; advisory `indicatorName` enum add `VWMA, PSAR, BASIS_PCT, ADVANCE_DECLINE_RATIO` (optional but recommended).
- `tools/indicator-vectors/generate_vectors.py` — add `VWMA_period20`, `BASIS_PCT`, `ADVANCE_DECLINE_RATIO` writers; document PSAR exclusion.

**Create:**
- `libs/strategy-engine/src/test/java/in/arthayantra/strategyengine/PsarBehaviorTest.java` — mirror `SupertrendBehaviorTest`.
- Vector CSVs (regenerated, then committed): `libs/strategy-engine/src/test/resources/vectors/{VWMA_period20,BASIS_PCT,ADVANCE_DECLINE_RATIO}.csv`.
- Add `Vector(...)` rows to `IndicatorVectorTest.java` (modify, not create).

**Cross-section dependencies (call out by name):**
- **§S5/§S6 (market-data candle spine):** `candles_3m` continuous aggregate (new `marketdata` Flyway migration, IST origin) + intraday `NSE:ADRATIO` capture. The indicator port runs on 3m only once this lands.
- **§S12 (Siva scalper strategies):** consumes these indicators; owns the strategy YAML gates (RSI 80/20, VWAP/PSAR/VWMA confluence, volume gates, `delta-0.6–0.7` strike selection, OI/sentiment quadrants) and the new Phase-23 golden fixtures that freeze each scalper sub-strategy end-to-end.
- **Source docs:** Siva's exact basis sign convention, VWMA/PSAR usage, and band values are in the sibling repo `C:\Trading\ArthaYantra\StockMarketStrategyTraining` — VERIFY against it during S12.

### 7.10 Numbered steps (each with a VERIFY check)

1. **Add `3m` to the three interval maps + schema enum** (§7.5 items 1-4).
   VERIFY: `cd libs/strategy-engine && ../../mvnw -pl libs/strategy-engine -am test` compiles; a quick unit test constructing `new SeriesKey(...,"3m")` + `EngineSeries.of(...)` does not throw.
2. **Implement VWMA** in `SessionIndicators`, register in `IndicatorRegistry`.
   VERIFY: `IndicatorRegistry.exists("VWMA")` true; `knownNames()` contains it.
3. **Implement PSAR** in `Ta4jIndicators` (confirm ta4j 0.22.0 `ParabolicSarIndicator` ctor), register.
   VERIFY: construct on a 60-bar rising series, assert PSAR < close on later bars (sanity), null on warm-up.
4. **Implement BASIS_PCT + ADVANCE_DECLINE_RATIO** (context-mechanism), register with `requiresContext()=true`.
   VERIFY: `IndicatorRegistry.create("BASIS_PCT", spot, null, Map.of())` throws "requires the indicator-level instrument context override"; succeeds with a context series.
5. **Regenerate reference vectors**: extend `generate_vectors.py` (VWMA, BASIS_PCT, ADVANCE_DECLINE_RATIO), run `python tools/indicator-vectors/generate_vectors.py`, commit the new CSVs. Add fixture parity in `VectorFixtures.java` only if a new synthetic input column is needed (existing primary+context series already cover all three).
   VERIFY: CSVs written to `.../resources/vectors/`; `.gitattributes` `*.json eol=lf` does not apply to `.csv` but confirm LF endings on Windows (`git add --renormalize` if needed).
6. **Add `Vector(...)` rows** to `IndicatorVectorTest` (VWMA, BASIS_PCT, ADVANCE_DECLINE_RATIO) and **write `PsarBehaviorTest`**.
   VERIFY: `./mvnw -pl libs/strategy-engine -am test -Dtest=IndicatorVectorTest,PsarBehaviorTest,SupertrendBehaviorTest` green; the Python mirror and Java engine agree to 8 dp.
7. **Update the advisory schema enum** (optional) and run the schema corpus test.
   VERIFY: `./mvnw -pl libs/strategy-schema -am test` green (`CorpusTest`, `ParameterPathsTest`); a strategy YAML naming `VWMA`/`PSAR` validates (or is a registry warning, never a schema reject).
8. **Full engine build + JaCoCo branch gate (≥70% on the engine bundle).**
   VERIFY: `./mvnw -pl libs/strategy-engine -am verify` passes `jacoco-check`; then `./mvnw -pl services/strategy-signal-service -am package -DskipTests` and `-pl services/backtest-service -am package -DskipTests` both build (parity JAR consistency).
9. **Hand off to §S12**: the new indicator ids are now registry-known and parity-safe; S12 authors the scalper strategy YAMLs + golden fixtures (`GoldenDeterminismTest` `FEATURES[]`) that freeze each sub-strategy end-to-end.
   VERIFY (in S12, noted here): `./mvnw -pl libs/strategy-engine -am test -Dtest=GoldenDeterminismTest` green — two independent tick-wise runs byte-match and match the frozen `expected/*.signals.json`.

**Gotchas (from CLAUDE.md):** build with the full reactor `-am`, never a bare `-pl` on the leaf (stale embedded lib); applied Flyway migrations (the `candles_3m` cagg in §S5) are checksum-locked → new suffix-versioned migration only; `IndicatorVectorTest` vectors are the A4-exception "generate once, freeze" pattern — never regenerate at test time; keep the Bash cwd at repo root so the `guard-paths.py` PreToolUse hook resolves.

---


## 8. marginism SPAN-Margin Appliance & Position Sizing

### 8.0 Decision: small Python FastAPI appliance (not a CLI)

Run **marginism** (MIT; pure-Python offline SPAN calculator for Indian F&O; `RiskEngine.from_file(<spn>).basket([positions])`) as a **standalone containerized FastAPI appliance**, `margin-service`, modeled byte-for-byte on `services/optimizer-service`. Rationale:

- The **order widget / signal→size flow (S12)** is a Java call path inside `strategy-signal-service` and the React UI; both need a synchronous request/response over HTTP. A CLI cannot be invoked from a JVM service or the gateway cleanly, and would re-fork the interpreter + reparse the `.spn` per call.
- The appliance can **memoize the parsed `RiskEngine`** per `.spn` file in-process (one parse per trading day), making `/margin` calls sub-millisecond CPU.
- MIT licence means we *could* import marginism into a Java appliance, but it is pure-Python with a `.spn` binary parser — re-porting to Java is wasted effort. Running it as a thin service behind a port boundary mirrors the OpenAlgo/optimizer Python convention already in the repo and keeps the dependency isolated.

This is **not** an AGPL concern (marginism is MIT) — we may modify it freely, but we will instead **depend on it as a pinned pip package / vendored source** and keep our code in `app/`, preserving the upstream MIT notice.

> Dependency note: this section depends on **§5 (opengreeks → Java greeks in `libs/black76-math`)** only insofar as margin sizing for *short option* legs benefits from a delta/premium estimate; margin itself needs no greeks (SPAN reads the `.spn` scan ranges). It depends on **§11 Track 2 / S12 (Siva risk rails)** for the consumer of the `/size` endpoint. It is **independent** of OpenAlgo (§1/§2): margin is computed offline from exchange files, not fetched from a broker.

### 8.1 What marginism actually provides (VERIFY against the pinned tag)

marginism's public surface (per upstream README — **(VERIFY)** against the pinned release before coding):
- `RiskEngine.from_file(path)` — parse an NSE/NFO PR-… SPAN `.spn` file into an engine.
- `engine.basket([Position(symbol=..., expiry=..., strike=..., option_type=..., net_qty=...)])` → an object exposing **SPAN margin**, **exposure margin**, **total** (SPAN+exposure), and the **hedge/spread benefit** (net of offsets across legs). Field names are upstream-specific — **(VERIFY)** the exact attribute names (`span`, `exposure`, `total`, `net_premium`, etc.) and the `Position` constructor signature against the pinned source, then adapt in `app/marginism_adapter.py`.

Because the upstream API shape is not in this repo, **wrap it behind a single adapter module** so the rest of the appliance is decoupled from marginism's exact types (anti-corruption boundary, same spirit as `kite/wire/`).

### 8.2 Acquiring the daily `.spn` files

SPAN parameter files are published **per trading day** by the exchange and are required input — marginism computes nothing without them.

- **Source:** NSE F&O SPAN files (file family `PR_<DDMMYY>.zip` / the `.spn` inside it) are published daily by NSE Clearing (NSCCL) — they are the same files broker SPAN calculators consume. **(VERIFY)** the current download URL/format at integration time (NSE rotates archive hosts). SENSEX/BSE F&O SPAN files come from BSE/ICCL similarly.
- **Refresh cadence:** once per trading day, after the exchange publishes (typically end-of-day for next-day risk params; intraday revisions exist on high-vol days). A **daily fetch** at ~08:30 IST before market open is sufficient for scalp sizing.
- **Where stored:** a host bind-mount, NOT a baked image layer (files change daily):
  - Host dir: `deploy/span-files/` (gitignored — add `deploy/span-files/` to repo `.gitignore`).
  - Mounted **read-only** into the container at `/spn`.
  - The appliance reads the **latest** file by mtime / a `latest.spn` symlink, and exposes which file it loaded via `/health` (so a stale file is visible).
- **Fetcher:** a tiny scheduled fetch. Two acceptable forms (owner picks):
  1. A `app/spn_fetch.py` module + a compose **healthcheck-independent** loop, OR
  2. **Simplest (recommended):** a host-side scheduled job — reuse the existing scheduling pattern (the repo already has `tools/` PowerShell scripts e.g. `tools/historical-import/run_full_load.ps1`); add `tools/span-fetch/fetch_spn.ps1` that downloads today's NSE PR zip, unzips the `.spn` into `deploy/span-files/`, and updates the `latest.spn` symlink/copy. Run it via Windows Task Scheduler at 08:30 IST.
  - Keep this OUT of the FastAPI container so a fetch failure (NSE host down) never crashes the margin appliance — it just keeps serving yesterday's params (acceptable for sizing; the `/health` `spn_date` makes staleness visible).

### 8.3 The appliance — files to CREATE

Create `services/margin-service/` mirroring `services/optimizer-service/` exactly:

```
services/margin-service/
  Dockerfile                # copy of optimizer Dockerfile, port 8086, COPY app
  requirements.txt          # fastapi/uvicorn/prometheus-fastapi-instrumentator + marginism + httpx
  requirements-dev.txt      # -r requirements.txt + pytest, pytest-cov, ruff, respx
  ruff.toml                 # identical to optimizer-service/ruff.toml
  conftest.py               # identical (puts service root on sys.path)
  .coveragerc               # source=app; omit main.py, settings.py
  .gitignore                # __pycache__, .pytest_cache, .coverage, .ruff_cache
  app/
    __init__.py
    main.py                 # build_app(): FastAPI, /health, /metrics, wires SpanService
    settings.py             # @dataclass(frozen=True) Settings.load() from env
    errors.py               # COPY optimizer-service/app/errors.py verbatim (shared envelope)
    marginism_adapter.py    # ANTI-CORRUPTION wrapper around marginism.RiskEngine
    span_loader.py          # picks latest .spn under SPN_DIR, caches parsed engine by file mtime
    service.py              # SpanService: margin(positions), size(req) -> sizing within rails
    api.py                  # APIRouter prefix=/api/v1/margin  -> POST /, POST /size
    models.py               # pydantic request/response models
  tests/
    __init__.py
    fakes.py                # FakeEngine (no .spn needed) for unit tests
    test_api.py
    test_margin.py          # known NIFTY straddle vs a golden number
    test_sizing.py          # rail enforcement (cap %, daily-loss, RR)
```

**`requirements.txt`** (pin exact versions; CI regenerates with hashes like optimizer does):
```
fastapi==0.115.6
uvicorn[standard]==0.34.0
httpx==0.28.1
prometheus-fastapi-instrumentator==7.0.0
marginism==<PINNED>        # (VERIFY) latest on PyPI; if not on PyPI, vendor source under app/_vendor/marginism/ keeping its MIT LICENSE file
```
> **MIT attribution:** keep marginism's `LICENSE` + copyright header. If pip-installable, the wheel carries it; if vendored, copy `LICENSE` into `app/_vendor/marginism/LICENSE` and add a one-line credit to repo `README.md` third-party section. **(VERIFY)** marginism is published to PyPI under that name; if not, vendor the source.

**`Dockerfile`** — copy `services/optimizer-service/Dockerfile`, change only: comment header, `EXPOSE 8086`, healthcheck URL `http://127.0.0.1:8086/health`, `--port 8086`, and the `COPY services/margin-service/...` paths. **Keep the repo-root build context** (`context: ..` + `-f services/margin-service/Dockerfile`) so the conditional `deploy/dev-certs/` CA-trust pip layer resolves — this is the CLAUDE.md image-build-context gotcha; CI image build must use the same `docker build -t ... -f services/margin-service/Dockerfile .` form.

### 8.4 API / contract

Router prefix `/api/v1/margin` (FastAPI `APIRouter(prefix="/api/v1/margin")`). Errors use the copied shared envelope (`{code,message,details}`) so the appliance speaks the same taxonomy as the Java services.

**`POST /api/v1/margin`** — raw SPAN for an arbitrary basket:
```jsonc
// request
{
  "positions": [
    {"symbol":"NIFTY","expiry":"2026-06-25","strike":23500,"optionType":"CE","netQty":-75},
    {"symbol":"NIFTY","expiry":"2026-06-25","strike":23500,"optionType":"PE","netQty":-75}
  ]
}
// 200 response (decimals as JSON STRINGS — matches the repo's Jackson-BigDecimal-as-string wire convention so frontend core/decimal handles them uniformly)
{
  "span":"112340.00",
  "exposure":"18550.00",
  "total":"130890.00",
  "hedgeBenefit":"0.00",
  "spnDate":"2026-06-19",
  "currency":"INR"
}
```
- 400 `VALIDATION_FAILED` on a malformed position (bad optionType, missing strike for an option leg).
- 503 `DATA_GAP` (reuse the repo's existing code) when no `.spn` is loaded yet.

**`POST /api/v1/margin/size`** — the **sizing** endpoint S12 calls (margin + the Siva risk rails baked in):
```jsonc
// request
{
  "capital":"500000",            // owner equity for this account (5-account model)
  "maxCapitalPct":"0.20",        // Siva rail: <=10-20% capital per trade (default 0.20)
  "dailyLossUsed":"4000",        // realized loss so far today (for the daily-loss cap gate)
  "dailyLossCap":"10000",        // hard daily loss cap
  "entry":"120.50",              // entry premium/price per unit
  "stop":"90.00",                // hard SL price (rail: mandatory)
  "rrTarget":"2.0",              // RR 1:2 -> target = entry + 2*(entry-stop)
  "legs":[ {"symbol":"NIFTY","expiry":"2026-06-25","strike":23500,"optionType":"CE","side":"BUY","lotSize":75} ]
}
// 200 response
{
  "lots":3,
  "qty":225,                     // lots * lotSize
  "marginRequired":"...",        // SPAN(total) for the sized basket
  "capitalUsed":"...",           // marginRequired (or premium for long opts)
  "riskAmount":"6862.50",        // (entry-stop)*qty -> compared to remaining daily-loss room
  "target":"180.50",             // RR 1:2 computed
  "withinRails":true,
  "warnings":["..."],            // non-blocking, e.g. clamped by daily-loss room
  "limitingRail":"capitalPct"    // which rail bound the size (capitalPct | dailyLoss | marginAvailable)
}
```
Sizing algorithm (in `service.py`):
1. `maxByCapital = capital * maxCapitalPct`.
2. `riskPerUnit = abs(entry - stop)`; remaining daily-loss room = `dailyLossCap - dailyLossUsed`; `maxQtyByLoss = floor(room / riskPerUnit)`.
3. For each candidate lot count, compute basket SPAN via `/margin` logic; `maxByMargin` = largest lot count whose `total <= maxByCapital`.
4. `qty = min(maxQtyByLoss, maxByMargin)` rounded **down** to a whole multiple of `lotSize`; `lots = qty/lotSize`.
5. Compute `target` from `rrTarget` (RR 1:2 default). Reject (`withinRails:false`, `lots:0`) if `stop` missing/zero (no-naked-risk rail) or remaining loss room ≤ 0 (daily cap hit).
6. **No averaging losers** is a *position-state* rule enforced by the caller (S12), not by this stateless endpoint — note this in the response docstring.

**Contract test (mirrors `KiteWireContractTest`/`ContractCanary`):** add `tests/test_marginism_contract.py` — a pinned-tag canary that asserts marginism still exposes the attributes `marginism_adapter` consumes (`from_file`, `basket`, and the `span/exposure/total/hedge` fields) using `FakeEngine` for unit runs and a guarded real-`.spn` test (`@pytest.mark.skipif(no .spn fixture)`). This catches upstream drift off the critical path, exactly like the Kite canary pattern. Ship a tiny **golden `.spn` fixture** (one real expiry day) under `tests/fixtures/` so the contract + known-straddle test run in CI without a live download.

> Note: optimizer-service is Python and **has no springdoc `ContractCaptureTest`** — neither does this appliance. Its contract is the pytest above + the TS types you choose to hand-write for the React client (the openapi-typescript codegen path covers Java services only).

### 8.5 How S12 (signal→size / order widget) calls it

Two callers, one config flag selecting the source per the §1/§2 capability-flag convention:

1. **Java `strategy-signal-service`** (paper + future live order legs): add a small REST client `MarginServiceClient` next to `InstrumentMetaClient` (`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/paper/`), using the same `RestClient` style. It calls `POST {margin.base}/api/v1/margin/size`. The existing `PaperAccountService.usageFor(...)` (`services/strategy-signal-service/.../paper/PaperAccountService.java`) today uses a **flat margin-pct approximation** (`artha.paper.margin-pct.future=0.15`, `short-option=0.12`). **Replace that approximation for futures/short-options with the SPAN call when `artha.margin.span-enabled=true`**, falling back to the flat pct when the appliance is unreachable (keep the existing fields as the fallback path so paper is never blocked — consistent with the current "buying-power warnings are non-blocking" design). Lot size comes from the existing `InstrumentMetaClient.InstrumentMeta.lotSize()`.
2. **React order widget (§10):** call the same `/api/v1/margin/size` through the gateway to show required margin + the rail-bound quantity before the owner confirms an order (a "fastscalper"-style sizing box). Decimals arrive as strings → handle via the React `core/decimal` port.

Config keys to add (strategy-signal-service `application.yml`):
```yaml
artha:
  margin:
    span-enabled: ${ARTHA_MARGIN_SPAN_ENABLED:false}   # capability flag, off by default
    base: ${ARTHA_ROUTE_MARGIN:http://margin-service:8086}
```

### 8.6 Compose service + healthcheck + loopback + gateway route

**`deploy/docker-compose.yml`** — add after the `optimizer-service` block, copying its shape:
```yaml
  # ---- margin-service (SPAN margin / position sizing) ---------------------
  # Python/FastAPI; offline SPAN from daily NSE .spn files (read-only mount).
  # Internal :8086. No DB, no Redis — stateless; reads /spn, memoizes per file.
  margin-service:
    build:
      context: ..
      dockerfile: services/margin-service/Dockerfile
    image: arthayantra/margin-service:dev
    container_name: ay-margin-service
    environment:
      SPN_DIR: /spn
    volumes:
      - ../deploy/span-files:/spn:ro      # daily .spn files, host-fetched, read-only
    healthcheck:
      test: ["CMD-SHELL", "python -c \"import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8086/health').read() else 1)\""]
      interval: 10s
      retries: 12
      start_period: 20s
    mem_limit: 192m
    restart: unless-stopped
    logging: *default-logging
```
- **Loopback:** like every app service, `margin-service` is **internal-only** (no `ports:`). It is reachable on the project network as `http://margin-service:8086` and from the host only via the gateway. (The repo deliberately publishes app ports on loopback ONLY under the `dev-tools` profile via socat — if host-direct access is wanted for debugging, add a `margin-publish` socat forwarder `127.0.0.1:8086:8086` under `profiles: [dev-tools]`, mirroring `mds-publish`/`sss-publish`.)
- **No secrets / DB:** margin-service needs no `postgres_password` (it is stateless and reads only `.spn` files), so it omits the `secrets:`/`depends_on: flyway-init` blocks the optimizer has.

**Gateway route** — `services/edge-gateway/src/main/resources/application.yml`, add to the `routes:` list next to `optimizer`:
```yaml
            - id: margin
              uri: ${ARTHA_ROUTE_MARGIN:http://margin-service:8086}
              predicates:
                - Path=/api/v1/margin/**
```
This puts `/api/v1/margin/**` behind the gateway's loopback-only auth filter (same XSRF/login enforcement as every routed path).

**`ay.ps1`/`ay` CLI:** no change needed — it passes `--env-file .env` and brings up the whole project. Confirm `margin-service` joins the default compose start set (it has no `profiles:` so it starts by default, like optimizer). **(VERIFY)** there is no explicit service allow-list in `ay.ps1` that would need the new name added.

**CI:** add `.github/workflows/ci-margin.yml` as a copy of `ci-optimizer.yml` with paths `services/margin-service/**`, the same gitleaks → ruff → pytest (≥75%) → image-build (`docker build ... -f services/margin-service/Dockerfile .`) stages. Per CLAUDE.md, a new service that is *not* added to CI never gets tested — this workflow IS its shard.

### 8.7 MIT attribution

- Keep marginism's `LICENSE`/copyright (PyPI wheel carries it, or copy into `app/_vendor/marginism/LICENSE` if vendored).
- Add a line to the repo `README.md` third-party/credits section: "SPAN margin via **marginism** (MIT, © upstream authors)."
- Do **not** strip headers from any vendored `.py`.

### 8.8 Numbered steps + VERIFY

1. **Scaffold the appliance.** Copy `services/optimizer-service/{Dockerfile,ruff.toml,conftest.py,.coveragerc,.gitignore,app/__init__.py,app/errors.py}` into `services/margin-service/`, adjusting port 8086 + COPY paths. **VERIFY:** `cd services/margin-service && ruff check .` passes on the scaffold.
2. **Pin marginism + write the adapter.** Add `marginism==<PINNED>` to `requirements.txt`; write `app/marginism_adapter.py` wrapping `RiskEngine.from_file` + `basket(...)` into a stable `MarginResult(span, exposure, total, hedge_benefit)` dataclass. **VERIFY:** `pip install -r requirements-dev.txt` resolves; `python -c "import marginism; print(marginism.__version__)"` works (or vendored import resolves).
3. **`span_loader.py`.** Implement latest-`.spn`-by-mtime selection under `SPN_DIR`, memoized parse keyed by `(path, mtime)`; expose `spn_date`. **VERIFY:** unit test loads `tests/fixtures/<golden>.spn` and reports the expected date.
4. **`service.py` + `models.py` + `api.py`.** Implement `/margin` and `/margin/size` per §8.4, decimals serialized as strings. **VERIFY:** `pytest tests/test_api.py -q` — 200 on a valid basket, 400 on bad optionType, 503 when no `.spn` loaded.
5. **Known-straddle golden test.** In `tests/test_margin.py`, compute SPAN for a short ATM NIFTY straddle (e.g. sell 1 lot CE + 1 lot PE at the ATM strike of the fixture's expiry) and assert the `total` against a **broker SPAN calculator** reference number (Zerodha/Upstox SPAN calculator) within a tolerance band (±2–3%, since exposure/scan params vary by source). **VERIFY:** the computed total is within tolerance of the broker figure; record the reference value + source in the test docstring.
6. **Sizing rail test.** `tests/test_sizing.py`: assert `/size` clamps `lots` to the binding rail — (a) `maxCapitalPct` bound, (b) `dailyLossCap` room bound, (c) `lots:0`/`withinRails:false` when `stop` is omitted or daily room ≤ 0, (d) `target` = RR 1:2 correct. **VERIFY:** all four cases pass; `pytest -q --cov=app --cov-fail-under=75` green.
7. **Compose + gateway wiring.** Add the `margin-service` block, the `:ro` `/spn` mount, `deploy/span-files/` (+ `.gitignore`), and the gateway `margin` route. **VERIFY:** `ay up` (mock) brings `ay-margin-service` to `healthy`; `curl -fsS http://127.0.0.1:<gw>/api/v1/margin` (authed via the gateway login+XSRF flow per CLAUDE.md) returns the envelope, and direct `docker exec ay-margin-service` health passes.
8. **S12 client + flag.** Add `MarginServiceClient` + `artha.margin.{span-enabled,base}` config in `strategy-signal-service`; route futures/short-option `usageFor` through SPAN when enabled, flat-pct fallback otherwise. **VERIFY:** `./mvnw -pl services/strategy-signal-service -am test` green (add a unit test that the client is bypassed when the flag is off and the existing `PaperAccountRiskIntegrationTest` still passes).
9. **`.spn` fetcher.** Add `tools/span-fetch/fetch_spn.ps1` (download today's NSE PR zip → unzip `.spn` → `deploy/span-files/` → update `latest.spn`) + a Windows Task Scheduler entry at 08:30 IST. **VERIFY:** running the script once populates `deploy/span-files/` and `/health` reports the fetched `spnDate`.
10. **CI shard + attribution.** Add `.github/workflows/ci-margin.yml` (copy of ci-optimizer, path-filtered) and the README MIT credit. **VERIFY:** the new workflow runs on a PR touching `services/margin-service/**` and is green; `gitleaks` clean.

### 8.9 Gotchas (from CLAUDE.md + this investigation)

- **Repo-root build context** for the Dockerfile (`context: ..` + `-f`), or the `deploy/dev-certs/` CA pip layer breaks — and keep CI's `docker build` in lockstep.
- **New service ⇒ new CI shard**, else it never runs in CI (CLAUDE.md sharding rule applies to Python workflows too).
- **`.spn` files are NOT committed** — they are daily, gitignored, host-fetched, mounted read-only. A stale file must be *visible* (`/health.spnDate`), never silently wrong.
- **Decimals on the wire are JSON strings** to match the repo-wide Jackson-BigDecimal convention and the React `core/decimal` port.
- **Always drive compose via `ay`/`--env-file .env`** — raw `docker compose` blanks env vars.
- **Margin tolerance:** SPAN parameters and *exposure* margin add-ons differ slightly between the exchange file and a broker's displayed figure (brokers add their own buffers); the golden test must assert a tolerance band, not an exact equality, and pin the reference source.
- **(VERIFY) items to resolve before coding:** marginism's exact `Position` signature + result attribute names; its PyPI availability/name; the current NSE `.spn` download URL/format; whether `ay.ps1` has an explicit service list needing the new name.

---

Confirmed: `surprise` and `est_revision` are NOT modeled as first-class columns today — they live (if at all) as `metric` rows in the tall `fundamentals` table. I have all the grounding I need.

## 9. openscreener Fundamentals Appliance (OPTIONAL — Minervini filter)

> **Status: OWNER-OPTIONAL, NON-BLOCKING.** This entire section can be skipped without affecting Track 1 (Minervini SEPA screener — §S13). The Track-1 daily screener is *price/volume-only* (8-gate Trend Template + RS-rank + volume-vs-50d-avg) and runs entirely off data ArthaYantra already captures (`marketdata.nse_eod_bhavcopy`, ~3.2k symbols/day EOD OHLCV+delivery, plus the openchart 200+-day daily backfill from §4). openscreener adds a *fundamental bolt-on filter* (accelerating EPS/sales, expanding margins, "Code 33") that layers AFTER the price gates. The owner has explicitly marked this "nice-to-have if easy, not blocking." Build it only after Track 1's price-based screener is green.

### 9.0 What already exists (grounded in the repo — DO NOT rebuild)

- **`marketdata.fundamentals` table ALREADY EXISTS** — `deploy/flyway/marketdata/V017__fundamentals.sql`. It is a *tall* (long-format) table, PK `(symbol, statement, period_end, metric)`, columns: `symbol TEXT`, `statement TEXT` (CHECK: `quarterly_results|profit_and_loss|balance_sheet|cash_flows|ratios|shareholding_yearly|shareholding_quarterly`), `period_end DATE` (month-end), `granularity TEXT` (`Q`|`A`), `metric TEXT` (cleaned, e.g. `Sales`, `ROCE %`, `EPS`, `Net Profit`), `value NUMERIC(20,4)`, `is_percent BOOLEAN`, `source TEXT DEFAULT 'BACKFILL'`, `fetched_at TIMESTAMPTZ`. Index `idx_fundamentals_symbol_metric (symbol, metric, period_end)`. It lives in the `marketdata` schema so backtest inherits the CD-1 SELECT grant (`deploy/flyway/admin/V001__roles_and_schemas.sql` lines 17–28).
- **A bulk fundamentals loader ALREADY EXISTS** — `tools/historical-import/ingest.py`. It classifies a Screener.in-export folder tree (`fundamentals/<SYMBOL>/<statement>.csv`) as `FUNDAMENTAL` (`classify_path`, line 104), transposes wide→long (`parse_fundamental_rows`, line 365), and cleans metric names + values with `clean_metric()` (strips `Â`/nbsp/`+`, line 346) and `clean_value()` (strips commas + `%`, returns `(Decimal, is_percent)`, line 351). The `period_end` parse `'Mar 2023' → 2023-03-31` is `parse_period()` (line 332). Upsert SQL `_UPSERT_FUND` (line 458) is `ON CONFLICT (symbol, statement, period_end, metric) DO UPDATE`. **This is the canonical transpose/normalization logic — the openscreener appliance MUST emit the same `(symbol, statement, period_end, granularity, metric, value, is_percent)` shape and reuse this upsert path, not invent a new one.** The existing data is a one-time Google-Drive Screener.in *export* snapshot (`tools/historical-import/DATA_SOURCES.md` §B.2, 2,540 company folders); openscreener's job is to *refresh* it periodically for the live watchlist.
- **The Minervini consumption point ALREADY EXISTS** — `services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/ScreenerService.java`, surface `GET /api/v1/market/screener` (`ScreenerController.java`, `@RequestMapping("/api/v1/market/screener")`). It is **pure parameterized SQL over the candle caggs — explicitly NEVER a Kite port** (the `WatchlistScreenerIntegrationTest` asserts `GATEWAY_CALLS == 0`). Presets today: `momentum`, `long_term`, `rs_rank` (return percentile vs `NSE:NIFTY 50`), `oi_buildup`. §S13's 8-gate Trend Template is a NEW preset (e.g. `trend_template`) added there; **this section's fundamental filter is a further bolt-on applied AFTER that preset's rows are computed.**
- **The appliance template ALREADY EXISTS** — `tools/historical-import/` is a standalone Python tool (psycopg + tqdm, `requirements.txt`), with a SQLite manifest for incremental re-runs, `127.0.0.1` DSN gotcha documented (`README.md` line 27), `test_ignest.py` for pure-logic tests. The optimizer-service Dockerfile (`services/optimizer-service/Dockerfile`) is the canonical pattern for a containerized Python appliance (python:3.x-slim, `deploy/dev-certs/` CA-trust layer for the TLS-intercepting AV, non-root `artha` uid 10001).

### 9.1 Form: a periodic Python scraper appliance, NOT a service

openscreener (MIT; Python Playwright scraper of Screener.in → normalized sections `summary/ratios/cash-flow/balance-sheet/shareholding`; batch fetch; JSON/DataFrame export) runs as a **standalone, scheduled job** under `tools/`, in the *exact same mold* as `tools/historical-import/`. It is **not** a Spring service, **not** a compose-default container, and does **not** sit on any request path. It scrapes the **current watchlist's** symbols, normalizes each company's sections, transposes wide→long, and upserts into the existing `marketdata.fundamentals` table (`source='OPENSCREENER'` to distinguish from the `'BACKFILL'` Drive snapshot).

Why an appliance, not a live call: scraping is slow (seconds/symbol, headless browser), fragile (Screener.in HTML can change), and rate-limited by politeness — utterly unsuited to a synchronous API. Daily/weekly batch is the right cadence (fundamentals change quarterly).

License: openscreener is **MIT** → we may port/import its scraping/normalization logic freely, keeping its copyright notice. (Contrast: the AGPL appliances OpenAlgo-core/ExpiryTrack must run behind a process boundary and be consumed via API/output only — see §1/§3. openscreener has no such constraint, but we still keep it as a *separate tool*, not merged into a service, purely for operational hygiene.)

#### Files to CREATE

```
tools/fundamentals-refresh/
├── refresh.py            # main: read watchlist → scrape via openscreener → wide→long → upsert
├── screener_client.py    # thin wrapper over openscreener's batch-fetch API (ported MIT code)
├── requirements.txt      # openscreener (+ its playwright/pandas deps), psycopg[binary], tqdm
├── Dockerfile            # python:3.12-slim + Playwright chromium + dev-certs CA layer
├── test_refresh.py       # pure-logic tests (section→long transpose, metric cleaning), no network/DB
├── manifest_fundamentals.sqlite   # (gitignored) per-symbol last-fetched fingerprint
└── README.md             # run instructions + fragility caveats + MIT attribution
```

- **`refresh.py`** — CLI mirroring `ingest.py`'s ergonomics: `--dsn` (default the live `postgresql://artha:...@127.0.0.1:5432/artha`, **127.0.0.1 NOT localhost** per `README.md` line 27), `--symbols` (CSV) OR `--from-watchlist <name>` (queries `marketdata.watchlists`/`watchlist_items` — `V007__watchlists.sql`), `--dry-run` (scrape + transpose, no DB), `--manifest`, `--max-age-days N` (skip symbols whose latest `fetched_at` for `source='OPENSCREENER'` is younger than N — politeness/incremental). For each symbol: call `screener_client.fetch(symbol)` → normalized sections → transpose each section wide→long → upsert.
- **`screener_client.py`** — wraps openscreener's batch fetch; maps openscreener's section names to our `statement` CHECK enum (`profit_and_loss`, `balance_sheet`, `cash_flows`, `ratios`, `quarterly_results`, `shareholding_yearly`, `shareholding_quarterly`). Maps NSE symbol → Screener.in URL slug (Screener uses the BSE/NSE code; NSE tradingsymbol usually works directly — VERIFY per symbol).
- **Reuse, do not reimplement:** `refresh.py` MUST import (or copy verbatim, with attribution) the proven helpers from `tools/historical-import/ingest.py` — `clean_metric()`, `clean_value()`, `parse_period()`, and the `_UPSERT_FUND` SQL (line 458). Factoring those four into a shared `tools/_fundamentals_common.py` and importing from both tools is cleaner (one transpose definition), but a verbatim copy is acceptable if cross-tool import is awkward. The output tuple shape is fixed: `(symbol, statement, period_end, granularity, metric, value, is_percent, 'OPENSCREENER', fetched_at)`.

#### Schema — NO new table needed (reuse `marketdata.fundamentals`)

The existing tall table already models everything the Minervini filter needs. The task brief's sketch (`symbol, period, eps, sales, margins, surprise, est-revision, fetched_at`) maps onto it as:

| Brief field | How it's stored in `marketdata.fundamentals` |
|---|---|
| `symbol` | `symbol` |
| `period` | `period_end` + `granularity` |
| `eps` | row `metric='EPS'`, `statement='quarterly_results'` (Q) or `'profit_and_loss'` (A) |
| `sales` | row `metric='Sales'` |
| `margins` | row `metric='OPM %'` (`is_percent=true`) and/or `'ROCE %'` (from `ratios`) |
| `surprise` | **NOT in Screener.in** (no analyst consensus) — see §9.5; leave UNMODELED |
| `est-revision` | **NOT in Screener.in** (no estimate feed) — see §9.5; leave UNMODELED |
| `fetched_at` | `fetched_at` |

**OPTIONAL Flyway sketch (only if needed — likely NOT):** the CHECK on `statement` already covers all openscreener sections, and `source` is free-text, so **no migration is required to ingest openscreener data**. The one *optional* additive migration would be a partial index to make the Minervini "latest 4-8 quarters of EPS/Sales" read fast at scale:

```sql
-- deploy/flyway/marketdata/V0NN__fundamentals_minervini_idx.sql  (OPTIONAL)
-- Accelerates "latest N quarterly EPS/Sales/OPM for symbol X" — the only
-- read pattern the Minervini fundamental bolt-on adds. Additive, no data change.
CREATE INDEX IF NOT EXISTS idx_fundamentals_q_metrics
  ON fundamentals (symbol, metric, period_end DESC)
  WHERE statement = 'quarterly_results'
    AND metric IN ('Sales', 'EPS', 'OPM %', 'Net Profit');
```

Per CLAUDE.md, applied migrations are checksum-locked — this is a **new suffix-versioned** file (next free `V0NN`), never an edit of `V017`. Use the `new-migration` skill. Skip it for the single-owner watchlist scale (~tens of symbols) — `idx_fundamentals_symbol_metric` already serves these reads adequately.

### 9.2 How the Minervini screener (§S13) consumes it — a bolt-on filter AFTER the price gates

The fundamental filter is layered **strictly after** §S13's price-based 8-gate Trend Template + RS-rank produce the candidate list. Order matters: the price gates are the SEPA primary filter (cheap, always-available); fundamentals are a *confirmation* that narrows survivors. Implement as an **optional** branch in `ScreenerService` (or a small companion `FundamentalFilter` collaborator in the same `screener` package):

1. §S13 preset (`trend_template`) returns its `List<Row>` of price-qualified Indian equities (off `candles_1d` / `nse_eod_bhavcopy`-derived caggs — same SQL-only, zero-Kite-port discipline the `WatchlistScreenerIntegrationTest` enforces).
2. **If** a query param `fundamentals=true` (or preset `trend_template_plus`) is set, post-filter those rows with a SQL read of `marketdata.fundamentals` (the screener already holds a `JdbcTemplate` — `ScreenerService.java` line 52). The fundamental gates (Minervini "Code 33" spirit — accelerating earnings + sales, expanding margins):
   - **Accelerating EPS ≥ 20–25% YoY:** latest quarter's `EPS` vs same quarter prior year ≥ +25% (and ideally accelerating across the last 2–3 quarters). YoY (not QoQ) avoids Indian seasonality.
   - **Accelerating Sales ≥ 20–25% YoY:** same comparison on `metric='Sales'`.
   - **Expanding margins:** latest `OPM %` ≥ trailing-4-quarter average `OPM %` (margin not contracting).
   - **"Code 33":** all three above firing together (the canonical Minervini triple — EPS accel + Sales accel + margin expansion) → a boolean flag/label on the row, surfaced in the `label` field of `ScreenerService.Row` (which is already nullable and used by `oi_buildup`).
3. Symbols with **no** openscreener data fall through gracefully — the filter must treat "missing fundamentals" as *unqualified-but-not-excluded* (configurable: drop, or keep with a `fundamentals=UNKNOWN` label). Default: keep + label `UNKNOWN`, so a fundamentals gap never silently empties the watchlist.

Example consumption SQL sketch (latest-quarter YoY EPS growth per symbol):

```sql
WITH q AS (
  SELECT symbol, period_end, value,
         row_number() OVER (PARTITION BY symbol ORDER BY period_end DESC) AS rn
  FROM fundamentals
  WHERE statement = 'quarterly_results' AND metric = 'EPS' AND symbol = ANY(?)
)
SELECT cur.symbol,
       (cur.value - yoy.value) / NULLIF(abs(yoy.value), 0) AS eps_yoy
FROM q cur
JOIN q yoy ON yoy.symbol = cur.symbol AND yoy.rn = cur.rn + 4   -- 4 quarters back
WHERE cur.rn = 1;
```

**Frontend (React rewrite, §10):** the Minervini screener page shows the price-gate columns plus, when `fundamentals=true`, an "EPS YoY / Sales YoY / Margin / Code-33" column group with a `UNKNOWN` badge for un-scraped symbols. Re-uses the same `{items:[...]}` envelope the existing Angular screener already consumes (CLAUDE.md: list endpoints return `{items}`).

**Contract drift note:** adding the `fundamentals` query param to `GET /api/v1/market/screener` **does** drift the springdoc snapshot (CLAUDE.md: new query params DO drift the spec, unlike `Map<String,Object>` response keys). Re-capture via `ContractCaptureTest` (`-Dcontracts.capture=true`) and regen `contracts/gen/market-data-service.d.ts` (`npx openapi-typescript@7`). Adding the `label`/Code-33 keys to the `Map<String,Object>` response does NOT drift.

### 9.3 Scheduling

No new always-on container. Run via the existing host-scheduling approach used for other periodic jobs (a Windows Task Scheduler entry / the same cadence machinery the host uses for capture). Cadence: **weekly is plenty** (Screener.in updates on results filings, ~quarterly). Optionally wrap as a one-shot compose service under a `--profile fundamentals` (mirroring `db-create`/`flyway-init`'s `restart: "no"` one-shots in `deploy/docker-compose.yml`) so it runs in-network with the DB; if so, it needs Playwright's chromium baked into the image and the `deploy/dev-certs/` CA layer (the Dockerfile pattern from `services/optimizer-service/Dockerfile`). **Do not** add it to the default profile — it must never block stack boot.

### 9.4 Scraping-fragility caveats (state plainly in README + code comments)

- **HTML-structure brittleness:** openscreener parses Screener.in's DOM. A site redesign breaks the scraper silently (wrong/empty sections). Mitigations: (a) per-symbol row-count + non-null sanity check before upsert — abort a symbol that yields zero metric rows rather than wiping good data; (b) **never** `DELETE`-then-insert per symbol (the `_UPSERT_FUND` `ON CONFLICT DO UPDATE` is non-destructive — keep it); (c) log + ntfy on a scrape that returns < expected sections (reuse the ops ntfy topic pattern, `ARTHA_NTFY_TOPIC`, seen in `db-backup`).
- **Playwright-in-a-container:** headless chromium needs the right base image deps; the `mcr.microsoft.com/playwright/python` base or `playwright install --with-deps chromium` in the Dockerfile. Heavier image than the slim optimizer one — acceptable for an off-path appliance.
- **Rate/politeness:** Screener.in is a free public site. Throttle (a few seconds between symbols, single-threaded — do NOT parallelize like `ingest.py`'s `--workers 8`), set a realistic User-Agent, respect the watchlist scope (tens of symbols, not all 3.2k NSE names). `--max-age-days` prevents needless re-scrapes. **This is research/educational use for a single owner; honor robots.txt and ToS, and do not redistribute scraped data.** State this explicitly in the README.
- **Latest-restatement, not point-in-time:** like the existing `V017` backfill (comment line 7: "Latest-restatement, NOT as-reported point-in-time"), openscreener gives the *current* reported figures, not what was known on a past date. **Acceptable for a forward-looking watchlist screener; a lookahead-bias hazard if ever fed into a backtest** — guard against using `fundamentals` in the deterministic backtest replay path without a point-in-time fix.

### 9.5 What openscreener does NOT provide (be honest about the gaps)

- **`surprise` (earnings vs consensus)** and **`est-revision` (analyst estimate revisions)** — Screener.in carries no analyst-consensus or forward-estimate feed, so these two brief-sketch fields are **not obtainable** from openscreener. Leave them UNMODELED (no column, no metric). True Minervini "Code 33" surprise/revision data needs a paid estimates provider (out of scope; the owner has Upstox Plus but that is price/OI data, not estimates). The implementable subset of "Code 33" is the **EPS accel + Sales accel + margin expansion** triple from §9.2 — flag that clearly so a future session does not hunt for a surprise feed that isn't there.
- The §S13 **price-based** Trend Template + RS-rank is the load-bearing screener and needs none of this.

### 9.6 Numbered build steps (each with a VERIFY)

1. **Confirm the data path exists.** Read `deploy/flyway/marketdata/V017__fundamentals.sql` and `tools/historical-import/ingest.py` (`parse_fundamental_rows`, `_UPSERT_FUND`). **VERIFY:** `SELECT count(*), count(DISTINCT symbol) FROM marketdata.fundamentals;` returns the Drive-backfill rows (proves the table + read path are live). If empty, the §S13 price-only screener still works — this section is purely additive.
2. **Scaffold the appliance** under `tools/fundamentals-refresh/` (files in §9.1), factoring `clean_metric/clean_value/parse_period/_UPSERT_FUND` into shared code (or copying with MIT/attribution comments). **VERIFY:** `python -m pytest tools/fundamentals-refresh/test_refresh.py -q` — pure-logic tests pass (section→long transpose, metric cleaning, period parse) with no network/DB, mirroring `test_ingest.py`.
3. **Wire openscreener** in `screener_client.py`; map section names → the `statement` CHECK enum; map NSE symbol → Screener.in slug. **VERIFY:** `python refresh.py --symbols RELIANCE,TCS --dry-run` prints normalized long rows (statement/period_end/metric/value) for both, with `EPS`, `Sales`, `OPM %` present — no DB write.
4. **First real refresh** against the live DB for the current watchlist. **VERIFY:** `SELECT symbol, count(*) FROM marketdata.fundamentals WHERE source='OPENSCREENER' GROUP BY symbol;` shows rows for each scraped symbol; the YoY-EPS sketch query (§9.2) returns finite numbers for symbols with ≥5 quarters of history.
5. **Add the bolt-on filter** to `ScreenerService` (new `fundamentals` param / `trend_template_plus` preset): post-filter the §S13 price rows by EPS-accel + Sales-accel + margin-expansion; label Code-33; treat missing fundamentals as `UNKNOWN` (keep, don't drop). **VERIFY:** a new `*IntegrationTest` in `services/market-data-service/.../screener/` (NOT `*IT` — CLAUDE.md: no failsafe plugin) seeds a couple of synthetic `fundamentals` rows + price caggs and asserts the filter (a) keeps a Code-33 symbol, (b) labels a no-data symbol `UNKNOWN`, (c) still issues **zero** Kite-gateway calls (carry the `GATEWAY_CALLS` assertion from `WatchlistScreenerIntegrationTest`). Build with the full reactor: `./mvnw -pl services/market-data-service -am verify`.
6. **Re-capture the contract.** Run `ContractCaptureTest` with `-Dcontracts.capture=true` (the new query param drifts the spec), regen `contracts/gen/market-data-service.d.ts` via `npx openapi-typescript@7`, ensure `tsc --strict` passes. **VERIFY:** `ci-contracts` shows the new param as a non-breaking additive diff.
7. **(Optional) schedule + containerize.** Add a `--profile fundamentals` one-shot to `deploy/docker-compose.yml` (`restart:"no"`, Playwright image, `deploy/dev-certs/` CA layer, `--env-file .env` via the `ay` CLI only — never raw `docker compose`, CLAUDE.md) and a weekly host scheduler entry. **VERIFY:** `ay`-driven one-shot run completes and refreshes the watchlist's fundamentals in `artha` (live) without touching `artha_mock`.

### 9.7 Dependencies on other sections & gotchas

- **Depends on §S13 (Minervini SEPA screener)** for the price-gated candidate list this filter narrows. §S13 is independently shippable; this section is its optional confirmation layer. Do not start §9 before §S13's `trend_template` preset is green.
- **Depends on §4 (openchart backfill)** only transitively — that's what gives §S13's MAs their 200+ days of daily history; §9 itself reads `marketdata.fundamentals`, not candles.
- **Touches §10 (React rewrite)** for the screener-page fundamental column group + Code-33/UNKNOWN badges.
- **Gotchas (CLAUDE.md):** build market-data-service with the full reactor + `-am`; new test classes must be `*Test`/`*IntegrationTest` (never `*IT`); ITs share the singleton DB with no per-method cleanup → seed unique symbols; new query params drift the springdoc spec (re-capture); never run raw `docker compose` without the `ay` CLI's `--env-file .env`; keep the Bash cwd at repo root (the `guard-paths.py` hook resolves relative to it); applied Flyway migrations are checksum-locked → any index is a NEW suffix-versioned file. Live writes go to `artha`/redis0 — point the appliance DSN at `artha` (live), never `artha_mock`.

---


## 10. React Migration Master Plan (Angular 21 -> React 19)

> **Scope.** Replace the entire `frontend-ui/` Angular 21 zoneless + PrimeNG 21 SPA with a new `frontend-react/` app (React 19 + Vite + Tailwind + shadcn/ui + TradingView Lightweight Charts). The new app must re-create the existing cockpit **and** the oipulse `/oi/*` analytics pages (the latter's data wiring is detailed in S11; this section owns the React app skeleton, shared infra, and the non-OI page inventory). Effort is explicitly not a constraint for the owner. The Java API, the edge-gateway, and `contracts/gen/*.d.ts` are the contract — **no Java changes** except the compose service swap + the gateway frontend route URI (already env-driven: `ARTHA_ROUTE_FRONTEND`).
>
> **Depends on:** S11 (oipulse `/oi/*` page parity in React — this section provides the shared `OiControlBar`/`SymbolContextStore`/`echarts-wrapper`/`DataBar` React equivalents those pages consume). **Cross-refs:** S7 (OpenAlgoGateway — no frontend impact, same `/api/v1` surface), S2 (openalgo-heatmap MIT core import for the OI heatmap).

### 10.0 Current Angular app — verified inventory (the migration source of truth)

Read from the live repo. Every fact below is grounded so the new session can map 1:1.

**Build/lint/test (Angular today):**
- `frontend-ui/package.json` scripts: `build` = `ng build` (prod config; budgets initial ≤1.5 MB error / 1.2 MB warn; `outputHashing: all`), `test:ci` = `ng test --watch=false` (Angular 21 `@angular/build:unit-test` builder running **Vitest 4** + jsdom 28), `lint` = `eslint src && stylelint "src/**/*.scss"`, `gen:api` = five `openapi-typescript` invocations over `../contracts/*.openapi.json` → `../contracts/gen/*.d.ts`.
- `frontend-ui/angular.json`: builder `@angular/build:application`, entry `src/main.ts`, global styles `node_modules/primeicons/primeicons.css` + `src/styles.scss`, dev-server proxy `proxy.conf.json`.
- `frontend-ui/proxy.conf.json`: `/api` → `http://127.0.0.1:8080`, `/ws` → `http://127.0.0.1:8080` (`ws:true`).
- `frontend-ui/src/environments/environment*.ts`: prod and dev both use **relative** `apiBase: '/api/v1'`, `wsUrl: '/ws'` — same-origin through the gateway, zero CORS.
- ESLint flat config (`eslint.config.js`): `@typescript-eslint/no-explicit-any: error`; a `no-restricted-imports` rule confines `lightweight-charts` to `pages/charts/**` + `shared/sparkline.ts` + `shared/equity-curve.ts` (E-9 containment).
- Stylelint (`.stylelintrc.json`): `color-no-hex` forbids raw hex outside `src/styles.scss` (all colour lives in `--ay-*` tokens).

**Serving / deploy (Angular today):**
- `frontend-ui/Dockerfile`: `nginx:1.27-alpine`, COPYs host-built `dist/frontend-ui/browser` → `/usr/share/nginx/html`, COPYs `nginx.conf`, `/healthz` → `200 "ok"`. **Host-build pattern** — the dist is built on the host (`npm run build`) before `docker build`, mirroring the Java services.
- `frontend-ui/nginx.conf`: SPA fallback `try_files $uri $uri/ /index.html`, immutable cache for hashed assets, `gzip_static on`.
- `deploy/docker-compose.yml` (line ~356) service `frontend-ui`: `build.context: ../frontend-ui`, `image: arthayantra/frontend-ui:dev`, `container_name: ay-frontend-ui`, `mem_limit: 64m`, healthcheck `wget -qO- http://127.0.0.1/healthz | grep -q ok`.
- `services/edge-gateway/src/main/resources/application.yml` (line ~74): route `id: frontend-ui`, `uri: ${ARTHA_ROUTE_FRONTEND:http://frontend-ui:80}`, `order: 10000`, `predicate: Path=/**` (catch-all; gateway-local controllers/actuator take precedence). **The gateway is the sole ingress; the SPA is internal-only, same-origin, zero CORS.**

**Auth/session/XSRF (verified in `src/app/core/`):**
- `session.store.ts` (`@ngrx/signals` root store): `probe()` GETs `/api/v1/auth/session` (PUBLIC, always 200; body `{authenticated:boolean}` decides), `login(password, returnUrl)` POSTs `/api/v1/auth/login` as `application/x-www-form-urlencoded` body `password=...`, `logout()` POSTs `/api/v1/auth/logout`, `refreshSystemStatus()` GETs `/api/v1/system/status` (sets `mockMode` when `kite.session === 'MOCK'`).
- `auth.guard.ts`: returns true if authenticated else awaits `probe()`, redirecting to `/login?returnUrl=...`.
- XSRF: Angular's `HttpClient` auto-echoes the `XSRF-TOKEN` cookie as the `X-XSRF-TOKEN` header on mutating calls. The gateway requires `X-XSRF-TOKEN` on POST/PUT/DELETE (CSRF, A.2.3). The e2e helper (`e2e/tests/helpers.ts`) confirms login POST returns **204** and that the raw fetch path must manually copy the cookie → header (React's `fetch` will need the same, see 10.3).
- `error.interceptor.ts`: the single place the D8 envelope `{code,message,details}` becomes a toast; session-probe 401s stay silent; `SILENCE_ERROR_TOAST` `HttpContextToken` suppresses fire-and-forget housekeeping errors.

**State/data conventions (verified):**
- 14 `@ngrx/signals` stores in `src/app/stores/` (`backtests, breadth, fii-dii, futures, jobs, journal, market, oi-analytics, optimizations, options, paper, signals, strategies, symbol-context`) + the root `SessionStore`.
- **`{items:[...]}` list envelope** for signals/paper/journal/screener/watchlists/backtest-trades/fii-dii/futures; **bare arrays** for `instruments/search`, `instruments/{name}/expiries`, `instruments/underlyings`, `ticks/latest`. (e.g. `fii-dii.store.ts` reads `res.items ?? []`; `backtests.store.ts` reads `page.items ?? []`.)
- **Decimals as JSON strings** — `core/decimal.ts` provides `compareDecimal`, `formatDecimal`, `isNegative`, `subtractDecimal` (BigInt-scaled), `multiplyByInt` (BigInt). **Never `parseFloat`** on money. Port verbatim — it's pure TS, framework-free.
- WS: `core/ws-client.service.ts` — one `@stomp/stompjs` `Client` over native WebSocket to `${ws|wss}://${location.host}/ws`, heartbeats 10s/10s, exponential backoff w/ jitter (`nextReconnectDelay`, 1s→30s cap, pure & unit-tested), **refcounted** subscribe/unsubscribe per destination, `reconnects$` Subject fires after every reconnect except the first (gap-heal → each store re-fetches its REST snapshot). `core/conflation.ts` `ConflationBuffer` flushes newest-per-key once per rAF (~16 ms). Destinations: `/topic/ticks.{EXCH}.{SYM}` (per-instrument ticks, `market.store.track(keys)`), `/topic/system` (Kite/connection deltas), `/topic/jobs/stream` (all `/topic/jobs/*` folded onto one `jobs.progress` channel by the gateway).
- Theming: `session.store.ts.applyTheme()` toggles `.ay-dark`/`.ay-light` on `<html>` (mutually exclusive), persisted to `localStorage['ay.theme']`, first-run respects `prefers-color-scheme`. `src/styles.scss` is the SOLE home of raw hex: a `--ay-*` token palette (`--ay-surface-0..2`, `--ay-border`, `--ay-text`, `--ay-text-muted`, `--ay-bull/bear/warn/accent`, `--ay-chart-grid`, `--ay-chart-crosshair`) with `:root` (dark default) and `:root.ay-light` overrides + `color-scheme`.
- Charts: `shared/echarts-chart.ts` (`ay-echart`) is a generic ECharts wrapper — caller supplies the option, wrapper merges a transparent token-coloured base (`backgroundColor:'transparent'`, `textStyle.color` read from `--ay-text-muted` via `getComputedStyle`, `aria.enabled`), owns init/resize(ResizeObserver)/dispose, jsdom-guarded. `shared/echarts-bootstrap.ts` tree-shakes ECharts 5.6 (Bar/Line/Scatter/Heatmap/Parallel/Candlestick/Custom + Grid/Tooltip/Legend/DataZoom/MarkLine/VisualMap/Parallel/Brush/Title + CanvasRenderer). `pages/charts/lwc-chart.component.ts` + `lwc-chart-binding.ts` + `datafeed/datafeed-core.ts` wrap lightweight-charts ≥5.2; the component holds **no** LWC types (binding isolates them).
- **Angular zoneless gotchas (CLAUDE.md "Frontend") that the React rewrite makes MOOT:** PrimeNG 21 `[virtualScroll]` rendering 0 rows; `lightweight-charts` needing `autoSize:true`; monaco/monaco-yaml workers failing → the app uses a `<textarea>` editor + a hand-rolled LCS diff (`pages/strategies/monaco-diff.ts`, `monaco-yaml-editor.ts`); the PrimeNG `darkModeSelector` `:root:not(.ay-light)` bug; PrimeNG icon-span accessible-name leak (`pt.button.icon['aria-hidden']`). **None of these PrimeNG/Angular-zoneless defects exist in React** — but each has a React equivalent risk noted in 10.5/10.7.

### 10.1 Where the new app lives + deploy swap

**Decision: new top-level `frontend-react/` alongside `frontend-ui/`, incremental cutover behind the gateway (recommended in 10.8).** Keep `frontend-ui/` building and shipping until the React app reaches parity, then flip the gateway route and delete `frontend-ui/`.

**Files to CREATE (skeleton):**
```
frontend-react/
  package.json            # React 19, Vite 6, Tailwind 4, vitest, eslint, prettier
  vite.config.ts          # @vitejs/plugin-react + server.proxy (/api,/ws) + build.outDir=dist
  tsconfig.json / tsconfig.node.json
  index.html              # <div id="root"></div>
  tailwind.config.ts      # darkMode:'class' (toggles .ay-dark on <html>)
  postcss.config.js
  eslint.config.js        # flat config; no-explicit-any:error; restrict lightweight-charts import
  components.json         # shadcn/ui CLI config (style, RSC:false, tailwind path, aliases)
  Dockerfile              # nginx:1.27-alpine, COPY dist + nginx.conf  (clone frontend-ui/Dockerfile, swap dist path)
  nginx.conf              # clone frontend-ui/nginx.conf verbatim (SPA fallback + /healthz + gzip_static)
  src/
    main.tsx              # ReactDOM.createRoot, QueryClientProvider, BrowserRouter, ThemeProvider
    index.css             # @tailwind base/components/utilities + the --ay-* token block ported from styles.scss
    App.tsx               # <RouterProvider> / route tree
    ...                   # (10.2–10.6 detail the tree)
```

**Vite config (`vite.config.ts`):** mirror `proxy.conf.json` exactly —
```ts
server: { proxy: {
  '/api': { target: 'http://127.0.0.1:8080', secure: false, changeOrigin: true },
  '/ws':  { target: 'http://127.0.0.1:8080', secure: false, ws: true },
}},
build: { outDir: 'dist' }   // nginx COPY target -> /usr/share/nginx/html
```

**Compose + Dockerfile swap (at cutover, not at start):**
- `frontend-react/Dockerfile`: identical to `frontend-ui/Dockerfile` except `COPY dist /usr/share/nginx/html` (Vite emits a flat `dist/`, not Angular's nested `dist/frontend-ui/browser`). Keep `nginx:1.27-alpine`, `/healthz`, the `HEALTHCHECK`.
- `frontend-react/nginx.conf`: clone `frontend-ui/nginx.conf` verbatim (SPA fallback, immutable hashed-asset cache, `gzip_static on`, `/healthz`).
- During cutover, run BOTH compose services (`frontend-ui` + `frontend-react`) and flip the gateway between them via the existing env var: set `ARTHA_ROUTE_FRONTEND=http://frontend-react:80` in `.env` (no Java/gateway-yaml edit needed — it's already `${ARTHA_ROUTE_FRONTEND:...}`). **Gotcha (CLAUDE.md Docker):** never invoke `docker compose` without `--env-file .env`; use `ay`/`ay.ps1` which always passes it. The gateway catch-all `Path=/**` order 10000 means controllers/actuator still take precedence — no route reordering needed.
- Final swap: rename `frontend-react`→ the compose `frontend-ui` service name (keep `container_name: ay-frontend-ui` and `image: arthayantra/frontend-ui:dev` so the e2e `gatewayHealthy()` SPA check and any tooling keep working), delete the old `frontend-ui/` dir, drop the dual route.
- **Image build context (CLAUDE.md Docker):** the frontend uses a **service-dir context** (`context: ../frontend-react`) and host-built dist — unlike market-data/optimizer which need repo-root context. Keep `frontend-react/Dockerfile` self-contained (no repo-root COPYs).

**Verify (10.1):** `cd frontend-react && npm run build` emits `dist/index.html` + hashed `assets/*`; `docker build -t arthayantra/frontend-react:dev frontend-react/` succeeds; `ARTHA_ROUTE_FRONTEND=http://frontend-react:80 ay up` then `curl -s http://127.0.0.1:8080/healthz`-equivalent (gateway serves `/` → React `index.html`); `wget http://127.0.0.1/healthz` inside `ay-frontend-react` returns `ok`.

### 10.2 Typed API client from `contracts/gen` (reuse the springdoc→openapi-typescript pipeline)

The existing pipeline already produces `contracts/gen/{backtest-service,edge-gateway,market-data-service,strategy-signal-service,optimizer-service}.d.ts` via `openapi-typescript@7` (the `gen:api` script). **The Angular app does not currently import these generated types** (verified: zero `contracts/gen` imports in `frontend-ui/src/`) — it hand-declares DTOs (e.g. `signals.store.ts` `SignalDto`). The React app should **adopt the generated types** as the typed-fetch source of truth.

**Files to CREATE in `frontend-react/`:**
- `package.json` `scripts.gen:api`: copy the five-invocation command from `frontend-ui/package.json` verbatim (paths are `../contracts/...` → still correct from `frontend-react/`). Add `openapi-typescript@^7` as a devDependency. **Workflow (CLAUDE.md "Contract spec drift"):** re-capture specs with `ContractCaptureTest -Dcontracts.capture=true`, then `npm run gen:api`. CI `ci-contracts` requires `tsc --strict` over the gen output.
- `src/api/types.ts` — re-export the generated `components['schemas']` and `paths` per service:
  ```ts
  export type { paths as MarketDataPaths, components as MarketDataComponents } from '../../../contracts/gen/market-data-service';
  // ...strategy-signal, backtest, optimizer, edge-gateway
  ```
- `src/api/client.ts` — a thin typed `fetch` wrapper (do **not** pull `openapi-fetch` unless desired; a 60-line wrapper suffices and matches "build own components"). Responsibilities:
  1. Relative base `'/api/v1'` (from a `config.ts` mirroring `environment.ts`); same-origin, no CORS.
  2. **XSRF:** read the `XSRF-TOKEN` cookie (`document.cookie`) and set `X-XSRF-TOKEN` on every mutating method (POST/PUT/DELETE/PATCH). `credentials: 'include'` so the `SESSION` cookie rides. This replicates Angular's automatic XSRF behaviour (Angular did it for free; React must do it explicitly — confirmed required by the e2e raw-fetch helper).
  3. **Error envelope:** on non-2xx, parse `{code,message,details}` and throw a typed `ApiError`; a TanStack Query global `onError` (or a small `errorToast` util) renders it as a shadcn `<Toaster>` toast — the single-toast-source rule from `error.interceptor.ts`. Provide a `silenceToast` option for fire-and-forget calls (the `SILENCE_ERROR_TOAST` equivalent).
  4. **`{items}` envelope:** a `listItems<T>(res): T[]` helper returning `res.items ?? []`; document which endpoints are bare-array (the four `instruments/*` + `ticks/latest`) vs enveloped.
  5. **Decimals:** the client does **not** coerce numbers — leaves money fields as the strings Jackson emits. Port `core/decimal.ts` verbatim → `src/lib/decimal.ts` + its spec (`decimal.spec.ts`) under Vitest. All money display goes through `formatDecimal`/`compareDecimal`.
- `src/api/queryKeys.ts` — central TanStack Query key factory (e.g. `['signals'], ['oi','options',name,expiry,interval]`).

**Generic `Map<String,Object>` returns are NOT in the spec** (CLAUDE.md): several endpoints return un-enumerated maps; for those, hand-type a small interface in `src/api/manual-types.ts` (the same way Angular hand-declared `SignalDto`/`ScoreBreakdownDto`). The frozen C-2.6 `ScoreBreakdown` shape (`signals.store.ts`) is one such — port its interface verbatim.

**Verify (10.2):** `npm run gen:api` regenerates without diff against committed `contracts/gen`; `tsc --noEmit --strict` passes over `src/api/`; a unit test asserts `listItems({items:[1]})===[1]` and `listItems({})===[]`; `decimal.spec.ts` ports green.

### 10.3 Auth / session / XSRF replication

**Files to CREATE:**
- `src/stores/session.store.ts` (Zustand): state `{auth:'unknown'|'checking'|'authenticated'|'anonymous', loggingIn, loginError, theme:'dark'|'light', mockMode}` + `authenticated` selector. Methods `probe()`, `login(password,returnUrl)`, `logout()`, `toggleTheme()`, `refreshSystemStatus()` — **port the exact endpoint contracts** from `session.store.ts`:
  - `probe`: `GET /api/v1/auth/session` → body `{authenticated}` decides (200 alone ≠ signed-in).
  - `login`: `POST /api/v1/auth/login`, `Content-Type: application/x-www-form-urlencoded`, body `password=...`; success = 204 → navigate `returnUrl||'/'`.
  - `logout`: `POST /api/v1/auth/logout` → set anonymous, navigate `/login`.
  - `refreshSystemStatus`: `GET /api/v1/system/status` → `mockMode = kite.session === 'MOCK'`.
  - theme persisted to `localStorage['ay.theme']`, first-run `prefers-color-scheme`, `applyTheme` toggles `.ay-dark`/`.ay-light` on `document.documentElement`.
- `src/auth/RequireAuth.tsx` — a React Router guard component (replaces `authGuard`): if `session.authenticated` render `<Outlet/>`; else `await probe()`; on false `<Navigate to="/login" state={{returnUrl: location.pathname+search}} replace/>`.
- `src/pages/login/LoginPage.tsx` — shadcn `<Input type="password" name="password">` + `<Button>Sign in</Button>` (keep `name="password"` + the visible "Sign in" button text so the e2e `loginThroughForm` helper still works).

**XSRF note:** because `login` is a mutating POST, the client must already hold an `XSRF-TOKEN` cookie. Angular seeded it via any prior GET. In React, call `probe()` (a GET to `/api/v1/auth/session`) on app boot before any POST so the cookie is set — the e2e `apiLogin` proves the gateway issues the cookie and requires the header echo.

**Verify (10.3):** `e2e/tests/login.spec.ts` passes unmodified against the React build (login form, 204, lands on shell). Manual: in mock mode, login with `e2e-owner-password`, a logout returns to `/login`, a wrong password shows the envelope toast.

### 10.4 Routing (React Router) + state (TanStack Query + Zustand)

**Stack decision (matches OpenAlgo's own React stack):** TanStack Query v5 for all server-cache state (every Angular `http.get→patchState` becomes a `useQuery`/`useMutation`), Zustand for client/UI state (theme, the shared symbol-context selection, WS connection status), React Router v7 (data router) for routing. **Do NOT use Redux/NgRx-like global stores for server data** — that was the Angular `@ngrx/signals` pattern; TanStack Query subsumes it (caching, refetch-on-reconnect, dedup).

**Routing (`src/App.tsx`, `createBrowserRouter`):** port `app.routes.ts` 1:1. `/login` public; everything else under `<RequireAuth>` → `<AppShell>` layout route with the same child paths and document titles (use a `useDocumentTitle` hook for the `· ArthaYantra` suffixes). Lazy every page via `React.lazy` + `<Suspense>` (replaces Angular `loadComponent`) so ECharts/LWC stay out of the initial bundle (the E-6 lazy-chunk discipline). The `strategies/:id/edit` `canDeactivate` guard → a `useBlocker` (React Router) unsaved-changes prompt.

**Store → React mapping table:**

| Angular store (`src/app/stores/`) | React replacement | Notes |
|---|---|---|
| `SessionStore` (core) | Zustand `session.store.ts` | 10.3 |
| `symbol-context.store` | Zustand `symbolContext.store.ts` | shared OI selection; persists `ay.oi.{name,expiry,interval}` to localStorage; `OI_INTERVALS=['1m','3m','5m','15m','30m','60m']` (note `60m` not `1h`); loads expiries via bare-array `GET /api/v1/instruments/{name}/expiries`. **S11 consumers depend on this.** |
| `market.store` | Zustand (`ticks` map + connection) + WS bridge | `track(keys)` → refcounted `/topic/ticks.{EXCH}.{SYM}` subs seeded from `GET /api/v1/market/ticks/latest`; conflated per-rAF; `/topic/system` deltas |
| `signals.store` | TanStack `useSignals()` + WS live append | REST snapshot `{items}` + `reconnects$`-equivalent invalidate; port `SignalDto`/`ScoreBreakdownDto` |
| `oi-analytics.store` | TanStack queries (S11 owns) | this section provides the shared infra; S11 wires endpoints |
| `options/futures/breadth/fii-dii` | TanStack queries | `{items}` envelope |
| `paper/journal/watchlists` | TanStack queries + mutations | `{items}` envelope; mutations need XSRF |
| `backtests/optimizations/jobs` | TanStack queries + `/topic/jobs/stream` WS | jobs progress folded onto `jobs.progress`; results keyed by **run id (`resultRef`)**, not jobId (CLAUDE.md backtest note) |
| `strategies.store` | TanStack queries + mutations | YAML form (10.6) |

**WS in React (`src/lib/ws-client.ts` + `src/stores/ws.store.ts`):** port `ws-client.service.ts` as a **framework-free singleton** module (it already is — `@stomp/stompjs` `Client`, not Angular-specific). Keep `nextReconnectDelay` and its unit test verbatim. Expose:
- a `wsTopic(destination): { subscribe, unsubscribe }` refcounted API (the existing `topic()` semantics), and
- a Zustand `useWsState()` for the TopBar pill.
- On reconnect, instead of Angular's `reconnects$` Subject, call `queryClient.invalidateQueries()` for the affected keys (gap-heal → re-fetch snapshot). Wire this in `main.tsx`.
- Port `ConflationBuffer` verbatim → `src/lib/conflation.ts` (used by the tick bridge).

**Verify (10.4):** `e2e/tests/ws-reconnect.spec.ts` passes (TopBar shows `WS connected`, a stack bounce re-heals); `dashboard.spec.ts` + `signals.spec.ts` pass; React Router deep-links (`/oi/spurt`, `/backtests/:id`) resolve and the SPA fallback serves them through nginx.

### 10.5 Theming (Tailwind dark mode + transparent-chart wrappers) — avoid the PrimeNG-class bug

**The PrimeNG `darkModeSelector` bug does not exist in React** (no PrimeNG). But Tailwind has the equivalent footgun, so the rule is explicit:
- `tailwind.config.ts`: `darkMode: 'class'` and **scope dark variants to a plain `.ay-dark` class** (`darkMode: ['class', '.ay-dark']` in Tailwind 4) — never an attribute or a `:root`-anchored selector. `session.store.toggleTheme()` toggles `.ay-dark`/`.ay-light` on `<html>` (identical to Angular). Keep the two classes mutually exclusive.
- `src/index.css`: **port the entire `--ay-*` token block from `src/styles.scss` verbatim** — `:root` (dark default, `color-scheme: dark`) + `:root.ay-light` (light overrides + the WCAG-retuned `--ay-bull/bear/warn/accent` darker shades + `color-scheme: light`). Map Tailwind's theme colors to these tokens (`theme.extend.colors.surface0 = 'var(--ay-surface-0)'`, etc.) so utilities resolve to the same palette. **Raw hex lives only in `index.css`** — keep a stylelint/eslint rule forbidding hex elsewhere (port `color-no-hex`). Also port the `.ay-pulse` price-flash keyframes + `prefers-reduced-motion` guard, the `.ay-sr-only` helper, and the `font-family: Inter,...` base.
- shadcn/ui: configure its CSS variables (`--background`, `--foreground`, `--primary`, ...) in `index.css` to **alias the `--ay-*` tokens** (e.g. `--background: var(--ay-surface-0)`) so shadcn components inherit the single palette under both `.ay-dark` and `.ay-light`. This is the one-palette discipline from D2/C-2.23. Run a contrast check (the Angular app fought WCAG 4.5:1 in light mode — the retuned tokens already pass; keep them).

**Charts-as-transparent-wrapper equivalent:**
- `src/components/charts/EChart.tsx` — port `ay-echart`: a `useRef` div, `echarts.init` in `useEffect`, `ResizeObserver`, `setOption(withBase(option), true)` where `withBase` injects `backgroundColor:'transparent'`, `textStyle.color` read from `getComputedStyle(el).getPropertyValue('--ay-text-muted')`, `aria:{enabled:true}`; dispose on unmount. Re-use `echarts-bootstrap.ts` tree-shaking list verbatim → `src/components/charts/echarts-bootstrap.ts`. **Re-init the option on theme change** (the Angular wrapper deferred this; in React, key the `<EChart>` on `theme` or call `setOption` in a theme-effect so charts re-read tokens).
- TradingView Lightweight Charts: `src/components/charts/LwcChart.tsx` — port `lwc-chart.component.ts` + `lwc-chart-binding.ts` + `datafeed/` (`datafeed-core.ts`, `marks.ts`, `timestamp.ts` are framework-free TS — port verbatim with their specs). **The Angular `autoSize:true` gotcha still applies** (LWC measures 0×0 before first paint) — pass `createChart(el,{autoSize:true})` and the chart must mount after the container has dimensions (a `useLayoutEffect` after the ref is set). Keep the E-9 containment as an eslint `no-restricted-imports` rule confining `lightweight-charts` to `components/charts/**` + the sparkline/equity-curve wrappers.

**Verify (10.5):** prod build (`npm run build` + serve) renders the **dark** PrimeNG-free shell with no white flash; toggling theme flips `<html>` class and both ECharts (`backtests/:id` equity curve) + LWC (`/charts`) re-colour; axe-core (via Playwright) reports 0 contrast violations in both themes.

### 10.6 Component inventory — every Angular page/component → React

| Angular (`src/app/...`) | React (`frontend-react/src/...`) | Source endpoints / notes |
|---|---|---|
| `shell/app-shell.ts` | `components/AppShell.tsx` | TopBar (IST clock via `Intl.DateTimeFormat('en-IN',{timeZone:'Asia/Kolkata'})`, mock-mode tag, `WS {state}` pill, theme toggle, logout) + collapsible SideNav (same link list/order) + `<Outlet/>` + shadcn `<Toaster>` |
| `pages/login/login-page.ts` | `pages/login/LoginPage.tsx` | 10.3 |
| `pages/dashboard/dashboard-page.ts` + `widget-shell.ts` + `widgets/*` (active-signals, jobs, kite-status, market-overview, paper-pnl, watchlist) | `pages/dashboard/` widgets | each widget = a `useQuery`; market-overview + watchlist use `market.store.track`; jobs widget subscribes `/topic/jobs/stream`; sparklines via the LWC sparkline wrapper |
| `pages/signals/signals-page.ts` + `reasoning-breakdown-panel.ts` | `pages/signals/` | `{items}` REST + live append; `ScoreBreakdownDto` gate-tree renderer (frozen C-2.6) |
| `pages/charts/*` (charts-page, chart-toolbar, lwc-chart, binding, datafeed, indicators, chart-state.store) | `pages/charts/` + `components/charts/LwcChart.tsx` | 10.5; overlays are **engine-computed series** (S7 — never client-side math) |
| `pages/options/options-page.ts`, `pages/futures/futures-page.ts` | `pages/options/`, `pages/futures/` | `{items}`; futures screener via `GET /api/v1/market/screener` |
| `pages/oi/*` (oi-options, oi-futures, oi-spurt, oi-big-oi, oi-premium, oi-futures-spurt, oi-eod, oi-bank-grid, **oi-control-bar**) | **S11 owns the pages**; this section provides `components/OiControlBar.tsx` + `symbolContext.store` + `EChart` + `DataBar` | `OiControlBar` binds the shared `symbolContext` store (Mode·Name·Date·Expiry·Interval); expiry hidden for futures |
| `pages/fii-dii/fii-dii-page.ts`, `pages/breadth/breadth-page.ts` | `pages/fii-dii/`, `pages/breadth/` | `{items}`; FII/DII three queries (cash/participant-oi/long-short) |
| `pages/paper/paper-page.ts` | `pages/paper/` | `{items}` + mutations (XSRF); P&L via `subtractDecimal`/`multiplyByInt` |
| **(NEW — no Angular source)** | `pages/orders/OrdersPage.tsx` (route `/orders`) | **§18.1: live order-book / open-positions / funds via §17.3 `OrderGateway` read endpoints (`GET /api/v1/orders/{orderbook,positions,tradebook,funds}`); Phase 4b. Reflects paper ledger in mock, OpenAlgo in live.** |
| `pages/journal/journal-page.ts` + `shared/journal-drawer.ts` | `pages/journal/` + `components/JournalDrawer.tsx` | `{items}` + CRUD mutations |
| `pages/watchlists/watchlists-page.ts` | `pages/watchlists/` | `{items}` + mutations |
| `pages/strategies/*` (strategy-list, strategy-editor, strategy-form, monaco-yaml-editor, monaco-diff, versions, quick-backtest-drawer, strategy-compare, stress-advisory) | `pages/strategies/` | **YAML editor = `<textarea>` + CodeMirror 6 (recommended) or keep the textarea fallback**; the Angular monaco-worker breakage does NOT recur in React (Vite handles workers), so a real editor is now feasible — but the textarea+LCS-diff path (port `monaco-diff.ts` LCS verbatim) is the zero-risk choice. `strategy-form.ts` YAML parse/stringify uses `yaml` pkg — port verbatim. `canDeactivate` → `useBlocker`. |
| `pages/backtests/*` (runner, results, compare, fold-panel) + `pages/jobs/jobs-page.ts` | `pages/backtests/`, `pages/jobs/` | ECharts (equity curve, fold bars); results keyed by **`resultRef`** run id; jobs progress via `/topic/jobs/stream` |
| `pages/optimizations/*` (sweep-detail, sweep-explorer) | `pages/optimizations/` | ECharts scatter/parallel-coords/heatmap (the brush-filtering `chartInit` output → React callback ref) |
| `pages/settings/settings-page.ts`, `pages/home/home-page.ts` | `pages/settings/`, `pages/home/` | |
| `shared/data-bar.ts` | `components/DataBar.tsx` | in-cell magnitude bar (`--ay-bar-w` width, bull/bear/neutral tone) — S11 OI tables depend on it |
| `shared/oi-int-badge.ts`, `degradation-badge.ts`, `degradation.ts`, `oi-interpretation.ts` (core) | `components/` + `lib/` | port the 4-state OI interpretation + degradation logic verbatim (pure TS) |
| `shared/sparkline.ts`, `equity-curve.ts` | `components/charts/Sparkline.tsx`, `EquityCurve.tsx` | LWC wrappers (allowed by the containment rule) |
| `shared/echarts-chart.ts`, `echarts-bootstrap.ts`, `pulse.directive.ts` | `components/charts/EChart.tsx`, `echarts-bootstrap.ts`, `usePulse` hook | `pulse.directive` → a `useEffect` adding `.ay-pulse` on value-change |
| **List tables** (currently PrimeNG `p-table` `[scrollable]` — virtualScroll banned under zoneless) | **shadcn `<Table>` + TanStack Table** | **The zoneless virtualScroll-renders-0-rows bug is GONE**; under React+TanStack Virtual, virtualization works correctly, so large OI/signal tables can use real row virtualization (a net improvement over the Angular `scrollHeight`-only workaround). |

**Shared `core/` ports (pure TS, verbatim + specs):** `decimal.ts`(+spec), `conflation.ts`, `oi-interpretation.ts`, `ws-client.ts`'s `nextReconnectDelay`(+spec), the `datafeed/{datafeed-core,marks,timestamp}.ts`(+specs).

**Verify (10.6):** every route renders without console errors in mock mode; the e2e suite (`backtest-results, chart-marks, chart-toolbar, charts, dashboard, signals, strategy-editor, strategy-versions, sweep-explorer, ws-reconnect, notifier`) passes against the React build with **minimal selector changes** (see 10.7).

### 10.7 Which MIT React repos to IMPORT (and the AGPL prohibition)

- **IMPORT — `openalgo-heatmap` (MIT, TypeScript/React):** use its **renderer-agnostic zero-dep core** (layout + colour math) to drive the OI heatmap (tile size = OI, colour = OI-change) on the OI pages (S11/S2). Vendor the core under `frontend-react/src/vendor/openalgo-heatmap/` with the upstream MIT `LICENSE`/copyright header preserved. Build the React tile component ourselves against the Java OI API; do **not** import its full React component if it pulls a renderer we don't want.
- **REFERENCE ONLY (do not vendor):** `yfinance-terminal` (layout/UX inspiration for the cockpit), `PineTS`/`openalgo-pinets` (TypeScript indicator implementations — a cross-check for the S6 Java indicator ports; the indicators run server-side in the scalp engine, so the frontend does **not** compute them — charts render engine-computed series only, S7). `fastscalper-tauri` (fast-order-entry UX reference for the scalp cockpit).
- **HARD PROHIBITION (LICENSE FILTER):** **NEVER** import OpenAlgo-core's AGPL Flask/Vue frontend or any AGPL-licensed UI source into `frontend-react/`. We consume OpenAlgo only as a **runtime API/data source** behind the S7 anti-corruption boundary — its source never enters our tree. MIT pieces (heatmap core) may be vendored with attribution; AGPL pieces may not, full stop.

**Verify (10.7):** the OI heatmap renders tile-by-OI / colour-by-OI-change from the live Java OI endpoint; `frontend-react/src/vendor/openalgo-heatmap/LICENSE` present; a grep confirms no AGPL-sourced files under `src/`.

### 10.8 Cutover strategy + e2e + obsolete-gotcha disposition

**Recommended: incremental page-by-page behind the gateway, NOT big-bang.** Rationale: 25+ routes, a live trading dependency, and the gateway already supports two frontend services via `ARTHA_ROUTE_FRONTEND`. Concretely:
1. Stand up `frontend-react/` with shell + login + dashboard + one OI page, served as a second compose service.
2. Validate each migrated page against the running mock stack via Playwright before moving on.
3. Flip `ARTHA_ROUTE_FRONTEND` to React only when the **full** route set + e2e is green (the gateway can't path-split between two SPAs cleanly because both want `Path=/**` and the SPA fallback owns deep links — so the "incremental" is in **development order**, with a single atomic route flip at the end). This keeps `frontend-ui/` shippable as the rollback the whole time.
4. Delete `frontend-ui/` and the dual route after a clean live + mock soak.

**Playwright e2e reuse:** the suite (`e2e/`) is **framework-agnostic** — `playwright.config.ts` targets `baseURL: http://127.0.0.1:8080` (the gateway), `global-setup.ts` boots the mock stack and gates on `gatewayHealthy()` (actuator UP **and** the SPA served through the catch-all). `helpers.ts` `loginThroughForm` targets `input[name="password"]` + the "Sign in" button by role; `apiLogin` hits `/api/v1/auth/login` (204) and copies `XSRF-TOKEN`→`X-XSRF-TOKEN`. **Keep these selectors/contracts stable in React** (same input name, same button text, same shell selector `ay-shell` — either keep the `ay-shell` selector as a `data-testid` or update the spec to `[data-testid="app-shell"]`). Most specs assert by role/text and should pass with near-zero change; budget a pass to fix any selector drift. Run with `cd e2e && E2E_OWNER_PASSWORD=<.env owner pw> npx playwright test` (or the fixed `e2e-owner-password` in CI). **CI readiness gate on container healthchecks, not gateway HTTP** (CLAUDE.md CI note).

**Obsolete Angular/PrimeNG-zoneless gotchas — disposition:**
| Old gotcha | React status |
|---|---|
| PrimeNG `[virtualScroll]` renders 0 rows (zoneless) | **Gone.** Use TanStack Virtual — real virtualization now works. |
| `lightweight-charts` blank without `autoSize:true` | **Still applies** — LWC measures 0×0 pre-paint; keep `autoSize:true` + mount after container has size (`useLayoutEffect`). |
| monaco/monaco-yaml workers fail → textarea + LCS diff | **Gone** (Vite handles workers) — a real CodeMirror editor is feasible; the textarea+LCS path remains the zero-risk default (port `monaco-diff.ts` LCS). |
| PrimeNG `darkModeSelector :root:not(.ay-light)` light-scheme bug | **Gone** (no PrimeNG). Tailwind `darkMode:['class','.ay-dark']` is the analogue — keep it a plain class, never `:root`-anchored. |
| PrimeNG button icon `::before` accessible-name leak | **Gone** (no PrimeNG icons; shadcn uses inline SVG/`lucide-react` with `aria-hidden`). |

### 10.9 Lint / test / build verify trio for React (matches the Angular `npm run lint`/`test:ci`/`build`)

**Files to CREATE / `package.json` scripts:**
- `lint`: `eslint src` + (if keeping SCSS-token discipline) a `stylelint` over the token file — but Tailwind+CSS-vars likely means just `eslint`. Keep the flat config with `@typescript-eslint/no-explicit-any: error` (the D1 typing bar) and the `no-restricted-imports` LWC-containment rule (port from `frontend-ui/eslint.config.js`). Add `eslint-plugin-react-hooks` + `eslint-plugin-jsx-a11y` (the a11y rules replace `angular.configs.templateAccessibility`).
- `test:ci`: `vitest run` (Vitest 4 + jsdom — same as Angular's `@angular/build:unit-test` already runs Vitest, so the test runner is unchanged; `@testing-library/react` for component tests). Port every `*.spec.ts` for pure-TS modules (`decimal`, `conflation`, `ws-client`, `datafeed/*`, `oi-interpretation`, `degradation`) verbatim — they're framework-free.
- `build`: `vite build` (then `tsc -b` typecheck). Add a bundle-size budget check (the Angular budgets were 1.5 MB initial / 8 kB per-component-style; for Vite use `rollup-plugin-visualizer` + a CI size gate to keep ECharts/LWC in lazy chunks — E-6).
- `gen:api`: as 10.2.

**Verify trio (the React analogue of the Angular "Verify trio"):**
```
cd frontend-react
npm run lint      # eslint clean (no-explicit-any, a11y, LWC containment)
npm run test:ci   # vitest run — ported pure-TS specs + RTL component tests green
npm run build     # vite build + tsc -b — bundle within budget, ECharts/LWC lazy-chunked
```
Then the full regression: `ARTHA_ROUTE_FRONTEND=http://frontend-react:80 ay up` + `cd e2e && npx playwright test` (mock stack, container-health gated). **Line-endings (CLAUDE.md Git):** `contracts/gen/*.d.ts` and any committed JSON stay `eol=lf` — after adding the React app's `.gitattributes`/eslint, `git add --renormalize`. **CI:** add a `ci-react` (or fold into the existing frontend CI) shard running the trio; keep `ci-contracts` (`-am`, `tsc --strict` over gen) intact since the React app now consumes `contracts/gen`.

---


## 11. oipulse OI-Analysis Pages in React

> **Depends on Section 10 (React shell migration).** This section assumes S10 has stood up the React 19 + Vite + Tailwind + shadcn/ui app skeleton with: an Axios/`fetch` API client carrying the loopback CSRF/session cookie convention, a `core/decimal.ts` port (see §11.4), a TanStack Query `QueryClientProvider`, a router (TanStack Router or React Router), the `.ay-dark`/`.ay-light` theme tokens (`--ay-*` CSS variables) carried over verbatim, the `<AyEChart>` wrapper (§11.3), and a `<LightweightChart>` wrapper. This section **adds the 10 OI/market pages, two Zustand stores, the OI primitives, and the heatmap import**. **No backend changes** — every endpoint already exists in `market-data-service` and is proxied through the edge-gateway.

### 11.0 What exists today (verified against the Angular app)

The Angular `frontend-ui` already ships the full oipulse parity surface. The React rewrite must re-create it 1:1. **(§18.7: this is 1:1 with CURRENT Angular only — oipulse features studied but never built in Angular [multiple-OI-chart, calendar-spread Add-Position, Advance-Chart/TradingView, Investing.com dashboard] are future work, not in this plan; the socket-driven ones wire to §10.4's WS bridge.)** The authoritative current sources:

- Routes: `frontend-ui/src/app/app.routes.ts` (lines 43–94) — eight `/oi/*` routes + `/market/fii-dii` + `/market/breadth`, plus the redirect `oi` → `oi/options`.
- Nav labels: `frontend-ui/src/app/shell/app-shell.ts` (lines 125–134).
- Stores: `frontend-ui/src/app/stores/oi-analytics.store.ts` (one `signalStore`, 13 generation-token loaders, all wire interfaces), `symbol-context.store.ts`, `fii-dii.store.ts`, `breadth.store.ts`.
- Control bar: `frontend-ui/src/app/pages/oi/oi-control-bar.ts` (`showName`/`showExpiry` flags).
- Primitives: `frontend-ui/src/app/core/oi-interpretation.ts` (4-state META map), `shared/oi-int-badge.ts`, `shared/data-bar.ts`, `core/decimal.ts`, `shared/echarts-chart.ts`, `shared/echarts-bootstrap.ts`.
- Pages: `frontend-ui/src/app/pages/oi/{oi-options,oi-futures,oi-spurt,oi-big-oi,oi-premium,oi-futures-spurt,oi-eod,oi-bank-grid}-page.ts`, `pages/fii-dii/fii-dii-page.ts`, `pages/breadth/breadth-page.ts`.
- Backend: `services/market-data-service/.../options/analytics/OptionsAnalyticsController.java` (`@RequestMapping("/api/v1/market/options")`), `.../futures/analytics/FuturesAnalyticsController.java` (`/api/v1/market/futures`), `.../nse/analytics/FiiDiiController.java` + `BreadthController.java`.

**Critical wire facts (from the Angular store comments + Java records):**
- BigDecimal fields (`pcr`, `maxPain`, `ltp`, `iv`, `spot`, `strike`, `sentimentPct`, `spurtPct`, `pricePct`, `oiPct`, `basis`, `straddle`, `ce`, `pe`, `atmStraddle`, `buyValue`, `ratio`, `deliveryPct`, `close`, `pctChange`, …) cross the wire as **JSON strings** (Jackson). `long` OI / count fields (`oi`, `oiChange`, `ceOi`, `peOi`, `totalOi`, `advances`, `futureIndexLong`, `oiClose`, `volume`, …) are **numbers**. Never `parseFloat` a price-string for display/compare — only `Number(...)` at the ECharts data boundary.
- `/oi-analysis` (options & futures), `/eod`, `/fii-dii/{cash,participant-oi,long-short}` return an `{ "items": [...] }` envelope. Everything else returns a bare object.
- Options endpoints **require `expiry`** (400 `VALIDATION_FAILED` if absent). Futures endpoints **ignore `expiry`**. `banks-grid` takes only `mode`/`date`/`interval` (no `name`). `eod` takes `name`/`from`/`to` (its own range, not interval). `fii-dii`/`breadth` take `from`/`to` or `date`.
- Empty data (`oi-stats`, `active-strikes`, `spurt`, `big-oi`, `premium`, `banks`, `banks-grid`, `buzz`, `fii-dii`, `breadth`) returns HTTP **422 `DATA_GAP`** (or empty `{items}`); this is a **normal empty state** — suppress the error toast. `interval` token for 1-hour is **`60m`, not `1h`** (`OI_INTERVALS = ['1m','3m','5m','15m','30m','60m']`).

---

### 11.1 Page-by-page inventory (re-create all 10)

| Route | React component | Endpoints consumed (params) | Tables | Charts | Controls / notes |
|---|---|---|---|---|---|
| `/oi/options` | `OiOptionsPage` | `options/oi-stats`, `options/active-strikes`, `options/oi-analysis`, `options/spurt` (mode,name,date,interval,expiry) | Mirrored CE/PE strike grid (9 cols: CE OI bar, CE ΔOI, CE IV, CE LTP, **Strike**, PE LTP, PE IV, PE ΔOI, PE OI bar); ITM tint via `compareDecimal(strike, spot)` | none | `<OiControlBar/>` (name+expiry+interval); header = PCR · MaxPain · CE/PE OI · sentiment% · strike count; OI-bias badge from `spurt.summary.interpretation` |
| `/oi/spurt` | `OiSpurtPage` | `options/spurt` | Per-strike·side grid (Strike, Side, LTP, OI bar, ΔOI bar, Spurt%, **Buildup badge**) | none | full control bar; OI-bias badge header |
| `/oi/big-oi` | `OiBigOiPage` | `options/big-oi`, `options/premium`, `options/trending` | Legs ranked by \|ΔOI\| (Strike, Side, LTP, OI bar, ΔOI bar) | **Total-OI line** (ECharts, when >1 pt) | header = ATM straddle @ strike · latest OI trend (`UP/DOWN/FLAT`) |
| `/oi/premium` | `OiPremiumPage` | `options/premium`, `options/premium-series` | Per-strike straddle chain (Strike, CE, PE, Straddle) | **ATM-straddle decay line** (ECharts, when >1 pt) | header = ATM straddle @ strike · spot |
| `/oi/futures` | `OiFuturesPage` | `futures/oi-analysis`, `futures/movers`, `futures/banks`, `futures/buzz` | (1) per-contract OI (Contract, LTP, OI bar, ΔOI bar); (2) Movers (Contract, LTP, Price%, OI%, **badge**); (3) Term structure / banks (Contract, Expiry, LTP, Basis, ΔOI, **badge**) | **Buzz heatmap** (bucket×contract, 4-state, labelled `visualMap`) | `<OiControlBar showExpiry={false}/>` |
| `/oi/futures-spurt` | `OiFuturesSpurtPage` | `futures/spurt` | Per-contract (Contract, LTP, OI bar, ΔOI bar, Spurt%, **badge**) | none | control bar, expiry hidden |
| `/oi/eod` | `OiEodPage` | `futures/eod` (name,from,to) | Daily rollup (Contract, Date, O,H,L,C, OI close, ΔOI, Volume) | **Front-contract candlestick** (ECharts, when >1 row) | control bar + **own From/To date pickers** (default 14d..today) |
| `/oi/banks-grid` | `OiBankGridPage` | `futures/banks-grid` (mode,date,interval — no name) | One row per bank stock (Stock, Contract, LTP, Price%, OI bar, ΔOI bar, OI%, **badge**) | none | `<OiControlBar showName={false} showExpiry={false}/>` |
| `/market/fii-dii` | `FiiDiiPage` | `fii-dii/cash`, `fii-dii/participant-oi`, `fii-dii/long-short` (from,to) | (1) Cash (Date,Category,Buy,Sell,Net); (2) FII L/S (Date,Long,Short,L/S ratio); (3) Participant OI (7 cols) | **FII vs DII net-flow grouped bar** (ECharts) | **own From/To pickers** (default 30d..today); separate `FiiDiiStore` |
| `/market/breadth` | `BreadthPage` | `breadth` (date) | Delivery leaders (Symbol, Delivery%, Close, %change) | **Advance/Decline/Unchanged bar** (ECharts, coloured + axis-labelled) | **own single Date picker** (default today IST); separate `BreadthStore`; summary line `▲ advances · ▼ declines · …` |

Nav (re-create in S10 shell, same labels/order): Options OI, Futures OI, OI Spurt, Big OI, Option Premium, Futures Spurt, Futures EOD, Bank Futures, FII / DII, Breadth.

---

### 11.2 State layer — TanStack Query + Zustand

**Design decision:** Angular's `OiAnalyticsStore` mixed two concerns — (a) the **selection** (Symbol Context) and (b) the **server cache** with per-loader generation tokens. In React, split cleanly:
- **Selection** → a Zustand store (`useSymbolContext`) + localStorage, mirroring `SymbolContextStore`.
- **Server cache + stale-drop** → TanStack Query. The generation-token / "drop stale response on symbol switch" logic Angular hand-rolls (13 `optGen`/`futGen` counters) is **exactly what a TanStack Query key gives you for free**: when the key changes, the old query is no longer the "current" observed query, so its late resolution can't overwrite the active key's data. **Do not port the generation counters.**

**CREATE `react-ui/src/stores/symbolContext.ts`** (Zustand) — mirrors `symbol-context.store.ts`:
```ts
export const OI_INTERVALS = ['1m','3m','5m','15m','30m','60m'] as const;   // 60m, NOT 1h
export type OiInterval = (typeof OI_INTERVALS)[number];
export type OiMode = 'live' | 'history';
const NAME_KEY='ay.oi.name', EXPIRY_KEY='ay.oi.expiry', INTERVAL_KEY='ay.oi.interval'; // keep same keys

interface SymbolContext {
  name: string; expiry: string | null; interval: OiInterval; mode: OiMode; date: string | null;
  setName(n: string): void; setExpiry(e: string|null): void; setInterval(i: OiInterval): void;
  setMode(m: OiMode): void; setDate(d: string|null): void;
}
```
- Hydrate `name`/`expiry`/`interval` from localStorage on create; persist on set (private-mode try/catch). `mode`/`date` are session-transient (NOT persisted) — match Angular exactly.
- `setName` clears `expiry` (set `null`) so the new underlying's expiry list reloads. The **expiry list itself** is NOT selection state — fetch it with a TanStack Query keyed on `name` (see below) and default `expiry` to `list[0]` when unset.

**Expiry list query** (replaces `loadExpiries`): `useExpiries(name)` → `GET /api/v1/instruments/{encodeURIComponent(name)}/expiries` → `string[]` (bare array, **not** `{items}`). In an effect, if `expiry` is unset and the list is non-empty, call `setExpiry(list[0])`.

**CREATE `react-ui/src/api/oiAnalytics.ts`** — one query hook per endpoint. Shared `oiParams(ctx, includeExpiry)` builds the query string (`mode`,`name`,`interval`, `date` only if set, `expiry` only if `includeExpiry`). Port the `unsatisfiable` guard as TanStack's `enabled`:
```ts
function satisfiable(ctx, needExpiry: boolean) {
  if (!ctx.name) return false;
  if (needExpiry && !ctx.expiry) return false;
  return !(ctx.mode === 'history' && !ctx.date);   // history requires date (backend 400s)
}
```
Each hook (named to match the Angular loader): `useOiStats`, `useActiveStrikes`, `useOiAnalysis`, `useOptionsSpurt`, `useBigOi`, `usePremium`, `usePremiumSeries`, `useTrending`, `useFuturesOi`, `useFuturesSpurt`, `useMovers`, `useBanks`, `useBankGrid`, `useBuzz`, `useEod`, `useFiiDii`, `useBreadth`.

- **Query key** carries the full selection so it doubles as the stale-drop token, e.g. `['oi','oi-stats', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date]`. `banks-grid` key omits `name`; `eod` key is `['fut','eod', ctx.name, from, to]`; `fii-dii` is `['fiidii', from, to]`; `breadth` is `['breadth', date]`.
- **422/DATA_GAP handling:** the gateway/API client must not show a toast for these. Mirror the Angular `SILENCE_ERROR_TOAST` context: tag these requests so the global error interceptor skips them, and in the hook map a 422 to `data = null`/`[]` (use `retry: false`, and in `queryFn` catch the 422 → return the empty shape rather than throw, so the page renders its empty-state copy).
- `{items}` envelope: in the `queryFn` return `res.items ?? []` for `oi-analysis`/futures-`oi-analysis`/`eod`/`fii-dii/*`; return the bare object otherwise.
- **`foldStrikes`** (port from `oi-analytics.store.ts` lines 234–253): fold flat `OiStrikePoint[]` into `OiChainRow[]` (CE+PE per strike), sorted by `compareDecimal(strike)`. Keep as a pure function in `react-ui/src/api/oiFold.ts` with a vitest spec. Derive `spot`, `maxOptionOi` (use `reduce`, not `Math.max(...spread)` — a wide chain overflows the arg limit), `maxFuturesOi`, `maxFuturesOiChange` (scale ΔOI bar against \|ΔOI\|, not OI) the same way.

Port the wire `interface`s verbatim from `oi-analytics.store.ts` (lines 12–215), `fii-dii.store.ts` (lines 8–35), `breadth.store.ts` (lines 8–28) into `react-ui/src/api/types.ts`. Keep the "decimal = string" typing (do **not** trust the generated `.d.ts` `number` typing for BigDecimal — the Angular comment at line 9 calls this out).

---

### 11.3 Charts — ECharts-in-React (+ optional Lightweight Charts)

The five OI charts are all ECharts today; keep them ECharts. **CREATE `react-ui/src/shared/AyEChart.tsx`** porting `echarts-chart.ts` + `echarts-bootstrap.ts`:
- Tree-shaken registration (`echarts/core` + `BarChart, LineChart, ScatterChart, HeatmapChart, CandlestickChart, GridComponent, TooltipComponent, LegendComponent, VisualMapComponent, MarkLineComponent, CanvasRenderer`) — copy `echarts-bootstrap.ts` lines 1–48.
- `useEffect` on mount: `echarts.init(el, undefined, {renderer:'canvas'})`, attach a `ResizeObserver` (wrap `.resize()` in try/catch for headless), and on cleanup `try { chart.dispose() } catch {}` (jsdom has no 2D canvas — guard exactly like Angular lines 84–86 / 50–55).
- `withBase`: `backgroundColor:'transparent'`, `textStyle.color` read from the computed `--ay-text-muted` CSS var (fallback `#93a0bd`), `aria:{enabled:true}`. Re-`setOption(opt, true)` (notMerge) in a `useEffect` keyed on the option. **Transparent bg is mandatory** — the chart sits on the themed shell; a non-transparent bg renders the wrong scheme on theme switch (CLAUDE.md echarts theming note).

Chart options to port verbatim (numbers only at the data boundary via `Number(str)`):
- **Buzz heatmap** (`oi-futures-page.ts` lines 174–206): `type:'heatmap'`, x=`buckets.map(b=>b.slice(11,16))` (HH:mm), y=`contracts`, piecewise `visualMap` with the four **labelled** pieces (`Long buildup #22c55e`, `Short buildup #ef4444`, `Short covering #3b82f6`, `Long unwinding #f59e0b`) — the label is the cue, satisfying the a11y "never colour-only" rule. Encode each non-null cell as `[bucketIdx, contractIdx, order.indexOf(state)]`.
- **OI-trend line** (`oi-big-oi-page.ts` 113–122): single `type:'line'` smooth, `series.data = items.map(p=>p.totalOi)`.
- **Premium-decay line** (`oi-premium-page.ts` 101–117): `data = items.map(p=> p.atmStraddle==null ? null : Number(p.atmStraddle))`.
- **EOD candlestick** (`oi-eod-page.ts` 139–150): front-contract OHLC; candle = `[open,close,low,high].map(v=> v==null?'-':Number(v))`; group rows by `tradingsymbol`, pick the most-captured contract, date-sort.
- **FII/DII net-flow grouped bar** (`fii-dii-page.ts` 187–206): two `bar` series `FII net`/`DII net`, x = unique sorted `tradeDate`, `net(cat)= rows where category.toUpperCase().includes('FII'|'DII')` → `Number(netValue)`.
- **Breadth bar** (`breadth-page.ts` 118–139): three coloured bars Advances/Declines/Unchanged with axis labels (axis carries meaning, colour reinforces).

**Lightweight Charts** is the S10 default for price/candle charts; the EOD candlestick *could* migrate to LWC for parity with the cockpit charts, but ECharts candlestick already works and supports the `'-'` gap-marker for forward-only days — **keep EOD on ECharts** to preserve the gap behavior, and reserve LWC for the cockpit/charts pages (S10). Honor the LWC containment boundary: import `lightweight-charts` only inside the LWC wrapper (ESLint `no-restricted-imports` — a11y reviewer focus #5).

---

### 11.4 OI primitives — 4-state badge + data-bar + decimal

**CREATE `react-ui/src/core/oiInterpretation.ts`** — verbatim port of `oi-interpretation.ts`: the `OiInterpretation` union (`LONG_BUILDUP|SHORT_BUILDUP|SHORT_COVERING|LONG_UNWINDING`) and the `META` record (label + severity + arrow tooltip). The label IS the non-colour cue.

**CREATE `react-ui/src/components/OiIntBadge.tsx`** — port `oi-int-badge.ts`. Use a shadcn/ui `<Badge>` with a Tailwind variant per severity. **The text label (`Long Buildup`, …) must always render** — colour is never the sole signal (a11y #2). The `arrow` ("price up · OI up") rides as a `title`/tooltip. When `value` is null, render `—` (`aria-hidden`) + an `.ay-sr-only` "no interpretation" span (port lines 19–22 exactly). Map severity→token: `success→--ay-bull`, `danger→--ay-bear`, `warn→--ay-warn`, `info→--ay-accent` — and verify each on BOTH `.ay-dark` and `.ay-light` for ≥4.5:1 (a11y #1; the classic dark-green-on-white fail).

**CREATE `react-ui/src/components/DataBar.tsx`** — port `data-bar.ts`. A `<span>` with `position:relative`, a `::before` fill `width = min(100, |value|/max*100)%`, label on top (`position:relative`, `tabular-nums`, right-aligned). Props `value:number, max:number, label:string, tone:'bull'|'bear'|'neutral'` (default neutral). Fill colors via `color-mix(in srgb, var(--ay-{text-muted|bull|bear}) 22%, transparent)`. The bar width is magnitude-only; colour conveys direction — keep the signed `+`/`-` in the label so it isn't colour-only.

**CREATE `react-ui/src/core/decimal.ts`** — port `core/decimal.ts` verbatim (`compareDecimal`, `formatDecimal`, `isNegative`, `subtractDecimal`, `multiplyByInt`, and the `normalize`/`toScaled`/`fromScaled`/`compareMagnitude` helpers). This is pure TS — copy the file and its spec unchanged. (S10 may already create this; if so, reuse — do not duplicate.) Used by `foldStrikes` ITM logic and every `dec()` display helper.

Per-page display helpers to re-create (small pure fns, can live in `react-ui/src/core/format.ts`):
- `dec(v, n)` = `v ? formatDecimal(v,n) : '—'`
- `oi(n)` = `n!=null ? n.toLocaleString('en-IN') : '—'`
- `signedOi(n)` = `'+'+...` for positive, native `-` for negative, `—` for null (direction is not colour-only)
- `pct(v)` / `pctClass(v)` for bank-grid (sign-prefixed, `bull`/`bear` class)
- ITM: `ceItm` = `compareDecimal(strike, spot) < 0`; `peItm` = `> 0` (`oi-options-page.ts` 177–185).

---

### 11.5 OI heatmap via `openalgo-heatmap` core (MIT)

Per decision 7, **IMPORT** the renderer-agnostic zero-dep core from `openalgo-heatmap` (layout + color math, separate from its React component) to drive a **tile heatmap** (tile **size = OI**, color = **OI change**). This is an *enhancement* over the existing ECharts buzz heatmap (which is state-based) — add it as a new visualization on `/oi/options` (or a dedicated tile under Futures OI), fed by the existing `oi-analysis`/`big-oi` data.

- License: MIT — keep the upstream copyright header in the vendored file(s). Vendor **only** the zero-dep core math (the part with no React deps); build the renderer as an own shadcn/Tailwind/SVG component — **do not** import OpenAlgo-core's AGPL frontend (license filter).
- Place under `react-ui/src/vendor/openalgo-heatmap/` (core math) + `react-ui/src/components/OiTileHeatmap.tsx` (own renderer). Feed it `{ label: strike+side, size: oi, delta: oiChange }`, map `delta` sign→`--ay-bull`/`--ay-bear` with magnitude→opacity, and put the numeric OI + signed ΔOI in each tile label (colour-not-only). Add a "View as table" fallback (a11y #4 — every chart keeps an accessible table representation; the strike grid already serves this on `/oi/options`).
- This is the only new visual the React pages add beyond strict parity; if vendoring proves heavy, it is deferrable — the parity bar (10 pages) does not depend on it.

---

### 11.6 a11y parity (ui-a11y-reviewer conventions — `.claude/agents/ui-a11y-reviewer.md`)

Re-create these, which the Angular pages already satisfy:
1. **Contrast ≥4.5:1 on both themes** for every `--ay-*` text/bg pair, especially bull/bear/warn/accent badge backgrounds on white (light) AND dark surfaces.
2. **Never colour-only:** ITM cells carry an `.ay-sr-only "in the money"`; ΔOI carries `+`/`-`; breadth carries `▲`/`▼`; the 4-state badge always shows its text label; bars carry numeric labels.
3. **axe structural:** every page has a `.ay-sr-only` `<h1>` (`Options OI analysis`, etc.); no empty `<th>`/icon-only control without an accessible name; control-bar selects carry `aria-label` (`Underlying`, `Expiry`, `Interval`); the mode toggle carries `aria-pressed`; date inputs have `<label htmlFor>`; tables keep proper `<table>`/`<th scope>` roles (use shadcn `<Table>` or a plain semantic table, **not** a div-grid).
4. **Accessible chart representation:** every chart route keeps its data table beside it (all parity pages already pair a chart with a table — keep that).
5. Live-region: header summary lines that update on selection use `aria-live="polite"` (port the `aria-live` attributes).
6. Reduced-motion: ECharts `smooth` lines are fine; avoid added pulse/flash.

Add an `@axe-core/playwright` (or `vitest-axe`) check per page in the e2e/visual suite (S10 sets up the React e2e harness).

---

### 11.7 Numbered steps (each with a VERIFY)

1. **Port `core/decimal.ts` + `oiInterpretation.ts`.** Copy verbatim with their specs.
   **VERIFY:** `vitest run decimal oiInterpretation` green; `compareDecimal('22500','22480') === 1`, `formatDecimal('1.5000',4)==='1.5000'`.
2. **Build `DataBar`, `OiIntBadge`.** shadcn `<Badge>`/span + Tailwind tokens.
   **VERIFY:** vitest: badge renders the text label for all 4 states + `—`+sr-only on null; DataBar `pct` = `min(100,|v|/max*100)`, `max<=0 → 0`.
3. **Create `useSymbolContext` (Zustand) + `useExpiries` query.** Same localStorage keys; `mode`/`date` transient.
   **VERIFY:** vitest: hydrates from `localStorage`, `setName` clears expiry + persists, `setInterval('60m')` persists; `useExpiries` defaults `expiry` to `list[0]`.
4. **Create `react-ui/src/api/types.ts` + `oiFold.ts`.** Port all wire interfaces + `foldStrikes`.
   **VERIFY:** vitest: `foldStrikes` folds CE+PE into one row per strike, ascending by `compareDecimal`, carries `spot`.
5. **Create `oiAnalytics.ts` query hooks** (all 17) with `oiParams`, `satisfiable→enabled`, 422→empty, `{items}` unwrap, silent-toast tag.
   **VERIFY:** vitest with a mocked fetch / MSW: options hooks send `expiry`; futures hooks omit it; `banks-grid` omits `name`; a 422 resolves to `null`/`[]` and fires no toast; switching `name` mid-flight drops the stale response (assert via query key, not a counter).
6. **Port `AyEChart` + echarts-bootstrap** (transparent bg, headless-safe dispose, ResizeObserver).
   **VERIFY:** renders without throwing in jsdom (no canvas); `withBase` sets `backgroundColor:'transparent'` + `aria.enabled`.
7. **Build `OiControlBar`** (shadcn `Select` ×3 + mode toggle + conditional date input; `showName`/`showExpiry` props; underlyings from `instruments/underlyings`, fallback `['NIFTY 50','NIFTY BANK']`).
   **VERIFY:** vitest: hides name with `showName={false}`, hides expiry with `showExpiry={false}`, toggles `mode`, exposes the date input only in history mode; selects carry `aria-label`.
8. **Build the 8 `/oi/*` pages** per §11.1, wiring the hooks + primitives + charts. Each subscribes to `useSymbolContext`; TanStack Query re-fetches on key change (no manual `effect`/reload).
   **VERIFY:** per-page vitest mirroring `oi-options-page.spec.ts` — flush the endpoints, assert table rows + header text (`PCR 1.5000`, `1 strike`, `Long Buildup`); buzz/trend/premium/eod charts mount when >1 point.
9. **Build `FiiDiiStore`/`BreadthStore` (Zustand) + `/market/fii-dii` + `/market/breadth` pages** (own date pickers; FII/DII default 30d, breadth default today IST via `Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata'})`).
   **VERIFY:** vitest: net-flow bar groups FII vs DII by date; breadth summary shows `▲ advances`/`▼ declines`; 422 → empty-state copy, no toast.
10. **Wire routes + nav** (8 `/oi/*` + 2 `/market/*`, redirect `oi→oi/options`), same labels/order as `app-shell.ts` 125–134, behind the S10 auth guard.
    **VERIFY:** navigating each nav link mounts the page; `tsc --strict` + `eslint` clean.
11. **(Optional) `OiTileHeatmap`** vendoring `openalgo-heatmap` core (MIT header retained) + table fallback.
    **VERIFY:** tile size tracks OI, colour tracks ΔOI sign, label shows numbers; axe finds the table alternative.
12. **a11y + visual parity pass.** Run `vitest-axe`/`@axe-core/playwright` per page; screenshot each React page beside the Angular page (run both stacks; the Angular app is the parity oracle until cutover).
    **VERIFY:** zero axe violations; tables/headers/charts/badges match the Angular pages cell-for-cell on a seeded dataset; bull/bear/warn/accent badges pass 4.5:1 on `.ay-dark` AND `.ay-light`.

---

### 11.8 Gotchas (from CLAUDE.md + the read)

- **`60m` not `1h`** for the hour interval token — the analytics endpoints reject `1h`.
- **Decimals are strings.** Never `parseFloat` for display/compare; `Number()` only at the ECharts boundary. The generated `contracts/gen/*.d.ts` types BigDecimal as `number` — do not trust it for these fields.
- **`{items}` envelope** only on `options/oi-analysis`, `futures/oi-analysis`, `futures/eod`, `fii-dii/{cash,participant-oi,long-short}`; everything else is a bare object. (`instruments/{name}/expiries` and `instruments/underlyings` are bare arrays — CLAUDE.md.)
- **422 DATA_GAP is normal**, not an error — suppress the toast (port the `SILENCE_ERROR_TOAST` mechanism into the S10 API client) and render the page's empty-state copy.
- **`expiry` required** by every options endpoint; **ignored** by futures; **omitted** by `banks-grid` (which also omits `name`). `eod`/`fii-dii`/`breadth` use their own date params, not the interval model.
- **Don't port the 13 generation counters** — TanStack Query keys subsume stale-drop.
- **Transparent ECharts bg + `.ay-dark`/`.ay-light` toggle** (S10) — a non-transparent bg renders the wrong scheme; hard-reload after a rebuild (stale chunk caches the old theme).
- The Angular app stays the **parity oracle** until the React cutover; do not delete `frontend-ui` until §11.7 step 12 passes for all 10 pages.

---


## 12. Track 2 — Siva Options Scalper Implementation (12 sub-strategies)

> **Implementer orientation (read first).** This section turns the Siva 12-sub-strategy options-scalper (source: `C:\Trading\ArthaYantra\StockMarketStrategyTraining\Options_Scalper_Siva_Cheat_Sheet.md` §0–§12 and `_Consolidated_Strategy.md`) into running signal generators. **~80% of the data spine already exists** — the oipulse-parity work in `market-data-service` (per-strike OI, 4-state interpretation, sentiment, spurt, premium, trending, futures OI, IV rank, FII/DII, breadth). The **net-new work** is (a) a confluence scoring layer in `strategy-signal-service` that reads those readers, (b) the §0 universal gate layer, (c) per-strike strike-selection by delta/IV (S6 greeks), (d) SPAN sizing (S8), and (e) options-symbol order placement via OpenAlgoGateway (S3). **This depends on §S5 (OpenAlgo OI/history data landing in `options_chain_snapshots`), §S6 (greeks in `libs/black76-math`), §S7 (ported indicators), §S8 (SPAN sizing appliance), §S3 (OpenAlgoGateway execution).** Build the gate layer + Connect-the-Dots scorer first; the other 11 are specializations of it.

### 12.0 What is already built (verified) vs net-new

The existing engine is a **single-record, byte-parity, YAML-compiled** evaluator. Re-use it; do **not** re-architect it.

**BUILT — strategy engine (`libs/strategy-engine`, the shared JAR embedded in both live + backtest):**
- `config/StrategyDefinition.java` — the compiled view: `indicators[]` (each `IndicatorSpec` has name/alias/timeframe/params/weight/optional/normalize/`instrument` context-override), `gate` (`GateNode` tree), `scoring` (threshold + optionalMinScore + optionalGateMargin), `exitRules[]`, `sizing`, `session` (style/windowFrom/windowTo/squareOff/preCloseAt/fillTiming/exitIntrabar), `direction` (LONG/SHORT/BOTH).
- `eval/EntryEvaluator.java` — **gates first, then A1 composite**; entry fires iff `gate.passed() && composite >= threshold`. Returns the full `ScoreBreakdown` either way.
- `eval/CompositeScorer.java` — the normative A1 weighted composite (`Σ w·s / Σ w`), optional-activation rule, "required indicator warming up ⇒ bar unscoreable (empty), never a silent zero".
- `eval/ExitEvaluator.java` — fixed precedence `stop_loss → trailing_stop → take_profit → time_stop → signal_exit`; bases `premium_pct` / `atr_multiple` / `r_multiple`; `evaluateIntrabarLevels()` for 1m-floor stop checks under a coarser primary; `entryLevels()` (the deterministic, parity-safe entry-time SL/TP computed once at entry).
- `eval/IndicatorBank.java` — alias-addressable indicator instances with multi-timeframe + A7 context-override resolution; `valueAt`/`previousValueAt`/`builtin(close|volume|vwap)`.
- `indicators/IndicatorRegistry.java` — current vocabulary: `EMA, SMA, RSI, VWAP, ADX, MACD_HIST, SUPERTREND, VOLUME_RATIO, OI_CHANGE_PCT, ATR, ORB_HIGH, ORB_LOW, PREV_DAY_HIGH/LOW/CLOSE, DAY_HIGH/LOW, GAP_PCT, RS_VS_INDEX, VIX_LEVEL`. **`SUPERTREND` and `VWAP` already exist; `VWMA`, `PSAR`, and the 80:20-band RSI normalize are NET-NEW (see §S7 / §12.6).**
- `eval/ScoreBreakdown.java` — **FROZEN single-record contract** (the Stage-D byte-parity assertion). New fields ride as a non-serialized side-channel (see §12.9).
- The gate grammar (`libs/strategy-schema/.../strategy-schema-v1.json` `$defs.gateNode`): `all` / `any` / `not` / `crossover{fast,slow}` / `crossunder{fast,slow}` / leaf `gateExpression` — **comparison operators only over aliases + built-ins (`close`,`volume`,`vwap`), NO arithmetic** (closed grammar). The indicator-name enum is **advisory**; existence is a registry check at publish.

**BUILT — live wiring (`services/strategy-signal-service/.../signals/`):**
- `SignalEngine.java` — subscribes `candles.1m.*` for published-strategy universes only; bar-close evaluation on a single-threaded executor; engine pinning `(strategy_id, version, checksum)` on every emit; hot-swap at bar boundary; session-window gating (`withinSessionWindow`); BTST pre-close clock (`@Scheduled cron "0 * 9-15 * * MON-FRI"`); 15:45 expiry sweep; `emitEntry`/`emit` write `signals` rows + publish on Redis `signals` channel.
- **Universe resolver gap:** `resolveUniverse()` handles `explicit` + `futures_of_underlying`; **`options_of_underlying` currently logs "evaluate from Stage F (chain-driven resolution); strategy stays unloaded" and yields `List.of()`. This is the single biggest NET-NEW gate to clear — see §12.4.**
- `EmissionGuard` SPI (entry risk gate + suggested qty), implemented by the paper module; `paper/RiskService.java` (kill switch / `max_open_paper_positions` / `daily_loss_limit`, per-IST-day trip dedup, `risk_audit`).
- `signals` table (`deploy/flyway/strategy/V003__signals.sql`): columns `id, strategy_version_id, exchange, tradingsymbol, interval, signal_type, side, entry_price, stop_loss, target, composite_score, score_breakdown (jsonb), status, generated_at, expires_at, suggested_qty`.

**BUILT — OI/IV/futures/breadth readers (`services/market-data-service`), all reachable over HTTP, all `{items}`-or-record JSON, decimals as strings:**

| Endpoint (`/api/v1/market/...`) | Reader/Service | Feeds Siva concept |
|---|---|---|
| `options/oi-stats` | `OptionsAnalyticsController.oiStats` → `OptionsChainService.pcr` + `MaxPainCalculator.maxPain` | PCR, Max Pain, ΣCE/ΣPE OI |
| `options/active-strikes` | `ActiveStrikeService.activeStrikes` + `sentimentPct` | Active (peak-OI) strikes; **Active-Strike Sentiment %** = `100·(ΣpeΔOI−ΣceΔOI)/Σ(ceOi+peOi)` — **§18.6: VERIFY vs the oipulse-EXACT `(ΣPut OI−ΣCall OI)/ΣPut OI×100` before any scalper gate uses it; different basis = mis-calibrated thresholds** |
| `options/oi-analysis` | `OptionsSnapshotReader.latest` | per-strike CE/PE OI table (Q1–Q4 quadrant inputs) |
| `options/spurt` | `OiSpurtService.spurts` | per-strike + underlying **4-state OiInterpretation** (LB/SC/SB/LU) + spurt% |
| `options/big-oi` | `OiBigOiService.bigOi` | strikes ranked by `|interval ΔOI|` |
| `options/premium` + `options/premium-series` | `OiPremiumService` | ATM **straddle combined premium** + decay series |
| `options/trending` | `OiTrendingService.trending` | per-bucket total/CE/PE OI tagged UP/DOWN/FLAT (Trending-OI lines) |
| `futures/oi-analysis,spurt,movers,banks,banks-grid,buzz,eod` | `FuturesSnapshotReader` + services | **Futures OI quadrant** (LB/SC/SB/LU), movers, term-structure basis |
| IV: `IvAnalyticsController` (`IvAnalyticsService.ivHistory`) | ATM IV nearest expiry, 30d constant-maturity IV, **IV rank/percentile** (suppressed below 60-day floor) | IV-across-strikes + IV-rank gate |
| `fii-dii/cash,participant-oi,long-short` | `FiiDiiController` | **FII L/S ratio**, FII/DII cash |
| `breadth` | `BreadthController` (`BreadthService.breadth`) | **Adv/Dec** (the Adv>32 / Dec>32 gate) |

`OiInterval` supports `1m/3m/5m/15m/30m/1h…` (`time_bucket` query-time downsample, no cagg). The Siva 3m clock and 5/15/30/60/120/240-min Trending-OI lines all map onto `OiInterval`.

**NET-NEW (the work this section creates):**
1. `VWMA`, `PSAR`, `SUPERTREND_LINE` (level not just ±1 direction), and an 80:20-band RSI normalize — ported from S7 into `IndicatorRegistry`.
2. An **OI-confluence input layer** in `strategy-signal-service`: a client + cache that pulls the market-data OI/IV/futures/breadth readers at bar close and exposes them as gate/score operands. The current engine gate grammar reads only chart aliases + `close/volume/vwap`; OI signals cannot be expressed as YAML leaves, so this is a **typed Java scorer that runs alongside the engine** (§12.3), not a new gate operator.
3. The `options_of_underlying` universe resolver + a per-strike strike picker (delta 0.6–0.7 via S6 greeks).
4. SPAN-aware sizing (S8) and OpenAlgoGateway order placement (S3) wired through the existing `EmissionGuard`/paper path.
5. 12 strategy YAML documents (the chart/gate skeleton) + 12 confluence scorers (the OI overlay).

---

### 12.1 §0 shared pre-flight / gate layer (built by ALL 12)

Create a single reusable gate engine in `strategy-signal-service` that every sub-strategy composes. The Siva §0B gates are mostly **OI/macro signals the YAML grammar cannot express**, so implement them as a typed Java layer that the per-strategy scorer (§12.3) consults; the **chart-only** gates (time window, RSI band, indicator alignment, candle/VWAP) stay in the engine YAML where they already work.

**Files to CREATE** (package `in.arthayantra.strategysignal.scalper`):

- `ScalperGateContext.java` — an immutable snapshot assembled once per (instrument, bar) holding everything a strategy needs:
  - chart: `close, vwap, supertrendDir, supertrendLevel, vwma20, psar, rsi14, volume` (from the `IndicatorBank` already built per bar);
  - OI: `OiSpurtService.SpurtSummary underlyingState`, `List<StrikeSpurt>`, `ActiveStrikeService` sentiment %, `OiTrendingService.TrendSeries` (the cross lines), futures `OiInterpretation`, futures basis;
  - macro: `IvHistory` (ATM IV + rank), India VIX level, breadth Adv/Dec, FII L/S ratio.
- `MarketOiClient.java` — a `RestClient` (mirror `signals/MarketDataCandlesClient.java`) calling the `/api/v1/market/options/*`, `/futures/*`, `/fii-dii/*`, `/breadth`, `/iv-*` endpoints. **Cache per (underlying, bucket) with a short TTL** so a 3m bar's 7-strike fan-out is one round-trip set, not 7. Note the CLAUDE.md gotcha: list endpoints return `{items:[...]}`; `oi-stats`/`active-strikes`/`spurt`/`premium`/`trending` return records.
- `ScalperGates.java` — pure functions, one per §0B row, each returns `GateOutcome(boolean pass, BigDecimal operandValue, String reason)` so the reason can ride the side-channel:

| §0 gate | Rule (from cheat sheet §0B) | Source |
|---|---|---|
| **Time window** | bar IST time ≥ 09:45 (ideal 09:15–10:00); block sideways 11:00–13:00; no fresh entry after 15:30 | `SignalEngine.withinSessionWindow` already gates the YAML `window.from/to`; add the 11:00–13:00 block as a strategy `window` exclusion + a `noTradeMidday` flag in the scorer |
| **Volume gate** | bar volume ≥ 50 000 (BANKNIFTY/SENSEX) / ≥ 125 000 (NIFTY) | `IndicatorBank.builtin("volume")`; threshold by underlying |
| **RSI 40–60 no-trade** | CE needs RSI>50 (zone 50–75, <75/80); PE needs RSI<50 (zone 40–25, >25/20); **40–60 = NO TRADE** | NET-NEW `rsi_band` normalize (§12.6); enforce as a gate, not just a score |
| **Indicator alignment** | bull: PSAR, VWMA, ST, VWAP **all below** price; bear: all above | chart operands; expressible as YAML leaves `close > vwap_1m`, `close > vwma20`, `close > psar`, `supertrend_dir == 1` |
| **Strike / delta / premium** | ATM±3; delta **0.6–0.7** (0.7–0.8 near expiry, ~0.5 new weekly); premium N 100–250 / BN 250–400 | strike picker §12.4 + S6 greeks delta + `OptionsSnapshotReader` LTP |
| **OI quadrant** | Futures LB/SC=long, SB/LU=short; strike Call-OI↓ & Put-OI↑=bull; **Q1/Q2 buy gate: price>50% AND OI>50% on the correct quadrant** | `OiSpurtService.SpurtSummary.interpretation`, futures `OiInterpretation`, per-strike `StrikeSpurt` |
| **VIX / breadth / cues** | CE→VIX down, PE→VIX up; Adv>32=CE / Dec>32=PE; VIX regime bands | `IvHistory`/VIX level, `BreadthService` Adv/Dec |
| **Futures basis [S21]** | future > spot = bullish; future < spot (discount) = bearish | `FuturesMoversService.banks` / `FuturesTermStructureService` basis |
| **FII L/S [S24]** | heavy short (~87–94%) = short bias; ratio crossing ~50% = SC-rally trigger | `FiiDiiController /long-short` |

**Numbered steps:**
1. CREATE `MarketOiClient` + a `WebClientConfig`-style bean; point its base URL at `market-data-service` (the same loopback base the existing `MarketDataCandlesClient` uses — VERIFY the property key, e.g. `artha.market-data.base-url`). **Verify:** an IT (`ScalperGateContextIntegrationTest`) seeds `options_chain_snapshots` for one bucket and asserts the client returns a populated `ScalperGateContext`.
2. CREATE `ScalperGates` with one unit-tested pure function per row above. **Verify:** `ScalperGatesTest` covers each gate's pass/fail boundary (esp. RSI 40–60 hard block and the volume thresholds by underlying).
3. CREATE `ScalperGateContext` + its assembler. **Verify:** assembler is deterministic given fixed reader outputs (so replay parity holds).

---

### 12.2 Per-strategy spec table (the 12)

Each strategy = **one YAML document** (chart skeleton, registered indicators, gate tree, exit rules, session) **+ one confluence scorer class** (the OI/macro overlay §12.3). "BUILT" = engine/readers already provide it; "NEW" = §12.6 indicator or §12.4 resolver or this scorer.

| # | Strategy | Trigger (cheat-sheet §) | Chart inputs (engine) | OI/macro inputs (reader) | BUILT vs NEW | Practical simplification |
|---|---|---|---|---|---|---|
| 1 | **Two-Candle** | 2 consecutive same-colour candles, each ≥ vol gate, 2nd not a rejection (wick < 2× body); enter 3rd; SL = 1st-candle low/high | close/vwap/PSAR/VWMA/ST + 2-bar lookback; vol gate | `OiSpurtService` LB/SC (bull) or SB/LU (bear); `OiTrendingService` ≥50% CE-vs-PE diff [S21] | engine candle pattern = **NEW** custom indicator `TWO_CANDLE`; rest BUILT | drop wick<2×body subtlety v1: require both candles green/red + each ≥ vol + 2nd body ≥ 1st body. SL = `prev_day` structure via session indicators |
| 2 | **Open=High / Open=Low** | OH on futures + ≥3 strikes OH; OIP prob ≥90%; window 09:15–10:00; target = OH (exit 2–5pts below) | `ORB_HIGH/LOW`, `DAY_HIGH/LOW`, vwap, vol | `OiSpurtService` per-strike (≥3 strikes show OH/OL); futures OI quadrant; premium not fallen >50% | OH detection = **NEW** scorer logic over `OptionsSnapshotReader` day-open vs running; "≥3 strikes" = NEW; chart BUILT | v1: detect day-open == running-high within ε on the underlying future; require futures LB/SC; skip the ≥90% AI badge (no OIP AI) — use Active-Strike sentiment ≥ threshold instead |
| 3 | **Market Movers** | F&O stock at ≥8-day high/low + OL/OH flag + LB/SB | per-stock daily/5m close, `PREV_DAY_*`, RS; vol | futures-stock OI quadrant; `nse_eod_bhavcopy` for N-day high | **NET-NEW track** — this is equity-futures, not index options; overlaps Track-1 screener. **DEFER** to after the index-option core | v1: a daily screener (reuse the existing `ScreenerService`/bhavcopy + `OiInterpretation`) emitting a watchlist, not a live signal |
| 4 | **Gap Theory** | 3m gap > 3 pts unfilled; wait for fill; trade with trend | `GAP_PCT`, prev candle high/low, ST, vwap, vol | underlying OI quadrant to confirm trend direction | gap-fill state machine = **NEW** scorer; `GAP_PCT` BUILT | v1: detect gap at session open from `GAP_PCT` + first-bar open vs prev close; arm a "fill" trigger; on fill, defer to the §1 two-candle entry in trend direction |
| 5 | **Trending-OI Crossover** | PE line crosses above CE line (bull) / reverse; ≥50% OI diff; sentiment slope; vol | close/vwap (S22: VWAP decisive), vol gate | **`OiTrendingService` is the core feed** — detect CE/PE line cross + gap-widening + sentiment slope; futures OI confirms | crossover detection over `TrendSeries` = **NEW** scorer; reader BUILT | v1: compute CE-OI and PE-OI series from `trending`; signal when sign of (PE−CE) flips and `|PE−CE|/total ≥ 0.5`; require `close` on correct side of VWAP |
| 6 | **Golden Crossover** | ST **and** VWMA cross VWAP together, same bar, with volume; drastic OI both sides | `SUPERTREND_LINE` + `VWMA` both cross `VWAP` (crossover/crossunder gate); vol mandatory | spurt drastic ΔOI both sides | `VWMA` + `SUPERTREND_LINE` = **NEW** indicators (§12.6); gate `crossover{st_line,vwap}` AND `crossover{vwma,vwap}` BUILT once indicators exist | clean: two `crossover` gate leaves on the same bar + volume gate; OI confirmation as an optional score booster |
| 7 | **Hero-Zero (expiry)** | expiry day after ~14:30; buy cheap soon-to-expire option where SC squeeze forms; hard close 15:20 | underlying 3m direction; RSI | `OiSpurtService` SC location, `big-oi` (max-OI S/R), premium ~10–50 strike | expiry-day clock + SC-strike picker = **NEW**; uses `ExpiryClock` (BUILT in market-data) | v1: only the SL/close rule (50% premium or 15:20) + buy the adjacent strike to the SC strike; manual confirm mandatory (lottery odds) |
| 8 | **BTST / STBT** | EOD close-at-high/low + OI quadrant + 15:20 confirm; carry overnight | `DAY_HIGH/LOW` close, RSI not >75 | EOD futures OI + option Trending-OI + FII/DII + breadth | **BTST machinery BUILT** — `SignalEngine.preCloseClock` + `session.style: btst` + `preCloseAt` already fire daily; scorer is NEW | reuse the existing BTST pre-close path; scorer = quadrant (Q1/Q3 BTST, Q2/Q4 STBT) + close-near-extreme + RSI gate |
| 9 | **Morning Trade** | opening-tick rejection wick; prev-EOD view; Adv>32/Dec>32; exit fast | 1m/3m first-bar candle pattern; prev-day VWAP | prev-day 15:20 OI; breadth; FII/DII; VIX | first-candle rejection = **NEW**; prev-day OI read BUILT | v1: a `session.window {from:09:15,to:09:30}` strategy; gate = prior-day quadrant + breadth; entry on first-bar rejection (reuse two-candle logic with n=1) |
| 10 | **Connect-the-Dots** | **the master confluence** — 5 chart dots + macro dots all aligned; 60m bias + 3m entry | all 5 chart dots (VWAP/ST(10,2)/Vol/RSI(80:20)/PSAR) + VWMA | **every reader**: spurt, trending, futures OI, IV(6 strikes), VIX, breadth, premium-vs-VWAP | the aggregate scorer (§12.3) — mostly composition of 1/5/6 | this **IS** the composite scorer; build it first, the others are its sub-cases |
| 11 | **Straddle (long/short)** | combined ATM CE+PE premium vs its own VWAP; long at low IV, short range-bound | combined-premium series vs VWAP | `OiPremiumService.premiumSeries` (ATM straddle), `IvHistory` rank, trending-OI moving together | combined-premium-vs-VWAP = **NEW** scorer over `premium-series`; reader BUILT | v1 long-only (short straddle = unlimited risk, needs SPAN + hedge): signal when combined premium crosses above its VWAP with volume AND IV rank low |
| 12 | **Trend Change** | structure break (HL/HH or trendline) + Trending-OI crossover + volume + 2-candle confirm; 09:45–14:30 | swing-structure break, RSI>60/<40, vwap, vol | `OiTrendingService` crossover (leads price) | structure break = **NEW**; combines §5 cross + §1 two-candle | v1 = §5 crossover gate + §1 two-candle entry, gated to 09:45–14:30, "both OI lines climbing ⇒ skip" |

---

### 12.3 The Connect-the-Dots aggregate (master confluence scorer)

This is §10 and the architectural heart. Because the engine's YAML gate grammar is **chart-only** (no OI operands), implement the confluence as a typed Java scorer that runs **after** the engine's chart-level `EntryEvaluator` passes, and **only emits** when both agree. This keeps the frozen `ScoreBreakdown` byte-identical (the OI score rides the side-channel, §12.9).

**File to CREATE:** `in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer.java`

```
record DotScore(String dot, BigDecimal score, boolean bullish, String reason) {}
record Confluence(BigDecimal aggregate, boolean bullish, boolean bearish,
                  List<DotScore> dots, String bias60m) {}

Confluence score(ScalperGateContext ctx, String underlying, int qty) {
  // 5 chart dots (already in ctx, computed by the engine IndicatorBank):
  //   vwap side, supertrend dir, volume gate, rsi(80:20) band, psar flip
  // macro dots:
  //   oiState (LB/SC vs SB/LU), trendingCross (PE>CE), sentimentPct sign,
  //   futuresOiQuadrant, ivRankBand, breadth Adv/Dec>32, vix direction,
  //   straddle premium-vs-VWAP
  // 60m bias dot: re-read ctx on the 1h IndicatorBank for the day bias.
  // aggregate = weighted mean of dot scores; require bias60m == 3m direction.
}
```

- **60m bias / 3m entry:** the strategy YAML's `primary: 3m`, `additional: [60m]`. The engine already maps a coarser timeframe's *last completed bar* into the primary bar (`IndicatorBank.mappedIndex`) — re-use it for the 60m bias dot; no new clock needed.
- **VWAP is the decisive dot [S22/S24]:** weight VWAP-side highest; treat a VWAP break **with volume** as a hard exit (wire to `signal_exit`), **without volume** as no-trade (gate fail).
- Emission contract: `ConnectTheDotsScorer` is invoked by a thin `ScalperSignalListener` that subscribes to the engine's per-bar evaluation (or, simplest, the scorer is called from a new `ScalperStrategy` SPI the engine consults right before `emitEntry`). **Recommended minimal seam:** add an optional `java.util.Optional<ScalperConfluenceGate>` to `SignalEngine` (mirroring how `EmissionGuard` is injected) — when present and the strategy's `tags` contain `scalper`, the engine calls `confluenceGate.allow(ctx)` after the chart gate passes and before `emitEntry`. This keeps the change surgical and the engine generic.

**Verify:** `ConnectTheDotsScorerTest` — feed a hand-built bullish `ScalperGateContext` (all dots up) and assert aggregate ≥ threshold + bullish; flip one decisive dot (VWAP side) and assert it blocks.

---

### 12.4 `options_of_underlying` resolver + strike selection (delta/IV)

Today `SignalEngine.resolveUniverse()` refuses `options_of_underlying`. This must be implemented or no Siva strategy can go live.

**Files to CREATE / MODIFY:**
- MODIFY `SignalEngine.resolveUniverse()` `case "options_of_underlying"` — resolve to the **concrete option tradingsymbols** for the configured expiry + ATM±width window, using the instrument master already in market-data. Subscribe `candles.1m.NFO.<symbol>` for those strikes (the engine already subscribes per resolved `InstrumentRef`). Re-resolve daily in `morningReload()` (expiry roll, ATM drift) exactly like the futures roll path.
- CREATE `in.arthayantra.strategysignal.scalper.StrikePicker.java`:
  - inputs: underlying spot, expiry, side (CE on bull / PE on bear), `OptionsSnapshotReader` per-strike LTP/IV, S6 greeks delta.
  - rule: pick the strike whose **delta ∈ [0.6, 0.7]** (S21: 0.7–0.8 near expiry, ~0.5 first day of a new weekly — make these `params`), **and** premium in band (N 100–250 / BN 250–400), ATM±3.
  - delta computed via `Black76.greeks(...)` (S6) using forward = spot + basis, IV from `OptionsSnapshotReader.StrikePoint.iv`, T from `ExpiryClock`. **No network hop** (greeks are in-JVM), preserving replay determinism.

**Gotcha (CLAUDE.md):** "`options_of_underlying` universes evaluate from Stage F (chain-driven resolution)". Confirm whether any of that chain-resolution landed; if partial, complete it here. Mark **(VERIFY)** the exact instrument-master query for NFO option symbols (likely via `MarketDataInstrumentClient` / `instruments/search`).

**Verify:** an IT publishes a `options_of_underlying` strategy and asserts `SignalEngine.loadedSlugs()` includes it and the resolved universe is non-empty (today it would be empty + a warn log).

---

### 12.5 Execution flow: signal → strike → SPAN size → pre-fill → human confirm → place

Wire the new path into the **existing** signal→paper→risk machinery; do not invent a parallel order system.

1. **Signal:** `ConnectTheDotsScorer` allows ⇒ `SignalEngine.emitEntry()` writes the `signals` row (BUILT) + publishes on `signals` channel. **(§18.4: also push to the phone — fire an ntfy/telegram notification with the option symbol/side/entry/SL/target/qty + a deep link to the §18.1 order ticket, behind `artha.scalper.notify-on-signal`; live-only, never in replay.)**
2. **Strike pick:** `StrikePicker` (§12.4) chose the concrete CE/PE symbol at universe-resolution time, so the emitted signal's `tradingsymbol` is already the option leg.
3. **SPAN size (S8):** extend `EmissionGuard.suggestedQty(...)` — the paper-module implementation calls the **marginism SPAN appliance (S8)** for the option leg's initial margin, then lot-rounds vs the configured capital + `daily_loss_limit`. Stamp via `SignalRepository.stampSuggestedQty` (already outside the frozen breakdown). **Depends on §S8.**
4. **Pre-fill order:** the UI (React, §10/§12 of the frontend section) reads the ACTIVE signal + `suggested_qty` and renders a pre-filled order ticket.
5. **Human confirm (semi-auto first):** the owner clicks "Take" → existing `POST /signals/{id}/taken` path fires `SignalTaken` → `PaperSignalListener.onSignalTaken` opens a paper position (BUILT). For **live**, this same "Take" routes to OpenAlgoGateway.
6. **Place via OpenAlgoGateway (S3):** add a `LiveOrderService` in the paper/execution module that, when the active profile is live and a config flag `artha.scalper.execution=live` is set, calls `OpenAlgoGateway.placeOrder(symbol, side, qty, productMIS, orderMARKET/LIMIT)` (the OpenAlgo-Java SDK `in.openalgo:openalgo:1.0.1`, §S2/S3). **Semi-auto first:** the gateway call is behind the human "Take" click; full-auto is a later flag. **Depends on §S3.**

**Verify:** mock-stack walk — publish a scalper strategy, force a bullish bar, assert a `signals` row + a paper position open on "Take"; live dry-run asserts the OpenAlgoGateway is invoked with the right option symbol/qty (mock OpenAlgo).

---

### 12.6 NET-NEW indicators (port from S7) + greek operand (S6)

**MODIFY `libs/strategy-engine/.../indicators/IndicatorRegistry.java`** — register (factories in `Ta4jIndicators`/`SessionIndicators`, ported per S7):
- `VWMA` — volume-weighted MA, `params {period}` (Siva VWMA(20)). 
- `PSAR` — parabolic SAR, `params {step:0.02, max:0.2}`; expose as a level + a `+1/-1` flip direction.
- `SUPERTREND_LINE` — the Supertrend **band level** (the existing `SUPERTREND` returns only ±1 direction; Golden Crossover needs the line to cross VWAP). `params {period:10, multiplier:2.0}`.
- A `rsi_band` **normalize type** in `libs/strategy-engine/.../normalize/Normalizers` so RSI maps to the 80:20 / 40–60-no-trade scoring (and a **hard gate** form: `rsi_1m > 50` for CE is already expressible; the 40–60 dead-zone is enforced in `ScalperGates`).

**S6 greeks operand:** the `StrikePicker` and any IV/delta gate call `libs/black76-math` `Black76.greeks(type, f, k, t, r, sigma)` directly (already returns delta/gamma/theta/vega/rho as `BigDecimal`). S6 will add higher-order greeks (vanna/charm/…) into this same lib; the scalper only needs **delta** (strike selection) and **vega/IV** (IV-band gate) for v1.

**Gotcha (CLAUDE.md build):** the strategy-engine lib is embedded in both services' fat JARs — build with the full reactor `-am` (`./mvnw -pl services/strategy-signal-service -am package -DskipTests`), never a bare `-pl` on the lib, or the compose JAR embeds a stale lib. Determinism: any new indicator MUST be pure over the series (no wall-clock, no per-run randomness) — `GoldenDeterminismTest` + `BacktestParityTest` enforce this.

**Verify:** `IndicatorRegistryTest` for each new id (param validation + a golden value); `npx openapi-typescript` regen NOT needed (no new endpoint) — but the advisory enum in `strategy-schema-v1.json` should be extended (a **new suffix-versioned** schema bump if the schema is frozen — VERIFY the freeze state; the doc says it "freezes at the Stage-C exit gate").

---

### 12.7 Risk rails as enforced code

Most rails already exist in `paper/RiskService`; extend, don't duplicate.

| Rail (cheat sheet §0A) | Where enforced | BUILT vs NEW |
|---|---|---|
| **RR 1:2 / hard SL** | `ExitEvaluator` stop_loss + take_profit; engine refuses to emit without a stop when `artha.scalper.require-stop=true` | hard-SL-required check = **NEW** small guard in `emitEntry` |
| **Daily loss cap (0.5% stop-all, ≤2–3% hard)** | `RiskService.DAILY_LOSS` (per-IST-day trip dedup, audited) | BUILT; set the limit row |
| **Kill switch / max-open** | `RiskService.KILL_SWITCH` / `MAX_OPEN` | BUILT |
| **5-account model (1%/acct, first-trade-must-win, stop acct on first loss, max 5 wins/day)** | NEW: a `ScalperAccountModel` — 5 logical paper sub-accounts, each its own `daily_loss_limit`; "first trade loses ⇒ freeze that account for the IST day" | **NET-NEW** (extends `RiskSettingsRepository` with an `account_id` dimension) |
| **No averaging losers / no adds below VWAP** | NEW: reject a `PaperService.openOrder` that would add to a position already below entry (or below VWAP) | **NET-NEW** guard in `PaperService` |
| **Single-day hard cap 10–12% [S24]** | `RiskService` as a second, higher `DAILY_LOSS` tier (hard stop-all) | **NET-NEW** second limit key |

**Verify:** `ScalperRiskIntegrationTest` — first-trade-loss freezes the sub-account; a 2nd ENTRY into a losing position is rejected; daily-loss trip pauses ENTRY emission but **not** EXIT (the `emitEntry`-only gate is already designed this way — `emit()`/exits are deliberately ungated).

---

### 12.8 12 strategy YAML documents (the chart skeletons)

CREATE 12 strategy documents (seed via the registry, e.g. an `R__seed_scalper_strategies.sql` in `deploy/flyway/strategy`, mirroring `R__seed_sample_strategy.sql`). Use `options-scalper.yaml` (the accept fixture at `libs/strategy-schema/src/test/resources/corpus/accept/options-scalper.yaml`) as the template. Each carries: `universe.mode: options_of_underlying`, `timeframes {primary: 3m, additional: [60m]}`, the 5 chart-dot indicators (VWAP/SUPERTREND(10,2)/VWMA(20)/RSI(14, rsi_band)/PSAR(0.02,0.2)), the chart gate tree, `exit_rules` (premium_pct SL/TP for RR 1:2 + time_stop), `session {style, window, square_off}`, and `tags: [scalper, <strategy-name>]` so the engine routes them through the confluence gate (§12.3). The OI/macro logic lives in the per-strategy scorer, not the YAML.

**Verify:** `StrategyCompilerTest`-style assert each doc compiles; `RegistryLifecycleIntegrationTest`-style publish each and assert `SignalEngine` loads it (requires §12.4 resolver done).

---

### 12.9 Golden / parity-safe signal fields (S6/S7 determinism)

The `ScoreBreakdown` record is **FROZEN** (Stage-D byte-parity; `GoldenSignalsJson.write()` is frozen). Therefore:
- The **OI/confluence score, dot breakdown, chosen strike delta, IV-band, SPAN qty** must **NOT** be added to `ScoreBreakdown` — they ride as a **non-serialized side-channel**, exactly like the existing `suggested_qty` (stamped via `SignalRepository.stampSuggestedQty`, outside the breakdown) and `ExitEvaluator.entryLevels` (computed at entry).
- Add new persisted scalper fields as **new columns** on `signals` (or a sibling `scalper_signal_detail` table) populated at emit, never inside `score_breakdown` jsonb. Flyway: a **new suffix-versioned** migration (`deploy/flyway/strategy/V009__scalper_signal_detail.sql`) — applied migrations are checksum-locked, never edit in place.
- **Determinism rule:** every confluence input must be computed from the deterministic replay series (the OI snapshot at that bucket, the in-JVM greeks), never `now()` or per-run randomness. Compute at entry (like `entryLevels`), persist, and the replay recomputes byte-identically. Parity holds **iff** both the live `SignalEngine` and the backtest replay read the same `options_chain_snapshots` buckets — which is why this section **blocks on §S5 OI data landing**.

**Verify:** extend `GoldenDeterminismTest` + `BacktestParityTest` with a scalper fixture: a fixed candle + OI-snapshot window must produce a byte-identical golden signal across two replays.

---

### 12.10 Build order + final verification gates

1. **Indicators (§12.6)** — VWMA/PSAR/SUPERTREND_LINE/rsi_band into the engine lib. *Verify: registry + golden tests, reactor build with `-am`.*
2. **Strike resolver (§12.4)** — `options_of_underlying` + `StrikePicker`. *Verify: a scalper strategy loads with a non-empty universe.*
3. **Gate layer + context (§12.1)** + **Connect-the-Dots scorer (§12.3)**. *Verify: gate + scorer unit/IT.*
4. **Confluence seam in `SignalEngine` (§12.3)** + the 12 YAML docs (§12.8). *Verify: each publishes + loads.*
5. **Risk rails (§12.7)** + execution flow (§12.5, blocks on S3/S8). *Verify: scalper risk IT + mock-stack "Take" walk.*
6. **Parity (§12.9).** *Verify: golden/parity tests green.*
7. **End-to-end:** once **§S5 OI history lands**, **replay one historical 3m window** through the backtest engine for strategy #10 (Connect-the-Dots) and confirm signals reproduce; then **paper-trade live** on the mock/live stack for #1, #5, #6, #10 (the index-option core), deferring #3 (equity-futures, Track-1-adjacent) and gating #11-short / #7 behind SPAN+manual-confirm.

**Cross-section dependencies:** §S2/S3 (OpenAlgo-Java SDK + OpenAlgoGateway — execution + live OI source), §S5 (OpenAlgo/ExpiryTrack OI data into `options_chain_snapshots` — the parity-blocking prerequisite), §S6 (greeks in `libs/black76-math` — delta strike pick), §S7 (ported indicators VWAP/Supertrend/VWMA/RSI/PSAR), §S8 (marginism SPAN sizing), and the React frontend section (the OI cockpit + pre-fill order ticket + Connect-the-Dots dashboard).

---


## 13. Track 1 — Minervini-India Momentum Screener

> **Scope.** A daily, long-only, swing/positional **momentum SCREENER for Indian equities ONLY** (owner-locked: NEVER US). Implements Minervini's 8-gate Trend Template + RS-rank as Java code over the **dense, broad equity EOD history**, producing a ranked watchlist (table + endpoint + UI list). VCP/pivot/Cheat/Power-Play/Primary-base/Stage detection are **DEFERRED** (manual chart-reading is the accepted fallback). Optional fundamentals filter layered after the price gates and clearly skippable. Build this **SECOND** (after Track 2 — the Siva options scalper).
>
> **Depends on:** **S5** (openchart daily backfill — supplies the 200+ days of daily OHLCV the moving averages need); **S14** (backtest hook for historical hit-rate). Touches the same screener machinery as the (already-built) Phase-17 `/api/v1/market/screener`.

### 13.0 Grounding — what already exists (verified)

- **`/api/v1/market/screener`** already exists: `services/market-data-service/.../screener/ScreenerController.java` + `ScreenerService.java`. It runs presets `momentum` / `long_term` / `rs_rank` / `oi_buildup` as **pure SQL over the continuous aggregate `candles_1d`** (and `candles_1h`/`candles_1w`), returning `ScreenerService.Row(exchange, tradingsymbol, latestClose, pastClose, value, avgVolume, distanceFromHigh52w, label)`. Response envelope is `{items, limit, offset}` (matches the frontend `{items}` convention from CLAUDE.md).
- **CRITICAL ARCHITECTURE FACT.** `candles_1d` is a cagg over the **1m `candles` hypertable** (`deploy/flyway/marketdata/V004__candles_continuous_aggregates.sql`), which only holds the **~200 subscribed/pinned instruments** (indices + pinned FUTs + watchlist symbols backfilled by `EodBackfillJob`, `services/market-data-service/.../kite/ticker/EodBackfillJob.java`). It is **sparse on a fresh boot** and **does NOT contain the ~3.2k-symbol equity universe**. The existing `rsRank()` even comments this: *"the interim FP-20 universe: cached ACTIVE EQUITIES only … `index_constituents` arrives in Phase 22"*. **The Minervini screener must NOT reuse `candles_1d`** — it must read from the dense, broad daily equity source.
- **The dense daily equity source = `nse_eod_bhavcopy`** (`deploy/flyway/marketdata/V014__nse_eod_bhavcopy.sql`): hypertable on `trade_date`, PK `(trade_date, symbol, series)`, columns `open_price/high_price/low_price/close_price/last_price/prev_close/avg_price`, `ttl_trd_qnty` (volume), `turnover_lacs`, `deliv_qty`, `deliv_per`, `series` ('EQ'|'BE'|…). ~3.2k rows/day, captured daily by `NseEodScheduler` (`pullBhavcopy()` → `LiveBhavcopyFetcher.fetchLatest()` → `NseEodBhavcopyRepository.upsertAll(...)`). **No retention policy** (≥5y floor). **This is the canonical Minervini input.**
- **S5 (openchart) must backfill INTO `nse_eod_bhavcopy`** (or a sibling table with identical shape), NOT into the 1m `candles`/`candles_1d`. openchart gives free NSE daily OHLCV (no OI, no broker auth) — exactly the 200+ days the 150/200-day MAs need. The historical-import tool (`tools/historical-import/`, see `DATA_SOURCES.md`) already maps "Equity daily" CSVs → `candles` @1d, but that is a separate large drive import; **the screener reads bhavcopy**, so S5's openchart job should `INSERT … ON CONFLICT` into `nse_eod_bhavcopy` with `series='EQ'`, `source` semantics via `fetched_at`. (VERIFY with S5 author which table S5 lands openchart rows in; if S5 lands them in `candles`@1d instead, §13.2 SQL must union both — see note there.)
- **`fundamentals` table already exists** (`V017__fundamentals.sql`): tall `(symbol, statement, period_end, granularity, metric, value, is_percent, source)`. Populated only by the historical-import backfill today; **no Java reader exists** (`find … fundamental*.java` → none). The optional fundamentals filter (§13.6) reads this table.
- **`index_constituents` exists** (`V008__index_constituents.sql`): `(index_name, as_of_date, exchange, tradingsymbol)` — point-in-time membership. Useful (optional) to scope the universe to NIFTY 500 / F&O list when populated.
- **Frontend screener UI** = a tab in `frontend-ui/src/app/pages/watchlists/watchlists-page.ts` (`<p-tabpanel value="screener">`, preset `<p-select>` + Run button + `[scrollable]` `p-table`). The React migration (decision 10) must re-create this; §13.5 specifies the columns.
- **Backtest reads candles read-only** via `services/backtest-service/.../replay/CandleReader.read(exchange, tradingsymbol, interval, from, to)` directly from the `marketdata` schema (D10 single-writer). The S14 hook (§13.8) reuses this read pattern.

### 13.1 Design decision — read from `nse_eod_bhavcopy`, compute SMAs in SQL

The 8 gates are arithmetic over a per-symbol trailing daily series. Two viable approaches; **chosen = SQL window functions over `nse_eod_bhavcopy`** (matches the existing `ScreenerService` "parameterized SQL, NEVER a Kite port" philosophy and the `WatchlistScreenerIntegrationTest` "ZERO gateway-port invocations" guarantee):

- SMA50/150/200 via `avg(close_price) OVER (PARTITION BY symbol ORDER BY trade_date ROWS BETWEEN N-1 PRECEDING AND CURRENT ROW)`.
- 52-week hi/lo via `max/min(...) OVER (… ROWS BETWEEN 251 PRECEDING AND CURRENT ROW)` (252 trading sessions ≈ 1y, matching the existing `high_52w = max(high) FILTER (WHERE rn <= 252)` convention in `ScreenerService.returns`).
- Slope-of-200 ("rising ≥ 1 month") = compare current SMA200 to SMA200 **~21 sessions ago** (`lag(sma200, 21)`).
- RS-rank computed in Java (§13.3) because it needs a cross-universe percentile after the per-symbol returns are known.

A new service class **`TrendTemplateService`** owns this (do NOT bloat `ScreenerService`, whose presets are cagg-based). A new preset string `"minervini"` on the existing controller delegates to it, **or** a dedicated endpoint (§13.5) — both are wired below; prefer the dedicated endpoint so the response shape (12 gate booleans) doesn't pollute the generic `Row`.

### 13.2 The 8-gate Trend Template (exact computation)

Inputs per symbol on a target `trade_date` (default = latest published bhavcopy date), `close = close_price`, series filtered to `('EQ','BE')`:

| Gate | Rule | Computation |
|---|---|---|
| 1 | Price > 150-day MA **and** Price > 200-day MA | `close > sma150 AND close > sma200` |
| 2 | 150-day MA > 200-day MA | `sma150 > sma200` |
| 3 | 200-day MA rising ≥ ~1 month | `sma200 > lag(sma200, 21)` (21 trading sessions) |
| 4 | 50-day MA > 150-day MA **and** > 200-day MA | `sma50 > sma150 AND sma50 > sma200` |
| 5 | Price > 50-day MA | `close > sma50` |
| 6 | Price ≥ 25% above 52-week low | `close >= low_52w * 1.25` |
| 7 | Price within ~25% of 52-week high | `close >= high_52w * 0.75` |
| 8 | RS rank ≥ 70 | computed in §13.3, joined back |

Skeleton SQL (`TrendTemplateService`, JdbcTemplate over the marketdata datasource — same wiring as `ScreenerService(JdbcTemplate jdbc)`):

```sql
WITH base AS (
  SELECT symbol, trade_date, close_price AS close, high_price AS high,
         low_price AS low, ttl_trd_qnty AS volume
  FROM nse_eod_bhavcopy
  WHERE series IN ('EQ','BE')
    AND trade_date <= ?            -- :asOf  (default latest)
    AND trade_date >  ? - 400      -- ~400 cal days = >252 sessions for the 52w windows
),
calc AS (
  SELECT symbol, trade_date, close, volume,
    avg(close) OVER w50  AS sma50,
    avg(close) OVER w150 AS sma150,
    avg(close) OVER w200 AS sma200,
    max(high)  OVER w252 AS high_52w,
    min(low)   OVER w252 AS low_52w,
    avg(volume) OVER w50 AS avg_vol_50,
    lag(avg(close) OVER w200, 21) OVER (PARTITION BY symbol ORDER BY trade_date) AS sma200_1mo_ago,
    count(*)   OVER wall AS sessions,
    row_number() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn
  FROM base
  WINDOW
    w50  AS (PARTITION BY symbol ORDER BY trade_date ROWS BETWEEN 49  PRECEDING AND CURRENT ROW),
    w150 AS (PARTITION BY symbol ORDER BY trade_date ROWS BETWEEN 149 PRECEDING AND CURRENT ROW),
    w200 AS (PARTITION BY symbol ORDER BY trade_date ROWS BETWEEN 199 PRECEDING AND CURRENT ROW),
    w252 AS (PARTITION BY symbol ORDER BY trade_date ROWS BETWEEN 251 PRECEDING AND CURRENT ROW),
    wall AS (PARTITION BY symbol ORDER BY trade_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
)
SELECT * FROM calc
WHERE rn = 1               -- the as-of row per symbol
  AND sessions >= 200      -- need >=200 sessions or SMA200 is undefined -> exclude
```

In Java, map each row to a `TrendCandidate` record carrying the raw numbers + the 8 booleans + `gatesPassed` (count) + `passesAll`. Gate 8 (RS≥70) is filled after §13.3. **Exclude** symbols with `sessions < 200` (insufficient history — they cannot pass and would dirty the output); surface a `coverage` count in the response so the owner knows how many were skipped.

**Liquidity / universe pre-filter** (cheap, before the heavy window scan if perf demands): require `avg_vol_50 >= :minAvgVolume` (default e.g. 50_000) and `close >= :minPrice` (default e.g. 20) to drop illiquid penny rows. Optionally restrict `symbol IN (SELECT tradingsymbol FROM index_constituents WHERE index_name = 'NIFTY 500' AND as_of_date = (SELECT max(as_of_date) …))` when that table is populated (§13.0); default = full bhavcopy EQ universe.

### 13.3 RS Rank (percentile of trailing relative strength vs NIFTY)

Follow the existing `ScreenerService.rsRank` semantics but compute on the bhavcopy universe (not the cagg). **Definition (lock these):**

- **Relative strength** per symbol = its trailing total return. Use a **weighted IBD-style RS** to favour recent momentum (Minervini uses IBD RS): `rs = 0.4*r63 + 0.2*r126 + 0.2*r189 + 0.2*r252` where `rN = close_today / close_{N sessions ago} - 1`. (63 ≈ 1 quarter; the existing `rs_rank` preset already defaults `lookback=63` — reuse that as the dominant term.) If a symbol lacks 252 sessions, fall back to the longest available window present (but it's already excluded by the `sessions >= 200` gate, so 252 is safe in practice — VERIFY: 200 sessions < 252, so cap `r252` at `min(251, sessions-1)` or simply drop the `r252` term when `sessions < 252`).
- **RS rank** = percentile of `rs` across the **EQ universe** (the same filtered set), expressed 0–100. Computed in Java exactly like the existing `rsRank`: sort ascending, `percentile_i = i/(n-1)`, then `*100`. (The existing one ranks vs benchmark by subtracting NIFTY return; here we rank the raw weighted RS percentile across stocks, which is the IBD definition. Keep NIFTY out of the ranked set.)
- **Gate 8** = `rsRank >= 70`.

Implementation note: compute `rs` in the same SQL CTE (add `lag(close, 63)`, `lag(close, 126)`, etc. as `close_63ago` …), return raw `rs` per candidate, then percentile-rank in Java over the returned list. This keeps one DB round-trip.

### 13.4 Volume vs 50-day average

Already available as `avg_vol_50` (§13.2). Add a derived field `volRatio = volume / avg_vol_50` to the output (today's volume vs the 50-day average) and an optional filter `minVolRatio` (default null). This is a **display/optional-filter** field, not one of the 8 gates (Minervini's template proper is price-based; volume confirmation is a refinement).

### 13.5 Daily scheduled job + persisted results table + endpoint + UI

**Flyway (marketdata lineage; next free version = `V018`).** Use the `new-migration` skill. Sketch — `deploy/flyway/marketdata/V018__minervini_screen_results.sql`:

```sql
-- Track 1 (Minervini-India): persisted daily Trend-Template screen output.
-- One row per (screen_date, symbol). Plain table (~hundreds of passing rows/day),
-- append/replace per run. Lives in marketdata so backtest inherits the CD-1 SELECT grant.
CREATE TABLE minervini_screen_results (
  screen_date   DATE          NOT NULL,
  symbol        TEXT          NOT NULL,
  close_price   NUMERIC(18,4) NOT NULL,
  sma50         NUMERIC(18,4),
  sma150        NUMERIC(18,4),
  sma200        NUMERIC(18,4),
  high_52w      NUMERIC(18,4),
  low_52w       NUMERIC(18,4),
  pct_from_high NUMERIC(8,4),   -- (close-high_52w)/high_52w
  pct_above_low NUMERIC(8,4),   -- (close-low_52w)/low_52w
  rs_rank       NUMERIC(6,2),   -- 0..100 percentile
  vol_ratio     NUMERIC(10,4),  -- volume / avg_vol_50
  gate1 BOOLEAN NOT NULL, gate2 BOOLEAN NOT NULL, gate3 BOOLEAN NOT NULL,
  gate4 BOOLEAN NOT NULL, gate5 BOOLEAN NOT NULL, gate6 BOOLEAN NOT NULL,
  gate7 BOOLEAN NOT NULL, gate8 BOOLEAN NOT NULL,
  gates_passed  SMALLINT      NOT NULL,
  passes_all    BOOLEAN       NOT NULL,
  computed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
  PRIMARY KEY (screen_date, symbol)
);
CREATE INDEX idx_minervini_passes ON minervini_screen_results (screen_date, passes_all, rs_rank DESC);
```

(Plain table, not a hypertable — small, queried by `screen_date`. Applied migrations are checksum-locked — corrections go in `V018_1__…`, never edit in place.)

**Files to CREATE (market-data-service, package `in.arthayantra.marketdata.screener.minervini`):**

1. `TrendTemplate.java` — record(s): `TrendCandidate(symbol, close, sma50, sma150, sma200, high52w, low52w, rs, rsRank, volRatio, boolean[] gates, int gatesPassed, boolean passesAll)`.
2. `TrendTemplateService.java` — the §13.2/§13.3 SQL + Java percentile + gate evaluation. Method `List<TrendCandidate> screen(LocalDate asOf, Filters f)`; `Filters(minPrice, minAvgVolume, minVolRatio, minGatesPassed, index)`.
3. `MinerviniScreenRepository.java` — `upsertAll(LocalDate screenDate, List<TrendCandidate>)` (batch `INSERT … ON CONFLICT (screen_date, symbol) DO UPDATE`, mirroring `NseEodBhavcopyRepository`); `List<TrendCandidate> latest(LocalDate screenDate, boolean passesAllOnly, int limit)`.
4. `MinerviniScheduler.java` — `@Scheduled(cron="${artha.minervini.cron:0 30 19 * * MON-FRI}", zone="Asia/Kolkata")` **after** `NseEodScheduler`'s 19:00 bhavcopy pull (so the day's bhavcopy is present); also `@EventListener(ApplicationReadyEvent.class)` one-shot on boot (mirror `NseEodScheduler.onStartup`). Each run: resolve latest bhavcopy `trade_date`, `screen(...)`, `upsertAll(...)`, log count. Wrap in try/catch (never fatal — same pattern as `NseEodScheduler.pullBhavcopy`).
5. `MinerviniController.java` — `@RequestMapping("/api/v1/market/screener/minervini")`:
   - `GET` (params: `asOf` optional, `passesAllOnly` default true, `minRsRank` default 70, `minGatesPassed` optional, `limit` default 50, `offset` default 0) → reads the **persisted** `minervini_screen_results` (fast path; the watchlist consumer). Returns `{items:[…], screenDate, coverage, limit, offset}` (envelope per CLAUDE.md `{items}` convention).
   - `POST /run` (optional `asOf`) → triggers an on-demand `screen + upsert` and returns the fresh result (for "recompute now" + the §13.8 backtest hook). Money/price fields cross the wire as **JSON strings** (Jackson `BigDecimal` → string; frontend uses `core/decimal`).

**Files to MODIFY:**
- `contracts/` — the springdoc snapshot drifts (new `@GetMapping`/`@PostMapping` paths + new query params DO drift the spec per CLAUDE.md). Re-capture: `ContractCaptureTest` with `-Dcontracts.capture=true`, regen TS via `npx openapi-typescript@7` → `contracts/gen/*.d.ts`, ensure `tsc --strict` passes.
- **CI shard** — market-data already has a CI shard (`.github/workflows/ci-java.yml` `market-data` leg), so new ITs run; no new shard needed (the gotcha applies only to *new services*).

**Frontend (REACT — decision 10).** Re-create as a dedicated **"Momentum (Minervini)"** screener view (the Angular `watchlists-page.ts` screener tab is the reference). Component (React 19 + Tailwind + shadcn/ui `Table`):
- Controls: `asOf` date picker (default latest), `passesAllOnly` toggle, `minRsRank` slider (default 70), Run button → `POST /run` then refresh, or just `GET` the persisted list.
- Columns: Symbol, Close, %fromHigh, %aboveLow, SMA50/150/200 (or a compact "above MA" badge set), RS rank, vol×, **gates 1–8 as 8 check/✗ chips** + `gatesPassed` (e.g. "8/8"), sortable by RS rank desc. Decimal strings rendered via the React `core/decimal` helper (mirror of Angular `formatDecimal`).
- Row → add to a watchlist (reuse `/api/v1/watchlists/{id}/items`) so a candidate flows straight into the tracked list; the owner then reads the chart manually for entry (§13.7).

### 13.6 OPTIONAL — fundamentals filter (S9 / openscreener), layered AFTER price gates

Clearly **skippable** (owner: nice-to-have, not blocking). Two data paths, both feeding the existing `fundamentals` tall table (`V017`):
- **Already-imported backfill** (`source='BACKFILL'`) — historical Screener.in export already loaded by `tools/historical-import`.
- **S9 openscreener appliance** — Playwright scraper of Screener.in, run STANDALONE behind a process boundary (MIT, but it scrapes — keep it an appliance; consume its OUTPUT). It writes/updates `fundamentals` rows (`source='OPENSCREENER'`) via the market-data write path or a one-shot loader. **License/contained:** openscreener is MIT (port/import OK with notice) but as a Playwright scraper it's cleaner as an appliance — its output is data, not encumbered.

**Layering.** Add an OPTIONAL post-gate filter in `TrendTemplateService` (flag `artha.minervini.fundamentals.enabled` default **false**): after the 8 price gates pass, join `minervini_screen_results.symbol → fundamentals` and require coarse Minervini "Code 33"-style fundamentals — e.g. latest-vs-prior **EPS growth > 0** and **Sales growth > 0** (read `metric IN ('EPS','Sales')` for the two most recent `period_end` rows of `statement='quarterly_results'`). Surface as extra boolean columns (`epsAccel`, `salesAccel`) rather than hard-excluding, so the owner can see them without losing candidates. **Create** `FundamentalsReader.java` (the missing reader noted in §13.0) only if/when this is enabled. Mark the whole subsection DEFERRABLE.

### 13.7 DEFERRED / FUTURE — VCP, pivot, Cheat, Power-Play, Primary-base, Stage detection

**All DEFERRED. Accepted fallback = manual chart-reading** (owner explicitly accepts; the screener hands candidates, the trader picks the entry on the chart). No repo does these (custom build). What each *would* need, for the future-implementer:

- **Stage analysis (Weinstein/Minervini Stage 1–4):** classify each candidate using SMA30W (≈150-day) slope + price-vs-MA + volume regime. Stage 2 ≈ price above a rising 30-week MA after a Stage-1 base. Largely **falls out of the 8-gate template** (a passing candidate is effectively Stage-2) — could be a cheap derived label now if wanted, but mark optional.
- **VCP (Volatility Contraction Pattern):** detect successive shrinking pullbacks (each contraction smaller than the last) + declining volume into the pivot. Needs swing-high/low detection over daily bars (zig-zag) + per-contraction depth & volume measurement. No off-the-shelf; custom.
- **Pivot / buy-point (Low-of-Low-Range, LoLR):** the tight price/volume cluster at the apex of the final contraction. Needs the VCP output.
- **Cheat / low-cheat, Power-Play (high-tight-flag), Primary-base:** named base patterns — each a bespoke geometric detector over daily bars.

These need only **daily bars** (already in `nse_eod_bhavcopy`/`candles_1d`), no new data — purely algorithmic. Park them; do not block the screener on them.

### 13.8 Backtest hook (S14) — historical hit-rate of the screen

The screen is **already point-in-time-capable**: `TrendTemplateService.screen(LocalDate asOf, …)` computes off `nse_eod_bhavcopy` as-of any date with ≥200 prior sessions (the bhavcopy keeps full history, no retention). The S14 backtest hook is therefore a **forward-return evaluation harness**, NOT a re-run of the live deterministic engine:

1. For each `asOf` over a historical date range (e.g. weekly Mondays across 2 years), call `screen(asOf, …)` → the set of `passesAll` symbols.
2. For each picked symbol, compute forward return at +5/+10/+21/+63 sessions from `nse_eod_bhavcopy` (`close_{asOf+N} / close_{asOf} - 1`), survivorship-aware (a symbol that vanishes is a loss/NA — note the **survivorship-bias caveat** that `index_constituents` carries pre-accrual; bhavcopy itself has no membership history).
3. Hit-rate = fraction with positive forward return at horizon N; compare vs the NIFTY 50 forward return over the same window (excess return). Output a summary table (hit-rate, avg excess return, count) per horizon.

**Where it lives:** because this reads bhavcopy directly (not the 1m candle replay), it belongs in market-data-service as a **`MinerviniBacktestService`** (read-only SQL) exposed at `POST /api/v1/market/screener/minervini/backtest` (params: `from`, `to`, `step` weekly, `horizons`), rather than the `backtest-service` engine path (which is keyed to `resultRef`/1m replay via `CandleReader` and is overkill here). **Coordinate with S14**: S14 owns the general backtest surface — if S14 prefers this evaluation routed through backtest-service, expose `TrendTemplateService.screen` results over REST and let S14 consume them; otherwise the self-contained market-data endpoint above is simplest. Mark this coordination point explicitly.

### 13.9 Numbered steps + VERIFY

1. **Confirm S5 landing table.** Verify (with the S5 author) that openchart daily backfill lands in `nse_eod_bhavcopy` (`series='EQ'`). **VERIFY:** `SELECT count(DISTINCT symbol), min(trade_date), max(trade_date) FROM nse_eod_bhavcopy` shows ≥~1.5k symbols and ≥200 trading days of history. If S5 lands openchart in `candles`@1d instead, change §13.2 `FROM` to `UNION` bhavcopy + `candles_1d` (and mark the divergence per the CLAUDE.md "Candle sources split by interval" note).
2. **Flyway V018.** Add `V018__minervini_screen_results.sql` via the `new-migration` skill. **VERIFY:** `ay reset-db` (or flyway-init) applies cleanly; `\d minervini_screen_results` shows the table; `flyway validate` green.
3. **`TrendTemplateService` + records.** Implement §13.2/§13.3/§13.4. **VERIFY:** a unit/IT seeding ~210 daily bhavcopy rows for 3 synthetic symbols asserts the 8 gates + RS percentile against hand-computed values (mirror `WatchlistScreenerIntegrationTest`'s "hand-computed fixture" + "ZERO gateway-port invocations" style; `*IntegrationTest`/`*Test` name only — no failsafe).
4. **Repository + scheduler.** `MinerviniScreenRepository.upsertAll/latest`; `MinerviniScheduler` (boot one-shot + 19:30 IST cron, after bhavcopy). **VERIFY:** boot the mock stack (`ay`), confirm log `minervini screen upserted N rows`; `SELECT count(*) FILTER (WHERE passes_all) FROM minervini_screen_results` > 0 on a date with backfilled history.
5. **Controller + envelope.** `GET` (persisted) + `POST /run`; `{items, screenDate, coverage}`. **VERIFY (PowerShell):** `Invoke-WebRequest -UseBasicParsing` POST `/api/v1/auth/login`, seed XSRF, then `GET /api/v1/market/screener/minervini?minRsRank=70&limit=20` returns ranked items with 8 gate booleans, decimals as JSON strings.
6. **Contract re-capture.** Run `ContractCaptureTest -Dcontracts.capture=true`; regen `contracts/gen/*.d.ts` (`npx openapi-typescript@7`); `tsc --strict`. **VERIFY:** ci-contracts diff is non-breaking (new path + params warn/gen-drift, not BREAKING).
7. **React view.** Build the "Momentum (Minervini)" screener page (§13.5) in the React rewrite (decision 10); decimals via `core/decimal`. **VERIFY:** run the screen on a recent date in the UI; **sanity-check 3–5 top names against TradingView/lightweight-charts** — each should visibly be above rising 50/150/200-day MAs and near its 52-week high (the manual-chart-reading fallback doubles as the acceptance check).
8. **(Optional) fundamentals filter** (§13.6) behind `artha.minervini.fundamentals.enabled=false`. **VERIFY:** with it off, output unchanged; with it on against backfilled `fundamentals`, `epsAccel`/`salesAccel` columns populate for symbols present in the table.
9. **Backtest hook (S14).** Implement §13.8 (coordinate routing with S14). **VERIFY:** run over a 1–2y range and print hit-rate + excess-return-vs-NIFTY per horizon; sanity-check the hit-rate is plausibly > 50% in a trending period and the count of picks per date is non-trivial.

### 13.10 Gotchas (from CLAUDE.md + repo)

- **Do NOT screen off `candles_1d`** — it's sparse (only subscribed/pinned instruments). Use `nse_eod_bhavcopy` (the whole point of §13.0/§13.1).
- **Series filter:** include `'EQ'` and `'BE'` only; exclude `'GS'`/SME/odd series and any `deliv_qty IS NULL` non-deliverable artifacts where it matters for liquidity.
- **`sessions >= 200` guard** — SMA200 is undefined otherwise; newly-listed stocks must be excluded, not silently zero-MA'd.
- **Applied migrations checksum-locked** — V018 corrections = V018_1, never edit in place.
- **Build with `-am`:** `./mvnw -pl services/market-data-service -am package -DskipTests` (never bare `-pl`).
- **ITs share the singleton DB, no per-method cleanup** — seed unique symbols/dates per test method; clean your own bhavcopy rows in `@BeforeEach` (the existing test does `candles.purgeSymbol` + `DELETE FROM watchlists`).
- **springdoc:** generic `Map<String,Object>` returns don't drift the spec, but the **new paths + new query params DO** — re-capture (step 6).
- **Decimals cross the wire as JSON strings** — frontend `core/decimal`; never parse as JS number.
- **Survivorship bias** — bhavcopy has no membership history; the backtest hook (§13.8) and any `index_constituents` scoping carry the documented pre-accrual caveat (`V008`).

---

WARMUP_SESSIONS = max(200, 252+20) = 272. That confirms the CLAUDE.md "272 daily benchmark sessions" reference. I now have everything needed. Writing the section.

## 14. Backtest & Forward-Test Strategy

This section plans backtesting and forward-testing for BOTH owner tracks against the EXISTING `services/backtest-service` engine. The engine, its parity machinery, the data-warm/pre-flight path, the options-premium fidelity contract, and the cost model are all already built — the work here is (i) feeding the two new strategy tracks into them, (ii) wiring ExpiryTrack-backfilled intraday OI into the replay path, (iii) hardening the cost/slippage model for low-fidelity scalp backtests, and (iv) preserving golden-vector parity throughout. **Depends on:** S5 (ExpiryTrack intraday-OI backfill → Parquet → TimescaleDB), S6 (opengreeks → `libs/black76-math` port — already used by `SyntheticPremium`), S7 (pyindicators port — VWAP/Supertrend/VWMA/RSI/PSAR into the engine), S12 (oipulse OI-interpretation/Trending-OI signals), S13 (Minervini daily screener).

### 14.1 What exists today (verified, ground for everything below)

The replay engine is `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/ReplayEngine.java`. Key facts established by reading it and its collaborators:

- **Signal generation is byte-identical to live by construction.** `ReplayEngine.replay(...)` runs signals through `new TickwiseGoldenRunner(definition, exchange, tradingsymbol).run(primaryOneMinute, contextCandles, signalProgress)` (lib `in.arthayantra.strategyengine.golden.TickwiseGoldenRunner`) — the SAME tick-wise model the golden vectors pin. The replay then pairs entry/EXIT `SignalEvent`s into directed legs (`ReplayEngine.legs(...)`), applies the shared `FillSimulator` (`libs/strategy-engine/.../fills/LtpSlippageV1.java`, id `ltp_slippage/v1`), marks the open position to market each 1m bar for the equity curve, and emits `Trade` records.
- **Fill/cost model is FULL and shared with the paper ledger.** `LtpSlippageV1` prices `fill = referencePrice ± slippage`, then applies brokerage + the statutory schedule (STT, exchange txn, GST, stamp, SEBI) side-aware per `InstrumentClass` (EQUITY/FUTURE/OPTION) via `FeeConstants`. Per-class slippage fallback: equities 5 bps, futures 1 tick, options `max(1 tick, half quoted spread)` → degrades to 1 tick when no spread. Config carrier is `services/backtest-service/.../replay/CostConfig.java`; **its `defaults()` is `InstrumentClass.EQUITY`, tickSize 0.05, lotSize 1, `Slippage.NONE`, brokerage `0.03%`/side, `Fees.DEFAULTS`** — a plain equity proxy. `ReplayEngine` and `BacktestRunner` currently call `CostConfig.defaults()` UNCONDITIONALLY (see `BacktestRunner.run` line ~152) — **a known gap for options/futures scalp realism (14.3).**
- **Trade record:** `services/backtest-service/.../replay/Trade.java` — `(seq, side, qty, entryTs, entryPrice, exitTs, exitPrice, pnl, pnlPct, exitReason, barsHeld, touchBasis, contributions, exchange, tradingsymbol, stopLoss, takeProfit)`. Persisted to `backtest.backtest_trades` (`deploy/flyway/backtest/V003__runs_trades.sql` + `V005__trade_symbol_levels.sql`).
- **Runs/results/folds/montecarlo are keyed by the RUN id (`resultRef`), not the jobId.** `BacktestRunner.run` calls `runs.insert(...)` → returns `UUID runId`, then `trades.insertAll(runId, ...)`. Row lives in `backtest.backtest_runs` (`V003`). `premium_source` is a `TEXT CHECK IN ('SNAPSHOT','SYNTHETIC_B76','NA')` column written on EVERY run; fold columns (`sharpe_degradation`, `fold_metrics`, `oos_fold_mean`, `oos_fold_std`) are NULL-able and written only on the fold path (`request.foldContext == true`). A plain `/backtests/run` job is full-window (fold columns NULL).
- **Premium fidelity contract (§D.15) already implemented:** `replay/options/SnapshotPremiumReader.java` reads `marketdata.options_chain_snapshots(ts,underlying,expiry,strike,option_type,ltp,oi,...)` at NATIVE 5-min granularity, with an archive-coverage pre-flight that 422s `DATA_GAP` when present slot count < calendar-expected (75 slots/session). It REFUSES sub-5-min primary timeframes (`refuseSubFiveMinute` → 400 `VALIDATION_INTERVAL_UNSUPPORTED`). `replay/options/SyntheticPremium.java` is the labelled approximate fallback: reconstructs premiums from underlying 1m closes via `Black76.price` (the S6 lib), tagging `Caveat.FLAT_IV` or `Caveat.NEAREST_SNAPSHOT_IV`. `PremiumSource.likeForLike(...)` gates cross-run comparison.

### 14.2 (a) OPTIONS SCALP backtest — wiring S5/S6/S7/S12 into the replay

The Track-2 scalp signals (S12: `OiInterpretation` 4-state, Trending-OI 5/15/30/60/120/240-min, sentiment slope, OI quadrants LB/SC/SB/LU; S7 indicators: VWAP, Supertrend(10,2), VWMA(20), RSI14, PSAR) replay against the captured/backfilled chain. The crux that S5 (ExpiryTrack) solves is **historical intraday OI** — without it, scalp backtests can only run over the live-forward archive (`options_chain_snapshots` accrues from Stage B onward; per CLAUDE.md the forward-capture went live 2026-06-15).

**Data spine:** S5 exports 1-min OHLCV+OI Parquet for expired F&O. The ingest target is `marketdata.options_chain_snapshots` (5-min native cadence) for premium replay AND the per-strike OI series that S12's `OiInterpretation`/`OiSpurtService`/Trending-OI read at query time via `time_bucket + last()`. **Decision the implementer must make (mark VERIFY against S5's actual export grain):** ExpiryTrack stores 1-MINUTE OI, but `SnapshotPremiumReader.NATIVE_GRANULARITY` is hard-pinned to 5 minutes and `checkArchiveCoverage` counts only slot-aligned (`epoch % 300 == 0`) rows. Two options:
  1. **Downsample ExpiryTrack 1m → 5m on ingest** (keep every 5-min slot start), so `SnapshotPremiumReader` and the existing coverage gate work unchanged. Simplest; loses sub-5-min OI detail.
  2. **Add a 1-min snapshot grade.** Generalize `NATIVE_GRANULARITY` to a per-source enum and add a `SLOT_SECONDS` of 60. This is a larger change to a frozen contract — defer unless scalp signals demonstrably need 1-min OI in replay.

**Scalp signals as the engine's signal source (S12).** S12's OI-interpretation/Trending-OI computations live in `strategy-signal-service`/the read layer (`OptionsSnapshotReader`, `FuturesSnapshotReader`). The backtest engine evaluates `StrategyDefinition` indicators via `TickwiseGoldenRunner` over `EngineCandle` primary + `contextCandles` keyed by `SeriesKey(exchange, tradingsymbol, timeframe)` (see `BacktestRunner.contextSeries`). The path of LEAST resistance — and the one that preserves parity — is to make the scalp OI features **engine indicators that read context series**, exactly as the existing VIX context series does in `BacktestParityTest` (`new SeriesKey("NSE","INDIA VIX","1m")`). That requires the OI/Trending-OI primitives (S7/S12) to be implemented INSIDE `libs/strategy-engine` so they run in the deterministic replay path with no network hop. **(VERIFY against S12's section — if S12 keeps OI logic only in strategy-signal-service, those signals can't replay deterministically and S12 must expose a port the engine can call at compute-at-entry time.)**

**LOW-FIDELITY warning is first-class here.** For intraday index-option scalps, realized P&L is dominated by spread, slippage, fills, and STT — NOT by signal edge. The replay's `LtpSlippageV1` already models all of this, but `CostConfig.defaults()` is an equity proxy. **The plan must (14.3) configure OPTION-class costs and treat scalp backtest numbers as a coarse filter, trusting forward/paper (which shares the IDENTICAL `LtpSlippageV1` per the FillSimulator Javadoc — "a second fill implementation anywhere outside this JAR is the explicit FAIL condition") over backtest.**

### 14.3 (a-cost) Realistic cost/slippage modeling — port the vectorbt-backtesting-skills ideas

`CostConfig.defaults()` is hardcoded into both `BacktestRunner.run` and `ReplayEngine.replay`'s parity-test caller. Today there is no path to set OPTION/FUTURE costs from a run request. Plan:

1. **Resolve `CostConfig` from the strategy/request, not a constant.** `BacktestRunRequest` already carries an optional `costs` JSONB (`JobsService.submit` copies `req.costs()` into the request). Add a `CostConfigResolver` (`services/backtest-service/.../replay/CostConfigResolver.java`) that maps the request `costs` block + the strategy's instrument class → a populated `CostConfig` (OPTION class, real `tickSize`/`lotSize`, explicit `Slippage.ticks`/`.bps`, `Brokerage(perLotInr, null)` for options). `BacktestRunner.run` passes the resolved config in place of `CostConfig.defaults()` at line ~152. **Surgical change** — `FillSimulator`/`LtpSlippageV1` already support every leg; nothing in the engine changes.
2. **Port the realistic-cost templates from vectorbt-backtesting-skills (MIT — keep copyright).** These are MODELING IDEAS, not code to import: (i) bps-of-notional slippage that scales with volatility/spread; (ii) fixed + variable cost legs; (iii) fill-at-next-bar-open vs at-close timing. Map them onto the existing knobs: slippage→`Slippage.bps`/`.ticks`, timing→`FillTiming` (already selected by `ReferencePriceSelector.defaultFor(style, fillTiming)` in `ReplayEngine`). For options scalps, set per-class option slippage to `max(1 tick, half quoted spread)` using REAL captured spreads — extend `FillRequest.quotedSpread` to be populated from the `options_chain_snapshots.bid/ask` columns when premium replay is snapshot-grade (currently `ReplayEngine.fill(...)` passes `quotedSpread = null`). **(VERIFY: premium-as-primary swap path — `SyntheticPremium.Result` Javadoc notes the wiring is not yet live; spread-aware fills land with that swap.)**
3. **Surface a fidelity caveat on every scalp run.** Append to `metrics.full().putArray("caveats")` (the carrier `RunRepository.findResult` reads, per `SyntheticPremium.Result` Javadoc) a line like "Intraday options scalp backtest: P&L dominated by spread/slippage/STT; treat as a coarse filter, trust forward/paper." This rides the existing caveats array — no schema change.

**Verify (14.2/14.3):** submit a windowed scalp backtest over a window the S5 backfill covers; assert the run row in `backtest.backtest_runs` has `premium_source='SNAPSHOT'` (or `SYNTHETIC_B76` with the FLAT_IV/NEAREST_SNAPSHOT_IV caveat), `trade_count > 0`, and `metrics->'caveats'` includes the scalp-fidelity line; assert the option cost legs are non-zero in `backtest_trades`.

### 14.4 (b) EQUITY SWING backtest — Minervini (S13) on daily data

The Minervini track is long-only Indian equity swing. The practical core (S13) is a daily SCREENER (8-gate Trend Template + RS rank + volume-vs-50d-avg) — NOT a fully automated entry strategy (VCP/pivot/Cheat are deferred; owner accepts manual chart-reading of entries). For backtesting:

1. **Daily-primary replay.** The engine's `CandleReader.read(exch, sym, "1d", from, to)` serves the `candles_1d` cagg; for the 150/200-day MAs and 52-week range the engine indicators need a long warm-up. The existing `readDailyWithWarmup(exch, sym, from, to, warmupSessions)` reads `WARMUP_SESSIONS` (=272, verified: `max(TREND_SMA=200, VOL_MEDIAN_WINDOW=252 + VOL_WINDOW=20)`) native daily buckets BEFORE `from` from `marketdata.candles` @1d. **S4 (openchart) backfills the 200+ days of daily history these MAs need** into `marketdata.candles` @1d; the existing `nse_eod_bhavcopy` capture (V014) supplies ongoing EOD. The Minervini Trend Template gates map to engine indicators (SMA150/SMA200, 50-day SMA, 52-week-high/low, RS-rank context) — implement these in `libs/strategy-engine` (S7/S13) so they evaluate in the parity replay path.
2. **Entry-pattern fills are manual/approx.** Since VCP/pivot is deferred, the backtest models entries as a simple rule on the screened candidates (e.g. breakout above prior-day high, or close > pivot proxy) with `FillTiming.NEXT_BAR_OPEN`. The exit applies Minervini risk rails: hard stop (`stop_loss` → already carried on `SignalEvent`/`Trade` as `stopLoss`, computed at entry by `ExitEvaluator.entryLevels`), and a swing trailing/target rule. Use `InstrumentClass.EQUITY` costs (the current `CostConfig.defaults()` is correct here — no change needed).
3. **The screener itself runs OUTSIDE the replay engine** as a daily job (S13) producing a watchlist; the backtest only validates the entry-rule edge on the screened universe. **Note dependency:** the run's universe is multi-instrument for a screen, but `BacktestRunner.signalInstrument` enforces a SINGLE-instrument universe ("backtest needs an explicit single-instrument universe (v1)"). Per-symbol backtests over the screened list (one run per symbol, aggregated) is the v1 path; a portfolio backtest is a larger engine change — defer.

**Verify (14.4):** backfill `NIFTY 50` + a sample equity 272×1d via the cache-first warm; submit a daily Minervini backtest over a covered window; assert run completes, `interval='1d'`, `benchmarkCoverage='present'` in metrics (regime pre-flight passed), and trades carry non-null `stop_loss`.

### 14.5 (c) PARITY PRESERVATION — the non-negotiable invariant

Every new field added to `SignalEvent` or `Trade` for the scalp/swing tracks MUST ride the FROZEN golden-vector side-channel:

- `GoldenSignalsJson.write()` (`libs/strategy-engine/.../golden/GoldenSignalsJson.java`) is **FROZEN** — it serializes only `timestamp, exchange, tradingsymbol, direction, composite, breakdown{score,weight,activated}` in documented order, scale-4 decimals, no whitespace. New `SignalEvent` fields (`stopLoss`/`takeProfit` are the existing precedent) are NOT serialized by `write()`, so the golden JSON stays byte-identical.
- **The parity invariant holds IFF the new field is computed deterministically at ENTRY (compute-at-entry, like `ExitEvaluator.entryLevels`), never per-run random and never wall-clock.** `SyntheticPremium` is the model: "Fully deterministic… same inputs yield a byte-identical premium series — no wall-clock, no randomness." Any scalp OI feature added to a signal must compute from the candle/context series only.
- **Trade-list determinism is also gated:** `BacktestParityTest` (`services/backtest-service/.../replay/BacktestParityTest.java`) asserts two replays produce the IDENTICAL `trades()` list AND byte-identical golden JSON, then byte-matches the frozen `expected/<feature>.signals.json`. New `Trade` fields must therefore be deterministic functions of the replay (no UUIDs, no timestamps-of-execution).
- **Verify with the two parity tests:** `BacktestParityTest` (backtest side) and `GoldenDeterminismTest` (`libs/strategy-engine/.../golden/GoldenDeterminismTest.java`, lib side). Both must stay green after any signal/trade field addition. CI runs these under the `backtest` and (lib via `-am`) shards.

**Verify (14.5):** after adding any scalp/swing signal field, run `./mvnw -pl services/backtest-service -am verify` (per CLAUDE.md, full reactor + `-am`, never bare `-pl` on a leaf — the lib must rebuild) and confirm `BacktestParityTest.replaySignalsMatchTheFrozenGoldenVectors` and `GoldenDeterminismTest` pass byte-identically. Tests must be named `*Test`/`*IntegrationTest` (no failsafe; `*IT` is silently skipped).

### 14.6 (d) Data-warm + pre-flight (the gotchas)

The replay engine NEVER fetches on demand — it reads the shared `marketdata` store read-only (`CandleReader`, D10). Coverage is established by warming:

- **Three series get warmed:** (i) primary 1m, (ii) each context `(instrument, timeframe)`, (iii) the benchmark daily with warm-up depth. Submission-time warm: `JobsService.runPreflight` calls `marketData.warm(exch, sym, "1m", from, to)` then `preflight.check(...)`, plus warms the benchmark `1d` over `from.minusDays(BENCHMARK_WARMUP_LOOKBACK_DAYS = WARMUP_SESSIONS*2 = 544)` and runs `regimePreflight.check`. Worker-time warm: `BacktestRunner.warmSeries` re-warms 1m + every indicator context series + benchmark `1d` before the reads. Warm is via `MarketDataClient.warm` → cache-first `GET /api/v1/market/candles?...&limit=5000` (timestamps URI-encoded so `+05:30`→`%2B`); **best-effort, never throws** — a warm miss leaves the real coverage error to the pre-flight.
- **Benchmark warm-up = 272 sessions (`RegimeLabeler.WARMUP_SESSIONS`, verified).** `RegimePreflight.check` 422s `DATA_GAP` if `count1dBucketsBefore(benchmark, from) < 272`. So every windowed run needs ~272 daily benchmark (`NSE:NIFTY 50` default) sessions BEFORE the window — backfill `NIFTY 50` 1d first (CLAUDE.md). S4 (openchart) / `nse_eod_bhavcopy` supply this.
- **market-calendar CURRENT-YEAR limitation (the 500-error gotcha).** `libs/market-calendar/.../MarketCalendar.java` loads `/nse-trading-holidays.csv` and `requireCovered(year)` THROWS `IllegalArgumentException("NSE holiday calendar covers years [...] but year N was queried")` for any uncovered year. The options snapshot coverage check (`SnapshotPremiumReader.expectedSnapshotSlots`) and the regime pre-flight transitively hit this. **A scalp/swing backtest window in 2024/2025 (the years ExpiryTrack backfill covers) 500s unless `nse-trading-holidays.csv` is extended to cover those years.** **MODIFY** `libs/market-calendar/src/main/resources/nse-trading-holidays.csv` to add the backfill years' holidays (CD-2 refresh, as the error message instructs); add a `MarketCalendarTest` assertion that `coveredYears()` contains every year S5's backfill spans. This is a HARD prerequisite for any historical scalp backtest.

**Verify (14.6):** on a fresh mock stack, submit a backtest over a backfilled 2024 window; assert no `DATA_GAP` 422 and no "covers years" 500; confirm `marketdata.candles` @1d has ≥272 `NSE:NIFTY 50` buckets before the window start.

### 14.7 (e) raptorbt — DEFERRED independent cross-check oracle

raptorbt (MIT, Rust options backtester) is **DEFERRED**. Do NOT replace the Java `ReplayEngine` — the engine's parity guarantee (replay == live == paper via the shared `LtpSlippageV1`) is the platform's core invariant and a second engine would break it. The only sanctioned future use is as an INDEPENDENT cross-check oracle: run the same scalp window through raptorbt offline and DIFF aggregate metrics (total return, trade count, max DD) against the Java run as a sanity check — never as a parity source, never in the deterministic replay path, never in CI gates. File a parking-list note in `PHASE_GATES.md`; no code in this phase.

### 14.8 Numbered build order (with per-step verify)

1. **Extend `nse-trading-holidays.csv`** to cover the ExpiryTrack backfill years (14.6). *Verify:* `MarketCalendarTest` asserts `coveredYears()` ⊇ backfill years; `./mvnw -pl libs/market-calendar -am test` green.
2. **Confirm S5 ingest grain** and decide 1m→5m downsample vs 1-min snapshot grade (14.2). *Verify:* a covered-window `SnapshotPremiumReader.checkArchiveCoverage` passes (present ≥ expected slots) for a known expired contract.
3. **Implement scalp OI/Trending-OI + indicator primitives (S7/S12) inside `libs/strategy-engine`** so they run in `TickwiseGoldenRunner`, computed at-entry. *Verify:* `GoldenDeterminismTest` green; a new golden fixture for a scalp strategy produces stable signals across two replays.
4. **Add `CostConfigResolver`** and wire option/future costs from the request `costs` block; populate `FillRequest.quotedSpread` from snapshot bid/ask on the premium-as-primary path (14.3). *Verify:* a scalp run's `backtest_trades` show non-zero option STT/brokerage legs; `BacktestParityTest` (equity defaults path) still byte-identical.
5. **Run a windowed scalp replay** over an S5-backfilled window. *Verify:* run row has `premium_source` set, `trade_count>0`, `metrics->'caveats'` carries the scalp-fidelity line; signals reproduce across two submissions (resultRef-keyed results identical).
6. **Implement Minervini daily entry rule + rails (S13)** as a single-instrument daily backtest over screened candidates (14.4). *Verify:* daily run completes with `benchmarkCoverage='present'`, trades carry non-null `stop_loss`.
7. **Full parity gate.** *Verify:* `./mvnw -pl services/backtest-service -am verify` — `BacktestParityTest` + `GoldenDeterminismTest` byte-identical, JaCoCo ≥60% holds.
8. **Park raptorbt** as a future cross-check oracle in `PHASE_GATES.md` (14.7). No code.

**Gotchas (from CLAUDE.md):** build with the full reactor + `-am` (the engine lib must rebuild or the fat JAR embeds a stale lib); add a CI matrix shard or these tests never run; ITs share a singleton DB with no per-method cleanup (unique slugs/names); `*IT` classes are silently skipped (name tests `*Test`); the mock stack's candles are rolling/real-time so derive a recent covered window unless the S5 backfill seeds fixed historical dates; keep the Bash cwd at repo root (the `guard-paths.py` hook resolves relative to cwd).

---


## 15. Dev & Agentic Tooling (optional installs)

> **Scope & status.** Everything in this section is **developer/agentic tooling only — NON-PRODUCT**. None of it ships in any ArthaYantra artifact (no Maven dependency, no Docker service in `deploy/docker-compose.yml`, no React import). It exists to *accelerate development* of the product sections (porting references for S5/S7, scaffolding aids, AI-context docs, cost-model reference for S14). Install only when actively useful; uninstalling leaves the product untouched. **All four toolsets below are MIT-licensed** (the marketcalls org publishes them MIT; OpenAlgo *core* is AGPL but is consumed only as the pinned appliance per §1/§2 — these are separate repos). Keep MIT copyright notices on anything you *port* code from (that obligation lives in the product sections S5/S7/S14, not here).

**Grounding notes (verified against the repo):**
- This repo already drives Claude with `.mcp.json` (servers: `postgres`, `context7`, `playwright`), `.claude/settings.json` (PreToolUse `guard-paths.py`, PostToolUse `format-frontend.py`), `.claude/skills/` (`build-service`, `mock-walk`, `new-migration`, `run-artha-yantra`), and `.claude/agents/` (`timescale-domain-reviewer.md`, `ui-a11y-reviewer.md`). New tooling slots into these existing locations — do not invent a parallel structure.
- `.gitignore` already excludes `.playwright-mcp/` and `.firecrawl/` as "ephemeral MCP working dirs"; follow that pattern for any new MCP scratch dirs.
- Porting-reference targets already exist: `libs/black76-math/src/main/java/in/arthayantra/black76/{Black76,IvSolver}.java` (S5 greeks port lands here) with golden vectors under `tools/greeks-vectors/`; indicator porting (S7) has golden vectors under `tools/indicator-vectors/`. The skills/catalogs below are *inputs* to those ports — they are not the ports themselves.
- **Gotcha (CLAUDE.md):** the `guard-paths.py` PreToolUse hook resolves its path relative to the **Bash cwd** — a persisted `cd <subdir>` breaks every later Edit/Write. Any install commands here that `cd` must subshell (`(cd dir && ...)`) or stay at repo root. `*.json` is pinned `eol=lf` in `.gitattributes`; if you commit any JSON config (e.g. an MCP entry), keep LF and `git add --renormalize` after.

---

### 15a. openalgo-mcp — MCP server for Claude-driven data/exec during dev

**What / why.** `marketcalls/openalgo-mcp` (also published as the `openalgo_mcp` PyPI package) is a **Model Context Protocol server that wraps the OpenAlgo REST API**. Installed as an MCP server it lets *this* Claude session, during development, pull live quotes/depth/history/optionchain and (in sandbox) place test orders **against the pinned OpenAlgo appliance from §1** — without writing throwaway Java. Use it for exploratory data checks ("what does optionchain return for NIFTY this expiry"), to sanity-check the §2 `OpenAlgoGateway` mapping against ground truth, and to drive sandbox order round-trips while building the Track-2 scalper (§11). It is a **dev convenience, not a runtime path** — the product's only OpenAlgo client is the Java SDK gateway from §2.

**Depends on:** §1 (pinned OpenAlgo appliance must be running) and §2 (the gateway it helps you validate). License: **MIT**.

**How it connects.** The MCP server needs the OpenAlgo **host URL + API key**, identical to what §2's gateway uses. Reuse the same appliance: the §1 compose service exposes OpenAlgo's HTTP API (default `http://127.0.0.1:5000` — **VERIFY** against the §1 port mapping) and an API key minted in the OpenAlgo UI. Point the MCP at the **sandbox/analyze** mode of the appliance for any order calls so dev never touches a real broker.

**Install / usage steps:**
1. Add an MCP server entry to `.mcp.json` (sibling of the existing `postgres`/`context7`/`playwright` entries). Keep the host/key out of the file — reference an env var (mirror how `postgres` uses `${POSTGRES_PASSWORD}`):
   ```jsonc
   "openalgo": {
     "command": "uvx",                  // or "npx"/"python -m" per the package's documented launcher — VERIFY
     "args": ["openalgo-mcp"],
     "env": { "OPENALGO_API_KEY": "${OPENALGO_API_KEY}",
              "OPENALGO_HOST": "http://127.0.0.1:5000" }
   }
   ```
   **VERIFY** the exact launch command + env-var names from the openalgo-mcp README before committing; the repo may ship a `--host/--api-key` CLI instead of env. Keep LF endings (`.gitattributes`).
2. Add `OPENALGO_API_KEY` to your local `.env` (already git-ignored) — never hardcode the key.
   - **VERIFY:** `.mcp.json` parses and Claude lists the `openalgo` server's tools (`/mcp` or tool list shows quotes/history/optionchain).
3. Confirm the appliance is reachable: from a dev shell hit the OpenAlgo health/funds endpoint with the same key.
   - **VERIFY:** an MCP `quotes`/`optionchain` call for `NIFTY` returns data, and the `oi` field is present per-strike (confirms the §1/§2 data assumption end-to-end).
4. For order testing, ensure the appliance is in **sandbox/analyze** mode.
   - **VERIFY:** a sandbox `placeorder` returns a simulated order id and **no** real broker order appears.

**Boundary reminder:** openalgo-mcp talks to the appliance over the network — it is *outside* the product, so its AGPL-irrelevance is automatic (it's MIT and we run it as a tool, not link it). Do not let any product code import it.

---

### 15b. openalgo-claude-plugin + the agent skills (`openalgo-skills` / `-indicator-skills` / `-execution-skills`)

**What / why.** `marketcalls/openalgo-claude-plugin` is a **Claude Code plugin-marketplace** entry; `openalgo-skills`, `openalgo-indicator-skills`, and `openalgo-execution-skills` are **MIT agent-skill bundles**. They accelerate development, they are **not product code**:
- **`openalgo-skills`** — strategy scaffolding patterns + the **single-file dual-mode (backtest + live) strategy template**. Use as a **design reference** for how a strategy reads the same signal inputs in both replay and live — informs (does not dictate) the §11 Track-2 scalper structure and the parity-safe replay path. *Reference only; ArthaYantra keeps its Java engine + golden/parity harness — do not adopt OpenAlgo's Python single-file runtime.*
- **`openalgo-indicator-skills`** — catalog of **100+ indicators with formulas**. This is the **porting reference for S7** (VWAP, Supertrend(10,2), VWMA(20), RSI14 on 80:20 bands, PSAR(0.02,0.2) → Java). Read the formula, port to Java, validate against the golden vectors under `tools/indicator-vectors/`. *Catalog of math, not a dependency.*
- **`openalgo-execution-skills`** — order-execution patterns (slicing, retries, modify/cancel) — a design reference for the §2 order path / §11 risk rails.
- **`openalgo-claude-plugin`** — the marketplace wrapper that can install the above as a plugin set in one step.

**Depends on:** S7 (consumes the indicator catalog), §11 (consumes the strategy/execution patterns). License: **MIT**.

**Where to install.** Drop the skills under this repo's existing **`.claude/skills/`** (alongside `build-service`, `mock-walk`, `new-migration`, `run-artha-yantra`) so they're available in-repo, or install the plugin via the Claude Code plugin marketplace for a one-shot. Because these are **dev-only**, prefer adding them to a developer-local skills path rather than committing them if you don't want them in the product tree; if committed, they sit in `.claude/skills/` and are clearly non-product (nothing under `services/`, `libs/`, `frontend-ui/`, or `deploy/` references them).

**Install / usage steps:**
1. Add the marketcalls marketplace, then install the plugin (or `git clone` each skill repo into `.claude/skills/<name>/`):
   ```
   /plugin marketplace add marketcalls/openalgo-claude-plugin
   /plugin install openalgo-skills            # + -indicator-skills, -execution-skills
   ```
   **VERIFY** the exact marketplace slug + plugin names from the openalgo-claude-plugin README.
   - **VERIFY:** the skills appear in the available-skills list / `.claude/skills/` and are invocable.
2. **S7 use:** when porting an indicator, open the matching entry in `openalgo-indicator-skills`, port the formula to Java, then run the S7 port against `tools/indicator-vectors/` golden vectors.
   - **VERIFY:** ported Java indicator output matches the golden vector byte/numeric tolerance (per S7's harness).
3. **§11 use:** read the dual-mode/execution patterns as design input only.
   - **VERIFY:** no `import`/dependency on any OpenAlgo skill code exists in `services/` or `libs/` (these stay reference-only).

**Boundary reminder:** these are agent skills (Markdown + helper scripts), MIT — keep the copyright notice if you copy a formula verbatim into S7. They never become a Maven/npm dependency.

---

### 15c. zerodha-api-docs — Kite API as markdown in an AI-context dir

**What / why.** `marketcalls/zerodha-api-docs` is the **Kite Connect API rendered as markdown**. We keep **Kite-direct as the fallback source** (§2: a config flag selects Kite-direct vs OpenAlgo per capability; the hand-rolled `kite/wire/` DTOs + `ContractCanary` + `KiteWireContractTest` remain the live Kite path). Having the Kite docs as local markdown gives Claude precise, greppable context when working on the Kite path (DTO fields, endpoint shapes, error codes) — better than relying on model memory or re-scraping. License: **MIT**.

**Depends on:** §2 (Kite-direct fallback gateway, existing `kite/wire/` package in `market-data-service`).

**Where to install.** Create an **AI-context docs dir** and drop the markdown there. The repo has no existing AI-context dir, so introduce one under `docs/` (consistent with `docs/oipulse-study/`, which is reference material, not product):
- `docs/ai-context/zerodha-api/` — the cloned markdown.

**Install / usage steps:**
1. Clone the docs into the new dir:
   ```
   git clone https://github.com/marketcalls/zerodha-api-docs docs/ai-context/zerodha-api
   ```
   (Run from repo root so `guard-paths.py` resolves; do not persist a `cd`.)
2. Decide commit vs ignore: if you don't want third-party docs vendored, add `docs/ai-context/zerodha-api/` to `.gitignore` (mirrors the ephemeral-MCP-dir pattern). If vendored, keep upstream LICENSE and note the source.
   - **VERIFY:** the markdown is present and greppable (e.g. searching for `historical` / `oi` / a Kite error code returns hits).
3. **Usage:** when touching `services/market-data-service/.../kite/wire/*` or the `ContractCanary` manifest, reference these docs for field names/shapes.
   - **VERIFY:** a change to a `kite/wire/` DTO checked against the markdown matches the documented Kite field (cross-check before re-running `KiteWireContractTest`).

---

### 15d. vectorbt-backtesting-skills — cost-modeling reference for S14

**What / why.** `marketcalls/vectorbt-backtesting-skills` is an **MIT agent-skill bundle** covering vectorbt-style backtesting, notably **realistic cost modeling** (commission/brokerage, slippage, STT/exchange charges, fee schedules). Use it purely as a **reference for S14's cost model** — how to parameterize and apply Indian-market transaction costs (brokerage, STT, exchange txn charge, GST, SEBI/stamp) in the backtest fill/PnL path. ArthaYantra keeps its own deterministic Java engine and golden/parity harness; this is **reference math, not a dependency**, and vectorbt itself is **not** introduced into the product (raptorbt is the deferred Rust cross-check oracle per the SKIP/DEFER list — vectorbt-backtesting-skills is only a cost-model reading, not a backtester swap).

**Depends on:** S14 (cost/slippage model). License: **MIT**.

**Where to install.** Same as 15b — `.claude/skills/vectorbt-backtesting-skills/` (or via the plugin marketplace if it's published there). Dev-only; not referenced by any product module.

**Install / usage steps:**
1. Clone/install into `.claude/skills/`:
   ```
   git clone https://github.com/marketcalls/vectorbt-backtesting-skills .claude/skills/vectorbt-backtesting-skills
   ```
   - **VERIFY:** the skill is listed/invocable.
2. **S14 use:** extract the cost components + formulas (commission, slippage, STT/exchange/GST/stamp) as the spec for S14's Java cost model.
   - **VERIFY:** S14's cost model enumerates the same Indian-market charge categories; cross-check a worked example (entry+exit fee total) against the reference.
3. Confirm no product import.
   - **VERIFY:** no `services/`/`libs/` reference to vectorbt or this skill (cost math is hand-ported into S14, MIT notice retained).

---

### 15e. Summary table

| Tool | Type | License | Install location | Accelerates | Depends on |
|---|---|---|---|---|---|
| `openalgo-mcp` | MCP server | MIT | `.mcp.json` (server `openalgo`), key in `.env` | Dev data/exec vs pinned appliance | §1 appliance, §2 gateway |
| `openalgo-skills` | Agent skill | MIT | `.claude/skills/` or plugin | Strategy scaffolding, dual-mode design ref | §11 |
| `openalgo-indicator-skills` | Agent skill | MIT | `.claude/skills/` or plugin | 100+ indicator formulas → **S7 port ref** | S7 (`tools/indicator-vectors/`) |
| `openalgo-execution-skills` | Agent skill | MIT | `.claude/skills/` or plugin | Order-execution patterns (design ref) | §2, §11 |
| `openalgo-claude-plugin` | Plugin marketplace | MIT | Claude Code marketplace | One-shot install of the above | — |
| `zerodha-api-docs` | Markdown docs | MIT | `docs/ai-context/zerodha-api/` | Kite-path AI context (fallback source) | §2 (`kite/wire/`) |
| `vectorbt-backtesting-skills` | Agent skill | MIT | `.claude/skills/` or plugin | **S14 cost-model reference** | S14 |

**Non-product invariant (apply to all of 15a–15d):** every item lives only in `.mcp.json`, `.claude/`, or `docs/ai-context/`. **Verify before any plan-complete:** `grep -r` for these tool names under `services/`, `libs/`, `frontend-ui/`, and `deploy/` returns **zero** product references. Anything that contributes *code* to the product (an indicator formula, a cost formula) is hand-ported into the relevant Java section (S5/S7/S14) with its MIT copyright notice retained — it is never wired in as a dependency or a compose service.

---


## 16. Sequencing, Phases, Milestones, Dependencies & Risk Register

This section ties §1–§15 into one executable roadmap a fresh session can drive across many sittings. It assumes the section numbering used throughout this plan:

- **§2** OpenAlgo appliance (pinned Docker container, AGPL, unmodified)
- **§3** OpenAlgoGateway (Java SDK `in.openalgo:openalgo:1.0.1` behind `QuoteGateway`/`CandleReader` ports) + contract test
- **§4** Source-selection config flag (Kite-direct vs OpenAlgo per capability) + capture routing
- **§5** ExpiryTrack intraday-OI backfill appliance → Parquet → TimescaleDB ingest
- **§6** opengreeks closed-form greek math ported into `libs/black76-math`
- **§7** pyindicators/openalgo-indicator-skills formulas ported into Java (scalp signal engine)
- **§8** marginism SPAN-margin Python appliance
- **§9** openscreener fundamentals appliance (OPTIONAL)
- **§10** React 19 + Vite + Tailwind + shadcn/ui + TradingView Lightweight Charts rewrite
- **§11** openalgo-heatmap zero-dep core import (OI heatmap)
- **§12** Siva Options Scalper — 12 sub-strategies (Track 2)
- **§13** Minervini SEPA momentum screener — Indian stocks (Track 1)
- **§14** Backtest/forward-test wiring for both tracks
- **§15** openchart daily-history backfill appliance

> Note: §11 (openalgo-heatmap) is consumed by §10 and is sequenced inside Phase 4. §15 (openchart) is a backfill appliance gated by §13's MA-history need, so it rides into Phase 1 (data) even though its consumer is Phase 5.

### 16.0 Ground truth this roadmap rests on (verified in-repo)

- **Ports already exist** to hang §3 behind: `services/market-data-service/.../kite/QuoteGateway.java` and `services/backtest-service/.../replay/CandleReader.java`. The Kite live impl is `kite/live/LiveQuoteGateway.java`; the mock is `mockfeed/MockQuoteGateway.java`. The anti-corruption DTO pattern to mirror lives under `kite/wire/` (`KiteQuote`, `KiteHistoricalResponse`, `KiteInstrument`, … each `@JsonIgnoreProperties(ignoreUnknown=true)`; see `kite/wire/package-info.java`).
- **Contract-drift tests to mirror for §3:** `kite/wire/KiteWireContractTest.java` (off-critical-path shape assertions) and `kite/canary/ContractCanary.java` + `ContractCanaryIntegrationTest.java` (daily raw-JSON vs manifest, sentinels for CONSUMED fields). The OpenAlgo equivalent is `OpenAlgoWireContractTest` + `OpenAlgoContractCanary`.
- **Greek math target for §6:** `libs/black76-math/src/main/java/in/arthayantra/black76/Black76.java` + `IvSolver.java`, guarded by `Black76GoldenVectorTest.java` against `src/test/resources/black76-golden-vectors.json`. New greeks ride as additive methods + new golden vectors — never edit existing vectors.
- **Golden/parity guard for §14:** `libs/strategy-engine/.../golden/GoldenSignalsJson.java` is FROZEN; `GoldenDeterminismTest`, the `FillSimulator` (`ltp_slippage/v1`), and BacktestParityTest enforce byte-identical signals + record-equality trades. New `SignalEvent`/`Trade` fields ride as a NON-serialized side-channel computed at entry.
- **OI data spine is ~80% built (Stage F/G):** the oipulse-parity endpoints under `/api/v1/market/options/{oi-analysis,oi-stats,active-strikes,spurt,big-oi,premium,premium-series,trending}` + `/api/v1/market/futures/{oi-analysis,spurt,movers,banks,banks-grid,buzz,eod}` + `/api/v1/market/{fii-dii/*,breadth}` already exist (see `docs/superpowers/plans/2026-06-15-stage-g-oipulse-parity.md`). §12 consumes these; it does NOT rebuild them.
- **CI is sharded** (`.github/workflows/ci-java.yml`, 3 legs: market-data / backtest / strategy-gateway) plus `ci-contracts.yml`, `ci-frontend.yml`, `ci-e2e.yml`, `ci-migrations.yml`, `ci-optimizer.yml`. **Any NEW service must add a matrix shard or its tests never run** (CLAUDE.md). The React rewrite (§10) must keep or replace `ci-frontend.yml` + `ci-e2e.yml`.
- **Flyway heads (verified against `deploy/flyway/` on 2026-06-19): marketdata = V017, strategy = V008, backtest = V005.** Next free marketdata = **V018** — see the authoritative allocation table in §17.1. (Earlier text in this section saying "V015/V016" is WRONG; every `V016` mention in §16 is superseded by §17.1.) Applied migrations are checksum-locked → all schema changes here are NEW suffix-versioned migrations.

---

### 16.1 Phased roadmap

Each phase has an **ENTRY GATE** (preconditions before a session starts) and an **EXIT/VERIFY GATE** (machine-checkable acceptance). One **stage = one branch = one PR**, phase-per-commit (§16.5). Phases are strictly dependency-ordered; within a phase, steps may parallelize per `superpowers:dispatching-parallel-agents`.

#### Phase 0 — Integration spine (§2 + §3 + §3 contract test)

The single hard prerequisite for everything that swaps the broker. Stand up the OpenAlgo appliance and the anti-corruption gateway, but DO NOT yet route production reads through it.

- **Entry gate:** mock + live stacks both boot green today (`ay up -d`, healthchecks pass); `in.openalgo:openalgo:1.0.1` resolves from Maven Central on this box (TLS-intercepting AV → set `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`; see the `build-service` skill).
- **Work:**
  1. §2: add `openalgo` service to `deploy/docker-compose.yml` pinned to a release **tag** (never `latest`), its own container, env-only config, behind the loopback network. Add `ARTHA_OPENALGO_URL`/`ARTHA_OPENALGO_WS_URL` to `.env` (escape any `$` as `$$`). Wire `ay`/`ay.ps1` so mock and live both know the URL. (VERIFY tag pin is recorded in `docs/design/DECISIONS_LOG.md`.)
  2. §3: create `services/market-data-service/.../openalgo/` with a `wire/` sub-package mirroring `kite/wire/` — one `@JsonIgnoreProperties(ignoreUnknown=true)` record per OpenAlgo endpoint (quotes/depth/history/optionchain/optiongreeks/expiry/orders), each mapped to the existing domain port records (`Quote`, `Candle`, `InstrumentRecord`). Add `OpenAlgoQuoteGateway`/`OpenAlgoCandleReader` impls behind the EXISTING `QuoteGateway`/`CandleReader` ports. Add `openalgo/package-info.java` stating the anti-corruption + AGPL-boundary rule.
  3. §3 contract test: `OpenAlgoWireContractTest` (mirrors `KiteWireContractTest`) + `OpenAlgoContractCanary` + `openalgo-contract-manifest.json` (sentinels for CONSUMED fields only), and `OpenAlgoContractCanaryIntegrationTest`.
- **Exit/verify gate:**
  - `./mvnw -pl services/market-data-service -am verify` green incl. the new contract tests.
  - WireMock-stubbed `OpenAlgoQuoteGateway` returns a `Quote` for NIFTY; `optionchain` round-trips per-strike `oi` into the domain record (proves field 1's "oi present" claim).
  - `docker compose --env-file .env up -d openalgo` healthy; `ay` switches profiles without unsetting `ARTHA_OPENALGO_*`.
  - **Branch:** `feat/openalgo-spine`.

#### Phase 1 — Data inflow (§4 routing + §5 ExpiryTrack + §15 openchart)

Fill the historical-data holes the strategies need, and let a config flag pick the source per capability.

- **Entry gate:** Phase 0 merged (`OpenAlgoGateway` callable behind the ports). TimescaleDB schema known (next free Flyway = V016).
- **Work:**
  1. §4: add the per-capability source flag, e.g. `artha.marketdata.source.quotes`, `.candles`, `.optionchain`, `.orders` = `kite|openalgo` (config key, NOT YAML strategy). A factory selects `LiveQuoteGateway` vs `OpenAlgoQuoteGateway` per capability. **Capture STILL writes ArthaYantra TimescaleDB** — OpenAlgo is source, not store.
  2. §5: stand up the ExpiryTrack appliance (AGPL, standalone container or a host-run job — consume its OUTPUT Parquet only). Add `tools/historical-import/` ingest (the repo already has `tools/historical-import/run_full_load.ps1` + `manifest_live.sqlite`) to load Parquet intraday OI into `options_chain_snapshots`/`futures_oi_snapshots`. New migration **V016** only if a backfill-provenance column is needed.
  3. §15: openchart backfill of 200+ days daily OHLCV for the equity universe into the `candles`@1d native daily table (the dense `readDailyWithWarmup` path) — this feeds §13's 150/200-day MAs.
- **Exit/verify gate:**
  - Flip `artha.marketdata.source.quotes=openalgo`; a live quote pulls through OpenAlgo and persists identically (schema-diff vs Kite-direct row = none).
  - ExpiryTrack Parquet for one expired NIFTY expiry ingested; `SELECT count(*) FROM options_chain_snapshots WHERE …` shows 1-min granularity, IST-correct buckets, OI populated. **Cross-check** a handful of strikes vs an independent source (mitigates the §5 mapping-mismatch risk).
  - openchart backfill: a chosen symbol returns ≥200 daily candles via `GET /api/v1/market/candles?interval=1d`; the 200-day MA computes without `DATA_GAP`.
  - **Branch:** `feat/data-inflow`.
  - **Gotcha:** `libs/market-calendar` covers only the CURRENT year — backfilling a 2024/2025 window 500s with "NSE holiday calendar covers years […]". Extend the calendar lib first if backfilling prior years (mark a sub-task).

#### Phase 2 — Quant libs (§6 greeks + §7 indicators)

Pure-Java, deterministic, no network hop, on the backtest replay path.

- **Entry gate:** Phase 0 merged (not Phase 1 — these libs have no data-inflow dependency, so Phase 2 MAY run in parallel with Phase 1 by a second session).
- **Work:**
  1. §6: port opengreeks closed-form greeks (vanna/charm/vomma/speed/zomma/color/veta/ultima/dual-delta/dual-gamma) into `libs/black76-math` as ADDITIVE methods on `Black76` (or a new `Greeks` record). Keep the MIT copyright notice.
  2. §7: port the scalp indicator formulas — VWAP, Supertrend(10,2), VWMA(20), RSI14(80:20 bands), PSAR(0.02,0.2) — into a new Java home. Reuse the existing ta4j integration if present (`IndicatorSeriesService` exists in backtest-service per Stage-E gate); otherwise a new `libs/scalp-indicators` lib.
- **Exit/verify gate:**
  - **§6: extend golden vectors parity-safely** — add new greek vectors to `black76-golden-vectors.json` (NEVER edit existing rows; CLAUDE.md frozen-vector rule). `Black76GoldenVectorTest` green; cross-validate ≥3 greeks against opengreeks's published values to within tolerance.
  - **§7:** unit tests assert each indicator vs a hand-computed reference series; Supertrend flip points and PSAR reversals match known fixtures.
  - `./mvnw -pl libs/black76-math -am verify` and the indicator lib both green; downstream services still build via `-am`.
  - **Branch:** `feat/quant-libs`.
  - **Gotcha:** if these indicators ever feed the deterministic backtest replay (they will, via §12/§14), compute-at-entry only — random/per-run values break parity.

#### Phase 3 — Track-2 scalper engine (§12 + §8 SPAN)

Build the 12 Siva sub-strategies on the (now-fed) OI spine + the Phase-2 indicators. **Build before §13** (owner-locked order).

- **Entry gate:** Phases 1 + 2 merged. Source docs read from the sibling repo `C:\Trading\ArthaYantra\StockMarketStrategyTraining`. Confirm the OI-spine endpoints/readers (`OiInterpretation`, `ActiveStrikeService`, `OiSpurtService`, `MaxPainCalculator`, `PcrHistoryService`, `OptionsSnapshotReader`/`FuturesSnapshotReader`) are live.
- **Work:**
  1. §12: implement each of the 12 sub-strategies as `strategy-signal-service` strategies consuming 3m candles + VWAP/Supertrend/VWMA/RSI/PSAR (§7) + per-strike OI/Trending-OI/sentiment/quadrants + futures OI + IV/India-VIX + FII/DII + breadth + straddle premium-vs-VWAP. Wire the risk rails (RR 1:2, hard SL, daily loss cap, 5-account model, no averaging) — risk limits on DB rows, never YAML (matches the Stage-F kill-switch/loss-cap pattern).
  2. §8: stand up the marginism Python appliance (offline SPAN from exchange `.spn` files) behind a small REST shim; `suggested_qty`/position sizing calls it (cache-first; never on the scalp execution hot path — see Risk R3).
  3. **Spec provenance (re-sync hook).** The 12 sub-strategies are a **one-time manual port** of the human-readable specs in the sibling repo `C:\Trading\ArthaYantra\StockMarketStrategyTraining` (+ the Siva `.txt` sources under `C:\Trading\`). There is **no automatic sync** — when those docs update later, the Java is re-ported by hand (deliberate: strategy logic is parity-locked on the deterministic backtest replay path, so an auto-pull from a docs repo is a parity hazard, and the 12-strategy registry is intentionally stable). To make a later re-sync a cheap diff instead of a hunt, record provenance at port time: (a) each Java sub-strategy carries a source tag (annotation/Javadoc) citing its source doc path + the consolidated-doc commit/date it was ported from; (b) add `docs/manual-tests/` or a `strategy-sources.md` manifest mapping each of the 12 strategies → source doc + last-ported commit. Re-sync flow then = read changed spec → edit the affected strategy's Java + its one-signal fixture → `-am verify` → commit. Threshold/limit tuning rides DB rows (not the Java), so it never requires a re-port.
- **Exit/verify gate:**
  - Each sub-strategy has a unit test with a fixture that fires exactly one signal; the per-strike OI inputs come from seeded snapshot rows.
  - Each sub-strategy carries a source-provenance tag and the `strategy-sources.md` manifest lists all 12 → source doc + last-ported commit (the re-sync hook).
  - `./mvnw -pl services/strategy-signal-service -am verify` green; the daily-loss trip pauses ENTRY only (mirrors Stage-F IT).
  - SPAN appliance: `RiskEngine.from_file(<spn>).basket([...])` returns a margin for a known NIFTY straddle within tolerance of the broker's number.
  - **Branch:** `feat/scalper-track2`.

#### Phase 4 — React migration (§10 + §11)

Re-create the Angular cockpit AND the oipulse OI pages in React; import only MIT pieces.

- **Entry gate:** Phase 3 signals exist to display, AND the OI-spine endpoints are stable. (React can START earlier against the existing API, but the scalper cockpit needs §12 emitting.)
- **Work:**
  1. §10: scaffold `frontend-ui` (or `frontend-react/`) on React 19 + Vite + Tailwind + shadcn/ui + TradingView Lightweight Charts. Build own components against the Java API. Re-create: the existing Angular cockpit (dashboard, strategy editor, version diff/publish, backtest runner, jobs monitor, trial explorer, equity curves, main chart, signals, paper ledger, notifier settings) AND the oipulse pages (`/oi/*`: Options OI, Futures OI, Spurt, Big-OI, Premium, EOD, FII-DII, Breadth, Banks-grid).
  2. §11: import openalgo-heatmap's zero-dep core (layout+color math) to drive an OI heatmap (tile size = OI, color = OI-change). NEVER import OpenAlgo-core AGPL frontend.
  3. Decimal-on-the-wire: re-implement `core/decimal` in React; money values arrive as JSON strings — never `parseFloat` arithmetic.
- **Exit/verify gate:**
  - **Page-parity checklist (mitigates Risk R5):** every Angular route in the Stage-E/Stage-G gates has a React equivalent rendering live mock-stack data.
  - Regenerate the Playwright e2e suite for React; the route+axe coverage matches or exceeds the 23-test Angular suite.
  - `ci-frontend.yml` + `ci-e2e.yml` updated for the React toolchain and green.
  - **Branch:** `feat/react-migration`.
  - **Gotchas (carry forward as React equivalents):** TradingView LWC needs `autoSize:true` to paint; decimal strings; list endpoints return `{items:[…]}` envelopes (only `instruments/search` + `instruments/underlyings` are bare arrays).

#### Phase 5 — Track-1 Minervini screener (§13 + optional §9)

The daily momentum screener over the Indian equity universe.

- **Entry gate:** Phase 1 merged (openchart 200+day daily history landed; `nse_eod_bhavcopy` ~3.2k symbols/day flowing).
- **Work:**
  1. §13: implement the 8-gate Trend Template + RS-rank percentile vs NIFTY + volume-vs-50-day-avg as a daily screener emitting a watchlist. Stage-2 detection falls out of the template. Owner accepts manual chart entry — NO VCP/pivot/Cheat automation.
  2. §9 (OPTIONAL, non-blocking): openscreener Playwright fundamentals appliance (EPS/sales/margins). Build only if it lands easily; isolate behind a flag so its scraping fragility (Risk R7) never blocks the screener.
- **Exit/verify gate:**
  - Screener run over the universe produces a non-empty watchlist; each gate is unit-tested against a seeded symbol that passes/fails each of the 8 conditions; RS-rank percentile validated on a small fixed set.
  - Runs without §9 (fundamentals optional) — proves the non-blocking boundary.
  - **Branch:** `feat/minervini-track1`.

#### Phase 6 — Backtest + forward-test wiring (§14)

Make both tracks backtestable AND forward-testable, parity-safe.

- **Entry gate:** Phases 3 + 5 merged (both strategy tracks exist).
- **Work:** wire §12 and §13 strategies into the backtest engine + the paper/forward path. Scalp backtests consume the intraday-OI history from §5 and the §6/§7 deterministic libs.
- **Exit/verify gate (parity-critical):**
  - `GoldenDeterminismTest` + BacktestParityTest green — `GoldenSignalsJson.write()` stays byte-identical; any new `SignalEvent`/`Trade` field rides the non-serialized side-channel, computed at entry.
  - Paper fill == backtest fill to the paisa (shared `ltp_slippage/v1` `FillSimulator`; no paper-local fill path) — the Stage-F invariant.
  - A scalp backtest over a real ExpiryTrack-backfilled expiry produces trades; a Minervini screener-driven swing backtest produces an equity curve.
  - **Branch:** `feat/backtest-wiring`.
  - **Gotcha (Risk R4):** document scalp-backtest fidelity limits (1-min OI snapshots ≠ tick-level fills) in the run output, not silently.

> **§9 (openscreener)** and **raptorbt** (DEFERRED cross-check oracle) may be attempted at ANY time after their data dependency lands; both are explicitly non-blocking.

---

### 16.2 Dependency graph

```
                     §2 OpenAlgo appliance
                            │
                            ▼
                     §3 OpenAlgoGateway + contract test   ──── Phase 0
                       │            │
        ┌──────────────┘            └───────────────┐
        ▼                                           ▼
  §4 source flag/routing                     (no data dep)
        │                                    §6 greeks   §7 indicators ── Phase 2
        ▼                                           │            │
  §5 ExpiryTrack OI backfill                        └─────┬──────┘
  §15 openchart daily backfill ── Phase 1                 │
        │                  │                              │
        │                  │                              ▼
        │                  └──────────────┐        §12 scalper + §8 SPAN ── Phase 3
        ▼                                 │              │
  §13 Minervini screener ── Phase 5       │              ▼
   (+ optional §9, non-blocking)          │        §10 React + §11 heatmap ── Phase 4
        │                                 │              │
        └──────────────┬──────────────────┘              │
                       ▼                                  │
              §14 backtest/forward wiring ── Phase 6 ◄────┘
```

Blocking edges:
- §3 blocks §4 (routing needs a gateway). §3 blocks §6/§7 only loosely (they need the Maven build green, not the gateway) — so **Phase 2 may run parallel to Phase 1**.
- §4 blocks §5 capture-source semantics; §5 + §15 block §13 (MA history) and §12 (scalp-backtest history).
- §6 + §7 block §12 (scalp signal inputs).
- §12 blocks §10 (cockpit needs signals) and §14.
- §13 blocks §14.
- §8 rides with §12; §9 and raptorbt are non-blocking sidecars.

---

### 16.3 Milestones (each with a concrete "done =")

- **M0 — Broker-swappable spine.** done = with `artha.marketdata.source.quotes=openalgo`, a live NIFTY quote and a NIFTY optionchain (per-strike `oi`) round-trip through `OpenAlgoQuoteGateway` into the domain `Quote`/snapshot records, and `OpenAlgoWireContractTest` + `OpenAlgoContractCanary` are green. (Phase 0)
- **M1 — Intraday-OI history backtestable.** done = one expired NIFTY expiry's 1-min OHLCV+OI is in `options_chain_snapshots` via the ExpiryTrack→Parquet→ingest path with IST-correct buckets, AND a sampled cross-check vs an independent source matches. (Phase 1)
- **M1b — Equity MA history present.** done = a chosen NSE symbol returns ≥200 daily candles and its 150/200-day MA computes without `DATA_GAP`. (Phase 1)
- **M2 — Greeks + indicators parity-locked.** done = new higher-order greeks added to `black76-golden-vectors.json` (existing rows untouched), `Black76GoldenVectorTest` green, and each ported indicator matches a hand-computed reference. (Phase 2)
- **M3 — Scalper fires real signals.** done = each of the 12 §12 sub-strategies has a green fixture test that emits exactly one signal from seeded OI + candle data; the daily-loss kill-switch pauses ENTRY only. (Phase 3)
- **M3b — SPAN margin online.** done = the marginism appliance returns a margin for a known NIFTY straddle within tolerance of the broker figure. (Phase 3)
- **M4 — React parity.** done = every Angular route in the Stage-E + Stage-G gates renders live mock-stack data in React, the OI heatmap (§11) draws, and the regenerated Playwright suite (≥23 tests + axe per route) is green in `ci-e2e.yml`. (Phase 4)
- **M5 — Minervini watchlist.** done = a daily screener run over the universe emits a non-empty Trend-Template watchlist with RS-rank, with each of the 8 gates unit-tested, AND it runs with §9 disabled. (Phase 5)
- **M6 — Both tracks backtest + forward-test, parity intact.** done = `GoldenDeterminismTest` + BacktestParityTest green, paper-fill == backtest-fill to the paisa, a scalp backtest over a backfilled expiry produces trades, and a Minervini swing backtest produces an equity curve. (Phase 6)

---

### 16.4 Risk register

| # | Risk | Likelihood / Impact | Mitigation |
|---|------|--------------------|------------|
| R1 | **AGPL contamination** (OpenAlgo core §2, ExpiryTrack §5) — network-use copyleft infects ArthaYantra if its source is merged or its frontend imported. | Low / Catastrophic (license) | Run BOTH strictly STANDALONE behind a process boundary: §2 = pinned unmodified Docker tag consumed only via REST/WS; §5 = consume OUTPUT Parquet only. NEVER fork-and-modify, NEVER import OpenAlgo-core's AGPL frontend (§10 imports only MIT pieces). Record the boundary + tag pin in `DECISIONS_LOG.md`. `openalgo/package-info.java` states the rule. |
| R2 | **OpenAlgo unified API flattens Kite-specific fields** (e.g. 20-level depth, Kite-only quote sub-fields). | Med / Med | Anti-corruption `openalgo/wire/` DTOs are `@JsonIgnoreProperties(ignoreUnknown=true)`; `OpenAlgoWireContractTest` asserts every CONSUMED field is present, and `OpenAlgoContractCanary` flags drift off the critical path. The §4 per-capability flag lets Kite-direct remain the source for any capability OpenAlgo flattens (e.g. keep `depth=kite`). |
| R3 | **Extra network hop adds latency on scalp execution** (OpenAlgo sits between ArthaYantra and broker). | Med / High (scalp slippage) | Fine for 1-min OI snapshots (confirmed). For order execution, MEASURE round-trip before committing; keep §4 able to route `orders=kite` direct if measured latency is unacceptable. Never put the §8 SPAN call on the order hot path (cache-first only). |
| R4 | **Scalp-backtest low fidelity** — 1-min OI snapshots + `ltp_slippage/v1` fills ≠ tick-level reality. | High / Med | Document fidelity limits in every scalp run's output (not silently). Treat scalp backtest as directional, not P&L-exact; reserve raptorbt (DEFERRED) as a future cross-check oracle. Forward/paper test (Phase 6) is the truth source for scalp. |
| R5 | **React migration regresses the oipulse pages** (§10) — subtle data/format/chart breakage vs Angular. | Med / High | Phase-4 page-parity checklist gate (one React route per Angular route, rendering live data). Regenerate the full Playwright + axe suite as the acceptance gate. Re-implement `core/decimal` and the `{items:[…]}` envelope handling explicitly; verify LWC `autoSize:true`. |
| R6 | **ExpiryTrack / Upstox-Plus data-mapping mismatch** (§5) — symbol/expiry/strike/timezone schema divergence on Parquet ingest. | Med / High (corrupt backtests) | M1 cross-check: sample strikes vs an independent source before declaring backfill good. Ingest validates IST bucketing + symbol normalization; reject rows that don't map. Optional V016 provenance column tags backfilled rows so they're auditable/re-loadable. |
| R7 | **openscreener scraping fragility** (§9) — Screener.in DOM/anti-bot changes break the Playwright scraper. | High / Low | §9 is OPTIONAL and behind a flag; §13 screener MUST run with it disabled (Phase-5 exit gate proves this). Fundamentals are nice-to-have, never blocking. |
| R8 | **Kite-fallback drift** — keeping Kite-direct alive per-capability (§4) lets the two source paths diverge silently (Kite mapper vs OpenAlgo mapper produce different rows). | Med / Med | Both paths map to the SAME domain port records; a contract/parity test asserts a Kite-sourced row and an OpenAlgo-sourced row for the same instrument are field-equal (schema-diff = none, the Phase-1 exit check). `ContractCanary` (Kite) + `OpenAlgoContractCanary` both run daily. |
| R9 | **Parity / golden-vector breakage** (§6 greeks, §14 wiring) — a new field or per-run value breaks byte-identical golden or paisa-parity. | Med / High | Frozen `GoldenSignalsJson.write()`; new `SignalEvent`/`Trade` fields ride the NON-serialized side-channel computed AT ENTRY (never per-run random). New greeks = ADD golden vectors, never edit existing. Gate every Phase-2/6 commit on `GoldenDeterminismTest` + BacktestParityTest + `Black76GoldenVectorTest`. |
| R10 | **New service skipped by sharded CI** — a new Java service (e.g. §3 if it became its own module) or the React app falls outside the CI matrix and its tests never run. | Med / High (silent) | §3 lives INSIDE `market-data-service` (existing shard) so it rides for free. Any genuinely new service MUST add a `ci-java.yml` matrix shard in the same PR (CLAUDE.md). The React app keeps/replaces `ci-frontend.yml` + `ci-e2e.yml`. |
| R11 | **Flyway checksum lock / calendar coverage** — editing an applied migration, or backfilling a year outside `libs/market-calendar`. | Med / Med | All schema changes are NEW suffix-versioned migrations (next free **V016**); never edit applied SQL. Extend `libs/market-calendar` BEFORE backfilling a 2024/2025 window (else 500 "calendar covers years […]"). |

---

### 16.5 Branch / commit / PR conventions (per CLAUDE.md)

- **Trunk-based, short-lived branches.** One stage = one branch; phase-per-commit; one final squash-merge PR. Branch names: `feat/|fix/|chore/|docs/<scope>`. Suggested per-phase branches: `feat/openalgo-spine` (P0), `feat/data-inflow` (P1), `feat/quant-libs` (P2), `feat/scalper-track2` (P3), `feat/react-migration` (P4), `feat/minervini-track1` (P5), `feat/backtest-wiring` (P6).
- **Conventional Commits**, scope = service/lib name, one commit per phase, e.g.:
  - `feat(market-data): OpenAlgoGateway behind QuoteGateway/CandleReader + wire DTOs`
  - `feat(market-data): per-capability source flag (kite|openalgo) routing`
  - `feat(black76-math): port opengreeks higher-order greeks + golden vectors`
  - `feat(strategy-signal): Siva two-candle-theory scalp sub-strategy`
  - `feat(ui): React oipulse OI-analysis pages + heatmap`
  - `chore(contracts): recapture OpenAPI for OpenAlgo + scalp endpoints`
- **Never push to `main`; squash-merge only.** A stage's single PR enumerates its phases, scope decisions, and any data caveats (mirror the Stage-G PR pattern).
- **Bash tool is bash, not PowerShell** — pass multi-line commit messages via `git commit -F -` with a heredoc (PS here-strings corrupt subjects). Commit-message footer:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; PR-body footer: `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- **Keep the Bash cwd at repo root** (the `guard-paths.py` PreToolUse hook resolves relative to cwd; a persisted `cd <subdir>` breaks every later Edit/Write). After any `*.json eol=lf` additions, `git add --renormalize`.
- **Per-PR CI must be green** across the relevant workflows: `ci-java.yml` (sharded; add a shard for any new service), `ci-contracts.yml` (recapture via `-Dcontracts.capture=true`, regen TS via `npx openapi-typescript@7`), `ci-frontend.yml` + `ci-e2e.yml` (React), `ci-migrations.yml` (Flyway validate — new V016+ only), `ci-optimizer.yml`. Gate e2e readiness on container healthchecks, not gateway HTTP (a 401 is the auth filter, not upstream unreadiness).
- **PHASE_GATES.md is the Friday-gate ritual input** — each phase's exit gate above is walked against the running mock stack at the phase boundary; an unchecked box extends the phase. Update PHASE_GATES.md's current-phase marker + checklist at each phase entry, and append phase outcomes to `docs/design/DECISIONS_LOG.md`.

---

## 17. Errata & Addendum (authoritative corrections from the review pass)

> **This section is AUTHORITATIVE.** It was produced by an adversarial review of §1–§16 (a completeness critic, a code-grounding critic with repo access, and an AGPL/sequencing critic). Where any body section conflicts with §17, **§17 wins.** Each item cites the body sections it corrects. 29 findings were raised (7 HIGH, 13 MEDIUM, 9 LOW); the structural ones are expanded into ready-to-implement specs below, then a full findings index closes the section.

### 17.1 Flyway version allocation (AUTHORITATIVE — supersedes every version number in §4, §5, §6, §9, §13, §16)

Verified against `deploy/flyway/` on 2026-06-19. **Lineage heads: `marketdata` = `V017__fundamentals.sql`, `strategy` = `V008__journal_entries.sql`, `backtest` = `V005__trade_symbol_levels.sql`, `admin` = `V001`.** Every `V015`/`V016` claim in §16 is WRONG. Applied migrations are checksum-locked (CLAUDE.md) → all of the below are NEW suffix-versioned files created via the `new-migration` skill. **Before writing any of these, run `flyway info` / check the dir again** — confirm the slot is still free. Allocate sequentially and DO NOT double-book a version across sections:

| Version (file) | Lineage | Purpose | Replaces the (wrong) mentions in |
|---|---|---|---|
| `V018__candles_source_enum.sql` | marketdata | Drop+recreate the `candles.source` CHECK to add `OPENALGO`, `EXPIRYTRACK`, `OPENCHART` | §4.8/§4.9 "free-text/VERIFY"; §16 "V016" |
| `V019__candles_3m_cagg.sql` | marketdata | `candles_3m` continuous aggregate (see §17.4) | (new — was missing entirely) |
| `V020__snapshot_provenance.sql` *(optional)* | marketdata | nullable `source` provenance col on `options_chain_snapshots`/`futures_oi_snapshots` for auditable backfill | §4.9 "V0NN"; §16 R6 "V016" |
| `V021__fundamentals_index.sql` *(optional)* | marketdata | partial index for openscreener reads (skip at single-owner scale) | §9 "V0NN/V018" |
| `V009__signals_scalper_cols.sql` | strategy | additive scalper signal columns (see §12.9) | §12.9 "V009" (already correct) |
| `V010__minervini_screen_results.sql` | strategy | Minervini daily screen-results table (see §17 note) | §13.5 "marketdata/V018" — **move to STRATEGY lineage** |

Note: §13 put `minervini_screen_results` in `marketdata/V018`; screen results are a strategy artifact → put them in the **strategy** lineage (`V010`) instead, freeing `marketdata/V018` for the `candles.source` enum which is the hard blocker. (If you keep it in marketdata, renumber — never collide.)

### 17.2 Client decision — RestClient for REST, SDK for WS + orders (reconciles the §3 ↔ §4 contradiction)

§3 says "use the OpenAlgo-Java SDK"; §4.2 says "hand-rolled RestClient, NOT the SDK." **Authoritative resolution:**
- **REST capture path (quotes, depth, history, optionchain, optiongreeks-mirror, instruments): hand-rolled `RestClient`** — mirrors the existing `kite/wire/` anti-corruption pattern and routes through the `KiteCallExecutor`-style rate-limiter/circuit-breaker so OpenAlgo calls get uniform throttling + WireMock testability. **Correction (verified 2026-06-19 against `C:\Trading\OpenAlgo-Java`, §19.1):** unlike the Kite SDK (whose `Routes._rootUrl` is pinned → unstubbable, the original CLAUDE.md reason), the OpenAlgo-Java SDK's base URL **is** settable (constructor/Builder), so WireMock-via-SDK was technically possible. The hand-roll decision still stands, but the load-bearing reason is **parity with the `kite/wire` typed-DTO + `ContractCanary` drift-detection pattern** — the SDK returns untyped `com.google.gson.JsonObject`, giving up field-level typing and the off-critical-path canary. (So even the Phase-3 WS/order SDK use will need a mapping layer.)
- **WebSocket streaming + ORDER placement: use the OpenAlgo-Java SDK** (`in.openalgo:openalgo:1.0.1`) — its WS client and order client are the value-add and are not on the throttled capture path.
- The SDK **remains a declared dependency** (decision 2 honoured) — we just don't use it for REST capture.
- **Unify the package + config namespace** (the two sections diverged): package = `…marketdata.openalgo.wire` (parallel to `kite/wire`); config prefix = **`artha.marketdata.source`** with per-capability keys `…source.quotes`, `…source.candles`, `…source.optionchain`, `…source.orders` each `kite|openalgo`. Drop the `artha.md.source.*` variant.

### 17.3 Order-Execution boundary — NEW first-class deliverable (decision 2; missing from §3/§12)

§12.5 only footnoted execution. Decision 2 requires order execution to route through OpenAlgo so the broker is swappable. Add this as a **Phase-3 deliverable**:
- **Domain port** `OrderGateway` (new): `place(OrderRequest)`, `modify`, `cancel`, `orderbook()`, `positions()`, `tradebook()`, `funds()` returning domain records (`OrderAck`, `OrderStatus`, `Position`, `Trade`, `Funds`) — no broker types leak through.
- **`OpenAlgoOrderGateway`** impl using the **SDK** order client (per §17.2); maps SDK responses → domain records.
- **Wire DTOs** under `…marketdata.openalgo.wire` for `placeorder`/`placesmartorder`/`optionsorder`/`modifyorder`/`cancelorder`/`orderbook`/`positionbook`/`tradebook`/`funds` (mirror every documented field, `@JsonIgnoreProperties(ignoreUnknown=true)`).
- **Contract test** `OpenAlgoOrderContractTest` + extend the `OpenAlgoContractCanary` with order-endpoint sentinels.
- **Config** `artha.marketdata.source.orders=kite|openalgo`; a `LiveOrderService` (strategy-signal) calls `OrderGateway`, NOT a gateway directly.
- **Latency gate (decision 2 caveat):** before routing live scalp orders through OpenAlgo, measure round-trip place-ack latency vs Kite-direct over ≥1 session; record in PHASE_GATES. The signal→pre-fill→**human-confirm**→place flow (§12) stays semi-auto first.

### 17.4 Two mandatory Phase-1 schema prerequisites (were missing/optional)

1. **`candles.source` CHECK migration (`V018`) — MANDATORY, not optional.** `candles.source` is CHECK-constrained in `V003__candles_hypertable.sql` (`IN ('KITE','TICK_AGG','MOCK','BACKFILL')`). OpenAlgo capture writing `source='OPENALGO'` (and ExpiryTrack/openchart backfill writing `'EXPIRYTRACK'`/`'OPENCHART'`) will **violate the constraint and 500 every insert** until this lands. **Hard entry-gate for any OpenAlgo capture and any §5 backfill.**
2. **`candles_3m` continuous aggregate (`V019`) — MANDATORY for the entire Siva scalper.** §7.5 established `3m` is NOT in the cagg set (only 5m/15m/1h/1d/1w) and every Siva sub-strategy executes on 3m. Add a `time_bucket('3 minutes', bucket, 'Asia/Kolkata')` cagg, IST-origin aligned to 09:15 (match the existing `V004` cagg origin). **VERIFY** 3m bucket reproducibility between live resampling (`LiveSeriesStore`) and backtest replay (parity). Schedule in **Phase 1**, as a hard prerequisite for Phase 3 (§12).

### 17.5 `libs/market-calendar` multi-year extension — Phase-1 entry gate (was a scattered footnote)

The intraday-OI-history crux (decision 3) and any 2024/2025 backtest depend on this and it had no owner. **Concrete deliverable, placed at the TOP of Phase 1 (before §5 ingest and §14 backtests):**
- MODIFY `libs/market-calendar/src/main/resources/nse-trading-holidays.csv` to add every year the ExpiryTrack/openchart backfill spans (and the forward year).
- ADD a `MarketCalendarTest` asserting `coveredYears() ⊇ {those years}`.
- **Hard ENTRY gate** for §5 ingest validation and §14 historical backtests (else the documented `500 "NSE holiday calendar covers years […]"` fires). Source the holiday dates from the NSE trading-holiday circular for each year.

### 17.6 black76-math golden vectors + greek scope (corrects §6)

- **Single source-of-truth fixture:** `libs/black76-math/src/test/resources/black76-golden-vectors.json`. §6's claim of a SECOND source copy under `services/market-data-service/src/test/resources/` is WRONG — that path only exists as a `target/test-classes` build artifact Maven copies from the lib. **Delete the "copy bytes to both source locations / guard the duplicate fixture" steps.** Before trusting `tools/greeks-vectors/generate.py`, read its actual `OUT` constant. No manual dual-sync exists or is needed.
- **Greek v1 scope (Working-Principle #2 — no speculative code):** §12.6 says the scalper v1 needs only **delta** (strike selection) + **vega/IV** (IV-band gate), and §11 shows no higher-order greek display. **Scope the §6 v1 port to the first-order set (delta/gamma/theta/vega/rho/IV) only.** DEFER the 10 higher-order greeks (vanna/charm/vomma/speed/zomma/color/veta/ultima/dual-delta/dual-gamma) until a named consumer exists — do not build 10 greeks with no caller. (Keep the math design notes for when one appears.)

### 17.7 Data-grain reconciliations (corrects §5 ↔ §13 ↔ §14)

- **ExpiryTrack ingest grain → downsample 1m to 5m slot-aligned on ingest.** `SnapshotPremiumReader.NATIVE_GRANULARITY` is hard-pinned to 5 minutes and `checkArchiveCoverage` counts only `epoch%300==0` slot-aligned rows (75 slots/session). Raw 1-min ExpiryTrack rows would NOT satisfy the coverage gate → §14 scalp backtests 422 `DATA_GAP`. **§5 must write 5-min slot-aligned snapshots** (downsample on ingest), unless/until the reader's granularity is generalized. State the chosen grain in §5 so §14's reader assumption matches.
- **openchart EOD lands in `nse_eod_bhavcopy` (series `EQ`), NOT `candles@1d`.** §13 reads `nse_eod_bhavcopy` (dense ~3.2k symbols); §5.B wrote openchart into `candles@1d` (which doesn't even flow into the `candles_1d` cagg). Reconcile: **§5.B writes openchart history into `nse_eod_bhavcopy`** to match what the Minervini screener (§13) reads. Remove §5.B.4's contradictory "assumes native candles@1d" note. (If both feeds are wanted, §13 unions them — but pick one and state it in both sections.)

### 17.8 OpenAlgo sandbox ↔ ArthaYantra profile coupling (corrects §2.4 — mock/live isolation)

§2.4 left OpenAlgo's sandbox-vs-live as a manual `deploy/openalgo/.env` edit, uncoupled from `SPRING_PROFILES_ACTIVE`. ArthaYantra's mock profile MUST never touch a real broker (CLAUDE.md). A forgotten `.env` flip would silently breach mock/live isolation. **Resolution:** couple it in `ay.ps1` — derive an `OPENALGO_ANALYZER`/sandbox flag from the profile (mirroring `ARTHA_DB_NAME`/`ARTHA_REDIS_DB`) so `profile=mock ⇒ OpenAlgo in sandbox/analyzer mode`. Add a **Phase-0 VERIFY** that probes OpenAlgo `/health` and asserts sandbox mode whenever ArthaYantra is mock. Do not leave it manual.

### 17.9 Greeks come from black76-math ONLY — OpenAlgo `optiongreeks` is mirror-only (decision 5)

§3.2 mirrors OpenAlgo's `optiongreeks` as a wire DTO. **Add an explicit prohibition + VERIFY:** `OpenAlgoGreeksResponse` is consumed by the **contract canary ONLY** (drift detection). `OptionsChainService` and the backtest replay MUST compute IV/greeks via `libs/black76-math` (§6) exclusively — no production/replay code path may read OpenAlgo greek *values*, or a network hop enters the deterministic path and breaks parity. Add an ArchUnit/architecture test or a code-review checklist item enforcing it.

### 17.10 AGPL containment hardening (corrects §2.5, §15a)

- **A patched OpenAlgo triggers the AGPL §13 source-offer obligation — regardless of single-tenancy.** §2.5's "still AGPL-clean because no AGPL source lands in our repo" is the WRONG test. The owner exposes the stack to a phone via Tailscale = a *network* use. If OpenAlgo is ever modified and served, the **modified Corresponding Source must be published and a source link surfaced to network users** of that instance. **Preferred rule: never patch — wait for an upstream release.** Keep the mirror-fork-patch path as a documented exception that explicitly carries the source-offer requirement.
- **openalgo-mcp (§15a):** install only from PyPI/npm via `uvx`/`npx` (never a vendored in-repo source checkout); verify its license AND dependency tree first; if it transitively bundles AGPL OpenAlgo code, keep it entirely under global `~/.claude` (NOT project `.mcp.json`). Extend §15e's "non-product" grep guard to also assert no AGPL-licensed package is vendored under `.claude/` or referenced by a local path in `.mcp.json`.

### 17.11 Phase re-sequencing (corrects the §16 dependency graph)

- **Move to Phase 1 (data):** `V018` candles-source enum (§17.4), `V019` candles_3m cagg (§17.4), and the `market-calendar` year extension (§17.5). These gate later phases.
- **Phase-1 ENTRY gate add:** `OpenAlgoContractCanary` must be **GREEN against the pinned LIVE appliance image** (not just WireMock) — incl. the per-strike `chain[].ce.oi`/`pe.oi` sentinel — and the OI-coverage canary (`sum(oi)>0`) must pass live **before** any `source.*` flag flips to `openalgo`. The irreplaceable `options_chain_snapshots` capture must not be cut over on an unverified live contract.
- **Split Phase 4 (React):** **4a** = React shell + oipulse OI pages (§11) + cockpit pages that depend ONLY on already-built endpoints — entry gate = OI-spine endpoints stable, **no Phase-3 dependency** (corrects §16 putting all of React after Phase 3). **4b** = scalper cockpit / order ticket / Connect-the-Dots dashboard — entry gate = Phase 3 (§12) emitting signals. Update the §16 graph so §10/§11 do not hang off §12 wholesale.
- **Phase-3 exit-gate note:** historical scalp **backtests** are OUT of scope in Phase 3 — they require Phase-1 §5 data + the §17.5 calendar extension and land in Phase 6. Phase 3 validates via unit-fired signals + live paper only.
- **CI during the React cutover:** keep `ci-frontend.yml` (Angular) green until the React suite reaches parity; add a parallel React lint/test/build + e2e shard from the moment `frontend-react/` exists; delete the Angular shard + `frontend-ui` ONLY at the atomic route flip.

### 17.12 Minor grounding corrections (apply inline where cited)

- **`suggested_qty`** is added by **`strategy/V006__paper_account_risk.sql`**, NOT `V003__signals.sql` (§4.1/§12 mis-cite V003). The new scalper columns correctly go in `strategy/V009` (§17.1).
- **IV endpoint** is `GET /api/v1/market/options/iv-history` (+ `POST /iv-rollup`), served by `IvAnalyticsController` sharing the `/api/v1/market/options` mapping — there is no `/iv-analysis` or `/iv-*` prefix (corrects §11.1/§11.8).
- **`KiteCallExecutor.Family`** is `HISTORICAL/QUOTE/DUMP/MISC` with a per-member `limiterName`. Adding `Family.OPENALGO` also requires registering the matching resilience4j limiter instance (e.g. `openalgo`) in `application.yml` (mirror `kite-quote`/`kite-historical`), not just an enum constant (corrects §3.7/§12.0).
- **MIT attributions** (opengreeks, marginism, openalgo-heatmap, pyindicators) go in the existing **`docs/LEGAL.md`** (which already logs lightweight-charts/Apache-2.0), NOT a new repo-root `NOTICE` file (corrects §6.6/§8.7).
- **openscreener deployment form:** ONE form — a host-scheduled scrape job (`tools/fundamentals-refresh/`); §9 and §13 must agree. The `FundamentalsReader.java` + the `ScreenerService` bolt-on filter are the SAME deliverable, built together only when the optional fundamentals flag is enabled (corrects §9/§13 mismatch).
- **SPAN `.spn` source (§8):** pin the concrete NSE Clearing source (the `PR_`/SPAN zip) with the required UA/cookie handshake (same anti-bot pattern as the working `NseHttpClient`), and emit an **ntfy staleness ALERT** when the fetched `spnDate` is older than the latest trading day (not just a `/health` field). Document the two auth paths explicitly: the **Java `MarginServiceClient` calls `margin-service` directly intra-network (no gateway/XSRF)**; only the React widget goes through the gateway route.
- **Phase-2 parallelism caveat (§16):** Phase 2 (greeks/indicators) branches must NOT also edit `market-data-service` capture code (Phase 1's surface); regenerate + commit golden/reference vectors within the Phase-2 branch to preserve byte-identical parity before merge.

### 17.13 Full findings index (traceability)

| # | Sev | Critic | Section(s) | Resolution → |
|---|-----|--------|-----------|--------------|
| 1 | HIGH | completeness | §3/§12/§16 | Order-execution boundary spec → **§17.3** |
| 2 | HIGH | completeness | §4/§5/§14 | `candles.source` CHECK migration mandatory → **§17.4.1** |
| 3 | HIGH | completeness | §3/§16 | SDK-vs-RestClient reconciled → **§17.2** |
| 4 | HIGH | completeness | §16/§5/§14 | market-calendar multi-year owner step → **§17.5** |
| 5 | HIGH | grounding | §6 | phantom 2nd golden fixture removed → **§17.6** |
| 6 | HIGH | grounding | §16 | Flyway V016→V018; heads corrected → **§17.1** |
| 7 | HIGH | license-seq | §16/§4/§5/§6/§9/§13 | version allocation table + CHECK definitive → **§17.1/§17.4** |
| 8 | MED | completeness | §5/§14 | ExpiryTrack 1m→5m slot-align → **§17.7** |
| 9 | MED | completeness | §12/§7 | `candles_3m` cagg prerequisite → **§17.4.2** |
| 10 | MED | completeness | §5/§13 | openchart → `nse_eod_bhavcopy` → **§17.7** |
| 11 | MED | completeness | §8 | `.spn` source + dual auth path → **§17.12** |
| 12 | MED | completeness | §2/§16 | sandbox↔profile coupling → **§17.8** |
| 13 | MED | completeness | §3/§6 | OpenAlgo greeks mirror-only → **§17.9** |
| 14 | MED | grounding | §4.9 | provenance migration version pinned → **§17.1** |
| 15 | MED | grounding | §4.1/§12 | `suggested_qty` → strategy/V006 → **§17.12** |
| 16 | MED | license-seq | §2.5 | AGPL §13 source-offer on patch → **§17.10** |
| 17 | MED | license-seq | §16 P0/P1 | live-canary gate before cutover → **§17.11** |
| 18 | MED | license-seq | §16 P4/§11 | split Phase 4a/4b → **§17.11** |
| 19 | MED | license-seq | §16 P6/§14 | scalp backtest is Phase 6, calendar to P1 → **§17.11/§17.5** |
| 20 | MED | license-seq | §15a | openalgo-mcp AGPL boundary → **§17.10** |
| 21 | LOW | completeness | §9/§13 | openscreener single form + shared deliverable → **§17.12** |
| 22 | LOW | completeness | §10/§16 | dual-frontend CI coverage → **§17.11** |
| 23 | LOW | completeness | §12/§6 | greeks v1 scope = first-order → **§17.6** |
| 24 | LOW | grounding | §3.7/§12 | `Family.OPENALGO` needs limiter instance → **§17.12** |
| 25 | LOW | grounding | §11 | IV endpoint = `…/options/iv-history` → **§17.12** |
| 26 | LOW | grounding | §1/§4 | `candles.source` is CHECK (V003), not free-text → **§17.4.1** |
| 27 | LOW | license-seq | §6.6/§8.7 | attributions → `docs/LEGAL.md` → **§17.12** |
| 28 | LOW | license-seq | §3/§4 | unify package + config namespace → **§17.2** |
| 29 | LOW | license-seq | §16 P2 | Phase-2 must not touch capture code → **§17.12** |

## 18. Gap Addendum (cross-check pass 2026-06-19 — AUTHORITATIVE, same standing as §17)

> **This section is AUTHORITATIVE** (co-equal with §17; where §1–§16 conflict, §17 and §18 win). It was produced by a second cross-check of the whole plan against the owner's stated GOALS + the project memory ([[openalgo-ecosystem-integration]], [[scalping-goal-and-data-architecture]], [[oipulse-study]]). §17 fixed 29 *internal* defects; §18 closes 7 *goal-vs-plan* gaps the section-writers never surfaced. 4 are real additions, 3 are fix-in-place clarity/verify items. Each cites the section it amends.

### 18.1 Live order-book / positions / funds page (NEW React page — amends §10.6, §11.1, §17.3)

§17.3 added the `OrderGateway` backend (`orderbook()`/`positions()/tradebook()/funds()` domain records) but the React page inventory plans only the **pre-fill order ticket** (§12.5 step 4) and the **paper ledger** (§10.6). For LIVE semi-auto scalping the owner must *see* live order status, open positions, and funds after placing through OpenAlgo — there is no display surface for it.

- **CREATE** a React page `frontend-react/src/pages/orders/OrdersPage.tsx` at route `/orders` (add to the §10.6 inventory + the AppShell nav, after "Paper"). Three sections: **Open positions** (`OrderGateway.positions()` → symbol, side, qty, avg, LTP, MTM P&L via `subtractDecimal`/`multiplyByInt`), **Order book** (`orderbook()` → status/side/qty/price/time), **Funds** (`funds()` → available/utilised margin). Decimals as JSON strings → `core/decimal`.
- **Backend surface (NEW):** §17.3's `OrderGateway` needs read endpoints to feed this page. Add `GET /api/v1/orders/{orderbook,positions,tradebook,funds}` in `strategy-signal-service` (or edge-gateway-routed to it), `{items}` envelope for the list ones, decimals as strings. These DO drift the springdoc spec → re-capture (`ContractCaptureTest -Dcontracts.capture=true`) + regen `contracts/gen/*.d.ts`.
- **Source flag:** when `artha.marketdata.source.orders=openalgo` the data comes from `OpenAlgoOrderGateway`; in mock/paper it reflects the ArthaYantra paper ledger (no live broker). Live mode polls or subscribes (the OpenAlgo WS order-update channel, §17.2 SDK path) for status changes.
- **Sequencing:** **Phase 4b** (the scalper-cockpit React split, §17.11) — entry-gate = §17.3 `OrderGateway` exists (Phase 3). Until then the `dev-tools` `openalgo-publish` forwarder (§2.4, loopback `:5001`) is the stopgap to watch live orders in OpenAlgo's own UI.
- **VERIFY:** in live dry-run, place a sandbox order via "Take" → it appears in `/orders` open-orders within one poll/WS tick; funds reflect the margin block; mock shows the paper position, never a real broker order.

### 18.2 Long-term investing — explicit future Track 3 (amends §1a; owner goal #3)

The owner's three goals are options scalping (Track 2 §12), Indian momentum/swing (Track 1 §13), **and future long-term investing**. The plan has no placeholder for the third — it must be parked, not silently dropped.

- **Track 3 — Long-Term Investing (Indian equities), DEFERRED/FUTURE.** Buildable core when started: the **already-existing `long_term` preset** in `ScreenerService` (quality + value + trend filters over `nse_eod_bhavcopy`/`candles_1d`) **plus** the §9 openscreener **fundamentals** as a *first-class* gate (not just a bolt-on confirmation as in Track 1) — durable ROCE/ROE, low debt, consistent EPS/sales CAGR, reasonable valuation. Reads the same `marketdata.fundamentals` tall table (§9.0). Long-hold ⇒ no scalp/SPAN/OI machinery; rides Track-1's daily EOD spine entirely.
- **Why parked, not built:** owner marked it "future"; building it now is speculative (Working-Principle #2). One line in §1a's "what this plan delivers" should name it as the explicit Track 3 so a later session extends `long_term` + fundamentals rather than re-deriving the goal.
- **No new infra:** EOD bhavcopy + openchart history + openscreener fundamentals (all already in this plan for Track 1) fully cover it. **No code in this plan.**

### 18.3 Always-on host — deployment prerequisite for LIVE systematic scalping (amends §1b, §16 Phase 3/6)

[[scalping-goal-and-data-architecture]] names the always-on host **"the gating decision"** for systematic scalping: OI capture from 09:15 + low-latency execution across the whole session is impossible on a laptop that joins late or sleeps. The plan assumes one Windows box and is silent on this — Phases 3 and 6 (live scalp) implicitly require it.

- **State as a hard operational prerequisite** (add to §16 Phase-3 ENTRY gate + §1b): live systematic Track-2 scalping requires a host that is up and Kite/OpenAlgo-session-armed the full session (09:15–15:30 IST). Laptop-only / intermittent ⇒ **discretionary-manual only**, NOT systematic auto/semi-auto — the signal engine can still run, but missed-capture gaps make the OI spine unreliable for live decisions.
- **Implications already partly handled:** `SubscriptionRegistry` is Redis-persisted (survives restart, [[live-mode-findings]]); the daily Kite token must be re-armed each morning (the `kite.FeedRearm` seam). The always-on host is what makes those continuous. **Backtest/forward (Phase 6) and Track-1 daily screener do NOT need it** — only live intraday scalp does.
- **No code** — a deployment note in §16 + `PHASE_GATES.md`. Owner decides the host (mini-PC / always-on desktop / VPS near NSE colo for latency — the §17.3 latency gate informs this).

### 18.4 Scalp signal → phone push via the notifier (amends §12.5; enhancement)

The notifier (ntfy/telegram, Stage E [[stage-e-progress]]) exists and §1b plans phone access over Tailscale, but §12.5's semi-auto flow assumes the owner is watching the screen to click "Take." For scalping while away, the fresh signal must reach the phone so the owner can act.

- **Wire scalp ENTRY emission → notifier push.** In `strategy-signal-service`, on a scalper `emitEntry` (the §12.3 confluence-gate path), publish a notifier event (reuse the existing ntfy/telegram client + `ARTHA_NTFY_TOPIC`) carrying: strategy, underlying, option symbol, side, entry, SL, target, `suggested_qty`, confluence summary. Behind a flag `artha.scalper.notify-on-signal` (default on for live). Include a deep link to the `/orders` pre-fill ticket (§18.1) so the owner confirms from the phone.
- **Rate/dedupe:** one push per signal id (idempotent), respect the §12.7 daily-loss/kill-switch (no pushes once ENTRY is paused). Keep it OFF the deterministic replay path (notify only in live, never in backtest — a side effect, never computed into a signal field; parity-safe by construction).
- **VERIFY:** mock-stack walk — force a bullish bar, assert one ntfy/telegram message fires with the option symbol + qty; a second identical signal id does not double-fire; backtest replay sends nothing.

### 18.5 §3 and §4 are ONE deliverable, not two (amends §3.0, §4.2, §17.2)

§3 ("OpenAlgoGateway Adapter") and §4 ("Market-Data Routing Migration") independently specify an OpenAlgo gateway + full-mirror wire DTOs + `OpenAlgoConfig` + a contract test — §3 in package `marketdata.openalgo`, §4 in `kite/openalgo`, with different config prefixes (`artha.openalgo`/`artha.marketdata.source` vs `artha.md.source`). §17.2 unified the *namespace* and the SDK-vs-RestClient question but did NOT merge the two file lists, so a literal reader builds two parallel `OpenAlgoQuoteGateway`s.

- **AUTHORITATIVE consolidation:** §3 and §4 are the SAME deliverable, sequenced across two phases, in ONE package `services/market-data-service/.../marketdata/openalgo/` (with `…/wire/`). **§3 = Phase 0** (stand the gateway + wire DTOs + contract test up behind the ports; default source stays Kite). **§4 = Phase 1** (add the per-capability `artha.marketdata.source.*` flag + flip routing + OI-coverage canary). There is exactly **one** `OpenAlgoQuoteGateway`, one `OpenAlgoHistoricalCandleGateway`, one `OpenAlgoOptionChainGateway`, one wire package, one `OpenAlgoConfig`, one `OpenAlgoWireContractTest`, one `OpenAlgoContractCanary` + manifest.
- **Discard the duplicated/conflicting bits:** the §4 `kite/openalgo/` path and the `artha.md.source.*` prefix are SUPERSEDED (use §17.2's `marketdata.openalgo` + `artha.marketdata.source.*`). Where §3 and §4 give different field/DTO details for the same endpoint, §4's are the more grounded (it read the live consumers) — reconcile to §4's mappings inside the §17.2 namespace.

### 18.6 Active-Strike sentiment% formula — reconcile before §12 consumes it (amends §12.0, §12.1)

[[oipulse-study]] fitted the EXACT oipulse formula from live data: **`Sentiment% = (ΣPut OI − ΣCall OI) / ΣPut OI × 100`**. The plan's §12.0 reader table cites a *different* basis: `100·(ΣpeΔOI − ΣceΔOI)/Σ(ceOi + peOi)` (uses ΔOI not OI levels; denominator is total OI not ΣPut OI). The §12.1 OI-quadrant gate and the §12.3 Connect-the-Dots scorer both consume `sentimentPct` — if the built `ActiveStrikeService` uses the non-oipulse basis, the gate thresholds the Siva cheat-sheet quotes (against oipulse's reading) are mis-calibrated.

- **VERIFY-and-reconcile (Phase-3 entry task):** read the actual `ActiveStrikeService.sentimentPct` implementation. Decide which metric the Siva gates need — oipulse-EXACT (`(ΣPut−ΣCall)/ΣPut×100`, level-based) vs the ΔOI-based one already built. They are different signals (one is a standing put/call OI skew, the other an interval-flow skew); the cheat sheet's numbers were read off oipulse, so the **EXACT level-based formula is the reference** for any threshold lifted from oipulse. If the built service differs and §12 needs the oipulse one, add it as a *new* method (don't silently change the existing one — other pages may depend on it) and point the scalper gate at the new method.
- This is a calibration correctness item, not a schema change. Resolve it before trusting any sentiment-threshold gate live.

### 18.7 React migration is 1:1 with CURRENT Angular, not full oipulse (amends §11.0 scope)

§11 explicitly re-creates the **10 existing Angular `/oi/*` + `/market/*` pages** 1:1. Several oipulse features that were *studied* but never built in the Angular app therefore stay unbuilt after the React cutover: **multiple-OI-chart** (`OD_OPT_CHART`), **calendar-spread Add-Position** (`CALENDAR_SPREAD_OPT`), the **Advance Chart** (oipulse's TradingView + OSPL Pine studies; ArthaYantra substitutes Lightweight Charts + the §7 ported indicators), and the **Investing.com-iframe dashboard**. ([[oipulse-study]] Phase-B decoded these socket channels.)

- **Scope clarification (not a defect):** the React migration completes *current* parity; full 53-page oipulse replication is **separate future work**, not in this plan. The §6/§7/§12 indicator + Connect-the-Dots work already captures the *analytical* substance of the OSPL/Advance-Chart studies (OSPL Signal ≈ SuperTrend(10,2) direction + Void structure-stop + volume-confirmed entries — [[oipulse-study]]); only the TradingView-native *rendering* and the two extra socket-driven chart pages are deferred.
- **Action:** one scope line in §11.0 stating "1:1 with current Angular; oipulse pages not yet in Angular (multiple-OI-chart, calendar-spread, Advance-Chart/TradingView, dashboard) are future work." Future-implementer note: multiple-OI-chart + calendar-spread are **socket-driven** (subscribe only after strike/position selected), per [[oipulse-study]] — wire them to the WS bridge (§10.4), not REST.

### 18.8 Findings index (this pass)

| # | Type | Amends | Resolution |
|---|------|--------|------------|
| 18.1 | NEW feature | §10.6/§11.1/§17.3 | live order-book/positions/funds React page + read endpoints (Phase 4b) |
| 18.2 | NEW (park) | §1a | explicit future Track 3 = long-term investing (`long_term` preset + fundamentals; no code now) |
| 18.3 | Prerequisite | §1b/§16 P3 | always-on host = hard op gate for live systematic scalp (deployment note) |
| 18.4 | Enhancement | §12.5 | scalp ENTRY → ntfy/telegram push + deep link to order ticket (live-only, parity-safe) |
| 18.5 | Clarity | §3/§4/§17.2 | §3+§4 = ONE gateway deliverable in `marketdata.openalgo`; discard the `kite/openalgo`+`artha.md.source` duplicate |
| 18.6 | Verify | §12.0/§12.1 | reconcile Active-Strike sentiment% vs oipulse EXACT before scalper gates use it |
| 18.7 | Scope | §11.0 | React = 1:1 with current Angular; extra oipulse pages are future work |

## 19. Process addenda — local source references & per-phase manual test guides (AUTHORITATIVE)

> Added 2026-06-19 after Phase 0. Same standing as §17/§18.

### 19.1 Local reference checkouts (consult these BEFORE hitting GitHub)

The owner has checked out the upstream sources locally so a session can read them directly instead
of repeatedly fetching from GitHub. **These are the authoritative reference for library facts** — when
this plan's description of an OpenAlgo wire shape / exchange code / symbol format / SDK API conflicts
with the checkout, **the checkout wins** (the plan was written from docs that may lag the code).

| Path | What it is | Use it to confirm |
|---|---|---|
| `C:\Trading\openalgo` | OpenAlgo platform source (Python/Flask) | REST request/response shapes (`restx_api/`, `blueprints/`, `services/`, `schemas`), exchange codes, analyzer/sandbox toggle, `start.sh` boot, healthcheck route, rate limits |
| `C:\Trading\openalgo-docs` | OpenAlgo documentation + API reference | `api-documentation/`, `symbol-format.md`, broker connect guides — the human-readable contract |
| `C:\Trading\OpenAlgo-Java` | OpenAlgo-Java SDK source | Maven coordinates (`pom.xml`), client API surface, whether base-URL is configurable (WS + order path, §17.2) |

**Rule:** before implementing or changing any OpenAlgo-touching code (§2–§5, §17.2/§17.3), grep/read
the relevant file in these checkouts to ground the field names + shapes. Wire DTOs and the
`OpenAlgoExchange` translator MUST match what the source actually emits, not what this plan guessed.

### 19.2 Checking out future upstream repos

Before incorporating ANY `https://github.com/marketcalls/*` repo (or other upstream we port/appliance
— opengreeks, pyindicators, marginism, openalgo-heatmap, ExpiryTrack, openchart, raptorbt, …),
**`git clone` it into `C:\Trading\<repo>`** first so the source is a handy local reference (mirrors
the three checkouts above). Note the local path in the relevant section + in project memory.

### 19.3 Per-phase manual testing guide (deployable phases)

After completing each phase, if that phase produced something the owner can run/observe, **create a
manual testing guide at `docs/manual-tests/phase-<N>-<slug>.md`** that lets the owner verify the
phase's deliverables by hand. It should cover: prerequisites, exact commands (PowerShell-first, this
box), what to click/observe, expected results, and how to tear down. Phase 0's guide is
`docs/manual-tests/phase-0-openalgo-spine.md`. A non-deployable / pure-library phase (e.g. §6 greeks)
can note "no manual surface — verified by tests" instead.

## 20. Phase 4 — React + oipulse Replication: Grilled Plan (2026-06-21, AUTHORITATIVE)

> Status: PLAN (no code changed). Produced by a per-decision grilling session with the owner on
> 2026-06-21. **Same standing as §17/§18/§19. Where §10/§11/§16-Phase-4/§17.11/§18.7 conflict with
> §20, §20 wins.** The React migration is now DRIVEN BY oipulse replication, built component-first,
> sequenced by scalping value. Decisions below are owner-confirmed unless marked "rec".

### 20.0 Headline reframe (what this supersedes)
- **§18.7 "React = 1:1 current Angular, oipulse deferred" → SUPERSEDED.** oipulse replication is the
  PRIORITY driver. Any page that exists in the oipulse study is built UNDER oipulse replication (to
  oipulse fidelity on the shared component library), NOT as a 1:1 Angular port. Only Angular-native
  cockpit pages with no oipulse equivalent are ported separately, AFTER the oipulse waves.
- **§16 "Phase 4a = zero backend changes" → SUPERSEDED.** Read-only analytics backend is built
  per-wave, just-in-time for that wave's pages. 4b live-trading (orders/positions/funds, §18.1) stays
  PARKED.
- **§11/§10 `OiControlBar` (showName/showExpiry) → generalized to `FilterBar`** (config-driven, fed by
  a shared cascade hook; more knobs).
- **§11/§18.7 "Advance Chart = LWC substitute" → openalgo-chart (MIT, React, lightweight-charts)**
  adopted as the single advanced-chart basis (covers Advance Chart + Multiframe + builder tools +
  option-chain-with-greeks + OI bars). The old app `C:\Trading\ArthaYantra\artha-yantra` ships a
  legitimately-obtained TradingView Advanced Charts binary + a working backend datafeed adapter —
  kept as the DOCUMENTED FALLBACK if openalgo-chart proves insufficient (license: Personal/
  Non-commercial → gitignore the binary, never redistribute).
- **Cutover "atomic flip at Angular parity" → flip at the very END** (after all oipulse waves + the
  cockpit ports); Angular stays the rollback throughout (gateway can't path-split two SPAs).

### 20.1 Stack & architecture (owner-confirmed)
- Stack: React 19 · Vite 6 · Tailwind 4 (`data-theme` token sets) · shadcn/ui · React Router v7 ·
  TanStack Query v5 · Zustand · ECharts · lightweight-charts · CodeMirror 6. New dir `frontend-react/`.
- **Component model = 3 tiers** (the oipulse study collapses 53 pages into a small reused set):
  - **9 page archetypes** — mirrored CSP table · 4-quadrant OI scanner · treemap heatmap · combo
    candle+line · dual-axis line · net-value bars · filter+table · TradingView/LWC widget · interactive
    builder · signal matrix.
  - **4 composites** — `FilterBar` (config-driven + shared cascade hook Instrument→Date→Expiry→Strike) ·
    `DataTable` (per-page column registry + TanStack Virtual) · `EChart` wrapper · `LwcChart` wrapper.
  - **~15 atoms** — ModeToggle · GoButton · TickerStrip · SubTabs · DatePicker · Pagination ·
    InstrumentSelect(+symbol adapter) · ExpirySelect(+format adapter) · StrikeSelect ·
    IntervalSelect(`allowedIntervals`) · SearchBox(free-text + autocomplete) · IndexSectorSelect ·
    OiBadge4 · SentimentBadge3 · ValueDeltaCell · ChartLegend/visualMap.
  - **Leaky-but-shared rule:** Instrument/Expiry/Interval vary by DATA (lists, formats, allowed sets),
    not behaviour → one component + per-page config/adapter, never copy-paste.
  - **One-offs (inline, no extraction):** strike-window selector · custom-time picker · period selector ·
    payoff/leg-builder/greeks · risk cards · amCharts world map · draw-tools/audio/OI-bar.
- **Shell = hybrid:** ArthaYantra topbar (IST clock · mock-mode tag · WS pill · theme picker · logout)
  + oipulse "All Menu" mega-dropdown (grouped by section) + sub-tab row + live ticker strip (wired to
  our WS). Mega-menu → hamburger drawer on mobile.
- **Mobile = adaptive both, baked in from PR-F.** Component-first makes it affordable: atoms / FilterBar
  / mega-menu / charts are responsive-once; only ~3-4 DENSE archetypes (CSP 16-col table, Options Chain
  45-col, treemap) need a real mobile VARIANT (tab CE/PE, card-per-strike, top-N). Target device =
  Samsung S24 Ultra → CSS breakpoints **~480px portrait / ~915px landscape** (NOT its 1440 physical px;
  DPR ~3). Favour vector/SVG + scalable charts. Retrofitting later is far costlier → decided up front.
- **Themes:** curated NAMED `--ay-*` token sets (Dark, Light, OiPulse-Red #c42b1e, Midnight-Blue,
  High-Contrast, extensible), `data-theme` on `<html>`, Settings picker, persisted; shadcn + Tailwind
  alias `--ay-*` so the whole app follows; each theme WCAG ≥4.5:1.
- **Live data = hybrid:** WS (existing tick bridge) drives ticker / underlying LTP-spot-DH-DL header /
  price-flash; OI tables stay REST, auto-refreshing on the 3-min capture cadence (or Go). No new WS OI
  push channel (OI is snapshot-grained → live push adds ~zero over a 3-min poll).
- **Routes:** section-based mirroring the mega-menu (`/options/* /futures/* /equity/* /fii-dii/*
  /features/* /cockpit/*`); old `/oi/*` → redirect. (rec)
- **Greeks:** option-chain display greeks computed **server-side via black76-math** in `/chain` (single
  source, §17.9 — OpenAlgo greeks stay canary-only; do NOT use openalgo-chart's client-side greeks). (rec)
- Dashboard: keep AY cockpit dashboard; drop oipulse's Investing.com-iframe Dashboard. Degradation/
  staleness badge ported. PWA = optional nice-to-have, not in scope. (rec)

### 20.2 Backend capability audit (2026-06-21 — what already exists vs gaps)
Mapped the oipulse 53-page data needs against existing endpoints. **~18 pages fully backed** (all 10
current Angular OI/market pages + Options oi-stats/trending/active-strikes-OI/chain-table, Futures
movers, all FII/DII). **~18 partial** (data/engine exists, needs endpoint wiring/overlay): greeks-in-
chain, Connecting-Dots read endpoint (engine `ConnectTheDotsScorer` exists, no controller), Active-
Strikes-IV, Interval-wise OI, Options/Futures OI-Chart, Trending-OI-PA, Multiple-OI, Straddle/Strangle,
Open-High/OI-Expiry, Index-Contribution, Sector-Stats, Delivery, Equity-Open-High, Vix (India VIX
captured as a pinned index, no endpoint), Advance Chart. **~10-12 missing** (no data/endpoint): Futures/
Equity pre-open, Sector-Heatmap, Equity-Returns, Announcement, World Indices, the 3 builder tools, +
static/utility. Wave 1's scalping core is almost fully backed; gaps concentrate in later waves.

### 20.3 Sequence — PRs to main (each green; `frontend-react/` NOT gateway-wired until the final flip)
Owner chose a SEQUENCE of smaller PRs (65 routes is unreviewable in one).
- **PR-F Foundation** — skeleton (Vite/Tailwind/shadcn/RR-v7/TanStack/Zustand, eslint no-explicit-any +
  jsx-a11y + LWC-containment, `gen:api`→`contracts/gen`) + hybrid shell + multi-theme system + API
  client (XSRF, `credentials`, error-envelope→toast, 422-suppress, `{items}` helper) + auth (session
  store/RequireAuth/LoginPage; probe() seeds XSRF before any POST) + WS singleton (`nextReconnectDelay`,
  refcount `wsTopic`, conflation, reconnect→`invalidateQueries`) + shared pure-TS ports (+specs) +
  **anchor-driven** component library (only what the anchor page exercises — ~12 atoms + FilterBar +
  cascade hook + DataTable + EChart + `symbolContext`/`useExpiries`; remaining atoms added by their
  first wave) + **anchor page = Options OI Analysis** (mirrored CSP table, with its mobile variant).
  Done = lint+test+build green · e2e (login+shell+anchor) + axe at desktop & ~480px mobile vs mock ·
  `ci-react` shard added.
- **PR-W1 Wave 1 (tight, minimum viable scalping cockpit)** — Options OI Spurt (4-quadrant scanner),
  Options Chain (+greeks), Connecting Dots (signal matrix + SentimentBadge3), Straddle/Strangle premium
  (combo candle+line). **2 backend tasks:** greeks-in-chain (black76 server-side) + Connecting-Dots read
  endpoint (expose the per-factor matrix). Both drift springdoc → recapture + `contracts/gen` regen.
- **PR-W2 depth (fast, config-only on the library)** — Active Strikes OI, Trending OI/-PA, Big OI,
  Premium, the Futures suite, FII/DII. All ✅ zero-backend.
- **PR-W3 breadth/equity** — + backend gaps: Vix endpoint, equity sector-stats/heatmap/returns/pre-open,
  delivery depth, index-contribution.
- **PR-W4 tools/charts** — adopt openalgo-chart (Advance/Multiframe + builder tools + option-chain-
  greeks + OI bars), Risk Calculator, Open-High/OI-Expiry strategy pages, static/utility pages. Entry
  task: clone openalgo-chart to `C:\Trading\openalgo-chart` (§19.2), assess its API coupling before
  wiring the data-adapter to our backend.
- **PR-Cockpit** — port the Angular-only cockpit pages one-by-one (dashboard, signals, charts, paper,
  journal, watchlists, strategies + editor [**CodeMirror 6** + ported LCS diff], backtests,
  optimizations, jobs, settings [theme picker home], home); skip any page oipulse already covers.
- **PR-Cutover** — add compose service + Dockerfile (clone frontend-ui's, swap dist path; keep service
  name `frontend-ui` / container `ay-frontend-ui` / image tag for e2e + tooling), flip
  `ARTHA_ROUTE_FRONTEND`, delete old `frontend-ui/`, full e2e green.

Full oipulse coverage = 52 pages (`Plans`/billing + the non-route Morning-Trade/3:20 excluded; all
other optional `+` groups INCLUDED). e2e selectors preserved: `input[name=password]`, "Sign in",
shell → `data-testid="app-shell"`.

### 20.4 Testing & CI (owner-confirmed)
- **Unit (vitest+jsdom):** port pure-TS specs verbatim (decimal, conflation, `nextReconnectDelay`,
  oi-interpretation, `foldStrikes`, datafeed) + new pure-fn specs (cascade, `oiParams`/`satisfiable`,
  format).
- **Component (RTL):** cost CONCENTRATED on the ~15 atoms + 4 composites (tested once → 65 pages inherit
  confidence — the component-first leverage).
- **Page:** light MSW-mocked smoke (key header/table/badge text + 422 empty-state).
- **e2e gate = "A+ with manual trigger":** per-PR runs THAT wave's pages, e2e + axe, at **desktop AND
  ~480px mobile**; a **scheduled nightly + mandatory pre-cutover** full-suite e2e; PLUS a
  `workflow_dispatch` **manual full-suite** trigger so the owner can run it between waves before
  starting the next. Pixel screenshot-diff = a BUILD-TIME one-shot vs the oracle, NEVER a CI gate
  (flaky on the 2-core cold-start runner). Shared-library PRs lean on heavy RTL + the nightly/manual
  full run (path-filter optional).
- **Parity oracle:** oipulse pages → the oipulse STUDY DOCS (cell-for-cell) + seeded deterministic mock
  fixtures (stable assertions). Cockpit pages → the running Angular app (1:1).
- **CI shards:** `ci-react` from PR-F (lint + unit/RTL + build with the E-6 bundle-size budget,
  ECharts/LWC lazy-chunked); `ci-e2e` regenerated for React; `ci-frontend` (Angular) green till cutover
  then deleted; `ci-contracts` intact (React consumes `contracts/gen`; recapture per Wave backend
  endpoint; `*.d.ts` stay `eol=lf`).

### 20.5 Flagged / deferred (non-blocking)
Per-page fidelity cross-checked against `docs/oipulse-study/` at build time · World Indices &
Announcement need NEW external data sources (defer or skip — single-owner) · openalgo-chart API-coupling
assessment is a Wave-4 entry task · the TradingView fallback binary, if ever used, must be gitignored
(Personal/Non-commercial license, no redistribution).

### 20.6 Corrected oipulse→React page mapping (audit 2026-06-21, AUTHORITATIVE — supersedes §11.1 labels)

A page-by-page audit of the ArthaYantra Angular UI against the oipulse per-page study docs found the
Angular app is a **condensed MVP** that **mislabels, conflates, and omits** large parts of oipulse's
structure. §11.1's inventory inherited those labels (it mapped the condensed Angular pages to oipulse
names). **Do NOT port the condensed Angular pages 1:1** — build the oipulse page set faithfully (the §20
component-first wave plan). Key corrections:

- **MISLABEL — the anchor:** Angular `/oi/options` "Options OI Analysis" renders **all strikes,
  strike-on-rows, no strike selector, PCR/max-pain header** → that is the oipulse **Options Chain**
  structure, NOT oipulse "Options OI Analysis" (which is **per-strike intraday, time-on-rows, with a
  Strike selector**). **Resolution (DONE in PR-F):** the React anchor is renamed **Options Chain**
  (`/options/options-chain`, `OptionsChainPage`). It is a "lite" chain off the `oi-analysis` endpoint
  (9 cols); the **full 45-col chain** (per-strike PCR/IV + black76 greeks via the existing `/chain`
  endpoint) is a Wave-1 enhancement. The true **"Options OI Analysis"** (per-strike intraday: Strike
  selector + buckets-on-rows, a NEW time-rows table variant — `MirroredCspTable` is strike-rows only)
  is a **Wave-1 build item** (the `oi-analysis` endpoint already carries the bucket dimension).
- **CONFLATIONS to un-merge in the waves:**
  - Angular `/oi/options` absorbed **OI Statistics** (→ PCR/max-pain header only; the per-strike OI-wall
    bars are unbuilt) + **Active Strikes** (→ sentiment% only) + spurt-summary. Build OI Statistics,
    Active Strikes OI, Active Strikes IV as their own pages.
  - Angular `/oi/futures` merged **4** oipulse pages: Futures OI Analysis + OI Chart + OI Buzz + Market
    Movers. Split into 4 routes (`futures-spurt`, `banks-grid`, `eod` are already faithful, standalone).
  - Angular `/market/fii-dii` merged **Capital Market + Participant-OI** (the `/fii-dii/long-short`
    endpoint also exists). Split into Capital Market, Participant-wise OI, FII Long-Short Ratio, FII
    Derivative Stats.
- **MISLABEL — breadth:** `/market/breadth` "Breadth" is an ArthaYantra-original (advance/decline +
  delivery leaders); oipulse has **no "Breadth" page** — it has **Delivery Data** (per-stock %delivery),
  a different page. Keep Breadth as AY-original; build Delivery Data separately under Equity.
- **MISSING oipulse pages (build per wave, by scalping value):** Options — Options OI Analysis (real),
  OI Chart, Options Chart, OI Statistics, Trending OI, Trending OI-PA, Active Strikes OI, Active Strikes
  IV, Interval-wise OI, Multiple OI Chart; Futures — Pre-open; FII/DII — FII Derivative Stats, FII LSR
  (as pages); **all 8 Equity pages**; Features — Connecting Dots, Vix & Index, World Indices.

**Condensation tally:** Angular had **10** pages covering (mislabeled/partially) what oipulse splits
across **~34** options+futures+fii-dii+equity pages. The wave page-list is the oipulse set, NOT the
Angular 10. Each wave's page is built faithful to its `docs/oipulse-study/<area>/<page>.md` doc.

### 20.7 Wave-1 Options Chain — fidelity acceptance criteria (vs oipulse, audit 2026-06-21)

The PR-F anchor (`OptionsChainPage`) is a **"lite" chain** that proved the architecture (FilterBar +
`MirroredCspTable` + atoms + cascade + adaptive mobile) but is NOT yet a faithful oipulse Options Chain.
Wave-1 makes it faithful to `docs/oipulse-study/options/options-chain.md`. Acceptance criteria (the
confirmed gaps — PR-F has 9 cols/neutral colours/5 controls; oipulse has ~17 visible cols + colour-coded
cells + Go/Column-Setting):

1. **Columns — 18 visible (from 9):** exact order **confirmed live 2026-06-21** (Claude-in-Chrome,
   SENSEX chain) — CALL `OI Int · OI% · OI · OI Chng · IV · LTP · LTP% · LTP Chg` | **Strike** | PUT
   mirror `LTP Chg · LTP% · LTP · IV · OI Chng · OI · OI% · OI Int` + **PCR Ratio**. Plus a **Column
   Setting** modal toggling the ~28 hidden cols (Greeks/Premium/Intrinsic/Volume/O=H/O=L). **Backend:**
   the full **`/chain` endpoint** + the **greeks-in-chain** task (per-strike PCR/IV + black76 greeks) —
   supersedes the lite `oi-analysis` feed.
2. **Colours (carry the signal — currently neutral):** OI bars **red (CALL) / green (PUT)** (`DataBar`
   already takes `tone` → pass by side); **OI Chng green/red bars** (not text); per-row **`OiBadge4`**
   (OI Int column); **ATM-row cream tint** (#ffeeba-equivalent token) + clickable; ITM row tint;
   **max-OI / max-ΔOI / max-Vol cell highlights**; LTP **flash on change** (`usePulse`).
3. **Controls:** add a **Go button**; **grouped Name select** (Index / Stocks headers); Interval add
   **Full-Day / 2h / 4h / custom-time**; add the **Column Setting** button. (Mode: keep the toggle or
   switch to a radio — note the divergence.)
4. **Header strip:** **INDIA VIX** (LTP/DH/DL/DO) · Total PCR (+prev +chg) · **ATM** · **Days-to-Expiry**
   · underlying LTP/DH/DL/DO. **Move Max-pain/Sentiment OFF this header** — they belong to the separate
   **OI Statistics** / **Active Strikes** pages (the current header bled them in).
5. **Also Wave-1 (distinct page):** the TRUE **"Options OI Analysis"** — per-strike intraday, a **Strike
   selector** + **buckets-on-rows** (a NEW time-rows mirrored-table variant; `MirroredCspTable` is
   strike-rows only). Endpoint `oi-analysis` already carries the bucket dimension.

Live pixel side-by-side (Claude-in-Chrome on the owner's logged-in oipulse) is the visual QA gate when
building this — the study doc is the authoritative spec until then.

### 20.7.6 Options Chain — built + live-QA'd; remaining-divergence sequencing (LOCKED 2026-06-21)

The faithful Options Chain shipped on `feat/wave1-options` (commits `dbaf6f9` backend `chain-table` +
`strike-series`; `98d5ecd` FE page; `03afd5f` + `980cde3` fidelity from the live QA). A live
Claude-in-Chrome QA vs the owner's oipulse SENSEX chain (2026-06-21) confirmed the 18-column order +
colour semantics match. Three fixes were applied (IV shown as percent ×100; optional cols →
Delta/Volume/Intrinsic; OI-Int badge → abbreviation `L.B./S.B./S.C./L.U.` + arrow, full label as
aria-label). The QA checklist + full divergence log live in
`docs/manual-tests/phase-4-wave1-options-chain.md`. The remaining divergences are sequenced (owner-locked):

- **Permanent / intended (NOT to "fix"):** badge **ring** not solid fill (solid fails WCAG AA on some
  theme×severity combos); the **`+` prefix** on positive deltas (sign must not be colour-only); **black76
  greeks** instead of oipulse server values (§17.9 parity).
- **INDIA VIX header → DONE** (pulled forward from PR-W3, owner-chosen 2026-06-21): new
  `GET /api/v1/market/vix` (the pinned INDIA VIX index quote → LTP + day OHLC + change) wired into the
  chain header. The **strike-click chart sub-view** + the **Chart** optional column → **PR-W4**
  (openalgo-chart).
- **End-of-Wave-1 polish pass** (pure FE, build-once after the other W1 pages exist): strike-column tan
  bg · stronger ATM row · max-cell **filled** highlight; **grouped Name select** (Index/Stocks — shared
  `FilterBar`, benefits every page); **Premium / Combine-Premium** derivable optional cols; header
  **underlying chg% + timestamp** (`asOf` exists; the chg needs prev-close → its W3 portion rides PR-W3).
- **PR-W3 backend-dependent** (batch with the W3 backend-gap work): **interval set** Full-Day/2h/4h/10m/
  custom-time (an `OiInterval` enum extension + custom-time pickers); **IV Chng** optional col (an
  IV-delta field on `chain-table`); **O=H / O=L** optional cols (a `strike-session-stats` join).

Net: the chain reaches full oipulse fidelity by end of **PR-W3** (+ PR-W4 for the chart click). Nothing
dropped. This same per-page rhythm — build to the study doc, then a live Claude-in-Chrome QA, then a
documented fidelity pass — applies to every Wave page (§20.8.2).

### 20.8 Standing UI-fidelity rules (apply to EVERY page, every wave — AUTHORITATIVE)

1. **UI authority = the oipulse study, NOT the Angular app.** Every React page's layout, columns,
   colours, controls, header, and interactions are replicated from `docs/oipulse-study/<area>/<page>.md`
   (the live oipulse capture). The React pages are **deliberately DIFFERENT** from the Angular pages
   (which were a condensed/mislabeled MVP — §20.6). **NEVER port a page from, or use as a UI/visual
   fidelity oracle, `frontend-ui/` (the Angular app).** The Angular code is a reference ONLY for: (a)
   framework-free **pure-TS ports** (decimal, conflation, ws-client/`nextReconnectDelay`,
   oi-interpretation, `foldStrikes`); (b) **backend wire-type + endpoint contracts** (the `name` param,
   `{items}` envelope, decimal-as-string conventions, the OI-analytics endpoint shapes). For anything a
   user SEES, the oracle is the oipulse doc + the live oipulse page — never the Angular page.

2. **Per-page live side-by-side QA gate (mandatory acceptance).** After building EACH page, open the
   corresponding **live oipulse page** in the owner's Chrome via **Claude-in-Chrome** and compare
   directly: columns (count + order), **cell colours** (the visual signal — side-coloured OI bars,
   per-row 4-state badges, max/min cell highlights, ATM-band tint), controls (set + position + type),
   and header metrics. A page is **not "done"** until it matches the live oipulse page — within the
   deliberate, documented substitutions only (LWC for TradingView; our `--ay-*` themes; our decimal
   handling; black76 greeks instead of oipulse's server values). Record any residual divergence in that
   page's `docs/manual-tests/` guide. (Owner connects the Claude extension for the live check; the study
   doc is the authoritative spec until then.)

## 21. Broker API selection — Kite vs Upstox, per-connection-case (2026-06-21, AUTHORITATIVE)

> Status: DECISION (no code changed). Produced by a current-docs comparison (Kite Connect v3 + Upstox
> v3, fetched 2026-06-21). **Same standing as §17/§18/§19/§20.** Governs which broker each external API
> connection points at. Does NOT change the locked architecture — the OpenAlgo abstraction (§2/§3, decision
> 1) makes the broker swappable, so this is a *routing* decision, not a coupling one. **Rule stands: never
> import a broker SDK into core; depend only on the domain ports (§3.139) and the OpenAlgo gateway.**

### 21.0 Verdict
**Upstox (Plus) is the technically superior API across most cases** — but the answer is per-connection-case,
not a single global swap. Kite's only retained edges are *incumbent/already-wired* and a higher
*per-connection* WS instrument cap (3000 vs 2000) — but Upstox allows **5** concurrent WS conns/user vs
Kite's 3, so aggregate WS capacity actually favours Upstox. Standardize NEW data work on Upstox; keep Kite
serving live until a deliberate migration;
couple to neither directly (route through OpenAlgo / run appliances).

### 21.1 Head-to-head (current docs)
| Capability | Kite Connect v3 | Upstox (Plus) | Winner |
|---|---|---|---|
| Live WS — OI in tick | yes (full mode) | yes (full mode) | tie |
| Live WS — greeks/IV in tick | **no** (we compute) | **yes** δ/γ/θ/vega/ρ + IV broker-side | Upstox |
| Live WS — per-conn instrument cap | **3000**/conn | 2000 full / 5000 ltpc | Kite |
| Live WS — concurrent connections | 3 conns/key (→ ≤9000 full aggregate) | **5 conns/user** (→ ≤10000 full aggregate) | Upstox |
| Live WS — depth | 5-level | 5-level (**D30 = 30-level**, Plus, 50 instr) | Upstox |
| Option-chain endpoint | build from instruments+quote (multi-call) | **one call** → per-strike OI+greeks+IV+PCR | Upstox |
| REST rate limit | **Quote 1/s, historical 3/s** | **50/s**, 500/min, 2000/30min | Upstox (big) |
| Recent intraday history + OI | minute, 60d back, `oi=1` | 1-min ~6mo back, OI incl., **1 instr/req (no batch)** | mixed |
| **Expired-contract OI history** | **NOT served** (tokens drop post-expiry) | **yes** (ExpiryTrack engine, needs Plus) | **Upstox ONLY** |
| Order throughput | 10/s, 400/min, 5000/day | 10/s reg / **50/s SEBI-algo**, 500/min | Upstox |
| SPAN margin | mature API | API + we use marginism (§8) offline | tie |
| Status in our app | **incumbent, wired, live** (`kite.FeedRearm`) | new | Kite |

### 21.2 Per-connection-case routing (AUTHORITATIVE)
1. **Historical OI backfill (expired contracts) → UPSTOX Plus. ONLY option.** Kite literally cannot serve
   expired F&O history. ExpiryTrack runs on it (§5.A). Decisive; this is why Plus was purchased.
2. **Live full-chain OI / per-strike snapshot capture → Upstox is technically stronger** (one-call chain
   with greeks, 50/s vs Kite's 1/s Quote). The 3-min full-chain NIFTY+SENSEX capture across hundreds of
   strikes is rate-limit-bound on Kite, roomy on Upstox. Migrate via the OpenAlgo source flag (§4) when
   convenient — not urgent (current Kite capture works).
3. **Live scalp WS feed → keep KITE for now** (incumbent, working, `kite.FeedRearm` deployed
   [[live-mode-findings]]). Note the WS-cap picture is a wash, not a Kite win: Kite higher *per-conn* cap
   (3000 vs 2000 full) but only 3 conns/key, while **Upstox allows 5 concurrent WS connections/user** →
   higher *aggregate* WS capacity (≤10000 vs ≤9000 full). Move to Upstox via OpenAlgo later if D30
   microstructure / broker greeks / more WS fan-out are wanted. Greeks stay computed in `black76-math`
   for parity (§6/§17.9) either way.
4. **EOD daily universe → NEITHER broker.** Already broker-free via NSE/BSE bhavcopy (§15, [[eod-bhavcopy-feature]]).
   Do NOT add a broker dependency here.
5. **Order execution / margin → through OpenAlgo**, broker = funded account; Upstox edge on algo throughput,
   Kite proven. Defer to whichever account trades. SPAN sizing via marginism (§8), not broker-dependent.

### 21.3 Caveat before trusting Upstox for scalp FILLS
The you→OpenAlgo→Upstox hop adds latency + a failure point (decision-2 caveat, §2). Fine for 3-min OI
snapshots; **MEASURE round-trip before any live order execution** (the §3.8 / §17.3 latency gate informs
the broker choice for the execution leg specifically).

*Sources (fetched 2026-06-21):* Kite [historical](https://kite.trade/docs/connect/v3/historical/) ·
[websocket](https://kite.trade/docs/connect/v3/websocket/) · [rate limits](https://kite.trade/docs/connect/v3/exceptions/);
Upstox [historical](https://upstox.com/developer/api-documentation/get-historical-candle-data/) ·
[market-data feed v3](https://upstox.com/developer/api-documentation/v3/get-market-data-feed/) ·
[rate limiting](https://upstox.com/developer/api-documentation/rate-limiting/).

*End of plan.*
