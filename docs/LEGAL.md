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

## OpenAlgo ecosystem (attribution record) [master plan §1d, 2026-06-19]

License filter governing the OpenAlgo integration (master plan §1d): **MIT → import/port freely,
keep the copyright notice; AGPL-3.0 → run STANDALONE behind a process boundary, consume only its
output/network API, NEVER merge its source.**

1. **OpenAlgo** (`marketcalls/openalgo`, Python/Flask, **AGPL-3.0**) — integration form: **APPLIANCE**.
   Run UNMODIFIED in its own container, **digest-pinned** (Phase 0:
   `sha256:b1bc2ec4fc40a0e32730bab9c4b9dd3a43daefee30453de46885544eab45fdd7`), consumed ONLY over its
   `/api/v1/` REST + WebSocket surface. Its source is **never** merged into any ArthaYantra module and
   its frontend is **never** imported. **AGPL containment:** never fork-and-patch the image (a modified,
   network-served AGPL work triggers the §13 source-offer obligation — and this stack is exposed to a
   phone over Tailscale, a network use). Wait for upstream releases; if a patch is ever unavoidable,
   publish the modified Corresponding Source (master plan §17.10).
2. **OpenAlgo-Java SDK** (`in.openalgo:openalgo`, Maven Central, **MIT**) — integration form: **IMPORT**
   (DEFERRED to Phase 3: WS streaming + order placement only; REST capture is hand-rolled per §17.2).
   An MIT client of the AGPL appliance does not infect ArthaYantra. Keep the MIT notice when added.
3. Future MIT ports/imports under this plan (opengreeks → `libs/black76-math`, pyindicators, marginism,
   openalgo-heatmap) record their attribution here as they land; each keeps its MIT copyright notice.
