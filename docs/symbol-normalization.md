# Symbol / instrument normalization across sources

How ArthaYantra reconciles instrument & contract naming across the three external sources —
**Zerodha Kite**, **Upstox**, and the **OpenAlgo** appliance. Reference doc (active); the code is the
authority — this is the map.

## Core principle

**One canonical key; every source is translated to it at the edge.**

- Canonical model = the in-house `Instrument` record
  (`services/market-data-service/.../instruments/Instrument.java`).
- **Identity = `(exchange, tradingsymbol)` in Kite's tradingsymbol grammar**, stored in the
  `marketdata.instruments` master table (Postgres, PK `(exchange, tradingsymbol)`); wrapper
  `InstrumentKey.canonical()` → `"NFO:NIFTY26JUNFUT"`.
- **Numeric tokens are source-local session handles, NEVER identity.** Kite `instrument_token` (refreshed
  daily) and Upstox `instrument_key` numeric tokens are resolved *through* the master, never stored as the
  key, never compared across sources directly.
- **Kite's tradingsymbol is the lingua franca.** Kite is also the always-on **fallback** — the Upstox /
  OpenAlgo mappings are additive enrichment that must never break the feed.

## The instruments master

`marketdata.instruments` (Flyway `V002__instruments.sql`) carries the cross-source identity columns:
`exchange` + `tradingsymbol` (PK) · `instrument_token` (Kite, session-scoped) · `exchange_token` (stored,
unused for identity) · `name` (underlying root, e.g. `NIFTY`) · `segment` · `instrument_type`
(`EQ`/`FUT`/`CE`/`PE`) · `underlying_exchange` / `underlying_tradingsymbol` (soft ref, e.g. `NIFTY 50`) ·
`expiry` · `strike` · `tick_size` · `lot_size`.

Populated from the daily Kite `/instruments/{exchange}` CSV dump: stage → atomic upsert keyed by
`(exchange, tradingsymbol)` → dedup (highest `instrument_token` wins) → **tombstone rows that vanished**
from the dump (`InstrumentRepository`). Underlying ref derived by `UnderlyingRef` (Kite `name` → cash
tradingsymbol; index derivatives → `NSE`, BSE → `BFO`).

## Per-source bridges (translate at the edge)

| Source | Native identifier | How it reconciles to canonical |
|---|---|---|
| **Kite** | `tradingsymbol` + session `instrument_token` | `tradingsymbol` IS canonical. Token→key via the in-memory `InstrumentRegistry`/`TokenResolverAdapter`. `kite/wire/` DTOs mirror every field, then map to the domain `Quote`/`Candle`/`InstrumentRecord`. |
| **Upstox** | `instrument_key` string | Index/equity via static maps (`NSE_INDEX\|Nifty 50`, `NSE_EQ\|<ISIN>`) — `UpstoxQuoteInstrumentKeys`. **F&O key = an opaque numeric token NOT derivable from the symbol** → resolved via Upstox's public instrument-master JSON (`UpstoxFnoMasterClient` + `UpstoxInstrumentMaster`, the gzip `complete.json`), keyed by the tuple `(segment, underlying, type, expiry, strike)` — `UpstoxFnoInstrumentKeys`. |
| **OpenAlgo** | own symbol grammar (`DDMMMYY` token, differs from Kite) | Requests are **built from the structured leg fields, never from the Kite tradingsymbol** (`OpenAlgoSymbols`). Wire DTOs → domain via `OpenAlgoMappers`. |

## The four hard mismatches + how each is solved

1. **Opaque numeric F&O key (Upstox).** Not derivable from a name → looked up by the structured tuple
   `(exchange, underlying, type, expiry, strike)` against the source's own master. Tokens never matched directly.
2. **Strike float equality.** `18000` vs `18000.0` vs `18000.00` → `normalizeStrike()` strips trailing zeros
   (a null/zero strike collapses to null for futures) before tuple comparison.
3. **Expiry format zoo.** Kite `DDMMMYY` token · Upstox epoch-millis (23:59:59 IST) · canonical `LocalDate` —
   converted per source at the boundary.
4. **Underlying-name drift.** Kite dump `name="NIFTY"` → cash `"NIFTY 50"` (`UnderlyingRef`); index →
   `NSE_INDEX|Nifty 50` via the static map.
5. **An option's derivatives exchange is NOT a function of its root's name.** `NIFTY→NFO`,
   `SENSEX|BANKEX|FOCIT→BFO` happens to be complete for the roots listed *today*, so a prefix guess
   passes every test you would think to write — and silently mis-routes the first newly listed BSE
   root. Downstream that is not benign: the instrument-meta lookup 404s, falls back to an equity
   proxy with lot size 1, and yields a non-lot-aligned quantity that also 400s the Upstox margin call
   (`UDAPI1104`). **Resolution order, never a guess at any step:** market-data publishes
   `(exchange, tradingsymbol)` as a PAIR on every `/options/chain` leg (it already resolved the
   instrument to quote it, so this costs nothing) → strategy-signal's `OptionExchangeResolver`
   re-reads the master via `/instruments/search` when an older market-data omits the field, exact
   tradingsymbol match only, ambiguity refused → still unresolved ⇒ the leg stays visible to
   read-only analytics but `SignalEngine.tradeableLeg` refuses the ENTRY.
   `ay_scalper_chain_exchange_capability` reads 0 while market-data omits the field.

