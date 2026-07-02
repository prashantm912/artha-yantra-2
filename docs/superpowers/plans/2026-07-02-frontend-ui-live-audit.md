# ArthaYantra Frontend UI/UX Live Audit — 2026-07-02

**Scope:** production-grade UI/UX + trust audit of `frontend-react` against the RUNNING live stack during
market hours (11:47–12:10 IST), as a loopback-only single-owner algorithmic-trading research platform.
Correctness, readability, hierarchy, state clarity and decision safety weighted over decorative styling.

**Method (pass 1):**
- Walked 40+ routes in Chrome (Claude-in-Chrome) against `http://127.0.0.1:8080`, live data, market open.
- Playwright pass at 480×915 (S24-class) mobile viewport; axe-core WCAG 2A/AA scans on 6 representative
  pages (dashboard, cockpit, options-chain, signal-rejections, oi-analysis, paper).
- Live side-by-side with oipulse (owner session) on OI Analysis, Options Chain, Trending OI, Straddle
  Chart, Connecting Dots at matched underlying/strike/interval (NIFTY 24100 3m where possible).
- Theme spot-checks (Light, OiPulse Red), keyboard-focus spot-check, console/network verification of the
  options-chain empty-state repro.

A **pass 2** (deeper interactive comparison: control-set parity + data-accuracy with oipulse as the
barometer, per-page control interaction, 5–10s data-load waits) is appended as §9 when complete.

---

## 1. Overall UI assessment

**Verdict: genuinely professional trading-research UI — with one systemic trust defect (loading states
rendered as "no data") and a tail of polish issues.** The design system holds: consistent `--ay-*` tokens
across 5 themes, Newsreader display headings + mono numerics, uniform FilterBar
(underlying/expiry/interval/Live/Go) on every analytics page, uniform header pattern (title + ⓘ + one-line
purpose), consistent Indian digit grouping (1,25,000), consistent OI-semantics colour language
(L.B./S.B./L.U./S.C. badges everywhere). Dense screens (options chain, OI analysis, banks matrix,
rejections) are scannable, not cluttered. Decision safety is taken seriously: paper-only disclaimers on the
order ticket, kill switch, honest degrade notes ("DH/DL/DO pending", "Funds not configured"). The weak
spots are state-clarity, not decoration.

## 2. What looks strong

- **Options Chain (loaded)** — mirrored CE/PE layout, OI bars, interpretation badges, PCR-ratio column,
  ATM row highlight, VIX/PCR/ATM/DTE/spot tiles, live as-of clock, Columns picker; black-76 IV/greeks
  BETTER than oipulse (theirs shows IV=0 on deep-ITM rows; ours computes ~11.x).
