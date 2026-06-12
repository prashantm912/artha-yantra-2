# LEGAL — license posture & attribution record (A.16)

## Chart library: lightweight-charts (attribution record) [A13, 2026-06-12]

**lightweight-charts (pinned `>=5.2 <6`) is the PRIMARY and sole main-chart
renderer** (ADR amendment A13; CD-9 as redefined by A13). TradingView
Advanced Charts is **dropped entirely** — TradingView's published eligibility
excludes this project (no licenses for personal/private use), so the old Q5
"verify the signed Advanced Charts agreement" checklist has no object and is
deleted. This file's scope is an **attribution record**, not a
signed-agreement record:

1. **License:** Apache License 2.0 — © TradingView, Inc.
   (https://github.com/tradingview/lightweight-charts). The library's
   **NOTICE attribution** is honored, including a link to
   https://www.tradingview.com/. The built-in **`attributionLogo` chart
   option stays ON** in every chart the app renders — this satisfies the
   link requirement.
2. **Distribution posture:** the **private GitHub repo and private GHCR
   registry are BY CHOICE** — trading-strategy IP, Kite-credential hygiene,
   and the single-user posture — **not** a redistribution mandate. No vendored
   proprietary bundle exists; the `frontend-ui` image ships the Angular
   `dist/` output only, with lightweight-charts as an npm-pinned dependency
   compiled into it.
3. **No second main-chart renderer.** Reintroducing TradingView (or any
   renderer swap) requires a new ADR amendment (CD-9/A13).

## Kite credentials (A6 record) [owner decision, 2026-06-12]

The 2.0 stack is provisioned with a **brand-new Kite API key pair**; the v1
pair is **never configured anywhere in 2.0**. Consequently:

- D13's "rotate the leaked v1 credentials at day zero" is **not a 2.0 build
  gate**, and the P1-4 leaked-credential digest tripwire is **dropped as
  moot** — no digests are recorded, nothing is compared at startup.
- Deleting/rotating the old v1 key in the Zerodha console remains
  **recommended housekeeping** (its secret is public in v1 git history;
  blast radius is read-only market access — no order placement, threat T1) —
  but it gates nothing.
- All other D13 mechanics stand unchanged: Argon2id login, AES-GCM token at
  rest (Stage B), `.env` + Docker secrets, credential-free mock mode.