## Resilience + drift detection

- **Enrichment never breaks the feed.** Upstox master CDN unreachable / parse failure → keep the prior cache
  → fall back to the Kite path. Kite is the floor (`UpstoxFnoMasterClient` "NEVER propagates out of a lookup").
- **3 contract canaries** (`kite/canary/ContractCanary`, `upstox/canary/UpstoxContractCanary`,
  `openalgo/canary/OpenAlgoContractCanary`) probe each source's raw JSON **off the critical path** and
  recursively diff against a pinned manifest — but only for the **CONSUMED** fields (sentinels, not a full
  mirror). A renamed/removed/retyped field → ntfy critical (missing/type-change) or warning (added), so a
  source changing a name can't silently corrupt the feed.

## A tradingsymbol that CHANGES while the company keeps trading

Everything above answers "the same instrument, named differently by three sources at the same
moment". It does **not** answer "the same listing, renamed by the exchange" — NSE renames ~59 tickers
a year (measured over the 13.5 months of `nse_eod_bhavcopy` that exist), and on the day it happens
`marketdata.instruments` tombstones the old row (`is_active=false`) and admits the new one as a
brand-new PK with a fresh `first_seen_at`. **Nothing linked them.** The cost lands on the equity
screens, which require 252 sessions over a trailing 420-day window: a successor accrues from zero and
cannot re-enter for ~a year, even though the history exists in the database under the retired key.
61 symbols were invisible for that reason on 2026-08-03 (N2 / #1285).

`marketdata.symbol_lineage` (V054) records those links — **as DATA, never as identity**:

- **The canonical key is unchanged.** It is still `(exchange, tradingsymbol)`. Lineage is a
  *view-time join* a reader opts into when it wants depth; it is not consulted by the live feed, the
  ticker, the order path, or any cross-source mapper, and a reader that does not join it behaves
  byte-identically to before.
- **Derived, not transcribed** (`SymbolLineageDetector`): the successor's first-bar `prev_close`
  equals the predecessor's last-bar `close_price` exactly, within 5 sessions, 1:1 or dropped.
- **BSE `scrip_code` is the independent check, and it can REFUTE, not just confirm.** One scrip
  carrying two tickers is a rename observed without reference to any NSE price ⇒ `confirmed`.
  Neither ticker on BSE ⇒ `inferred` (nothing to check). **Both tickers on BSE under DIFFERENT
  scrip_codes ⇒ `refuted`** — BSE contradicts continuity, and the pair is withheld automatically.
  Measured across all 66 pairs: 58 / 6 / 2, and the 2 are `CREATIVE→CNL` and `WORTH→WORTHPERI`,
  amalgamations whose predecessors **never stopped** (both still print on BSE; they delisted from
  NSE only). `status` is a one-way ratchet — the detector may demote `ACTIVE→WITHHELD`, never promote.
- **Biased toward false negatives on purpose.** A wrong pair merges two unrelated companies' price
  histories into an owner-facing screen, so every ambiguity resolves to DROP. Known misses: a switch
  across a suspension longer than 5 sessions, concurrent-trading renames, anything before the
  bhavcopy floor.
- **A demerger on the SAME listing is the one thing no signal separates** — `TATAMOTORS→TMPV` keeps
  its BSE scrip AND its ISIN and stops printing on the switch date, so structurally it *is* a
  rename; what changed is the asset mix behind it. The escape hatch is data: `status = 'WITHHELD'`,
  seeded by V055, which the detector never promotes.
- **The lineage read is not just a relabel.** `eod_corporate_actions` records an action under the
  ticker live at its ex-date, so the CA plane is resolved through lineage on BOTH sides — otherwise
  a split dated after a rename adjusts only the successor's bars and opens a price cliff at the
  join (the audit-H6 defect `AdjustedEquityDailySql` exists to prevent). The walk is also bounded by
  the screen date, so a historical replay never sees a ticker that did not exist yet.
- `marketdata.symbol_rename_events` captures NSE "Change in Name" corporate actions (which arrive
  with their ISIN) so detection stays current from a primary source instead of inference alone. The
  feed names the COMPANY, not the predecessor ticker, so it corroborates rather than pairs.

## Known-thin areas

- **Upstox F&O coverage** was index+cash only pre-W-U4; the F&O master lookup (`UpstoxFnoInstrumentKeys` /
  `UpstoxFnoMasterClient`) is the recent "F&O token→Upstox-key map" the W-U4 scalp latency A/B needs — until
  it's exercised live, F&O legs fall back to Kite.
- Cross-source identity rests on the **tuple** `(exch, underlying, type, expiry, strike)` matching exactly —
  the strike/expiry normalizers are load-bearing; a new exchange or a non-standard expiry would need a
  normalizer tweak.

## One-line summary

Canonical = Kite `(exchange, tradingsymbol)` in Postgres; each source has an edge mapper to it; numeric
tokens are resolved through masters (never stored as identity); strike/expiry/underlying are normalized into
a tuple for cross-source match; **Kite is the always-on fallback**; three canaries watch for name drift.
