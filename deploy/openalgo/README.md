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
`OpenAlgoWireContractTest` + `OpenAlgoContractCanary` against it, **re-sync `.env.sample` from the
new image's `/app/.sample.env`** (`docker run --rm <digest> cat /app/.sample.env`), re-applying the
ArthaYantra overrides (the header in `.env.sample` lists them) — because `.env.sample` is mounted AS
the app's `/app/.env`, a missing/renamed key OR a stale `ENV_CONFIG_VERSION` makes the app
hard-refuse boot (missing-key error or a no-TTY version prompt → healthcheck timeout). Then update
the digest here and in `docs/design/DECISIONS_LOG.md`.

## Running it (opt-in)

The service is behind the compose `openalgo` profile, so the default `ay up` does NOT start it (and
nothing depends on it — the core stack always boots green). Start it explicitly:

```powershell
.\ay.ps1 up openalgo      # starts ay-openalgo + ay-openalgo-publish (loopback :5001)
```

`ay` creates `deploy/openalgo/.env` from `.env.sample` on first run: it generates `APP_KEY`,
`API_KEY_PEPPER`, and `FERNET_SALT`, and (single-owner) fills `BROKER_API_KEY/SECRET` from the
existing `deploy/secrets/kite_*` files. The file is gitignored (its keys are the appliance's, not
ours). `.env.sample` is the **complete** OpenAlgo config (mirrors the pinned image's
`/app/.sample.env`, ArthaYantra overrides baked in) because it is mounted **AS** `/app/.env`
(compose `volumes:`, not `env_file:`) — OpenAlgo is file-config-native, so every key must be present
and the generated secrets must persist verbatim (a salt rotated into a throwaway `/app/.env` would
make stored ciphertext undecryptable on the next recreate). See DECISIONS_LOG 2026-06-19.

## Broker login (Zerodha — single-owner)

OpenAlgo reads `BROKER_API_KEY/SECRET` from `.env` (env, not the UI — `blueprints/auth.py`); `ay`
seeds them from `deploy/secrets/kite_*`. The UI only runs the OAuth. The broker is **derived from
`REDIRECT_URL`'s path segment** (`/zerodha/callback`).

1. `ay up openalgo`, then open **http://127.0.0.1:5001** (the loopback publisher) and create the
   OpenAlgo admin account on first run.
2. **Repoint the Zerodha Connect app's Redirect URL** to `http://127.0.0.1:5001/zerodha/callback`
   (developers.kite.trade). Zerodha allows one redirect + one session per app, so the single app is
   repurposed to OpenAlgo; ArthaYantra's Kite-direct path is the never-deleted fallback (reads via
   OpenAlgo after the Phase-1 cutover). For another broker, edit `REDIRECT_URL`'s `/zerodha/` segment.
3. In the UI, connect the broker → complete Zerodha login + TOTP. **Daily re-login** applies (Kite
   tokens expire ~06:00 IST). Verify: `POST http://127.0.0.1:5001/api/v1/quotes`
   `{"apikey":...,"symbol":"RELIANCE","exchange":"NSE"}` returns `status:success` with live data.
4. Generate an **OpenAlgo API key** in the UI and write it to `deploy/secrets/openalgo_api_key`
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

Two layers:
- `deploy/openalgo/.env` (host file, mounted AS `/app/.env`) holds the **stable** secrets
  (`APP_KEY`/`API_KEY_PEPPER`/`FERNET_SALT`) + broker creds. Survives everything including
  `ay reset-db` (it is not a volume) — so the Fernet salt never changes and stored ciphertext stays
  decryptable. Deleting this file makes `ay` regenerate fresh secrets → existing ciphertext is lost.
- `openalgo-data` volume → `/app/db` (SQLite: admin account, sessions, hashed API keys, sandbox
  state; DuckDB history). Survives recreate. `ay reset-db` drops it — you re-create the admin
  account + re-log-in the broker afterward (the `.env` salt is unchanged, so no decrypt issues).