- **Signal Rejections** — rail-count chips, blocking-rail badges, operand/threshold/**margin**
  (negative-red), composite vs threshold, reason strings, expandable breakdowns. Exactly what
  "why didn't it fire" needs.
- **Scalping Cockpit** — one-screen operator layout (chain + confluence matrix + straddle + signals +
  paper) with fresh 3-min rows; per-bar dot grid reads at a glance.
- **OI Spurt** — 4-quadrant day-cumulative scanner, bold strength rows, rule stated in the header.
- **Interval-wise OI** — six panels, one shared legend, compact L/Cr axes, angled labels.
- **Backtest results** — 15 KPI tiles + equity/benchmark/drawdown + `dataHash`/seed provenance footer;
  exit-reason breakdown on the Trades tab.
- **Empty states with next actions**; honest not-configured states (Orders/Funds); paper-safety copy.
- **Mobile (480px)** — chain becomes per-strike cards; tables wrap not clip; readable throughout.

## 3. What looks weak

- **P0 — pending renders as empty.** Options Chain shows *"No chain for this selection"* + all header
  tiles "—" for 4–30s on first mount (expiries query → select fill → auto-fetch with greeks); reproduced
  twice, self-heals silently. Straddle Chart worse: fully blank panel, info chips "—", ~8s, no skeleton.
  During market hours the operator cannot distinguish quiet market / still loading / backend down — the
  exact class QueryState was built to kill (#418 fixed the *error* branch; the *pending* branch still
  renders final empty-copy on these pages).
- **"OiPulse" watermark on Options Premium** — competitor watermark bottom-right (replicated echarts
  `graphic` config). One-line removal.
- **Trades table colours BUY red** (Backtest results → Trades SIDE column). Semantic colour misuse.
- **Settings "Data sync: NEVER_RUN"** on a live synced system — reads a reset/in-memory key, asserts a
  falsehood.
- **Two near-identical page names** — "Scalping cockpit" (/cockpit) vs "Scalper cockpit" (/scalper).
- **Strategies list tag-flood** — 10+ chips per row drown name/status; NOTIFY checkbox no save feedback.
- **Jobs list raw hashes** for deleted strategies; results header titled by hash not strategy name.
- **Market Movers scope confusion** — universe "NIFTY 50" lists only 3 NIFTY futures + "No losers".
- **Paper risk-limit row** — cramped unlabeled group, ambiguous "Off", tiny inputs.
- **Journal form** — "Disc."/"Emo." no scale hint; Delete bare red link, no confirm observed.
- **Active Strikes IV chips over-round** — 0.10/0.10/0.00 vs plotted 0.106–0.118.
- **Open-High Strategy dead columns** — Call O=H / Put O=L all "—" mid-session.

## 4. Accessibility and responsiveness

- **axe (WCAG 2A/AA) near-clean:** dashboard + paper 0 violations; elsewhere only `color-contrast`
  (1–3 nodes on cockpit/chain/oi-analysis) and `scrollable-region-focusable` (chain + rejections scroll
  containers unreachable by keyboard).
- **Focus rings** visible on controls (Live toggle), but several tab stops show no visible focus
  (table/segmented regions).
- **Themes:** Light + OiPulse Red hold on the densest page; minor persistence race (server appearance vs
  localStorage) seen across sessions.
- **Mobile 480px:** good (cards, wrapped tables). Gaps: 3-row stacked nav (~300px, no hamburger); mobile
  chain card list starts at deepest ITM — ~55 cards of scrolling to ATM, no jump-to-ATM.

## 5. Charts and data presentation

- Disciplined echarts + LWC: consistent bull/bear palette, brushes, ATM markers, legends. Interval-wise OI
  and OI Statistics are model examples.
- **OI Heatmap:** visualMap slider labels ("-13,77,480", "8,77,480") collide with x-axis ticks under both
  panels (F3-class polish).
- **Straddle chart:** min/max floating text ("299.00", "238.05") overlap candles — oipulse uses balloon
  pins.
- **Advance Chart (5m):** RSI pane boundary not separated — stray "0"/"80.00"/"70.00" labels bleed between
  panes; giant 09:15 gap-artifact first bar; VWAP/VWMA hard to spot vs legend.
- **Index charts carry a dead volume pane** (index volume = 0) on /charts + Advance Chart.
- **Multiframe 1m pane** "No candles" for NSE:NIFTY 50 by default — the default 4th pane permanently empty
  for index symbols.
- **Backtest equity chart** repeats x-axis dates (ticks per bar); CAGR -90.67% annualized from a 6-week
  window is decision-noise.

## 6. State and interaction quality

- TanStack Query wiring mostly sound: cache-first nav, Go in-flight states, WS pill + reconnect
  invalidation, envelopes handled; #418's 401-redirect/MOCK-tag/PanelError in place.
- **Systemic gap = the pending branch** (§3-P0): pages whose FilterBar context is unsatisfied render final
  empty copy instead of a skeleton.
- **No as-of/auto-refresh on Cockpit market panels** (known open register item): spot/PCR/matrix freeze at
  last Go with no staleness cue while the paper book polls live.
- Duplicated clocks (topbar vs page "Live") tick seconds apart.
- Signals/rejections cap at 200 rows silently (register item, still open).

## 7. oipulse replication fidelity (pass-1 live-vs-live, matched params)

- **Structural parity complete** on all five compared pages. OI Analysis columns 1:1; Options Chain
  matches + exceeds on IV/greeks; Straddle replicates candles/VWAP/20EMA/legs/brush; Connecting Dots full
  dot set.
- **Data fidelity: OI within ~0.4–0.5%, LTP within a few %** on completed buckets (NIFTY 24100 3m).
  Snapshot-timing offset (our 3-min REST capture vs their source), not a pipeline defect; interpretations
  matched wherever |ΔOI| was decisive.
- **Connecting Dots trend flips on marginal buckets** — 5/8 matched, 3 flipped. Root causes visible in the
  dots: **our Dow Jones dot is permanently neutral on LIVE** (theirs active ↑/↓ — un-armed by decision but
  it mutes the live composite too), plus VIX/ASIV timing divergence. Marginal-bucket flips vs oipulse are
  expected behaviour today; worth revisiting the Dow-dot decision for live.
- **Feature gaps:** Trending OI lacks their Strength pill + Day-H/L-diff-in-OI columns, Show-Graph-View /
  positional toggles, strike-basket selector ("Change Strike Prices"); platform-wide no futures ticker
  strip / underlying-quote+chg header (previously accepted divergences).

## 8. Prioritized fixes

### Top 10 (fix first)

1. **Pending-renders-as-empty on Options Chain / Straddle / expiry-dependent pages** — skeletons while
   pending or FilterBar unsatisfied; reserve empty copy for a settled empty response.
2. **Remove the "OiPulse" watermark** from Options Premium.
3. **BUY rendered red** on Backtest Trades SIDE column → bull-green/neutral.
4. **Cockpit as-of + auto-refresh** — render chain `asOf` + stale flag; 30–60s market-hours refetch on the
   four market panels.
5. **Mobile chain: jump/auto-scroll to ATM** (strike-window around ATM).
6. **Settings "Data sync: NEVER_RUN" lie** — persist real last-run or label "since restart".
7. **Keyboard access for scrollable tables + consistent focus rings** (+ the few contrast nodes).
8. **OI Heatmap colorbar/axis label collision.**
9. **Rename one cockpit** ("Scalper cockpit" → e.g. "Paper ticket").
10. **Jobs/results show strategy names, not hashes** (resolve deleted IDs to last-known name; title
    results with name + version).

### Fix after the top 10

**Trust/data-reading:** Active-Strikes-IV chip precision; short-window CAGR suppression; equity-chart
daily tick de-dup; hide zero-volume pane on index charts; Multiframe skip-1m default for index symbols;
Market Movers universe semantics; trending-oi all-same-arrow column merge; absurd PCR (16385.00) cap/dash
below an OI floor; 09:15 gap-artifact first bar; single clock source.

**Forms/controls:** Paper risk-limit row redesign; Journal hints + delete confirm; watchlists remove
confirm; strategy tag chips collapse to +N; NOTIFY save feedback; backtest runner max-width card; login
brand lockup.

**Charts polish:** straddle min/max pins with collision offset; RSI pane divider; multiple-OI right-axis
clip; sentiment "0" label clip; heatmap 09:15 grey-column note.

**oipulse parity backlog (optional):** Trending OI Strength + Day-H/L-diff columns + graph-view toggle +
strike-basket selector; underlying-quote header with chg%; futures ticker strip (deliberately skipped —
reaffirm or build).

**Register-known, reconfirmed live:** signals/rejections 200-row cap; cockpit fetch-once; calendar-spread
expiry-heal opt-out.

---

## 9. Pass 2 — interactive control-parity + data-accuracy audit (2026-07-02, 12:20–12:50 IST)

**Method:** repeated the pass-1 walk with 8–10s data-load waits; interacted with every FilterBar control
class (underlying, expiry, interval, strike, date, Live/History toggle, Go) on both platforms; matched-param
live AND historical (2026-07-01, fully settled) comparisons with oipulse as the accuracy barometer;
control-option-set diffs recorded; AY-only pages analyzed logically.

### 9.1 Data accuracy vs the barometer — the headline result

**The OI pipeline is CORRECT; the bucket *labels* are phase-shifted.** Historical Jul-01, NIFTY 24100, 3m,
both sides fully settled:

- Multiple buckets matched **EXACTLY to the contract** at the same label: 15:03–15:06 Call OI 62,24,595
  AND Put OI 31,12,850 identical on both platforms; 15:12–15:15 Call OI 61,08,830 identical.
- The end-of-day rows matched exactly but **one bucket apart**: our "15:30–15:33" row = oipulse's
  "15:27–15:30" row (Call OI 52,85,085, Put OI 22,74,935 — both exact).
- Adjacent buckets drift 2k–1.7L contracts and LTPs differ 1–4 points.

**Diagnosis:** our 3-min snapshot lands mid-bucket (capture offset seconds inside the window) while
oipulse samples at the bucket boundary. When OI is static across the offset → exact match; when moving →
one-bucket skew and drift. Consequences: (a) interpretation chips (L.B./S.C./…) flip vs oipulse on
marginal buckets, live and historical; (b) our post-close artifact bucket "15:30–15:33" exists where
theirs says "15:30-EOD"; (c) LTP columns come from a different source (our candle close vs their quote
snap) and differ by a few points at identical-OI buckets. **Fix direction:** align the snapshot job to
bucket boundaries, or label rows by the boundary the snapshot represents; render an EOD label; document
the LTP source.

**EOD pipelines are barometer-perfect:** FII/DII Capital Market matched oipulse **digit-for-digit on
every value of all 5 most-recent rows** (e.g. 01-07: 11,623.31 / 12,763.81 / −1,140.5 / 2,018.74 /
3,159.24 / 17,136.57 / 13,977.33). Participant-wise OI contract counts matched **exactly in every cell**
(FII Future Index 32,476 / 2,92,535 / −2,60,059 / −1,026 / +2,623 / −3,649 …).

### 9.2 Semantic divergences (same label, different meaning — decision risk)

1. **Participant-wise OI "%"**: ours = share of the participant's own book (Future Index Long "0.6%");
   theirs = participant's share of that segment market-wide ("8%"). Same parenthesis, different
   denominator. Label ours explicitly or adopt theirs (the standard reading).
2. **Participant-wise OI "Interpretation"**: ours reads the POSITION level (Future Stock net-long →
   Bullish); theirs reads the day DELTA with put-inversion (Chng-in-Total −13,245 → Bearish). Badges
   disagree on 2 of 6 FII rows against identical numbers. Pick one semantic and label it.
3. **Options-chain OI Chng / OI %**: theirs defaults to a "Full Day" period (day-cumulative change);
   ours is bucket-relative to the selected interval. Ours answers "what changed in the last 3m", theirs
   "what changed today". **We have no Full-Day option anywhere on the chain** — add a "Full day" interval
   (oi-stats has the same Select-Period concept).
4. **Trending OI Δ columns**: ours claims "cumulative vs the session-open baseline" but the backend
   returns only ~20 buckets, so the baseline silently rebases ~1 hour back (live AND history: Jul-01
   history bottoms out at a fake all-zero 14:33 "Neutral" row; live magnitudes read ~7L where the
   barometer reads 2.11Cr). **The header formula is false as shipped — this is the one genuine DATA BUG
   found** (as opposed to label/semantic issues). Fix: serve the full session (their table = 66 rows @3m)
   or rebase honestly. Affects /options/trending-oi and /options/trending-oi-pa.
5. **Big OI Movement**: theirs is a session-long event LOG (rows timestamped through the day); ours is a
   top-10 NOW leaderboard (all rows share the latest bucket). Different questions; consider adding the
   chronological log view.
6. **Sentiment/trend enums track directionally**: live Trending OI flipped Bullish on both platforms at
   12:03–12:06; ours grades "Ext. Bullish" where theirs shows "Bullish +45%" — grading thresholds differ
   but the direction matched all buckets checked. Connecting-Dots trend flips on marginal buckets remain
   (pass-1 §7 — Dow dot permanently neutral live).

### 9.3 Control-set parity (interacted on both sides)

- **Underlying/Name**: full F&O universe on both (~200 stocks + indices). oipulse pins the 9 indices at
  the top; ours sorts them into the alphabet — add an indices group header.
- **Expiry**: ours is a super-set (weeklies + monthlies + quarterlies + LEAPS to 2031); theirs stops at
  near monthlies. Expiry switching works (07-14 loads with correct DTE 12 / ATM / PCR).
- **Interval**: ours 1m/3m/5m/10m/15m/30m/60m ⊇ theirs 3/5/10/15/30/60 (their straddle also has 1 min).
  Gap is only the "Full day" period (see 9.2-3).
- **Live/Historical**: both have it; **our historical depth is effectively unlimited (candle-derived)
  vs their 2 calendar days** on the owner's plan — a major AY advantage. BUT two AY mode bugs: (a) the
  free-text date input accepts garbage ("02-07-72026" typed naturally) — the resulting server error DID
  render the proper #418 error card; (b) after History→Live toggle + Go, the table kept rendering
  YESTERDAY's rows under a "Live" label until a full page reload (stale param/cache-key leak) — trust bug.
- **Strike selects**: same range both sides (NIFTY 21350–26300 class); ours on straddle/oi-analysis
  auto-picks ATM ✓ (theirs defaults 24000 or blank; their active-strikes requires a manual pick with an
  empty "No data available" chart until Go — ours auto-loads, better).
- **Stock underlyings**: RELIANCE chain loads correctly (monthly expiry auto-picked, ATM/greeks/PCR fine)
  but **all interval-Δ columns render "—": our 3-min OI capture covers index chains only, oipulse has
  stock-chain interval data**. Biggest data-scope gap; at minimum annotate the page ("interval OI is
  captured for index chains only").

### 9.4 Coverage gaps vs counterparts (beyond pass-1 §7)

- **Futures Market Movers**: theirs = all-F&O-stocks universe (143 movers), Min-B.O.-Days breakout-depth
  column, per-row mini-chart links, a live "New High/Low Maker" panel, historical mode. Ours = the
  NIFTY-futures family only with none of those columns. The pass-1 "scope confusion" is actually a
  data-universe gap (bank-radar capture vs full F&O).
- **Equity Pre-Open**: theirs = full scanner (188 F&O stocks + 25 indices, prev-day-break badges,
  pre-open snapshot preserved all day, historical mode). Ours = 4-index phase card that pivots to LIVE
  LTP after 09:15 (the morning pre-open snapshot is unreviewable later; no history). Partly a deliberate
  descope — reaffirm or extend.
- **Index Contribution**: theirs is LIVE intraday (points vs live index, as-on 12:47); ours is
  previous-day EOD with the "live index level deferred" note. Intraday the page answers yesterday's
  question. The deferred live-points item is worth pulling forward.
- **Option Premium**: theirs plots NEGATIVE extrinsic on deep-ITM (discount) rows; ours clamps ≥0 —
  real microstructure info dropped.
- Interval-wise OI: ours is a super-set (10 bars vs 5; 4-class interpretation colouring vs plain CE/PE).
  OI Spurt / OI Stats / Active Strikes / big-OI columns: full structural parity confirmed.

### 9.5 "Oi Pulse" watermark — platform-wide, not one page

The competitor watermark is hard-coded in shared chart components, some interpolating our own symbol into
their brand: `VixIndexChart.tsx:89` (`text: 'Oi Pulse'`), `FuturesOiChart.tsx:130` + 
`OptionsLegChart.tsx:130` (`` `Oi Pulse / ${tradingsymbol}` ``), plus "OiPulse" strings in
AdvanceChartPage, FiiLongShortPage, OptionsPremiumPage, AdvanceChart. Replace with an ArthaYantra
watermark (or none) in one sweep.

### 9.6 AY-only pages — logical analysis (no counterpart)

- **Signal Rejections / Signals / Paper / Cockpit**: internally consistent — 0 signals ↔ 63 rejections ↔
  rails blocking on volume-floor/time-window; rejection volume resumed post-redeploy (the 10:00→11:48 gap
  in pass-1 was the redeploy window). Paper equity 200k / AUTO-PAPER ON / kill-switch all coherent.
- **OI Heatmap** (openalgo-derived, no oipulse counterpart): 21 strikes × 53 buckets at 3m = exactly
  09:15→11:51 ✓; grey first column = capture-start artifact (label it).
- **Vix & Index**: live to the minute; VIX 12.6 matches the chain tile ✓. Axis-label/toolbox collision
  top-right; carries the watermark (9.5).
- **Market Breadth** (EOD bhavcopy): 1346+1038+23=2407 ✓ sums; avg delivery 55.34% plausible. But the
  Delivery-% Leaders board is 100% ETFs/BeES (always ~100% delivery) — filter ETF series or floor by
  traded value, else the leaderboard is decorative.
- **World Indices**: per-row as-of timestamps ✓, GIFT-NIFTY +0.62% consistent with NIFTY +0.53% ✓; but
  closed markets (Dow at 03:19 ET) still show "live"-looking quotes with drift — add a market-closed
  state per row.
- **Multiple Window**: panes work but embed each page's full H1 + description + FilterBar — a compact
  pane-header mode would double the data density.

### 9.7 Pass-2 additions to the fix queue

**Promote to the top-10 (data correctness):**
- **T1. Trending-OI window cap + false baseline** (9.2-4) — genuine data bug, both modes, two pages.
- **T2. Snapshot capture phase-alignment** (9.1) — makes every OI page's buckets and interpretation chips
  match the barometer exactly; also kills the post-close artifact bucket.
- **T3. Watermark sweep** (9.5) — upgraded from one page to platform-wide.
- **T4. History→Live stale-render leak** (9.3) — mode label says Live, data is yesterday's.

**Queue behind those:** participant-wise % + interpretation semantics (9.2-1/2); chain "Full day" period
option (9.2-3); stock-chain capture note (9.3); pre-open scanner + index-contribution live-points +
market-movers universe (9.4 — owner-scope decisions); option-premium negative extrinsic; breadth ETF
filter; world-indices closed-market state; date-input validation on History mode.

**Reaffirmed accurate (no action):** FII/DII capital market (exact), participant-wise contract counts
(exact), OI levels at aligned buckets (exact), straddle/VWAP live agreement, sentiment direction
agreement, expiry/strike/underlying control behaviour, RELIANCE/stock chain mechanics, breadth sums,
heatmap bucket math, VIX consistency.

---

## 10. Pass 2b — full remaining-page sweep (2026-07-02, 13:15–13:45 IST)

Completes 100% page coverage: every remaining options/futures/FII/equity/features page compared
interactively against its oipulse counterpart (8–10s waits, control interactions on both sides), and
every AY-only workflow page exercised.

### 10.1 New barometer-EXACT confirmations (data accuracy verdicts)

- **Delivery Data (AXISBANK, 15 days): every row identical** — %Delivery 53.82/45.73/70.13…, delivery
  and traded quantities, OHLC, day ranges, all digit-for-digit.
- **Futures OI: exact same source** — our Futures OI Analysis 13:27–13:30 Total OI 1,77,16,530 equals
  their EOD-analyzer current-month value exactly; JULFUT OI in our oi-spurt (1,77,16,205) matched to
  live drift.
- **OI Buzz treemap: exact** — Advance 40 / Decline 10 identical; tile values match (TECHM 4.08=4.08,
  TCS 3.94=3.94; rest within live drift).
- **FII Long-Short Ratio: headline identical to the word** — "FII are long for 9.99%" on both; ours is
  internally consistent with the exact participant-wise counts (32,476/(32,476+2,92,535)).
- **Market Holidays: all trading-holiday dates match**; ours deliberately lists engine trading closures
  only (weekday) vs their all-observances list (incl. Sunday festivals + muhurat asterisk) — semantic,
  fine.
- **Announcement page live-verified** (filings minutes old, PDF links, field mapping correct — closes
  the #378 deploy-verify note). Futures Pre-Open + Equity O=H/O=L live-sane.

### 10.2 New findings (pass-2b)

1. **Active-Strikes-IV skew is dead by construction** — the barometer plots distinct Call IV (~9–11.5)
   vs Put IV (~11.5–12), a real ~2-point put skew; ours plots call≈put overlapping because our Black-76
   IV uses the PCP-implied forward, which forces put-call parity → IV skew ≈ 0 always. The page's whole
   signal (skew) cannot appear. Fix: solve per-side IV off the actual forward/spot (or display side IVs
   from unconstrained solves), and display in % not decimals. **Promote next to T1/T2.**
2. **Journal Delete confirmed one-click, no confirmation** (round-trip tested: created + deleted an
   entry) — add a confirm.
3. **Compare Backtests page has no picker** — literally "Add runs to compare (e.g.
   /backtests/compare?ids=run1,run2)"; needs run selection from the jobs list.
4. **Futures EOD OI Analyzer defaults to the wrong contract** (AUGFUT, not the front month) and offers
   only per-dated-contract views (10 rows since contract birth) vs the barometer's continuous
   current-month view with 400-session history + chart/cumulative/range toggles.
5. **Futures OI Analysis** (AY table): full 84-row session — confirms the 20-bucket cap is
   trending-oi-specific, NOT pipeline-wide. Nits: Day-High/Low columns repeat one static value per row,
   Level-Break column renders empty all session, and the OI y-axis on Futures OI Chart prints "1.8Cr"
   on every tick (zero resolution).
6. **Watermark scope re-confirmed live** on Options Chart (both panels: `Oi Pulse / NIFTY…CE|PE`),
   Futures OI Chart, and the LSR page — matches the §9.5 file list.
7. **Options Chart**: full parity incl. side-by-side/Go/strike interactions (strike switch verified);
   only gap = IV + Volume sub-panes, which the page already declares as deferred in a footer note.
8. **Open-High Strategy vs theirs**: they populate O=H/O=L chips live, show "Hit ✓" probability states,
   Triggered Time, New D.High/D.Low, and an Open=High/Open=Low toggle; our equivalent columns sit empty
   ("—") all session. (Our extra: premium Fall% columns.)
9. **OI Expiry Strategy**: theirs renders a 5-strike basket (a CE+PE table pair per strike, Change-
   Strike-Prices button); ours is single-strike with a dropdown.
10. **Trending-OI-PA** shares trending-oi's engine — the §9.2-4 window-cap/baseline bug and
    basket-vs-chain scale divergence apply to it too.
11. **Sector Stats / Sector Heatmap / Equity Returns freshness mix** — live index tiles and live
    "Current Day" columns sit beside "as on <yesterday>" EOD aggregates on the same screens (NIFTY IT
    tile +4.47% live vs IT sector card −1.23% EOD reads contradictory); the barometer's sector-stats is
    fully live with per-index constituent up/down tables. Label the EOD panels loudly or compute live.
12. **Risk Calculator**: ours is pure-client (manual entry price); theirs auto-fills from a live
    option picker (name/expiry/strike/type). Minor gap, ours self-contained.
13. **Calendar Spread purpose divergence is by design** — theirs is a position-builder tool (Add
    Position, signals); ours is the read-only near−far premium chart (#36 descope, #390 viz). Strangle:
    our checkbox = their separate page; functional parity.
14. **Multiple-OI-Chart interactions verified** (searchable multi-select, add-strike renders 3rd
    series); their mode radio resets Historical→Live on Go (their quirk, not ours).
15. **AY-only workflow pages exercised**: strategy editor (YAML + validation + version-bump + save
    ✓), strategy builder (chain-gated leg composer, sane empty state), data-ops coverage/query/export/
    collection (all functional; coverage 40,740 contracts 100%, SQL console presets + CSV), news
    (works, thin 7-day Upstox feed — AY-extra), equity pre-open / index-contribution (as §9.4).

### 10.3 Final coverage statement

Every route in App.tsx has now been exercised across the three passes (66 routes; the two
parameterized detail routes — backtest results, sweep detail — via a live results page and the
#416-tested sweep flow respectively). All 30 oipulse-counterpart pages compared live or historical
with matched params where their plan allowed (their historical depth = 2 days). Barometer-exact set:
OI buckets (aligned), FII/DII capital market, participant-wise counts, delivery data, OI buzz, futures
OI totals, LSR, holidays. Divergence classes are fully enumerated in §9.2/§10.2 — nothing else
surfaced.
