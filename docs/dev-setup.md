# Developer setup — iteration tiers & port map (A.14 / plan §9.3, S6)

## Iteration tiers

| Tier | Service under iteration | Rest of stack | Reload | Use when |
|---|---|---|---|---|
| **T1 — UI inner loop** | `ng serve` on `:4200` with dev proxy → `127.0.0.1:8080` (D3) | default compose profile (containerized) | < 1 s HMR | Frontend work |
| **T2 — backend inner loop** | `mvnw spring-boot:run -Dspring-boot.run.profiles=dev,mock` on host (devtools restart); `uvicorn --reload` for optimizer-service | **`ay up dev-tools` — required**: this profile publishes Redis `127.0.0.1:6379` and the internal service ports on loopback (Postgres 5432 is always published, D7) | 2–4 s | Single Java/Python service work |
| **T3 — full containerized** | in container | `docker compose watch` (`sync` + `rebuild`) | 10–30 s | Pre-merge parity check — matches what CI and Playwright E2E run |

There is **no Tier-4 "full Linux VPS" row** — the single-Windows-machine
constraint is binding; the optional cloud exit ramp is documentation only
(COMMON §10.5).

Host-run tiers (T1/T2) connect to the **same Docker Postgres/Redis published
on loopback**, so compose-network parity holds — nothing ever runs against a
second local database. Switch tiers with `ay down` / `ay up [dev-tools]` —
**never raw `docker kill`**.

## Port map

| Port | Published by | When |
|---|---|---|
| `127.0.0.1:8080` | edge-gateway | always — the only published app port |
| `127.0.0.1:5432` | timescaledb | always (dev tooling, D7) |
| `127.0.0.1:6379` | redis | `dev-tools` profile only |
| `127.0.0.1:8081–8084` | market-data / strategy-signal / backtest / optimizer | `dev-tools` profile only |
| `127.0.0.1:8085` | Adminer | `dev-tools` profile only (S6: moved off host 8080) |
| `127.0.0.1:5540` | RedisInsight | `dev-tools` profile only |

Host **8080 stays reserved for the gateway**; nothing binds `0.0.0.0`, ever.

## `ay` verb reference

See the table in [README.md](../README.md#ay-operator-cli) — `up [obs]
[dev-tools]`, `down`, `logs <svc>`, `status`, `backup`, `restore <file>`,
`reset-db`. All verbs are project-scoped compose; they can never touch
non-ArthaYantra containers.

## Single-clone rule

The repo lives in **one clone**. The design target is WSL2 ext4
(`\\wsl.localhost\…`) for bind-mount and build speed (COMMON §10.2);
Windows-native frontend work accesses that same clone via `\\wsl.localhost`
or runs Node inside WSL2 — **never a second checkout**. (Current owner clone:
`C:\Trading\ArthaYantra\artha-yantra-2` — Windows-native; revisit when
`docker compose watch` performance starts to matter, before Stage C frontend
work.)

## Local tooling expectations

- `pre-commit install` once after clone; the gitleaks hook uses the **system
  `gitleaks` binary** (pinned 8.30.1; `winget install Gitleaks.Gitleaks`) —
  Windows Smart App Control blocks locally-built unsigned Go binaries, so the
  upstream golang-language hook is not used (A.9).
- Maven via the committed `mvnw` wrapper (no global Maven needed).
- PR checklist item: update this file whenever ports or compose profiles
  change.
