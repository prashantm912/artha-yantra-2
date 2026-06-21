# Upstox Market-Information analytics as a direct side-channel (NSE kept as fallback)

Status: accepted (2026-06-22)

Extends [ADR-0001](0001-broker-coupling-openalgo-live-upstox-historical.md). Master-plan §21 set the
broker-neutral rule ("route everything through OpenAlgo so the broker stays swappable"). ADR-0001 carved
the first sanctioned exception: a **direct Upstox** dependency for the *historical/expired-OI import* path,
because OpenAlgo cannot serve it. This ADR carves the **second** exception, for the same structural reason.

## Context

On **11 May 2026** Upstox launched two REST API families:

- **Market Information APIs** — `Get FII` (`GET /market/fii`), `Get DII`, `Get OI`, `Get Change in OI`,
  `Get Max Pain`, `Get PCR` (the last two with configurable **intraday-bucket** series).
- **Company Fundamentals APIs** — profile / balance-sheet / cash-flow / income-statement / **share-holdings
  (Promoter·FII·DII·Public)** / key-ratios / **corporate-actions (div·bonus·split·rights)** / competitors,
  keyed by ISIN.

These are server-side REST endpoints. The **Upstox-Java SDK lag is irrelevant**: as of v1.25.0 the Java SDK
wraps only the Payments API (the Python SDK v2.27.0 added the wrappers), but the endpoints are
language-agnostic and our house pattern is **hand-rolled broker REST with full-mirror DTOs** (same as Kite
`kite/wire/`). We will not import the Upstox-Java SDK for these.

**OpenAlgo does not normalize any of this.** Verified against the 2.0.1.4 checkout's `restx_api/__init__.py`
namespace list: `quotes / multiquotes / history / depth / optionchain / optiongreeks / expiry / margin / …`
— **no** `maxpain / pcr / fii / participant`. Upstox analytics exist inside OpenAlgo only as internal UI
blueprints (`oitracker`, `gex_service`), never as `/api/v1`. So there is no broker-swappable path to this
data; consuming it = direct Upstox coupling, exactly the situation ADR-0001 addresses.

Today we obtain the equivalents the hard way: **FII/DII cash** by scraping NSE (`LiveFiiDiiFetcher`,
`/api/fiidiiTradeReact`), **participant-wise OI** by scraping the NSE SEBI CSV (`LiveParticipantOiFetcher`),
**PCR** by a back-end fold of captured chain OI (`PcrHistoryService`), and **Max Pain** not at all.

## Decision

Adopt the Upstox **Market Information** family as a **second Upstox-specific side-channel, behind ports**,
canary-gated, **with the existing NSE scrapers kept as the swap-out fallback** (so we are not hard-locked to
Upstox). Hand-rolled `upstox/wire/` full-mirror DTOs; a dedicated long-lived **analytics access token**
(generated in the Upstox Developer Apps dashboard) isolated from the live execution session.

Scope, per verified field shapes:

| Capability | Today | ADR-0002 action | Why |
|---|---|---|---|
| FII/DII **cash** | NSE scrape | **Replace** (primary = `Get FII NSE_EQ\|CASH` + `Get DII`); NSE → fallback | kills a fragile scrape |
| FII **derivative** long/short | not surfaced | **Add** (`Get FII NSE_FO\|*`) | unlocks W3 *FII Derivative Stats* |
| **PCR** (authoritative + intraday) | BE fold | **Add** `Get PCR` | unlocks W3 *OI-Statistics PCR series* |
| **Max Pain** | not built | **Add** `Get Max Pain` | new capability |
| **Participant-wise OI** (FII/Pro/DII/Client) | NSE CSV | **Keep NSE** | `Get FII` is **FII-only** — no 4-client split |
| Per-strike OI | OpenAlgo `/optionchain` | **Keep OpenAlgo** | already broker-swappable |
| Fundamentals (corp-actions, holdings, ratios) | NSE CA-scrape / none | **Defer to W3** (record only) | corp-actions could replace the EOD CA-scrape; the rest powers a W3 equity page |

## Considered options

- **Keep computing/scraping ourselves** — rejected for FII/DII (NSE scraping is fragile: rate-limits,
  HTML-sniff non-trading days) and for Max Pain/intraday-PCR (we simply do not have them). Retained as the
  **fallback** so leaving Upstox is a config flip, not a rewrite.
- **Wait for OpenAlgo to normalize these** — rejected: they are vendor analytics, not a common broker
  surface; OpenAlgo exposes them only as its own UI, and we consume OpenAlgo API-only.
- **Direct Upstox behind a port (chosen)** — same anti-corruption pattern as ADR-0001; isolated, gated,
  reversible to the NSE fallback.

## Consequences

- Two sanctioned Upstox couplings now exist (historical-import = ADR-0001; analytics = this), **both behind
  ports**. The analytics one is fallback-able (NSE); the historical one is not (no alternative). A future
  reader seeing a direct Upstox analytics dependency should read this ADR, not "fix" it back to OpenAlgo.
- **History only from 1 Apr 2026** (Upstox's stated earliest) — adequate for live/recent value-verify; deep
  history still belongs to the ADR-0001 (B) importer.
- **Cost tier is undisclosed** on every Upstox doc page checked. No hard commitment until a **live `200`**
  (vs `403`/subscription error) confirms our Upstox Plus covers it; until then NSE stays primary and the
  Upstox flags stay dormant (same default-off discipline as the data-foundation milestone).
- **Participant-wise OI stays on NSE** — Upstox `Get FII` does not carry the SEBI Pro/DII/Client breakdown.
- Gated on the **same Upstox activation** as the data-foundation milestone (app review + F&O/Equity
  segments) → no new wait, only added upside on the same unlock.
