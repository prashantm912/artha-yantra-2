# ArthaYantra 2.0

Single-owner research platform for **Indian index-options scalping** (NIFTY/NSE + SENSEX/BSE):
live market data (Kite + Upstox, routed through an OpenAlgo appliance), an **options/OI analytics
suite** (oipulse-style — chain, OI spurt, Connecting-Dots, trending/big OI, active strikes, premium),
**intraday scalping strategies** with paper trading, **walk-forward backtesting + optimization on a
real expired-option-premium archive** (~121M bars), and a **SPAN margin** appliance — running entirely
on one Windows 11 machine under Docker Desktop, behind a single loopback-bound gateway.

> **Current state.** See [`PHASE_GATES.md`](PHASE_GATES.md) for the live phase marker and
> [`docs/DEFERRED_BACKLOG.md`](docs/DEFERRED_BACKLOG.md) for what's pending. As of 2026-06-26 the
> scalper tunable-infra (12 Siva scalpers → 36 instrument-agnostic NIFTY/SENSEX variants, functionally
> backtested) is complete; ~~the remaining frontier is the W-U4 Upstox cutover (live-session gated).~~
> **(Updated 2026-07-12: the W-U4 Upstox cutover is a settled owner NO — stay Kite, split-by-capability, per
> [`PHASE_GATES.md`](PHASE_GATES.md). Since 2026-06-26 the Phase-5 Minervini + Manas swing families shipped
> live and both design programs (evolution + intelligence) are substantially built and live-deployed (EVO→E4,
> INT→I3-backend, 2026-07-11/12) — see `PHASE_GATES.md` for the current frontier.)**

> **Design authority.** The frozen design set under [`docs/design/`](docs/design/) —
> `ARTHAYANTRA_2_COMMON_REFERENCE.md` plus the seven stage files (Stage A–G) — is the original-design
> authority and the **historical as-built record** for Stages A–G. **Forward work** (since the 2026-06-19
> re-platform) follows the OpenAlgo + React master plan at
> [`docs/superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md`](docs/superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md)
> — read §17 (Errata) + §18 (Gap Addendum) first; they override §1–§16 on conflict.
>
> **Adaptation note (Phase 1 deliverable).** The original Phase-1 spec said to
> copy "the three frozen design docs + the phases document" into `docs/design/`.
> Those four originals were superseded and deleted; per the spec's own
> SPECIAL-instruction adaptation, the **new 8-file set** (`COMMON_REFERENCE` +
> stage files A–G) is the per-session reference set and lives canonically at
> `docs/design/` (relocated from `docs/phases/` — one copy, no duplication).

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Windows 11 + Docker Desktop | WSL2 backend | pin `%UserProfile%\.wslconfig`: `memory=10GB`, `processors=6`, `swap=4GB` (COMMON §10.2) |
| Java (Temurin) | 21 | for host-run inner loop (Tier 2) only — containers bring their own |
| Node.js | 24.x | `frontend-react` (React 19 + Vite 6 + Tailwind v4 + shadcn) + e2e tooling |
| Python | 3.12+ | `pre-commit`, optimizer-service tooling (later stage) |
| git | 2.x | with `pre-commit install` run once after clone |

## Clone → green (mock mode, zero credentials)

```
git clone <repo> && cd artha-yantra-2
pip install pre-commit && pre-commit install
cp .env.example .env          # mock mode needs no secret values
.\ay.ps1 up                   # Linux/WSL2: pwsh ./ay.ps1 up (ay.sh removed — lacked profile isolation)
# → http://127.0.0.1:8080 — login, dashboard on deterministic mock ticks (D13)
```

The stack boots **with no Kite credentials** under `SPRING_PROFILES_ACTIVE=mock`
(the default in `.env.example`).

## `ay` operator CLI

| Verb | Effect |
|---|---|
| `ay up [dev-tools] [openalgo]` | `docker compose --env-file .env [--profile …] up -d` |
| `ay down` | `compose down` (project-scoped only — never raw `docker kill`) |
| `ay logs <svc>` | `compose logs -f <svc>` |
| `ay status` | healthcheck summary of all containers |
| `ay backup` | manual `pg_dump` into `./backups` |
| `ay restore <file>` | documented restore from a dump file |
| `ay reset-db` | down, drop volume, re-up → Flyway rebuilds schemas from empty |

## Compose profiles (D14/D16)

| Profile | Command | Contents | RAM delta |
|---|---|---|---|
| *(default — core)* | `ay up` | stateful containers + `flyway-init` (one-shot) + `db-backup` sidecar + app services as they land | ~3.9 GB full build-out |
| `dev-tools` | `ay up dev-tools` | Adminer `127.0.0.1:8085`, RedisInsight `127.0.0.1:5540`, publishes redis 6379 + internal service ports on loopback | +~250 MB |

Mock vs live is **orthogonal to profiles**: `SPRING_PROFILES_ACTIVE=mock|live`
in `.env` (COMMON §10.3). Mock requires zero secrets; live fails fast without
them.

## Ports (COMMON §3)

| Port | Bound | What |
|---|---|---|
| `127.0.0.1:8080` | always | edge-gateway — the **only** published app port |
| `127.0.0.1:5432` | always | TimescaleDB (dev tooling) |
| `127.0.0.1:8085` / `:5540` / `:6379` / `:8081–8084` | `dev-tools` profile only | Adminer / RedisInsight / Redis / internal services |

Nothing ever binds `0.0.0.0`. Phone access is Tailscale-serve-first; see
[`docs/remote-access.md`](docs/remote-access.md).

## CI & branch protection (A.10)

CI is the mechanical reviewer: `ci-java.yml` (path-filtered Checkstyle/Error
Prone → unit + Testcontainers IT on production-pinned images → Modulith
verify → JaCoCo ≥ 60 % line on services → image build, GHCR push on `main`
only) and `ci-migrations.yml` (two-step checksum-drift check against the
merge-base, then a fresh-volume run of all four Flyway lineages +
`flyway validate`). A **gitleaks** step runs in every workflow.

> **Owner action (GitHub settings → Branches):** protect `main` — PRs
> required, all path-filtered checks green, force-push and direct push
> disabled (yes, even for the owner). Trunk-based, short-lived
> `feat/|fix/|chore/|docs/` branches, squash-merge only.

## Development

- [`docs/dev-setup.md`](docs/dev-setup.md) — host/container iteration tiers and port map.
- [`PHASE_GATES.md`](PHASE_GATES.md) — current phase marker, acceptance checklist, parking list.
- [`docs/LEGAL.md`](docs/LEGAL.md) — third-party attribution record: lightweight-charts (Apache-2.0),
  the OpenAlgo ecosystem, and **SPAN margin via marginism (MIT, © span-margin contributors)**.
- Git workflow: trunk-based, short-lived `feat/|fix/|chore/|docs/` branches,
  **Conventional Commits 1.0** (scope = service/lib name), **squash-merge only**.

### Generating `ARTHA_OWNER_PASSWORD_HASH`

The gateway verifies login against an Argon2id PHC string supplied via `.env`.
Generate one with the helper (CD-13), once it lands with the gateway phase:

```
.\mvnw.cmd -pl tools/hash-password -q compile exec:java "-Dexec.args=<your password>"
```

Paste the printed PHC string into `.env` with **every `$` escaped as `$$`**
(docker compose interpolates `$` inside `.env` values).
