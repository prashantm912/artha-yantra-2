# deploy/span-files/ — daily NSE SPAN risk files (§8.2)

This directory is the **read-only bind-mount source** for `margin-service`
(`../deploy/span-files:/spn:ro` in `deploy/docker-compose.yml`). It holds the
exchange's daily CME-SPAN `.spn` risk-parameter files — the same inputs a broker
margin calculator consumes. `marginism` computes **nothing** without one.

## What lives here

- `*.spn` — the parsed risk files. **Gitignored** (they change every trading day;
  see repo `.gitignore`). Only `.gitkeep` + this README are committed so the
  bind-mount target exists in a fresh checkout.
- The appliance picks the **newest by mtime** and exposes its business date at
  `GET /health` → `spnDate`, so a stale or missing file is always visible.

## How files get here

The host-side fetcher `tools/span-fetch/fetch_spn.ps1` downloads today's NSE PR
zip, unzips the `.spn` into this directory, and runs daily (~08:30 IST) via
Windows Task Scheduler. The fetcher is intentionally **outside** the container so
an NSE outage never crashes the margin appliance (it keeps serving yesterday's
params).

> **VERIFY-PENDING:** the exact NSE download URL/format is marked `(VERIFY)` in
> `fetch_spn.ps1` — NSE rotates archive hosts. Confirm the live URL before relying
> on automated fetches; until then drop a `.spn` here manually to smoke-test.
