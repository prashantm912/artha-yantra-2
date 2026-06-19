# deploy/openalgo/ — OpenAlgo appliance config (plan §2 / §3)

OpenAlgo is the **broker-swap boundary**: an **AGPL-3.0** appliance run UNMODIFIED in its own
container (`marketcalls/openalgo`, **digest-pinned** in `deploy/docker-compose.yml`), consumed by
ArthaYantra ONLY over its `/api/v1/` REST + WebSocket surface. Its source is **never** merged into
any ArthaYantra module (license filter, master plan §1d). It is the data **source**, never the
store — capture is written by ArthaYantra into TimescaleDB.

## Pinned image

`marketcalls/openalgo@sha256:b1bc2ec4fc40a0e32730bab9c4b9dd3a43daefee30453de46885544eab45fdd7`
(Docker Hub publishes only commit-hash tags + `latest`, no semver — so we pin by **digest**, never
`latest`. Source tracks GitHub release `v2.0.1.3`.) Bump procedure: pull the new digest, run the
`OpenAlgoWireContractTest` + `OpenAlgoContractCanary` against it, **sync `ENV_CONFIG_VERSION` in
`.env.sample` to the image's `/app/.sample.env` Version header** (`docker run --rm <digest> head
/app/.sample.env` — a stale version makes the app hard-refuse boot with an interactive prompt under
the no-TTY container, so the healthcheck times out), then update the digest here and in
`docs/design/DECISIONS_LOG.md`.

## Running it (opt-in)

The service is behind the compose `openalgo` profile, so the default `ay up` does NOT start it (and
nothing depends on it — the core stack always boots green). Start it explicitly:

```powershell
.\ay.ps1 up openalgo      # starts ay-openalgo + ay-openalgo-publish (loopback :5001)
```

`ay` creates `deploy/openalgo/.env` from `.env.sample` on first run, generating `APP_KEY` and
`API_KEY_PEPPER`. The file is gitignored (its keys are the appliance's, not ours).

## Broker login (runtime, in the UI)

Broker credentials are NOT set in `.env` — log in through the OpenAlgo web UI:

1. `ay up openalgo`, then open **http://127.0.0.1:5001** (the loopback publisher).
2. Pick a broker, enter that broker's API key/secret, complete OAuth. The broker OAuth
   `REDIRECT_URL` must point at `http://127.0.0.1:5001/<broker>/callback` (edit `.env` if you use a
   broker other than the Zerodha default).
3. Generate an **OpenAlgo API key** in the UI and write it to `deploy/secrets/openalgo_api_key`
   (single line, no newline). market-data-service reads it ONLY when a capture capability is routed
   through OpenAlgo (`artha.marketdata.source.*=openalgo`, Phase 1).

## Sandbox / analyzer (mock) mode — §17.8

OpenAlgo's sandbox/analyzer mode is a **runtime toggle**, NOT a startup env flag (the only
sandbox-related env var, `SANDBOX_DATABASE_URL`, just sets the isolated DB path). Toggle it via the
UI or `POST /api/v1/analyzer/toggle`. **Mock-profile coupling (mock ⇒ analyzer) cannot be baked into
the container via env** — it must be set post-boot once an OpenAlgo API key exists. This is wired in
Phase 1 alongside the routing cutover; until then it is a manual step. **Never point a mock-profile
ArthaYantra stack at a live (non-analyzer) OpenAlgo broker session** (mock/live isolation, CLAUDE.md).

## Persistence

`openalgo-data` volume → `/app/db` (SQLite: sessions, hashed API keys, sandbox state; DuckDB
history). Survives recreate. `ay reset-db` drops volumes (you re-log-in the broker afterward).
