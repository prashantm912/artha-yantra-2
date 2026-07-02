# Live signal/trade analysis & optimization runbook

**Status:** ACTIVE — the standing procedure for analysing ~1 month of LIVE-PAPER scalper
results and turning them into a data-grounded tune (the E9 target/trail band + per-strategy
keep/cut/tune). Written 2026-06-30 so a fresh session can drive the whole analysis cold.

> **Why this exists.** The scalper exit-band (E9 — 35% take-profit, ST-line trail) is a
> placeholder. The backtest optimizer can't tune it: scalpers fire ≈0 trades on replay
> (parity firewall + derived history → OI/macro read NEUTRAL), and forcing trades via
> `backtest.relax_session` overfits a tiny sample (a past proof-run "winner" was sharpe
> 33 / +190% / 0.21% DD = untradeable). The **only trustworthy signal is real live-paper
> trades on the real captured option premium**. This runbook is how to mine that.

---

## 0. When to run
After ~1 month (or ≥ a few hundred fired signals / ≥ tens of taken paper trades per family)
of the published scalpers running live-paper. Owner says: "analyze the last month."

## 1. Prerequisites the OWNER must ensure during the gather window
- **Stack up during market hours** (09:15–15:30 IST), Kite token fresh (re-login after the
  06:00 IST expiry → dashboard "Ticker: DISCONNECTED" means re-arm via Settings → Connect Kite).
