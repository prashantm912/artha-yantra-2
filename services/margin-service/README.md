# margin-service — SPAN margin & position sizing appliance (§8)

A small, **dormant-by-default** Python/FastAPI appliance that computes Indian F&O
**SPAN margin** offline from the exchange's daily `.spn` risk files, and turns that
into a **rail-bounded position size** for the order widget / signal→size flow (S12).
Modeled byte-for-byte on `services/optimizer-service` (same Dockerfile shape, ruff,
pytest, CI shard). Internal port **8086**, stateless, no DB/Redis.

## API (decimals are JSON **strings** — repo-wide wire convention)

Behind the gateway at `/api/v1/margin/**` (loopback-only auth, same as every route).

- `POST /api/v1/margin` — raw SPAN for a basket → `{span, exposure, total, hedgeBenefit, spnDate, currency}`.
  `400 VALIDATION_FAILED` on a bad `optionType` / missing option strike; `503 DATA_GAP` when no `.spn` is loaded.
- `POST /api/v1/margin/size` — sizing with the Siva risk rails → `{lots, qty, marginRequired, capitalUsed,
  riskAmount, target, withinRails, warnings, limitingRail}`. Rails: `maxCapitalPct` of capital,
  remaining daily-loss room, and a mandatory hard stop (no-naked-risk). `withinRails:false, lots:0`
  when the stop is missing or the daily room is exhausted. *No averaging losers* is a position-state
  rule enforced by the **caller** (S12), not this stateless endpoint.
- `GET /health` → `{status, spnDate, spnLoaded}` — reports the loaded SPAN business date so a stale or
  missing file is visible (never silently wrong).

## How it loads SPAN files

`app/span_loader.py` picks the **newest `.spn` by mtime** under `SPN_DIR` (`/spn`,
a read-only bind-mount of `deploy/span-files/`), parses it **once**, and memoizes the
engine keyed by `(path, mtime)` — so every call after the first is sub-millisecond CPU.
A re-fetch (new mtime) invalidates the cache. The host fetcher
`tools/span-fetch/fetch_spn.ps1` drops a fresh file daily (~08:30 IST) **outside** the
container, so an NSE outage never crashes the appliance.

The SPAN engine is the MIT **marginism** package (`marginism==0.1.1`,
`marketcalls/marginism`), wrapped behind the `SpanEngine` anti-corruption seam in
`app/marginism_adapter.py` (the `kite/wire/` pattern) so an upstream rename never
leaks past that module. The pinned-tag drift canary is `tests/test_marginism_contract.py`.

## Enforcement posture

- **Paper = advisory.** `strategy-signal-service`'s `PaperAccountService.usageFor()` calls
  `/margin/size` only when `artha.margin.span-enabled=true`; it falls back to the existing
  flat margin-pct approximation when the flag is off **or** the appliance is unreachable.
  Buying-power warnings never block a paper fill.
- **Live = the broker's job.** No live hard-gate is added here.

## VERIFY-pending gates (NOT claimed correct yet)

This appliance ships with its wiring + sizing rails proven against a **synthetic `.spn`
fixture** (`tests/fakes.py`, which drives the *real* marginism algorithm). The following
are documented manual VERIFY steps before trusting production margins:

1. **Real `.spn` golden (broker parity).** The CI golden asserts numbers golden to the
   synthetic fixture, **not** broker parity. Confirm a short NIFTY ATM straddle's `total`
   against a Zerodha/Upstox SPAN calculator within **±2–3%** (exposure/scan add-ons differ
   by source) using a real file in `deploy/span-files/`. Record the reference value + source.
2. **NSE `.spn` download URL/format.** `tools/span-fetch/fetch_spn.ps1` uses the historically
   documented NSCCL PR archive pattern, marked `(VERIFY)` — NSE rotates archive hosts. Confirm
   the live URL, then schedule the fetcher (Windows Task Scheduler, 08:30 IST).
3. **marginism pin.** `marginism==0.1.1` is current on PyPI and verified against this code's
   adapter. Bump deliberately (the contract canary pins the version); if a pin ever leaves PyPI,
   vendor the source under `app/_vendor/marginism/` keeping its MIT `LICENSE`.

## Develop / test (Python 3.x global, no venv)

```
cd services/margin-service
python -m ruff check app tests
python -m pytest tests/ -q --cov=app --cov-fail-under=75
```

## Run dormant in the stack

The compose block (`deploy/docker-compose.yml`, `margin-service`) has no `profiles:`, so it
starts with the default set — but it does nothing until `artha.margin.span-enabled=true` and a
`.spn` is present in `deploy/span-files/`. Build/run only via the `ay`/`ay.ps1` CLI
(`--env-file .env`); never raw `docker compose`.
