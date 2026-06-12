# ArthaYantra 2.0 — Stage F: Options Analytics + Paper Trading + Universe Pinning

**Stage letter / name:** F — Options analytics + paper trading
**Plan macro-phase:** Phase 5 — Options analytics + paper trading
**Phases covered:** 42, 42A, 42B, 43, 43A, 43B, 44, 44A *(42A/42B/43A/43B/44A added by the 2026-06-12 owner feature selection; a suffixed phase runs immediately after its base number in the global build sequence)*
**Prerequisite stages:** A (foundations, compose/auth/mock), B (market-data spine — options chain endpoints/topics, snapshots, `index_constituents` accrual + constituents REST, last-tick map; futures data slice incl. the term-structure endpoint + INDIA VIX pin (Phase 15A) and continuous futures + `roll_events` (Phase 15B) `[FP-9, FP-11b, FP-14, owner selection 2026-06-12]`), C (strategy engine MVP — signals, registry, publish guard, BPB score breakdown), D (backtest/optimizer — `FillSimulator` port `ltp_slippage/v1`, jobs spine, `backtest_runs` incl. `universe_checksum`, stress-test), E (frontend UX — dashboard, stores, editor, leaderboard/compare; signal/trade chart marks on the lightweight-charts chart page (Phase 40) via the Phase 40A chart-marks surface (lightweight-charts series markers) `[FP-67, owner selection 2026-06-12]` `[A13, 2026-06-12]`)
**Common reference:** [ARTHAYANTRA_2_COMMON_REFERENCE.md](ARTHAYANTRA_2_COMMON_REFERENCE.md) — app-wide conventions, ADR decision tables (D1–D18, amendments A1–A13), stack versions, error-code taxonomy (§8.3), repo layout, phase index (§5), timeline (§15.2/§16.2), ER overview (§9.4).

**Stage goal.** Close the platform out to feature-complete (the §15.3 "Feature-complete" milestone) by landing the three remaining capabilities that depend on the whole spine being in place: (1) the **options workbench** — a live calls/strike/puts chain grid with IV/OI/Greeks, *actually-wired* filters (the v1 dead-filter defect fixed), PCR / IV-smile / OI-profile analytics tabs, and snapshot-history queries against the self-archived ≥ 5-year IV archive; (2) the **paper-trading ledger** in strategy-signal-service, whose fills run through the **same `FillSimulator` implementation in the strategy-engine JAR** that backtest replay uses, so paper P&L is comparable to the backtests that motivated the strategy by construction (the Q1 answer, "rest" — the audit columns and wiring); and (3) **universe pinning by copy** — `index_constituents` universes resolved via market-data REST at *submission time*, embedded in `jobs.request` JSONB, hashed into `backtest_runs.universe_checksum`, surfaced in the editor and leaderboard, lifting the Phase 21 publish guard (the S8 answer, "rest"). All three are demonstrable on the mock stack with zero Kite credentials (ADR D13). **The 2026-06-12 owner feature selection extends this stage** with five additional phases and two phase extensions, governed by ADR amendments A7–A12 (COMMON §6): the **futures workbench** `/futures` (Phase 42A) `[FP-9, FP-10]`, **IV rank/percentile analytics** over the snapshot archive (Phase 42B) `[FP-12]`, the **paper account + capital/margin model with suggested quantities and global risk limits/kill switch** (Phase 43A) `[FP-41, FP-42, FP-43]`, the **derivative paper lifecycle** — expiry settlement + rollover prompts (Phase 43B) `[FP-2]`, and the **trade journal** (Phase 44A) `[FP-66]`; Phase 42 additionally gains an India-VIX chip `[FP-14]` and Phase 44 the `futures_of_underlying` resolver leg `[FP-11a]`. The feature-complete milestone stays at the Stage F exit, and every addition remains demonstrable on the mock stack with zero Kite credentials (D13). `[owner selection 2026-06-12]`

> **Provenance note.** Cross-references in this file resolve inside the new file set. References tagged `[plan §x.y]`, `[ADR Dn / An]`, or `[review Qn/Sn/BPn]` are breadcrumbs to the four (now-deleted) source docs; the substantive content is inlined below or, where it is genuinely app-wide, cited to **COMMON §n**. Where an ADR amendment (A1–A13) governs over older D-section text, the amended form is stated inline. Content added by the 2026-06-12 owner feature selection is tagged `[FP-N, owner selection 2026-06-12]` (N = proposal number in docs/ARTHAYANTRA_2_FEATURE_PROPOSALS.md) and is governed by ADR amendments A7–A12 (COMMON §6).

---

## Part 1 — Design reference (inlined source detail this stage needs)

This part inlines the plan/review material the three phases consume at implementation time. Phase specs in Part 2 point here or at COMMON; a verifier never needs the deleted source docs.

### F.1 Service ownership recap — who owns what in this stage [plan §5.2.2, §5.2.3; ADR D7]

Two services do all the work in Stage F; the frontend consumes both through the gateway.