- **Auto-paper-trade ON** (#367) so fired signals actually become paper trades — that yields
  the real `close_reason` exit-attribution. (Signals-only is still analysable via the
  counterfactual replay in §4.B, but taken trades are richer.)
- The published scalpers are the **9 `-nifty` directional families × {CE published, PE published}**
  (the SENSEX + the 18 SENSEX-PE drafts are NOT published unless the owner publishes them).
- Forward OI/premium capture stays LIVE (full-chain NIFTY+SENSEX, ≤90d expiries, 3-min) — it
  has run since 2026-06-15, so the captured `candles` cover the window. Nothing to enable.

## 2. Data sources (live DB `artha`, one Postgres, cross-schema joins OK)
All four Flyway lineages live in the SAME `artha` database as different schemas, so a single
query can join signals ↔ paper ↔ candles. Read via the `postgres` MCP, in-container `psql`
(`docker exec -it ay-timescaledb psql -U artha -d artha`), or the gateway API.

| what | table / endpoint | key columns |
|---|---|---|
| Fired signals | `signals` (V003/V006/V009) | `id, strategy_version_id, exchange, tradingsymbol, "interval", signal_type ('ENTRY'/'EXIT'), side ('BUY'/'SELL'), entry_price, stop_loss, target, composite_score, score_breakdown JSONB, suggested_qty, scalper_detail JSONB, status, generated_at, expires_at` |
| Strategy identity | `strategy_versions` → `strategies` | join `signals.strategy_version_id = strategy_versions.id`, then `strategies.slug` (e.g. `scalp-gap-theory-nifty`) |
| Paper positions | `paper_positions` (V005/V007) | `id, exchange, tradingsymbol, side, qty, avg_entry_price, realized_pnl, status ('OPEN'/'CLOSED'), close_reason TEXT (V007 — STOP_LOSS/TAKE_PROFIT/…), opened_at, closed_at` |
| Paper orders (signal link) | `paper_orders` (V005) | `id, signal_id → signals.id, exchange, tradingsymbol, side, qty, fill_price, status, created_at` |
| Captured option premium | `candles` (marketdata) | the option leg's OHLCV: `exchange (NFO/BFO), tradingsymbol (the option), "interval" ('1m'/'3m'), bucket, open/high/low/close, volume, oi`. 3m = read-time 1m→3m rollup (#365). |
| Convenience (API) | `GET /api/v1/signals` (envelope `{items}`), `GET /api/v1/paper/positions`, `/api/v1/paper/ledger` | same data, pre-shaped |

**`scalper_detail` JSONB** (V009 side-channel) carries per signal: `side` (CE/PE/NEUTRAL),
the option leg (`tradeable_*`/`legs[]`), `|delta|`, IV, the **confluence dots**, and
`manual_checks`. **`score_breakdown` JSONB** = the frozen ScoreBreakdownDto:
`composite = Σ(w·s)/Σw` plus each factor's weight + score. These two are the gate-predictiveness goldmine.

> **IST/UTC trap (CLAUDE.md):** in-container `now()`/`::date` is **UTC**. Filter
> `generated_at` / `opened_at` / candle `bucket` by **explicit `+05:30` ISO bounds**, never
> `::date = CURRENT_DATE` (off-by-one across IST midnight). A 02:xx-IST row stores as the
> previous UTC calendar day.

## 3. Starter queries
```sql
-- 3.1 Per-strategy fired-signal counts + taken vs untaken (the sample-size sanity check)
SELECT sv_slug, COUNT(*) fired,
       COUNT(*) FILTER (WHERE status='TAKEN') taken
FROM (
  SELECT s.*, st.slug sv_slug
  FROM signals s
  JOIN strategy_versions v ON v.id = s.strategy_version_id
  JOIN strategies st ON st.id = v.strategy_id
  WHERE s.signal_type='ENTRY'
    AND s.generated_at >= TIMESTAMPTZ '2026-07-01 00:00:00+05:30'
    AND s.generated_at <  TIMESTAMPTZ '2026-08-01 00:00:00+05:30'
) q
GROUP BY sv_slug ORDER BY fired DESC;

-- 3.2 Closed paper trades: P&L + exit attribution, joined back to the firing strategy
SELECT st.slug, p.tradingsymbol, p.side, p.qty, p.avg_entry_price,
       p.realized_pnl, p.close_reason, p.opened_at, p.closed_at,
       s.composite_score, s.scalper_detail
FROM paper_positions p
JOIN paper_orders o      ON o.id = (SELECT MIN(id) FROM paper_orders WHERE signal_id = o.signal_id) -- entry order
JOIN signals s           ON s.id = o.signal_id
JOIN strategy_versions v ON v.id = s.strategy_version_id
JOIN strategies st       ON st.id = v.strategy_id
WHERE p.status='CLOSED'
  AND p.opened_at >= TIMESTAMPTZ '2026-07-01 00:00:00+05:30'
ORDER BY p.closed_at;
-- NOTE: verify the order→position link at analysis time (a position may have >1 order);
-- the robust join is paper_orders.signal_id → signals.id, then group by position.

-- 3.3 Exit-attribution distribution (answers "is the trail enough?")
SELECT close_reason, COUNT(*), ROUND(AVG(realized_pnl),2) avg_pnl
FROM paper_positions
WHERE status='CLOSED' AND opened_at >= TIMESTAMPTZ '2026-07-01 00:00:00+05:30'
GROUP BY close_reason ORDER BY COUNT(*) DESC;
```

## 4. The analyses to run

### A. Exit attribution (robust, the headline)
From `close_reason` (§3.3): what fraction of trades exited on TAKE_PROFIT (the 35% cap) vs
the trail/signal_exit/structural stop/time_stop. **If TAKE_PROFIT fires on only a few %, the
band is near-irrelevant → leave it.** If it fires often AND those names kept running after the
exit (check the captured premium past `closed_at`), the 35% is too tight.

### B. Counterfactual band grid (the legit E9 "optimization")
For each REAL taken signal: pull the option leg's captured premium path from `candles`
(`bucket` between `opened_at` and end-of-session) and **re-simulate the exit** under each
candidate band — TP ∈ {20,25,30,35,40,45,50}% and trail tightness (ST mult ∈ {1.5,2.0,2.5,3.0})
— holding the entry + structural stop fixed. Tabulate realized expectancy per band. The band
that maximises expectancy ON YOUR ACTUAL TRADES is the answer. This works even for UNTAKEN
signals (replay from `entry_price` + `scalper_detail` leg), so the sample isn't gated on
auto-paper being on. **This is the real optimizer for E9** — real entries, real premium, no
NEUTRAL-history artifact.

### C. Per-family + per-side expectancy
Win rate, avg win / avg loss, expectancy, max-drawdown by `strategies.slug` and by CE vs PE
(`scalper_detail->>'side'`). → **which scalpers actually make money live; which to cut.** This
is usually a bigger win than the band itself.

### D. Gate predictiveness
Regress trade outcome (win / P&L) on `composite_score` and each factor in `score_breakdown`
(and the `scalper_detail` confluence dots). Do higher-confluence signals win more? Which
factors carry signal, which are noise → candidates to re-weight or drop.

## 5. Caveats + overfitting guards (state these in every report)
- **1 month ≈ one regime + a thin sample.** Per-family trade counts may be < a few dozen.
  Suggestions are DIRECTIONAL, not statistically conclusive. A clearly-bad band shows;
  35-vs-38 micro-tuning does not.
- **Don't re-overfit.** The §4.B grid can overfit to the month. Only recommend band changes
  that are LARGE + CONSISTENT across families/weeks. Prefer the robust facts (exit-attribution,
  give-back-from-peak) over chasing the grid's single best cell.
- **Survivorship/regime tag:** label the window's regime (trending/choppy/event-heavy) — a band
  tuned to a trend month over-fits to trend.
- The scalpers are **owner-LIVE-validated**; this is refinement, not a go/no-go gate.

## 6. Deliverable
A short report: per-family expectancy table, exit-attribution breakdown, the §4.B band grid
with a recommendation (e.g. "TP fired on 8% of trades; 3 of those ran +20% after — loosen to
50% or drop it" / "trail gave back avg 18% from peak — tighten ST mult to 1.5"), the
score-vs-outcome read, and a **keep / cut / tune list per scalper**. Then, if a band change is
warranted, apply it as a parity-safe YAML edit (the `take_profit.value` is already
optimizer-sweepable, #386) + republish — never a guess.

## 7. Pointers
- E9 band design + why placeholder: `docs/superpowers/plans/archive/2026-06-27-backlog/trade-management-exits.md`,
  `archive/2026-06-29-e8-e12-numbers.md` (answer #1 = both target+trail, DB/optimizer-tunable).
- Why backtest can't tune scalpers: [[scalper-tuning-findings]] (overfit proof-run),
  [[scalper-100-remaining-map]] (parity firewall, `relax_session`).
- Exit mechanics: `ExitEvaluator` precedence `stop_loss → trailing_stop → take_profit →
  time_stop → signal_exit`; the ST-line trail is `trailing_stop{basis: indicator, alias:
  supertrend_line}` on every directional family; the 35% TP only on gap-theory + market-movers.
- Auto-paper-trade toggle = #367; signals persistence = `strategy.signals` (only FIRING bars).
- Ledger: `2026-07-02-remaining-items.md` §2 (E9 band = owner forward-paper output; inventory archived).