- **market-data-service** (Java 21, Boot 3.5.x + Modulith, 640 MB) — owns **everything Kite** and the `marketdata` schema (single writer, ADR D10). Relevant here: the live options chain via batched quotes with **computed Black-76 IV/Greeks** (fixing v1's hard-coded zeros), 5-minute chain snapshots into the `options_chain_snapshots` hypertable, the `index_constituents` accrual table + constituents REST endpoint, and the Redis last-tick map. Publishes `options.chain` (and `ticks.*`, `candles.1m.*`). [plan §5.2.2 — inlined]
- **strategy-signal-service** (Java 21, Boot + Modulith + ta4j + strategy-engine JAR, 640 MB) — owns the `strategy` schema and the paper-trading ledger; resolves `index_constituents` universes **via market-data REST only** (it holds **no `marketdata` grant**, D7/D10), and runs paper fills through the shared JAR's `FillSimulator`. [plan §5.2.3 — inlined]
- **frontend-ui** (Angular 21 SPA on nginx, 32 MB) — the `/options` and `/paper` pages and the editor's universe label; served through the gateway so the browser sees one origin (no CORS). [plan §5.2.6]

**The D7/D10 grant model is load-bearing for Phase 44:** strategy-signal-service never reads the `marketdata` schema directly. It obtains index membership over the market-data REST endpoint (§F.7 below) and resolves the universe in-process from that ordered list + instrument-master filters. backtest-service has a **read-only** `marketdata` grant for candle replay but is *not* the resolver — resolution is strategy-signal-service's job at submission time. (Acceptance FAIL for Phase 44: "strategy-signal-service reading `marketdata` directly".)

### F.2 §4.5 routes consumed by this stage [plan §4.5 — inlined]

All routes lazy (`loadComponent`/`loadChildren`), guarded by an auth guard against the gateway session (ADR D13) except `/login`.

| Route | Purpose | Key components / stores |
|---|---|---|
| `/options` | Live options chain: calls/strike/puts grid with IV/OI/Greeks, PCR, IV smile, OI profile; **filters actually wired to the API** (fixes the v1 dead filters) | `ChainTable` (virtual scroll), ECharts analytics tabs, `OptionsStore` fed by the `options.chain` topic |
| `/paper` | Paper positions, ledger, realized/unrealized P&L, equity curve, mark-to-market from live ticks | `PaperStore`, lightweight-charts equity curve |
| `/strategies/:id/edit` | Author YAML against `strategy-schema/v1` — Phase 44 adds the **"Published Universe (as of …)" label** for `index_constituents` strategies | Monaco + monaco-yaml, `StrategiesStore`, `JobsStore` |
| `/backtests/compare` | Up to 4–6 runs side-by-side; sweep explorer — Phase 44 adds **universe-checksum mismatch flagging** beside `dataHash` | ECharts 5.6, `BacktestsStore` |
| `/futures` | Futures workbench: term-structure cards, basis history, calendar-spread/rollover cost, OI-buildup heat tiles (§F.10) `[FP-9, FP-10, owner selection 2026-06-12]` | `FuturesStore`, ECharts cards/tiles — same lazy-chunk/store patterns as `/options` |
| `/journal` | Trade-journal weekly review: filterable entry list; drawer host (§F.14) `[FP-66, owner selection 2026-06-12]` | `JournalStore`, `JournalDrawer` (also opened from signals/paper/backtest-trade surfaces) |

**Real-time update strategy that the chain page must honor [plan §4.6 — inlined].** Per-symbol topics, **not the firehose**: subscriptions follow `ticks.{exchange}.{tradingsymbol}` / `options.chain` so the options page no longer receives every tick for every symbol (a Phase 42 acceptance FAIL is "chain page subscribing to the tick firehose"). Tick handlers write into a per-symbol "latest value" map; a single `requestAnimationFrame` loop flushes the map into store signals at most once per frame (≈16 ms) — under burst load the UI renders the newest price, never a queue of stale ones. Zoneless + signals means only components whose read signals changed re-render; `OnPush` is the default. Virtualized tables (`p-table` virtual scroll, CDK-based, fixed row height) keep only ~30 DOM rows live for the ≤ ~1,500-row chain; price-change flashes are CSS-class pulses keyed off signal updates, not row re-creation. On WS reconnect each store re-subscribes and re-fetches its REST snapshot to heal gaps (at-least-once display semantics).

**Decimal handling [plan §4.9 — inlined].** Prices arrive as JSON **decimal strings** from the DTO layer and are formatted/compared via a thin decimal utility — never `parseFloat` for arithmetic, preserving the exact-decimal convention end-to-end. Derived values (PCR, score breakdowns, P&L) use `computed()` — never stored; pure pipes for formatting; stable `trackBy` keys = `exchange:tradingsymbol`. Tabular numerals on numeric columns.

### F.3 Options chain — market-data REST + topic contract [plan §5.2.2 — inlined; ADR D8]

market-data-service exposes the chain endpoints the `/options` page consumes (the page is **pure frontend** in Phase 42 — these endpoints/topics shipped live, mock-capable, in Stage B / Phase 15). Prices serialize as decimal strings; timestamps carry the `+05:30` IST offset.

| Method | Path | Purpose | Request / Response (described) |
|---|---|---|---|
| GET | `/api/v1/market/options/chain` | Live chain | Query: `underlying`, `expiry` (default nearest). 200 chain with **LTP, OI, volume, computed IV/Greeks, PCR, spot from the underlying index quote** (not a strike average — fixes v1's fake spot) |
| GET | `/api/v1/market/options/chain/history` | Snapshot replay | Query: `underlying`, `expiry`, `at`. 200 nearest stored snapshot — drives the page's **history mode** against the self-archived `options_chain_snapshots` data |
| POST | `/api/v1/market/options/snapshot` | Force snapshot now | 202 with `jobId` |
| GET | `/api/v1/instruments/{underlying}/expiries` | Option expiries from the master | 200 sorted expiry dates — feeds the expiry filter |
| GET | `/api/v1/instruments/{underlying}/strikes` | Strikes for an expiry | Query: `expiry`. 200 sorted strike list — feeds the strike-window filter |

- **Topic:** `options.chain` (Redis pub/sub → edge-gateway WS bridge) — chain refresh with IV/Greeks, ~30 s cadence during market hours [COMMON §7.3.1 pub/sub catalog]. The `OptionsStore` subscribes to **this topic plus the REST snapshot**, never the per-symbol tick firehose.
- **IV/Greeks provenance:** every snapshot row carries the Black-76 inputs (`forward_price`, `risk_free_rate`, `price_source`) so a stored IV is exactly recomputable — see §F.5 schema. The chain endpoint returns non-zero IV in mock (a Phase 42 PASS criterion: "chain renders non-zero IV in mock").
- **Performance target [plan §5.10 / COMMON §8.4]:** options chain full refresh (~200 strikes ×2) ≤ 3 s — rate-limit bound (~2 quote calls at the default 250-instrument batch, 1 call/s).

> **NSE expiry-day fact [review SPIKES].** NSE index weeklies are **Tuesdays** (single-expiry-day rule effective September 2025) — not the legacy "Expiry Thursday". Relevant to nearest-weekly expiry resolution in the chain and to options strategies' `expiry_day` session style.

### F.4 `options_chain_snapshots` hypertable — column descriptions [plan §6.4 — inlined; ADR D10, amendments A2/A3]

The only market data that is **irreplaceable** (Kite offers no historical IV/OI chains). **Hypertable**, 1-day chunks, compress after 7 d, **retain ≥ 5 years** (amendment A2 raised the floor from ≥ 2 y; a floor, not a cap — the §6.5 no-drop default with export-before-drop continues above it). The Phase 42 history mode and IV-smile/OI-profile analytics read this table via the `/chain/history` endpoint.

| Column | Type | Notes |
|---|---|---|
| `ts`, `underlying`, `expiry`, `strike`, `option_type` | TIMESTAMPTZ, TEXT, DATE, NUMERIC, TEXT | **Composite PK** (`option_type` CE/PE) |
| `tradingsymbol` | TEXT | Joins back to `instruments` |
| `ltp`, `bid`, `ask`, `spot_price` | NUMERIC(18,4) | Real underlying spot stored per row (fixes v1's strike-average fake) |
| `volume`, `oi`, `oi_change` | BIGINT | OI/volume drive the OI-profile and PCR analytics |
| `iv`, `delta`, `gamma`, `theta`, `vega`, `rho` | NUMERIC(12,6) | **Computed Black-76 at capture time** in market-data-service (fixes v1's IV=0 dead end); `iv` drives the IV-smile tab |
| `price_source` | TEXT | Which quote the solver priced from (e.g. LTP vs mid) — provenance for the stored IV/Greeks |
| `forward_price`, `risk_free_rate` | NUMERIC(18,4), NUMERIC(8,5) | Exact solver inputs captured per row (forward per the S1 convention precedence; pinned `r`) — makes every stored IV/Greek recomputable from the row alone, so a later solver fix backfills the archive instead of losing it |

### F.5 §7.1 *Costs & fills* — the full cost model consumed by paper [plan §7.1 *Costs & fills*, §7.4 *Execution model*; review Q1; ADR amendment A5]

This block is consumed **identically** by backtest replay (Stage D) and the paper ledger (Phase 43) — same `costs` block, same `FillSimulator` port, same JAR. The keys are part of `strategy-schema/v1` at the Phase 2 freeze (validated from day one); their backend consumers land in Phases 3 (backtest) and 5 (paper) — so no post-freeze schema-shape change occurs.

`backtest.defaults.costs` carries:

1. **Brokerage legs** (preserved, not regressed to bps-only): `per_lot_inr` for options, `pct_per_side` for equities. *(The review's bps-only framing was a regression; the plan's brokerage legs stand — review Q1.)*
2. **Slippage knob** — expressed as **`slippage_ticks`** (integer ticks) **or** **`slippage_bps`** (basis points of fill price); **at most one of the two**. When neither is set the engine applies per-instrument-class fallbacks:
   - **equities:** 5 bps;
   - **options:** `max(1 tick, half the quoted spread)` when bid/ask is known from the last tick or chain snapshot, degrading to **1 tick** without a quote. *(The review's flat "10 bps of premium" is quantitatively wrong: one ₹0.05 tick on a ₹10 OTM premium is already 50 bps — hence the spread-aware options fallback.)*
3. **Optional statutory fee schedule `fees{}`** — flat per-order brokerage plus **side-aware percentage legs**: STT on sell-side option premium, exchange transaction charge on premium, GST on (brokerage + transaction charge), stamp duty on the buy side, SEBI turnover fee. Defaulted from the current Zerodha/NSE schedule when omitted, applied **identically in backtest and paper**.
   - **Documented caveat:** a flat per-lot cost knob *understates the premium-proportional statutory charges* on option strategies — which is exactly why the schedule exists.
   - **Open item (pinned at implementation) [review §4 item 3]:** the current Zerodha brokerage and NSE/SEBI/GST/STT/stamp **rate values** are captured as the fee-schedule defaults *when the `FillSimulator` cost legs are coded*, with a config-refresh note. The values are not invented here — they are pinned at coding time.

**Fill semantics (`FillSimulator` port, shipped implementation `ltp_slippage/v1`) [plan §7.4 — inlined]:** thin and deterministic. Fill price = **reference price** (next-bar **open** in replay; **next-tick LTP** in the live paper ledger) **adjusted by the configured slippage**, no partial fills. Because backtest-service and strategy-signal-service link the **same JAR**, backtest fills and paper fills are one implementation — the validate-in-backtest → trust-paper loop holds **by construction**, and a fill-vector suite (Stage C/D golden vectors) pins the parity. Richer microstructure (bid/ask-aware option fills, partial fills) is a later `FillSimulator` implementation plus an additive `paper_fills` child table (ADR D17 additive-first), **never a ledger refactor**.

### F.6 Paper-trading ledger — spec + `strategy` schema tables [plan §5.2.3, §6.4 — inlined; review Q1; ADR D7/D18]

**Ledger spec [plan §5.2.3].** The paper-trading ledger (simulated fills, positions, P&L) lives in strategy-signal-service. Paper fills run through the **same `FillSimulator` implementation** in the strategy-engine JAR that backtest replay uses (§F.5), with the full cost model (brokerage + slippage + the optional statutory fee schedule), so **paper P&L stays comparable with the backtests that motivated the strategy**. A paper-local fill path outside the engine JAR is an explicit acceptance FAIL (Phase 43).

**Owned data [plan §5.2.3]:** PG schema `strategy` (`strategies`, `strategy_versions`, `signals`, **`paper_orders`**, **`paper_positions`**, `notification_events`, audit log).

#### `paper_orders` / `paper_positions` — the paper ledger [plan §6.4 — inlined verbatim]

`paper_orders(id, signal_id NULL FK, exchange, tradingsymbol, side, qty, order_type, limit_price NUMERIC, status OPEN/FILLED/CANCELLED, placed_at, filled_at, fill_price NUMERIC)`.

`paper_positions(id, exchange, tradingsymbol, side, qty, avg_entry_price NUMERIC, realized_pnl NUMERIC, status OPEN/CLOSED, opened_at, closed_at)` with a **partial-unique constraint on `(exchange, tradingsymbol, side) WHERE status='OPEN'`**.

**Indexing [plan §6.7]:** `paper_orders (placed_at DESC)` plus the partial unique open-position key — serves the ledger views.

**Unrealized P&L is never stored** — it is computed on demand from the Redis last-tick map (a stored-unrealized-P&L column is an explicit acceptance FAIL, Phase 43).

**Fill-audit columns on `paper_orders` [plan §6.4 — inlined; review Q1].** `paper_orders` additionally carries fill-audit metadata **written by the strategy-engine JAR's `FillSimulator`** (§F.5), not by any paper-local code:

| Column | Type | Notes |
|---|---|---|
| `fill_simulator` | TEXT | Implementation id, e.g. **`ltp_slippage/v1`** (asserted in the Phase 43 IT) |
| `slippage_applied` | NUMERIC(18,4) | Signed delta between reference price and fill price |
| `quote_bid`, `quote_ask` | NUMERIC(18,4) NULL | Last-known quote at the fill decision, from the Redis last-tick map or chain snapshot, **when available** |

Historical fills stay auditable against the model that produced them. **Partial fills are deliberately not modeled**; if a richer simulator ever needs them, the additive migration is a **`paper_fills(order_id FK, seq, qty, price, ts)`** child table (ADR D17 additive-first), **never a ledger rework**. *(Note: the review's proposed `paper_trades` table does not exist — the audit columns live on `paper_orders`, and the closed-trade ledger is served by querying orders/positions — see endpoints below.)*

#### Related `strategy`-schema context [plan §6.4 — inlined]

- **`signals`** — generated calls with the explainability payload; `POST /signals/{id}/taken` optionally opens a paper position. Columns of interest to paper: `id BIGINT PK`, `strategy_version_id` UUID FK (pins the firing config), `exchange/tradingsymbol/interval`, `signal_type/side`, `entry_price/stop_loss/target NUMERIC(18,4)`, `composite_score`, `score_breakdown` JSONB, `status` (ACTIVE/EXPIRED/TAKEN/DISMISSED), `generated_at/expires_at`. `paper_orders.signal_id` is a **same-schema FK** into this table.
- **ER relationships [COMMON §9.4]:** `SIGNALS |o..o{ PAPER_ORDERS : "optionally from"` and `PAPER_ORDERS }o--|| PAPER_POSITIONS : "builds"` — all same-schema FKs, no cross-schema references.

#### Paper endpoints [plan §5.2.3 — inlined; ADR D8]

All under the `/api/v1/paper/**` prefix, routed by edge-gateway to strategy-signal-service; standard error envelope on every non-2xx [COMMON §8.3].

| Method | Path | Purpose | Request / Response (described) |
|---|---|---|---|
| GET | `/api/v1/paper/positions` | Open simulated positions | 200 list with **mark-to-market P&L from the last-tick map** |
| GET | `/api/v1/paper/trades` | Closed-trade ledger | Query: `from/to`, `limit/offset`. 200 paged list |
| GET | `/api/v1/paper/pnl` | Aggregate P&L curve | Query: `period`. 200 daily equity points + summary (**win rate, expectancy**) |
| POST | `/api/v1/paper/orders` | Simulate an entry (from a signal or manual) | Body: `signalId`, `qty`, optional `price`. **201** position DTO; fills via `ltp_slippage/v1` against the **next-tick LTP + full cost model** |
| POST | `/api/v1/paper/positions/{id}/close` | Close at market/stated price | 200 realized trade DTO |
| POST | `/api/v1/paper/reset` | Wipe paper ledger | **204** — **guarded by a confirm flag in the body** |
| POST | `/api/v1/signals/{id}/taken` | Owner executed manually at broker | Body: optional fill price/qty/note. 200 updated signal; **optionally opens a paper position** |

**Mark-to-close [plan §5.2.3 / phase deliverable]:** a **15:45 mark-to-close** runs for intraday styles (the `session.style: intraday` archetype, §F.8) so intraday paper positions don't carry past the session.

### F.7 Universe pinning by copy — the S8 "rest" [plan §6.4, §7.4 *Universe pinning*; review S8; ADR D7/D10]

The reproducibility goal: NIFTY-100 membership drifts, so a sweep launched across a constituent rebalance must not split its own leaderboard. The S8 design resolves the universe **once, at submission, and pins it by copy** — never an FK, never lazy resolution inside workers.

**Source table — `marketdata.index_constituents` [plan §6.4 — inlined; built in Stage C / Phase 22].** Append-only, owned by market-data-service (single writer, D7/D10). The Kite dump carries **no** membership data, so `universe.mode: index_constituents` resolves against this table, fed from the NSE Indices published constituent CSVs (e.g. `ind_nifty100list.csv`) on the daily 08:30 IST sync; the mock profile bundles a fixture CSV so the mode works credential-free (D13).

| Column | Type | Notes |
|---|---|---|
| `index_name` | TEXT | Normalized to the index instrument's tradingsymbol, e.g. `NIFTY 100` |
| `exchange`, `tradingsymbol` | TEXT | Constituent's stable key; soft reference to `instruments` |
| `as_of_date` | DATE | Fetch date (IST); **PK `(index_name, as_of_date, exchange, tradingsymbol)`** |
| `fetched_at` | TIMESTAMPTZ | Cache-audit column |

> NSE publishes *current* lists, not point-in-time archives, so membership history is reconstructable only for dates **on or after capture begins**; backtest windows that predate accrual carry the documented **survivorship-bias caveat** (below). *(Open item [review §4 item 2]: NSE constituent-CSV source verification — format, URL stability, cadence, ToS — was a pre-Phase-22 gate; by Phase 44 the accrual table and fetcher already exist.)*

**Resolution — REST only, never a schema read [plan §7.4 — inlined; D7/D10].** strategy-signal-service resolves `index_constituents` universes **via market-data-service's constituents REST endpoint** (§F.7 endpoint below) plus the instrument-master filters, returning an **ordered `(exchange, tradingsymbol)` list + a SHA-256 checksum** over its canonical JSON. strategy-signal-service **holds no `marketdata` grant** — it never reads the schema directly (the consumer rule, D7/D10). This is the resolver the Phase 44 deliverable builds.

**Constituents REST endpoint [plan §5.2.2 — inlined; market-data-service]:**

| Method | Path | Purpose | Request / Response (described) |
|---|---|---|---|
| GET | `/api/v1/instruments/indices/{index}/constituents` | Point-in-time index membership | Query: `asOf` (optional; defaults to the latest `as_of_date`). 200 **ordered `(exchange, tradingsymbol)` list with the resolved as-of date**, served from the `index_constituents` accrual table (Kite's dump carries no membership data); consumed by strategy-signal-service at publish/universe-resolution time **over REST, never via direct schema reads** (D7/D10) |

**Pinning by copy, never FK [plan §6.4, §7.4 — inlined].** At backtest/optimization submission the resolved list is **copied into the job's `request` JSONB** and the hash lands in **`backtest_runs.universe_checksum`**. Soft references only — **no cross-schema FK** (the §6.2 no-cross-schema-FK rule, the §6.4 "soft cross-schema reference" convention). **Every trial in a sweep reuses the embedded copy**, so a constituent rebalance mid-sweep can never split a leaderboard; `data_hash` continues to flag any drift in the candles actually read. Resolution is **submission-time only** — never lazy resolution inside workers (a Phase 44 acceptance criterion).

`backtest_runs.universe_checksum` [plan §6.4 — inlined; column exists since Stage D / Phase 30]:

| Column | Type | Notes |
|---|---|---|
| `universe_checksum` | TEXT NULL | SHA-256 over the ordered resolved universe copied into `jobs.request` at submission (the universe-pinning hash); **NULL for explicit/single-instrument runs** where `data_hash` alone suffices |

**Honesty clauses [plan §6.4, §7.4 — inlined].**
- **Survivorship bias:** v1 resolves *current* membership. Backtest windows that **predate constituent capture** permanently carry the documented survivorship-bias caveat (NSE publishes only the current list). The editor surfaces this caveat for windows predating capture (Phase 44 deliverable).
- **Point-in-time later:** point-in-time `as_of: trade_date` resolution over the accrued `index_constituents` history is a **noted later enhancement, not built now**.

**Publish-guard lift [Phase 21 / Stage C; Phase 44 deliverable].** Stage C's Phase 21 set a publish guard refusing `index_constituents` strategies *because no resolver existed*. Phase 44 builds the resolver, so the guard is **lifted** — `index_constituents` strategies become publishable.

**Leaderboard/compare flagging [plan §7.6, §7.7 — inlined; Phase 44 deliverable].** The leaderboard/compare UI gains **universe-checksum mismatch flagging beside `dataHash`** — two runs with differing `universe_checksum` are not like-for-like, rendered with a mismatch banner exactly as `dataHash` mismatches already are (cross-sweep comparison keyed by `(strategyId, version, dataHash)` per §7.6; the universe checksum is the additional like-for-like axis).

### F.8 §7.7 / §4.5 screens this stage builds [plan §7.7, §4.5 — inlined]

**Options chain page (`/options`) [plan §4.5, §4.6, §4.7 — inlined].** The owner's workbench:
- **ChainFilterBar** — underlying / expiry / strike-window filters, **actually wired to the API** (refetch on change; fixes the v1 dead-filter defect). Expiry options come from `GET /instruments/{underlying}/expiries`; strikes from `GET /instruments/{underlying}/strikes`.
- **ChainTable** — `p-table` **virtual scroll**, **calls / strike / puts grouping**, **ITM/OTM highlighting**, tabular numerals; up to ~1,500 rows across strikes/expiries with only ~30 DOM rows live.
- **Analytics tabs (ECharts 5.6, canvas renderer)** — **PCR trend**, **IV smile**, **OI profile**; all **derived from store signals via `computed()`, never stored**. ECharts loaded only on this chunk (lazy per route).
- **Off-hours staleness chips** — surface stale data when the market is closed.
- **History mode** — queries `GET /options/chain/history` against the self-archived snapshots; returns a stored snapshot for the IV-smile/OI-profile tabs over historical IV.
- Fed by `OptionsStore` ← `options.chain` deltas + REST snapshot (not the tick firehose).

**Paper page (`/paper`) [plan §4.5, §4.7 — inlined].** `PaperStore` + the page:
- **Positions** — open positions with **live P&L** (mark-to-market from the last-tick map via `computed()`).
- **Ledger** — the closed-trade ledger (`GET /paper/trades`).
- **Equity curve** — **lightweight-charts ≥5.2** (the equity/drawdown/paper-P&L surface; same pinned dep as the chart page [A13]).
- **Dashboard PaperPnl widget** — the at-a-glance paper P&L tile on `/dashboard` (the dashboard's widget grid already lists paper P&L per §4.5).

**Editor universe label (`/strategies/:id/edit`) [plan §7.7 screen 2, Phase 44 deliverable].** For `index_constituents` strategies the editor shows a **"Published Universe (as of …)" label** with the **constituent count + checksum**, and the **survivorship-bias caveat** for windows predating capture.

**Signal reasoning breakdown [plan §7.7 screen 7 — reference].** Paper positions opened from a taken signal carry the signal's `score_breakdown`; the existing reasoning-breakdown component (Stage C/E) renders per-indicator `weight × score`, optional-indicator reinforcement, and the composite-vs-threshold gauge. No new work here — paper just links to it.

### F.9 Composite-score formula (governing form) [ADR amendment A1; plan §7.1 — reference]

For any signal-derived paper entry, the composite that fired is the **weight-normalized** form (amendment A1 supersedes D18's literal `sum(weight × normalized_score)` phrasing):

`composite = ( Σ_required wᵢ·sᵢ + Σ_activated-optional wⱼ·sⱼ ) / ( Σ_required wᵢ + Σ_activated-optional wⱼ )`

An **optional** indicator *activates* (counts in numerator and denominator) only when (a) its own score ≥ `optional_min_score` (default 0.6) **and** (b) the required-only composite ≥ `threshold − optional_gate_margin` (default 0.15). Optional indicators can only **activate, never gate or carry a signal alone**. *(Inlined here only as the governing reference for signal-strength values shown on the paper page; full score-breakdown contract lives in Stage C / COMMON.)*

### F.10 Futures workbench — `/futures` route [FP-9, FP-10, owner selection 2026-06-12; ADR A11]

The futures companion to §F.8's options workbench: a lazy `/futures` route built on the **same store/ECharts patterns as `/options`** (per-symbol topics, `computed()` derivations, canvas renderer, ECharts confined to the lazy chunk). It is **pure frontend** in Phase 42A — every input ships in Stage B:

- **Term-structure cards** — near/next/far contract LTP, **basis vs spot** (absolute + annualized), **contango/backwardation state**, days to expiry; fed by `GET /api/v1/market/futures/term-structure?underlying=` (Stage B Phase 15A) `[FP-9]`.
- **Basis history chart** — the basis series computed from **cached FUT vs spot candles** (existing candle endpoints; no new backend) `[FP-9]`.
- **Calendar-spread / rollover-cost panel** — next-month − front-month spread, absolute + annualized % of spot — the cost of rolling a position `[FP-9]`.
- **OI-buildup heat tiles** — **long buildup / short buildup / long unwinding / short covering**, consuming the Phase 17 screener's `oi_buildup` preset (`GET /api/v1/market/screener?preset=oi_buildup`, classified server-side from cached close + per-bar OI — the Stage B Phase 15A data slice) `[FP-10]`. The classification lives **in the preset**; the tiles only render it (re-deriving it client-side is a Phase 42A FAIL).
- Off-hours staleness chips and decimal-string handling exactly per §F.2 (never `parseFloat` for arithmetic; derived values via `computed()`, never stored).

**Mock parity (D13):** the Phase 15A endpoint and the `oi_buildup` preset are fixture-driven in mock, so every card and tile renders with zero Kite credentials.

### F.11 IV analytics — `iv_daily_summary` rollup + IV rank/percentile [FP-12, owner selection 2026-06-12]

The highest-leverage read of the platform's **only irreplaceable dataset** (§F.4): a daily IV rollup plus a trailing-1-year rank/percentile lens. Owned end-to-end by market-data-service (single writer, D10).

**`marketdata.iv_daily_summary`** — one row per underlying per trading day, rolled up at **16:00 IST** (MarketCalendar-gated) over that day's `options_chain_snapshots`. Because every snapshot row carries its Black-76 inputs (§F.4), the rollup is **recomputable retroactively over the whole archive** — a solver fix or method change re-derives history instead of losing it.

| Column | Type | Notes |
|---|---|---|
| `underlying`, `summary_date` | TEXT, DATE | **Composite PK** (IST trading day) |
| `atm_iv` | NUMERIC(12,6) | ATM-strike IV at the rollup snapshot, nearest expiry (ATM = strike closest to spot) |
| `iv_30d` | NUMERIC(12,6) | 30-day constant-maturity IV — variance-time interpolation across the two expiries bracketing 30 calendar days; **NULL when fewer than two expiries bracket 30 d** (early archive) |
| `spot_price` | NUMERIC(18,4) | Underlying spot at rollup (context for the series) |
| `snapshot_count` | INT | Snapshots that fed the day's rollup — a per-day coverage/data-quality signal |
| `computed_at` | TIMESTAMPTZ | Recompute audit — bumped by retroactive recomputes |

**IV rank / IV percentile** — computed over a **trailing 1-year window** of `iv_30d` (falling back to `atm_iv` where `iv_30d` is NULL): rank = `(current − window min) / (window max − window min)`; percentile = share of window days with IV below current. Served by **`GET /api/v1/market/options/iv-history?underlying=`** — 200 with the daily series, current rank/percentile, and the `window_days` actually covered (decimal strings; IST dates).

**UI (Phase 42B):** an **IV-rank badge** on the `/options` chain header plus an **IV time-series tab** (ECharts, same lazy chunk).

**Honesty caveat (binding):** the value **matures with the archive** — rank/percentile over a thin window is noise. Responses carry the covered `window_days`, and the UI renders an explicit **"insufficient history (n of 252 trading days)"** state below a documented floor (default **60 trading days**) instead of a fake rank. (Same accrue-from-today posture as `index_constituents`, §F.7.)

### F.12 Paper account, capital/margin model & global risk limits [FP-41, FP-42, FP-43, owner selection 2026-06-12; ADR A12]

Amendment **A12** gives the paper ledger a capital base — without one, `percent_equity`/`atr_risk`/`kelly_fraction` sizing and `max_daily_loss_pct` are undefined live and paper P&L percentages are meaningless.

**`strategy.paper_account`** — **single row**, owned by strategy-signal-service:

| Column | Type | Notes |
|---|---|---|
| `id` | SMALLINT PK, `CHECK (id = 1)` | Single-row enforcement (single-user app) |
| `starting_capital` | NUMERIC(18,2) | Owner-set; seeded with a default at migration, editable from `/paper` |
| `cash` | NUMERIC(18,2) | Free cash after the capital usage of open positions |
| `created_at`, `updated_at` | TIMESTAMPTZ | Audit |

**Equity is computed, never stored** (the §F.6 invariant extends to the account): `equity = starting_capital + Σ realized P&L + Σ mark-to-market unrealized from the Redis last-tick map`. A stored equity/unrealized column is a Phase 43A FAIL.

**Capital usage per instrument class [A12]** — pure config, **no Kite margin API**:

- **equities:** full notional;
- **long options:** premium paid;
- **futures & short options:** a configured **margin-pct-of-notional approximation** (per-class percentages in service config, mock + live profiles) — a **documented approximation**: real SPAN + exposure margins vary daily, and Kite's margin API is deliberately not called.

**Buying-power warnings:** `POST /paper/orders` computes the order's projected capital usage; when it exceeds free cash, the response DTO (and the `/paper` UI) carries a **non-blocking warning** — paper stays paper, nothing is rejected on margin grounds.

**`GET /api/v1/paper/account`** — 200: starting capital, cash, computed equity, capital usage by class, the configured margin percentages, day P&L. Feeds the **`/paper` account header** (equity, cash, usage bar, day P&L).

**Global risk limits — `strategy.risk_settings` [FP-42; A12].** DB rows, **never YAML** — the notification-settings pattern (Stage E, E-14): toggling a limit never mints a strategy version or perturbs a D18 checksum.

| Column | Type | Notes |
|---|---|---|
| `key` | TEXT PK | `max_open_paper_positions`, `daily_loss_limit` (INR or % of equity — typed in the payload), `kill_switch` |
| `value` | JSONB | Typed payload incl. `enabled` |
| `updated_at` | TIMESTAMPTZ | |

Semantics: tripping the **global daily loss** (day's realized + mark-to-market, vs the configured INR amount or % of equity) **pauses ENTRY signal emission for the rest of the IST day** — exit/stop evaluation and notifications continue; **`max_open_paper_positions`** caps concurrent open paper positions across all strategies; the **kill switch** is a **one-click pause-all** for entry emission. Every trip/flip writes an **audit row** to the existing strategy audit log. Endpoints: `GET/PUT /api/v1/risk/settings` (strategy-signal-service; standard error envelope, COMMON §8.3).

**Suggested quantity — `signals.suggested_qty` [FP-43; A12].** Additive `NUMERIC NULL` column (D17). At **emission time** the engine runs the strategy's `risk.position_sizing` method against the **paper-account equity**, lot-rounds for derivatives (instrument-master lot size), and stamps the result on the signal. It lives **outside the frozen `ScoreBreakdown` contract** (a `suggested_qty` key inside `score_breakdown` JSONB is a FAIL) and **prefills** `POST /paper/orders` and the `signals/{id}/taken` dialog.

### F.13 Derivative paper lifecycle — expiry settlement + rollover prompts [FP-2, owner selection 2026-06-12; ADR A11]

Before this, the ledger's only lifecycle sweep is the 15:45 intraday mark-to-close (§F.6) — a positional paper option held through Tuesday expiry stays OPEN forever with frozen P&L. Amendment **A11** adds the **expiry-day settlement job** in strategy-signal-service (MarketCalendar-gated; runs after the 15:30 IST close on each expiry date from the instrument master; NSE index weeklies are **Tuesdays** — §F.3 note):

- **Index options:** cash-settle at **intrinsic value vs the spot LTP at expiry close** — a **documented approximation of the official settlement price** (Kite exposes no official settlement-price feed; the divergence is stated, never hidden).
- **Index futures:** cash-settle at the spot LTP at expiry close (same approximation caveat).
- **Stock F&O:** the position is **closed with a physical-settlement warning** — physical delivery is never modeled; the warning states that a real position would have gone to delivery/auction.

Mechanics: settlement runs through the **normal close path** (realized P&L + fees), stamping **`paper_positions.close_reason = 'EXPIRY_SETTLEMENT'`** (new additive `TEXT NULL` column, D17 — usable by other closers too, e.g. the 15:45 mark-to-close). The **partial-unique open-position key (§F.6) is released on settle** — the same `(exchange, tradingsymbol, side)` can re-open on the next series. The **expiry STT leg** joins the **shared fee schedule in the engine JAR** (§F.5; same pinned-at-implementation fee-constants pattern), so settlement costs match what a backtest of the same exit would charge.

**T-1 rollover prompt [FP-2]:** on the session before expiry, an **"expires tomorrow — roll or close?"** push goes out via the Phase 41 notifier (Stage E; deduped per position through the existing cooldown machinery) for every open derivative paper position on the expiring contract.

**Mock parity (D13):** the mock profile pins a fixture expiry date, so the settlement job, `close_reason`, key release and T-1 push are all demonstrable with zero Kite credentials.

### F.14 Trade journal — `journal_entries` + CRUD + review route [FP-66, owner selection 2026-06-12]

**`strategy.journal_entries`** — owned by strategy-signal-service:

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `signal_id` | BIGINT NULL FK → `signals` | **Same-schema FK** |
| `paper_position_id` | BIGINT NULL FK → `paper_positions` | **Same-schema FK** |
| `backtest_run_id`, `backtest_trade_id` | UUID NULL, BIGINT NULL | **Soft cross-schema references** (the §F.7 / plan §6.4 soft-reference convention) — **never FKs** into the `backtest` schema |
| `note` | TEXT | The journal text |
| `tags` | TEXT[] | Free-form tag array (e.g. `fomo`, `early-exit`, `plan-followed`) |
| `discipline_rating`, `emotion_rating` | SMALLINT NULL, `CHECK` 1–5 | The discipline/emotion self-scores |
| `created_at`, `updated_at` | TIMESTAMPTZ | |

All link columns are nullable — **free entries** (no linked object) are first-class.

**Endpoints — the `/api/v1/journal` CRUD family** (strategy-signal-service; standard error envelope, COMMON §8.3):

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/journal` | Paged list; filters: `tag`, `from/to`, `minRating`, `linkedTo` (signal / paper / backtest / none) |
| POST | `/api/v1/journal` | Create — validates same-schema FK targets exist |
| GET / PUT / DELETE | `/api/v1/journal/{id}` | Read / update / delete one entry |

**UI:** a **`JournalDrawer`** opens from signal cards, paper ledger rows and the backtest-trade tables (Stage E Phase 38) with the link prefilled; the **`/journal` lazy route** is the weekly-review surface — a filterable list (tag/date/rating/linked-entity) rendering each entry beside its linked-object summary.

**Out of v1 (binding):** **screenshot attachments are explicitly out of v1** — a documented follow-on; this avoids read-only-root volume/upload work in Stage G's hardening. One line, no design here.

---

## Part 2 — Phase specs

Each phase below is copied near-verbatim from the implementation-phases doc, with cross-references rewritten to point at Part 1 (§F.n) or COMMON. Phase numbers are the global build sequence (Stage F = Phases 42–44). The 2026-06-12 owner-selection phases interleave at their suffix positions — a suffixed phase runs immediately after its base number — so the Stage F build order is **42, 42A, 42B, 43, 43A, 43B, 44, 44A** `[owner selection 2026-06-12]`.

### Phase 42 — Options chain UI + analytics tabs

**Objective**
The options workbench: live chain grid with IV/OI/Greeks, wired filters, PCR/IV-smile/OI-profile analytics, and snapshot-history queries over the self-archived data.

**Why this phase is independent**
Chain endpoints/topics ship live (mock) data since Phase 15 (Stage B — see §F.3 for the endpoint/topic contract); pure frontend.

**Deliverables**
- `OptionsStore` fed by `options.chain` deltas + REST snapshot (§F.3, §F.8).
- `/options` — **ChainFilterBar** (underlying/expiry/strike-window, **actually wired**); **ChainTable** (`p-table` virtual scroll, calls/strike/puts grouping, ITM/OTM highlighting, tabular numerals); **analytics tabs (ECharts):** PCR trend, IV smile, OI profile; off-hours staleness chips; history mode against `GET /options/chain/history` (§F.8).
- **VIX context chip [FP-14, owner selection 2026-06-12]:** India-VIX **level + trailing 1-year percentile** chip on the chain header, derived client-side (`computed()`) from the pinned INDIA VIX index's cached 1d candles (pinned + backfilled in Stage B Phase 15A; ordinary candles REST — no new endpoint; fixture-driven in mock).
- **E2E:** chain renders non-zero IV in mock; filter changes refetch; history query returns a stored snapshot.

**Minimal code/config**
none.

**DB changes**
none.

**Build & Run**
```
cd frontend-ui && npm test && cd ../e2e && npx playwright test
```

**Tests & Verification**
- Vitest: chain row formatting, PCR `computed()`, staleness-chip logic. E2E green.

**Acceptance criteria**
- **PASS:** ~1,500-row chain scrolls at 60 fps (virtualized); analytics derive from store signals (`computed()`, never stored).
- **FAIL:** dead filters (the v1 defect); chain page subscribing to the tick firehose (§F.2 — per-symbol topics / `options.chain` only).

**Commit message**
`feat(frontend): live options chain with wired filters, analytics tabs and snapshot history`

**PR title**
`Phase 42: options chain UI`

**Time estimate**
120–150 min

**Token size target**
≤ 35k output tokens.

**If phase too big**
(a) chain table + filters; (b) analytics tabs + history.

---

### Phase 42A — Futures workbench UI [FP-9, FP-10, owner selection 2026-06-12]

**Objective**
The futures workbench: `/futures` with term-structure cards (basis, contango/backwardation), basis history chart, calendar-spread/rollover-cost panel, and OI-buildup heat tiles (§F.10).

**Why this phase is independent**
The term-structure endpoint, FUT backfill and per-bar OI ship in Stage B Phase 15A; the `oi_buildup` preset ships on the Phase 17 screener (Stage B); pure frontend — same posture as Phase 42.

**Deliverables**
- `FuturesStore` + lazy `/futures` route (same store/ECharts patterns as `/options` — §F.2, §F.10).
- **Term-structure cards** — near/next/far LTP, basis vs spot (absolute + annualized), contango/backwardation state, days to expiry, fed by `GET /api/v1/market/futures/term-structure?underlying=`.
- **Basis history chart** — computed from cached FUT vs spot candles (existing candle endpoints; no new backend).
- **Calendar-spread / rollover-cost panel** — next-month − front-month, absolute + annualized % of spot.
- **OI-buildup heat tiles** consuming the Phase 17 `oi_buildup` screener preset (long buildup / short buildup / long unwinding / short covering) — classification stays server-side in the preset.
- Off-hours staleness chips; decimal-string handling per §F.2.
- **E2E:** `/futures` renders non-zero basis and all four buildup states from mock fixtures; switching the underlying refetches.

**Minimal code/config**
none.

**DB changes**
none.

**Build & Run**
```
cd frontend-ui && npm test && cd ../e2e && npx playwright test
```

**Tests & Verification**
- Vitest: basis/annualized-basis `computed()` math (decimal utility, never `parseFloat`), contango/backwardation state logic, heat-tile mapping from preset rows. E2E green on the mock stack.

**Acceptance criteria**
- **PASS:** term-structure cards show non-zero basis in mock; all derived values via `computed()`, never stored; ECharts stays inside the `/futures` lazy chunk.
- **FAIL:** re-deriving the OI-buildup classification client-side instead of consuming the `oi_buildup` preset; `/futures` subscribing to the tick firehose (§F.2 — per-symbol topics only).

**Commit message**
`feat(frontend): futures workbench with term structure, basis history and oi-buildup tiles`

**PR title**
`Phase 42A: futures workbench UI (FP-9, FP-10)`

**Time estimate**
90–120 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
(a) term-structure cards + basis history; (b) rollover-cost panel + OI heat tiles.

---

### Phase 42B — IV rank/percentile rollup + IV analytics tab [FP-12, owner selection 2026-06-12]

**Objective**
The daily IV rollup over the snapshot archive (`marketdata.iv_daily_summary`), IV rank/percentile over a trailing 1-year window, `GET /api/v1/market/options/iv-history`, and the `/options` IV-rank badge + IV time-series tab (§F.11).

**Why this phase is independent**
`options_chain_snapshots` accrues since Phase 15 (Stage B) and carries its Black-76 inputs per row (§F.4), so the rollup is computable — and retroactively recomputable — from data the platform already owns; market-data-service is the single `marketdata` writer (D10).

**Deliverables**
- **Migration:** `marketdata.iv_daily_summary` per §F.11 (composite PK `(underlying, summary_date)`).
- **16:00 IST rollup job** (MarketCalendar-gated) + a **retroactive recompute command** over the whole archive (idempotent upserts; `computed_at` bumped).
- **`GET /api/v1/market/options/iv-history?underlying=`** — daily series + current IV rank/percentile (trailing 1-year window) + covered `window_days`; explicit **insufficient-history state** below the 60-trading-day floor (§F.11 honesty caveat).
- `/options` UI: **IV-rank badge** on the chain header + **IV time-series tab** (ECharts, same lazy chunk).
- **E2E (mock):** seeded fixture snapshots → rollup → badge shows a rank; a thin-window fixture renders the "insufficient history" state, never a fake rank.

**Minimal code/config**
none.

**DB changes**
`marketdata/V009__iv_daily_summary.sql` — next after Stage C Phase 22's `V008__index_constituents.sql`; Stage B's owner-selection phases use suffix versions (`V002_1`/`V006_1`/`V006_2` per B-8, and 15A has no migration), so they consume no numbers after `V008`.

**Build & Run**
```
./mvnw -pl services/market-data-service -am verify
cd frontend-ui && npm test && cd ../e2e && npx playwright test
```

**Tests & Verification**
- **IT:** rollup over a seeded fixture archive is deterministic (recompute reproduces identical rows); rank/percentile match hand-computed values for a fixture series; endpoint shape + insufficient-history flag.
- Vitest: badge/tab states incl. the insufficient-history rendering. E2E green.

**Acceptance criteria**
- **PASS:** retroactive recompute over the full fixture archive is idempotent (identical rows; only `computed_at` changes); rank/percentile served from the rollup table, never recomputed from raw snapshots per request.
- **FAIL:** a rank rendered when the window is below the documented floor; `iv_daily_summary` written by any service other than market-data-service (D10).

**Commit message**
`feat(market-data,frontend): daily iv rollup with rank/percentile, iv-history endpoint and options iv tab`

**PR title**
`Phase 42B: IV rank/percentile analytics (FP-12)`

**Time estimate**
60–90 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
(a) rollup table + job + endpoint; (b) badge + IV tab UI.

---

### Phase 43 — Paper trading ledger + UI (Q1 rest)

**Objective**
The paper ledger in strategy-signal-service using the **same** `FillSimulator` as backtests (parity by construction), with fill-audit columns, P&L endpoints, and the paper page.

**Why this phase is independent**
`FillSimulator` (Phase 29, Stage D — §F.5), signals (Phase 23, Stage C), the last-tick map (Phase 7, Stage A) all exist.

**Deliverables**
- **Migration:** `paper_orders` (incl. fill-audit: `fill_simulator`, `slippage_applied`, `quote_bid/ask`; `(placed_at DESC)` index) + `paper_positions` (partial-unique open key) per §F.6; **unrealized P&L never stored**.
- **Endpoints (§F.6):** `POST /paper/orders` (from signal or manual; fills via `ltp_slippage/v1` against next-tick LTP + full cost model — §F.5), `GET /paper/positions` (mark-to-market from last-tick map), `GET /paper/trades`, `GET /paper/pnl` (daily equity + win rate/expectancy), `POST /paper/positions/{id}/close`, `POST /paper/reset` (confirm-guarded); `signals/{id}/taken` optionally opens a position; **15:45 mark-to-close** for intraday styles.
- `PaperStore` + `/paper` page: positions (live P&L), ledger, equity curve (lightweight-charts); dashboard **PaperPnl** widget (§F.8).
- **E2E:** signal → take → position tracks mock ticks → close → realized P&L in ledger.
- *Cross-reference:* **Phase 43A** layers the paper account/capital model, global risk limits + kill switch and `suggested_qty` prefill on this ledger (§F.12) — nothing in this phase moves `[FP-41, FP-42, FP-43, owner selection 2026-06-12]`.
- *Cross-reference:* **Phase 43B** adds the derivative expiry-settlement lifecycle, `close_reason` and T-1 roll-or-close prompts (§F.13) `[FP-2, owner selection 2026-06-12]`.

**Minimal code/config**
none.

**DB changes**
`strategy/V005__paper_trading.sql`

**Build & Run**
```
./mvnw -pl services/strategy-signal-service -am verify
cd e2e && npx playwright test
```

**Tests & Verification**
- **IT:** fill-audit columns populated by the engine JAR implementation (id `ltp_slippage/v1` asserted); cost math matches a Phase 29 fill vector; open-position uniqueness.
- E2E journey green.

**Acceptance criteria**
- **PASS:** paper fill for a fixture scenario equals the backtest fill for the same scenario **to the paisa** (parity check).
- **FAIL:** a paper-local fill path outside the engine JAR; stored unrealized P&L.

**Commit message**
`feat(strategy-signal): paper trading ledger on the shared fillsimulator with fill audit and pnl ui`

**PR title**
`Phase 43: paper trading (Q1)`

**Time estimate**
120–150 min

**Token size target**
≤ 35k output tokens.

**If phase too big**
(a) ledger backend + fills + endpoints; (b) `PaperStore` + page + E2E.

---

### Phase 43A — Paper account, capital/margin model, suggested qty + global risk limits [FP-41, FP-42, FP-43, owner selection 2026-06-12]

**Objective**
Give paper a capital base (A12): the single-row `paper_account`, per-class capital-usage rules with the config margin approximation, buying-power warnings, `GET /paper/account` + the `/paper` account header; global risk limits + one-click kill switch on `strategy.risk_settings` rows; engine-computed `signals.suggested_qty` at emission (§F.12).

**Why this phase is independent**
The paper ledger (Phase 43), signals + the position-sizing math in the engine JAR (Stage C), and the last-tick map (Stage A) all exist; everything here layers additively on them (D17).

**Deliverables**
- **Migration:** `strategy.paper_account` (single row, `CHECK (id = 1)`), `strategy.risk_settings`, `signals.suggested_qty NUMERIC NULL` — all additive, per §F.12.
- **Capital model:** per-class capital usage (equities notional; long options premium; futures/short options **config margin-pct-of-notional approximation — no Kite margin API**); equity computed on demand = starting capital + realized + mark-to-market unrealized from the last-tick map (**never stored**).
- **Endpoints:** `GET /api/v1/paper/account`; `GET/PUT /api/v1/risk/settings`; **non-blocking buying-power warnings** on `POST /paper/orders` (§F.12).
- **Global risk limits:** `max_open_paper_positions`; global daily loss (INR or % equity) — a trip **pauses ENTRY signal emission for the IST day** (exit/stop evaluation continues); **kill switch** = one-click pause-all; **audit row per trip/flip** in the strategy audit log. DB rows, never YAML (§F.12; the E-14 notification-settings pattern — no D18 checksum perturbation).
- **`suggested_qty` at emission:** the strategy's `risk.position_sizing` method run against paper-account equity, lot-rounded for derivatives; **outside the frozen `ScoreBreakdown`**; prefills `POST /paper/orders` and the `signals/{id}/taken` dialog.
- UI: `/paper` **account header** (equity, cash, usage by class, day P&L), risk-settings panel + **kill-switch button** (confirm-guarded), suggested-qty prefill in the order/taken dialogs.
- **E2E (mock):** set capital → signal carries `suggested_qty` → order prefilled → an over-sized order returns a buying-power warning → daily-loss trip pauses entries + writes the audit row → kill switch flips emission off.

**Minimal code/config**
per-class margin percentages in strategy-signal-service config (mock + live profiles).

**DB changes**
`strategy/V006__paper_account_risk.sql`

**Build & Run**
```
./mvnw -pl services/strategy-signal-service -am verify
cd e2e && npx playwright test
```

**Tests & Verification**
- **IT:** `percent_equity` sizing on fixture equity yields the hand-computed lot-rounded qty; the daily-loss trip suppresses ENTRY emission only (exit path asserted still live); kill-switch flip + audit row; equity math against a seeded last-tick map; a settings flip mints **no** strategy version (D18 checksum untouched).
- E2E journey green.

**Acceptance criteria**
- **PASS:** `suggested_qty` lands as a `signals` column, **not** inside `score_breakdown` (frozen contract); equity/unrealized computed on demand, never stored; risk limits live on DB rows only.
- **FAIL:** any Kite margin-API call; a stored equity/unrealized column; risk limits in strategy YAML.

**Commit message**
`feat(strategy-signal): paper account and capital model, suggested qty at emission, global risk limits with kill switch`

**PR title**
`Phase 43A: paper capital model + global risk (FP-41/42/43)`

**Time estimate**
120–150 min

**Token size target**
≤ 35k output tokens.

**If phase too big**
(a) paper account + capital model + endpoints; (b) risk limits + kill switch + `suggested_qty` + UI.

---

### Phase 43B — Derivative paper lifecycle: expiry settlement + rollover prompts [FP-2, owner selection 2026-06-12]

**Objective**
Stop derivative paper positions from outliving their contracts (A11): the expiry-day settlement job (index options at intrinsic vs spot LTP, index futures cash-settle, stock F&O close-with-warning), the expiry STT leg in the shared fee schedule, `paper_positions.close_reason`, and the T-1 roll-or-close push (§F.13).

**Why this phase is independent**
The paper ledger (Phase 43) + account (Phase 43A), the instrument master's expiry dates (Stage B), MarketCalendar (Stage B), the engine-JAR fee schedule (Stage D Phase 29; futures legs per A9), and the notifier (Stage E Phase 41) all exist.

**Deliverables**
- **Settlement job** (strategy-signal-service; MarketCalendar-gated, post-close on expiry dates): index options cash-settle at **intrinsic vs spot LTP at expiry close** — a **documented approximation of the official settlement price** (Kite has no official settlement-price feed); index futures cash-settle the same way; stock F&O closed with a **physical-settlement warning** (§F.13).
- **Expiry STT leg** in the shared engine-JAR fee schedule (values pinned at implementation — the §F.5 fee-constants pattern); settlement runs through the normal close path (realized P&L + fees).
- **Migration:** `paper_positions.close_reason TEXT NULL` (e.g. `EXPIRY_SETTLEMENT`; additive, D17); the partial-unique open key (§F.6) is **released on settle**.
- **T-1 push** via the Phase 41 notifier: "expires tomorrow — roll or close?" per open derivative paper position on the expiring contract (cooldown-deduped).
- **Paper-trade chart marks [FP-67]:** paper trades now feed the Phase 40A chart-marks surface (lightweight-charts series markers) `[A13, 2026-06-12]` (read-only query params on the existing paper endpoints — no new service).
- **E2E (mock fixture expiry date):** open a fixture index option → the clock crosses expiry close → position CLOSED with `close_reason = EXPIRY_SETTLEMENT` and intrinsic-based P&L incl. the expiry STT leg → the same key is re-openable → T-1 push row in `notification_events`.

**Minimal code/config**
none beyond the fixture expiry date in the mock profile.

**DB changes**
`strategy/V007__paper_close_reason.sql`

**Build & Run**
```
./mvnw -pl services/strategy-signal-service -am verify
cd e2e && npx playwright test
```

**Tests & Verification**
- **IT:** intrinsic settlement math (ITM/OTM CE + PE vectors incl. the expiry STT leg) matches engine-JAR fee-schedule fixtures; stock-F&O close carries the warning; the partial-unique key is released on settle; the T-1 push dedupes on re-run.
- E2E journey green.

**Acceptance criteria**
- **PASS:** no derivative paper position remains OPEN past its expiry close in mock; settlement fees computed by the shared engine JAR (one fee schedule for backtest and paper); the spot-LTP **approximation caveat** is stated in docs/UI tooltip.
- **FAIL:** a paper position OPEN after expiry; settlement math implemented outside the engine JAR; presenting the settle price as the official settlement price (caveat omitted).

**Commit message**
`feat(strategy-signal,engine): derivative paper expiry settlement with close_reason and t-1 roll-or-close prompts`

**PR title**
`Phase 43B: derivative paper lifecycle (FP-2)`

**Time estimate**
90–120 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
(a) settlement job + STT leg + `close_reason`; (b) T-1 push + paper chart marks + E2E.

---

### Phase 44 — Universe pinning + checksum + editor label (S8 rest)

**Objective**
Pin resolved universes by copy at submission time: `index_constituents` universes resolve via REST, embed in `jobs.request`, hash into `backtest_runs.universe_checksum`, and surface in the editor and leaderboard.

**Why this phase is independent**
Constituents API (Phase 22, Stage C — §F.7), jobs (Phase 28, Stage D), leaderboard (Phase 39, Stage E) all exist.

**Deliverables**
- **strategy-signal-service universe resolver:** `index_constituents` mode → market-data REST + instrument-master filters → ordered list + SHA-256 (**it holds no `marketdata` grant — REST only**, D7/D10; §F.7).
- **`futures_of_underlying` resolver leg [FP-11a, owner selection 2026-06-12]:** the same submission-time resolver handles `universe.mode: futures_of_underlying` (A7 keys: `futures.contract: front_month | next_month`, `roll_days_before_expiry` default 1): front/next-month contract resolution against the instrument master **via market-data REST**, plus **roll re-resolution** `roll_days_before_expiry` sessions before expiry (live re-subscribes to the new contract; backtests of this mode replay the Stage B Phase 15B **CONT** series — the **roll-day basis divergence is documented, not hidden**, A11). Same REST-only / no-grant rules as `index_constituents` (D7/D10).
- **Backtest/optimization submission:** resolved list **copied into `request` JSONB**; every sweep trial reuses the embedded copy (mid-sweep rebalance can never split a leaderboard); `universe_checksum` persisted on runs (§F.7).
- **Editor:** **"Published Universe (as of …)" label** with constituent count + checksum; **survivorship-bias caveat** shown for windows predating capture (§F.8).
- **Lift the Phase 21 publish guard:** `index_constituents` strategies are now publishable (the resolver exists; §F.7).
- **Leaderboard/compare:** universe-checksum mismatch flagging beside `dataHash` (§F.7).
- **E2E:** an index-universe strategy's two backtests across a mock rebalance show identical checksums (pinned), and a fresh submission after the rebalance shows the new one.

**Minimal code/config**
none.

**DB changes**
none (column exists since Phase 30, Stage D — §F.7).

**Build & Run**
```
./mvnw -pl services/strategy-signal-service,services/backtest-service -am verify
```

**Tests & Verification**
- **IT:** pin-by-copy proven (constituents table mutates mid-sweep; trials keep the embedded list); checksum determinism.

**Acceptance criteria**
- **PASS:** no cross-schema FK introduced; **submission-time resolution only — never lazy resolution inside workers**; **Stage-F exit** — `PHASE_GATES.md` mirrors the plan §15.2 Phase-5 row (§F-exit below).
- **FAIL:** strategy-signal-service reading `marketdata` directly.

**Commit message**
`feat(backtest,strategy-signal): submission-time universe pinning by copy with checksums and editor label`

**PR title**
`Phase 44: universe pinning (S8)`

**Time estimate**
90–120 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
Not applicable.

---

### Phase 44A — Trade journal [FP-66, owner selection 2026-06-12]

**Objective**
The trade journal (§F.14): `strategy.journal_entries` (note, tags, discipline/emotion ratings, nullable same-schema links to signals/paper positions, soft references to backtest trades), the `/api/v1/journal` CRUD family, the `JournalDrawer` on signal/paper/backtest-trade surfaces, and the `/journal` review route.

**Why this phase is independent**
Signals (Stage C), the paper ledger (Phase 43), and the backtest-trade tables (Stage E Phase 38) all exist; the journal is purely additive on top of them (D17), and as the stage's last phase every linkable surface is already live.

**Deliverables**
- **Migration:** `strategy.journal_entries` per §F.14 — same-schema FKs to `signals`/`paper_positions`, **soft** `backtest_run_id`/`backtest_trade_id` references (**never cross-schema FKs**), `note`, `tags TEXT[]`, `discipline_rating`/`emotion_rating` (1–5), timestamps.
- **`/api/v1/journal` CRUD** (strategy-signal-service): paged GET with `tag`/`from/to`/`minRating`/`linkedTo` filters; POST validating same-schema link targets; GET/PUT/DELETE by id.
- **`JournalDrawer`** opened from signal cards, paper ledger rows and the backtest-trade tables with the link prefilled; **`/journal` lazy route** — the weekly-review list with filters (tag/date/rating/linked-entity), each entry beside its linked-object summary.
- **Screenshots out of v1** — a documented follow-on (§F.14); no upload/volume work in this plan.
- **E2E (mock):** journal a signal from its card → the entry appears on `/journal` filtered by tag → edit + delete round-trip; a free entry (no links) also passes.

**Minimal code/config**
none.

**DB changes**
`strategy/V008__journal_entries.sql`

**Build & Run**
```
./mvnw -pl services/strategy-signal-service -am verify
cd frontend-ui && npm test && cd ../e2e && npx playwright test
```

**Tests & Verification**
- **IT:** CRUD lifecycle; FK validation (unknown `signal_id` → 422 envelope); filter combinations return seeded entries; the backtest references are plain columns (schema asserted FK-free toward `backtest`).
- Vitest: drawer prefill per surface. E2E green.

**Acceptance criteria**
- **PASS:** the drawer opens prefilled from all three surfaces in mock; free entries (no linked object) are first-class; **Stage-F exit** — `PHASE_GATES.md` mirrors the plan §15.2 Phase-5 row incl. the owner-selection lines (stage gate below).
- **FAIL:** a cross-schema FK into `backtest`; any screenshot/attachment scope in v1.

**Commit message**
`feat(strategy-signal,frontend): trade journal with linked entries, crud api and review route`

**PR title**
`Phase 44A: trade journal (FP-66)`

**Time estimate**
90–120 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
(a) table + CRUD API; (b) drawer + `/journal` route + E2E.

---

## Stage exit gate — plan §15.2 Phase-5 row (the S5 Friday gate ritual input)

At the Stage F boundary, `PHASE_GATES.md` mirrors the plan §15.2 macro-Phase-5 row and the Friday gate ritual [review S5] walks this checklist against the running mock stack. An unchecked box extends the stage (S5: "an unchecked box extends the phase"). [plan §15.2 — inlined as a checklist; COMMON §15.2/§16.2] With the 2026-06-12 owner-selection additions the stage's last phase is **44A**, so the gate walk happens at its close; Phase 44's pre-existing Stage-F-exit PASS line is subsumed into 44A's (both assert the mirrored checklist — the older line is kept, not deleted). `[owner selection 2026-06-12]`

**Phase 5 — Options analytics + paper trading. Acceptance criteria (demo-able):**

- [ ] **Chain refreshes live via WS** — the `/options` page updates from the `options.chain` topic through the gateway STOMP bridge (§F.3, §F.8).
- [ ] **Historical IV query over ≥ 1 month of own snapshots** — history mode returns stored `options_chain_snapshots` rows with non-zero IV via `GET /options/chain/history` (§F.4, §F.8).
- [ ] **Accepting a signal opens a paper position whose P&L tracks ticks** — `signals/{id}/taken` → paper position → mark-to-market from the last-tick map (§F.6, §F.8).

**Key-deliverable checklist (plan §15.2 Phase-5 deliverables + review additions):**

- [ ] Options chain UI (Black-76 IV/Greeks, PCR, strike filters) on **live data** + snapshot-history queries (Phase 42).
- [ ] Paper-trading ledger in strategy-signal-service with **P&L tracking against live prices** (Phase 43).
- [ ] **Universe pinning** — submission-time resolve copied into `jobs.request` + `universe_checksum`, editor **"Published Universe (as of …)" label** (§7.4; review S8 rest, **+2.5 d**) (Phase 44).
- [ ] **Paper fill-audit wiring** — `FillSimulator` id, `slippage_applied` and quote columns on `paper_orders` (§6.4; review Q1 rest, **+0.5 d**) (Phase 43).

**Owner-selection additions (2026-06-12) — same gate, walked at the close of Phase 44A:**

- [ ] **Futures workbench** — `/futures` term-structure cards, basis history, rollover cost, OI-buildup heat tiles (§F.10) (Phase 42A) `[FP-9, FP-10, owner selection 2026-06-12]`.
- [ ] **IV rank/percentile** — `iv_daily_summary` rollup + `GET /options/iv-history` + `/options` IV badge/tab with the honest insufficient-history state (§F.11) (Phase 42B) `[FP-12, owner selection 2026-06-12]`.
- [ ] **VIX chip** — India-VIX level/percentile chip on the chain header (Phase 42 extension) `[FP-14, owner selection 2026-06-12]`.
- [ ] **Paper capital model + global risk** — single-row `paper_account`, per-class capital usage (config margin approximation, no Kite margin API), `GET /paper/account` + account header, `risk_settings` limits + kill switch + audit rows, `signals.suggested_qty` prefill (§F.12) (Phase 43A) `[FP-41, FP-42, FP-43, owner selection 2026-06-12]`.
- [ ] **Derivative paper lifecycle** — expiry settlement (intrinsic vs spot LTP, documented approximation), expiry STT leg, `close_reason`, T-1 roll-or-close push, paper chart marks (§F.13) (Phase 43B) `[FP-2, FP-67, owner selection 2026-06-12]`.
- [ ] **`futures_of_underlying` resolver leg** — front/next-month resolution + roll re-resolution in the submission-time pinning path, REST-only (Phase 44 extension) `[FP-11a, owner selection 2026-06-12]`.
- [ ] **Trade journal** — `journal_entries` + CRUD + drawer from signals/paper/backtest trades + `/journal` review route; screenshots out of v1 (§F.14) (Phase 44A) `[FP-66, owner selection 2026-06-12]`.

> **Stage-F review-ledger budget [review §5 timeline ledger]:** Phase 5 additions = **S8 pinning + editor label (2.5 d) + Q1 paper wiring (0.5 d) = +3.0 d** over the §15.2 baseline (FT 2–3 weeks / PT 7–10 weeks). Never silently absorbed. **+ owner-selection additions (2026-06-12):** Phases 42A/42B/43A/43B/44A plus the Phase 42/44 extensions = **+7.5–10 active hours (≈ +1–1.5 d)** on top `[FP-2, FP-9, FP-10, FP-11a, FP-12, FP-14, FP-41–FP-43, FP-66, FP-67, owner selection 2026-06-12]`. Never silently absorbed.

### Stage-end notes

- **Feature-complete milestone [plan §15.3 gantt].** Stage F is the last feature stage; its completion is the "Feature-complete" milestone. Only **Stage G** (Phase 6 — observability, k6 p99 gate, hardening, docs/runbook, GA) remains.
- **Owner-selection additions stay inside the stage `[owner selection 2026-06-12]`.** Phases 42A/42B/43A/43B/44A and the Phase 42/44 extensions all land before the stage exit, so the **feature-complete milestone stays at the Stage F exit** and Stage G stays strictly last. Their budget is carried explicitly in the ledger line above (never silently absorbed), and the stage's last phase — and gate-walk trigger — is now **44A**.
- **De-scope seam [review §5 lever-1].** Phase 44's universe pinning is the **"S8 pinning"** member of the single named anti-overfitting de-scope unit (S1A + S1B + S1C + BPC + S8-pinning, ~9–10 d, Phases 3–5, §15.6 lever 1). The unit is pulled together or not at all; if pulled, the chain UI (Phase 42) and paper ledger (Phase 43) still ship — only Phase 44's pinning/checksum/editor-label slice and its leaderboard flagging recede (constituents accrual + REST from Phase 22 are unaffected).
- **Parity is the load-bearing invariant.** The Phase 43 PASS criterion (paper fill == backtest fill *to the paisa* for the same fixture) is the whole point of putting `FillSimulator` in the shared JAR. If it ever fails, the golden-vector / fill-vector suite (Stage C/D) should catch the divergence first.
- **Grant model is the load-bearing constraint.** The Phase 44 FAIL criterion (strategy-signal-service reading `marketdata` directly) and the no-cross-schema-FK rule are the two things a reviewer must check most carefully — resolution is **REST-only, submission-time, by-copy**.
- **Pinned open items carried into implementation [review §4]:** (3) statutory fee-schedule **values** pinned when the `FillSimulator` cost legs are coded (§F.5); (2) NSE constituent-CSV source verification (resolved before the Phase 22 fetcher, upstream of this stage). Point-in-time `as_of: trade_date` universe resolution is a **noted later enhancement, not built now** (§F.7).




