## Gap Theory (Siva #4) — automation-vs-doc audit

Scope: audits the Gap-Theory dimension of the options-scalper automation against doc §3.4 (full
strategy spec) and §6.4 (cheat-sheet/matrix view), against the three `scalp-gap-theory-*.yaml`
strategies (`scalp-gap-theory-nifty.yaml`, `scalp-gap-theory-sensex-niftyoi.yaml`,
`scalp-gap-theory-sensex-sensexoi.yaml`), the live-signal gate code
(`ScalperConfluenceGate` / `GapTheoryGate` / `GapState`), and the shared scalper rails
(`ScalperGates`, `ScalperManualChecks`, `ConnectTheDotsService`). KEY STRUCTURAL FACT used throughout:
the gap-fill primitive (`GapTheoryGate`) runs ONLY on the LIVE signal path
(`strategy-signal-service`); it is NOT wired into the strategy-engine backtest replay, whose only
entry conditions are the YAML `entry_rules.gate.all` (`close > vwap` AND `close > vwma20`) + scoring
threshold 0.2. So "FULL" below means automated for live signal emission; the backtest is a thinner
chart-only proxy. Derived-history caveat applies to the §3.4 OI/VIX/Dow confluence (degrades to
NEUTRAL on backtests).

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|------|-------|--------|----------------------------------|--------------------|
| Significant gap = above **3 points / 60 ticks** between prior candle close and current open | §3.4 L596; §6.4 setup | FULL | `GapState.java:34` `MIN_POINTS = new BigDecimal("3")`; `GapState.detect` L43-67 | — (live path only; backtest has no gap detector) |
| Gap NOT already filled by the creating candle's own body/wick (filled-on-creation is inert) | §3.4 L597; §6.4 | FULL | `GapState.java:59-62` `filledBy(gapCandle, origin, bullish)` skip | — |
| WAIT for the gap to fill; BLOCK a fresh entry while a significant gap is still open | §3.4 L604 / L613; §6.4 entry | FULL | `GapTheoryGate.java:56-57` returns `BLOCK` when `!gap.filled()`; armed by `ScalperConfig.java:121` `tags.contains("gap-theory")`→`requireGapFill`; consumed `ScalperConfluenceGate.java:173-177` | — |
| After the fill, trade WITH the overall/prevailing trend (not the short move that made the gap) | §3.4 L605/L638; §6.4 | PARTIAL (live FULL on bias) | `GapTheoryGate` passes once filled, then the side is decided by VWAP (`ScalperConfluenceGate.java:149-152`) + the full ConnectTheDots confluence (L250-253). **Live: the 1h-Supertrend `bias60m` alias (`...nifty.yaml:43`) IS a HARD prevailing-trend filter** — `ScalperConfluenceGate.java:252` feeds it to `ConnectTheDotsScorer.java:111` (`biasAligned`) which is an AND-term of `valid` (L114-115), so a CE signal is invalidated when the 1h Supertrend is bearish. The backtest `gate.all` lacks it (chart-only). | Live path already enforces a higher-TF (1h Supertrend) prevailing-trend alignment; only the BACKTEST proxy ignores it. v2: corrected per README §5 false-coverage flag #1. |
| Significant gap → INERT when absent (leave entry to normal confluence) | §3.4 (gap only on a fraction of bars) | FULL | `GapTheoryGate.java:53-54` returns `INERT` when `!gap.present()` | — |
| High-probability variant: measure gap from prior candle **HIGH→open** (bull) / **LOW→open** (bear) | §3.4 L595; §6.4 setup | NONE | `GapState.java:12-13` doc-comment: high/low variant "is a stricter superset and is not used in v1"; detector uses prior **close**→open only (L52-53) | Trader must eyeball whether the stricter high/low gap is present for a higher-probability entry. Automatable: true (candle high/low are in the series). |
| Stop-loss = **SuperTrend level** for in-trend entries (CE 9-Jan SL 42431 = ST) | §3.4 L619; §6.4 stop_loss | PARTIAL | `GapTheoryGate.java:17-21` explicitly DEFERS the ST-level SL ("engine exposes SuperTrend only as a direction, not a level"); uses the matrix alternative instead | Manually compare the engine's pre-gap SL against the live SuperTrend level; tighten if ST is closer. Automatable: true (would need ST exposed as a price band, not just +1/-1). |
| Stop-loss matrix alternative = **low of the candle BEFORE the gap candle** (longs) / its high (shorts) | §3.4 L619 ("Matrix alternative"); §6.4 stop_loss | FULL | `GapTheoryGate.java:60-65` `preGapCandle(...).low()`/`.high()`; `ScalperConfluenceGate.java:178` `structuralStop = gap.stopLevel()`; `ScalperConfig.StructuralStop.GAP_TREND` (`ScalperConfig.java:139-140`) | — |
| **[S24] Gap is a 30–60 min play**; if unfilled on volume by ~30–40 min, abandon gap & trade the prevailing trend | §3.4 L622 | PARTIAL | `time_stop max_bars: 20` (`scalp-gap-theory-nifty.yaml:56`) = 20×3m = 60 min EXIT cap. But this is a post-entry time-stop, NOT the "abandon the unfilled gap at ~40 min and switch to trend" rule, and the "on volume" qualifier is absent. | Manually watch the ~30-40 min mark: if the gap has not filled on volume, drop the gap setup and take the trend trade. Automatable: partial (a fill-deadline counter could be added; "on volume" needs a volume gate on the fill bar). |
| **[S24] Gap-trade stop-loss = ~50–60 points or a nearby S/R level** | §3.4 L622 | NONE | The engine anchors the gap-fill SL on the pre-gap candle's low/high (`GapTheoryGate.java:60-65`, `StructuralStop.GAP_TREND`), NOT a fixed ~50–60 pt distance and not an S/R level. The doc states this SL specifically for the S24 gap play; no points-based or S/R-based stop is encoded. | Manually size the SL to ~50–60 pts (or the nearest S/R) on the gap trade if that is tighter/looser than the pre-gap-candle anchor. Automatable: partial (a fixed-points SL is encodable; an S/R-level SL needs S/R levels). |
| **Counter-trend "trade TOWARD the gap"** (entry on rejection toward gap, target = gap level, SL = day-high/day-low) — risky/scalping-only | §3.4 L614/L639; §6.4 bearish[5] | NONE | Explicitly NOT automated: `scalp-gap-theory-nifty.yaml:12-14` ("DEFERRED (manual only, not automated in v1)"); `GapTheoryGate.java:23-25` | If trading the risky gap-fill scalp, place it manually: enter on rejection toward the gap, target the gap level, SL = day high/low. Automatable: true (gap level + day high/low are derivable) but deliberately deferred as risky. |
| Take trade only **after 9:45 AM** (Gap time filter) | §3.4 L598/L627; §6.4 filters | FULL | `risk.session.window.from: "09:45"` (`scalp-gap-theory-nifty.yaml:65`, all three variants) | — |
| Ideal window 9:15–10:00; avoid 11 AM–1 PM sideways; no new entries before post-3:30 PM events | §3.4 L627; §6.4 filters | PARTIAL | **Live path DOES hard-block the 11:00–13:00 sideways window and any fresh entry at/after 15:30** — `ScalperGates.java:23-25` (`MIDDAY_BLOCK_FROM/TO`, `NO_FRESH_ENTRY_AFTER`), enforced in `timeWindow(...)` L37-42, consulted at `ScalperConfluenceGate.java:112-117`. Genuinely unautomated: only the soft 9:15–10:00 "ideal" preference and the pre-event post-3:30 lockout (the 15:30 cut is a flat clock, not an event calendar). | Soft 9:15–10:00 ideal preference + a true post-3:30 event-calendar lockout remain manual. v2: corrected per README §5 false-coverage flag #2 (11–1 block + 15:30 cut ARE automated live). |
| RSI 14 (3-min): CE RSI < 75 (not overbought), PE RSI > 25 (not oversold); 40–60 no-trade zone | §3.4 L628; §6.4 indicators/filters | PARTIAL | Live: `ScalperGates.rsiBand(chart.rsi14(), side)` is a HARD rail (`ScalperConfluenceGate.java:158-161`). Backtest: RSI indicator is declared (`...nifty.yaml:39`) but feeds SCORING only — the `gate.all` (L48-50) has no RSI clause, so the backtest does not hard-block on RSI bands. | For backtest fidelity / manual confirm: ensure RSI is < 75 (CE) and outside the 40-60 no-trade zone at entry. Automatable: true (add RSI clauses to the YAML `gate.all`). |
| Volume confirmation on gap/fill candle (BN 50K / Nifty 125K, per Common Components) | §3.4 L629; §6.4 indicators; §6.4 uncertain | PARTIAL | Live: `ScalperGates.volume(...)` is a hard rail (`ScalperConfluenceGate.java:161`). Backtest: no volume gate in `gate.all`. Doc itself flags this as UNCERTAIN — "the deck does not set a numeric gap-candle volume rule" (§3.4 L629, §6.4 L2180). | Manually confirm the fill/gap candle prints ≥ the volume threshold. Automatable: true for live (already is); doc-uncertain on the exact numeric rule. |
| Primary direction chart = the index FUTURE (current month), 3-minute timeframe | §3.4 L590/L593/L598; §6.4 timeframe/instruments | FULL | `signal_underlying: { exchange: NFO, tradingsymbol: "NIFTY-FUT-CONT" }` (`...nifty.yaml:26`), `timeframes.primary: 3m` (`...nifty.yaml:33`); the gate detects gaps on the signal FUTURE series (`GapTheoryGate.evaluate(EngineSeries future, ...)` L47-48). Doc charts Bank Nifty FUT; automation re-homes to NIFTY-FUT-CONT (BN is now monthly-expiry — 2b LOCKED decision, `...nifty.yaml:1`). | — (instrument re-homed off Bank Nifty by design; the 3-min FUT timeframe + future-series gap detection are faithful) |
| Indicators loaded on 3-min: VWAP, VWMA 20, SuperTrend (10,2), RSI 14 | §3.4 L599/L629; §6.4 indicators | FULL | `...nifty.yaml:37-43` VWMA period 20, SUPERTREND period 10 mult 2.0, RSI period 14; VWAP via `close > vwap` gate | — (engine exposes Supertrend as direction, not level — see SL rows) |
| Entry at gap-filled area, **pullback near VWMA / SuperTrend / VWAP** | §3.4 L606; §6.4 entry/filters | PARTIAL | `gate.all`: `close > vwap` AND `close > vwma20` (`...nifty.yaml:49-50`) encodes price-leads-VWAP/VWMA, but NOT a "pullback proximity to ST/VWAP" entry-location rule | Prefer entries that are a pullback to VWMA/ST/VWAP, not extended away. Automatable: true (a distance-to-VWAP band could gate it; doc §3.4 L186 even warns wide gap-to-VWAP = wider SL). |
| Align OI / Trending OI, India VIX, DOW & global cues per Common Components | §3.4 L630; §6.4 filters | PARTIAL / MANUAL_COVERED | Live: full ConnectTheDots confluence runs (`ScalperConfluenceGate.java:250-253` via `ConnectTheDotsService`). Backtest: `oi_confluence_gate.enabled: false` (`...nifty.yaml:70`) so OI is OFF; VIX/DOW degrade to NEUTRAL on derived history. Manual coverage: `vix_normal` (§4.5), `global_cues_ok` (§4.7) in `ScalperManualChecks.java:46-55`. | Confirm OI / VIX / DOW align before the gap trade (manual checks already cover VIX + global cues). Automatable: true for live; muted on backtests by data fidelity. |
| **Gap-UP bias**: do NOT short Bank Nifty/Nifty on a gap up — look for support/long instead | §3.4 L605/L615/L630; §6.4 bearish[6] | NONE | Strategies are `direction: long`, CE-only (`...nifty.yaml:30/46`), so they never short a gap-up — but there is no explicit "on a gap-up flip to support/long" rule; it is incidental to being long-only. | On a gap-up, look for a support/long entry, never a short. Automatable: partial (long-only side already prevents the short; the active "seek support on gap-up" is not encoded). |
| Targets: in-trend next S/R (R:R 1:2.5 / 1:1.6–1.7); scalp aim ≤1–2% (let R:R ~1%) | §3.4 L618; §6.4 target | NONE | No target/take-profit in exit_rules — only `signal_exit close < vwap` + `time_stop` (`...nifty.yaml:55-56`). No R:R or S/R target. | Set a manual target at the next S/R (≈1:2 R:R, ≤1-2%). Automatable: partial (a fixed-R:R or ATR target is encodable; a true next-S/R target needs S/R levels). |
| Trail SL ~5 pts below price (longs) / above (shorts) once in profit; trail 5 pts below gap reference on gap trades | §3.4 L620; §6.4 scaling; §6.4 stop_loss | NONE | No trailing-stop in exit_rules (`...nifty.yaml:54-56`); only a fixed pre-gap structural SL + signal/time exit | Manually trail the SL ~5 pts below price once in profit. Automatable: true (a trailing-stop exit type). |
| Strike/Delta selection: ATM ±3, **delta 0.6–0.7** buys, premium 250-400 BN / 100-250 Nifty | §3.4 L631; §6.4 filters | PARTIAL | `strikes: { selector: atm_window, width: 3 }` (`...nifty.yaml:29`) = ATM ±3; CE-only. Live `StrikePicker.pick(...)` applies the delta/premium band via `cfg.strikeParams()` (`ScalperConfluenceGate.java:271-276`; band built in `ScalperConfig.java:117-118` `DELTA_LO 0.6`/`DELTA_HI 0.7` + per-index `PREMIUM`). Backtest selects by ATM window only (no delta/premium band in the YAML). | Confirm the chosen strike sits in the 0.6-0.7 delta band / premium range. Automatable: true (delta band already in the live StrikePicker; not surfaced into the backtest selector). |
| Higher-TF / option-price gaps do NOT reliably fill — do not apply gap-fill there | §3.4 L588/L636/L637; §6.4 edge_cases | FULL (by construction) | Detector runs on the 3-min futures session only (`GapState.detect` scoped to IST session, on the signal future series); option-leg premiums are never gap-detected | — (the constraint is honoured by only detecting on the 3-min future). |
| News overrides the data on gap/event days ("throw the data out") | §3.4 risk; doc §2.13 | MANUAL_COVERED | `ScalperManualChecks.java:26-30` `news_clear` (doc_ref 2.13) | Confirm no market-moving news against the trade before entry. Automatable: false (judgement / news feed). |
| Avoid parabolic / forced entries; clean "one good trade"; regime suits (not choppy) | §3.4 risk; doc §3.1/§3.10 | MANUAL_COVERED | `ScalperManualChecks.java:36-60` `not_parabolic`, `clean_setup`, `regime_ok`, `level_respected` | Confirm the entry is a clean, non-parabolic setup at a respected level. Automatable: partial (VWAP-crossover-count proxy exists in the assist text, not gated). |

### Not automated (gaps)

- **High-probability high/low gap variant** (§3.4 L595): the detector measures prior-close→open only; the stricter prior-high→open (bull) / prior-low→open (bear) variant is explicitly "not used in v1" (`GapState.java:14-15`). Manual: eyeball whether the stricter gap is present.
- **Counter-trend "trade toward the gap" scalp** (§3.4 L614/L639): deliberately deferred as risky/scalping-only — entry on rejection toward the gap, target = gap level, SL = day-high/low — fully manual.
- **SuperTrend-level stop-loss** (§3.4 L619): the doc's preferred in-trend SL is the ST level; the engine only uses the matrix pre-gap-candle alternative because Supertrend is exposed as a direction, not a price level.
- **Targets / R:R and trailing stop** (§3.4 L618/L620): no take-profit, no ≈1:2 R:R target, no ~5-pt trailing stop in any gap YAML — exits are VWAP-cross + a 60-min time-stop only. Fully manual.
- **Session-24 "abandon the unfilled gap at ~30-40 min, on volume, and trade the trend"** (§3.4 L622): only a post-entry 60-min time-stop is encoded; the pre-entry fill-deadline switch and the "on volume" qualifier are absent.
- **Intraday time-of-day filters** (§3.4 L627): **[v2 corrected]** the 11 AM–1 PM sideways exclusion AND the no-fresh-entry-after-15:30 cut ARE hard-blocked on the live path (`ScalperGates.java:23-25,37-42`). Only the soft 9:15–10:00 "ideal" preference and the post-3:30 PM pre-event lockout (event calendar) are genuinely unautomated.
- **Gap-up "seek support/long" active rule** (§3.4 L605/L615): only incidentally honoured by being long/CE-only; the doc's active instruction to look for support on a gap-up is not encoded.
- **Backtest fidelity vs live**: in the backtest, the gap-fill gate, RSI band, volume threshold, delta/premium strike band, the 1h-Supertrend `bias60m` prevailing-trend filter, the 11–1/15:30 time blocks, and the OI/VIX/DOW confluence are all OFF or scoring-only — the only hard backtest entry conditions are `close > vwap` AND `close > vwma20` (threshold 0.2). All of these ARE hard rails on the live `ScalperConfluenceGate` path; judge backtest results accordingly.

## v2 review notes

Independent second-pass review of this section against doc §3.4/§6.4 and the live automation
(`GapTheoryGate`/`GapState`/`ScalperConfluenceGate`/`ScalperGates`/`ScalperConfig`/`ConnectTheDotsScorer`)
+ the three `scalp-gap-theory-*.yaml`. The v1 table was otherwise accurate (24 rows traced to real
file:line / yaml keys). Changes:

**Inaccurate rows corrected in place (both pre-flagged in README §5 "Audit-quality flags"):**
1. *"After the fill, trade WITH the prevailing trend"* — v1 claimed the `bias60m` alias "is not gated."
   FALSE-COVERAGE on the live path: `bias60m` (1h Supertrend, `...nifty.yaml:43`) IS a hard validity
   AND-term — `ScalperConfluenceGate.java:252` → `ConnectTheDotsScorer.java:111` (`biasAligned`) → L114-115
   (`valid`). A CE signal is invalidated when the 1h Supertrend is bearish. Status nuanced to
   "PARTIAL (live FULL on bias)"; only the backtest proxy lacks it.
2. *"Ideal window 9:15–10:00; avoid 11–1; no entry after 3:30"* — v1 marked NONE, saying "only a flat
   09:45→15:00 window… no 11-1 exclusion." FALSE-GAP: the live `ScalperGates.timeWindow` HARD-blocks
   11:00–13:00 and any fresh entry at/after 15:30 (`ScalperGates.java:23-25,37-42`). Re-graded PARTIAL;
   only the soft 9:15–10:00 ideal preference and the post-3:30 event lockout remain unautomated. The
   "Not automated (gaps)" intraday-time bullet updated to match.

**Missed doc rules added as new rows:**
3. *Primary direction chart = the index FUTURE (current month), 3-min* (§3.4 L590/L593/L598) — v1 had an
   "indicators loaded" row but no row asserting the chart/timeframe/instrument mapping. FULL:
   `signal_underlying: NIFTY-FUT-CONT` (`...nifty.yaml:26`) + `primary: 3m` (`...nifty.yaml:33`); the gate
   detects on the FUTURE series (`GapTheoryGate.evaluate` L47-48). Re-homed off Bank Nifty by design
   (BN now monthly-expiry, 2b LOCKED, `...nifty.yaml:1`).
4. *[S24] gap-trade SL = ~50–60 points or a nearby S/R level* (§3.4 L622) — v1's S24 row captured the
   30–60-min play / 40-min give-up but omitted this explicit SL figure. NONE: the engine anchors the SL
   on the pre-gap candle's low/high, not a ~50–60-pt distance and not an S/R level.

**Confirmed accurate (no change):** the remaining 22 v1 rows — the 3-pt/60-tick significance floor
(`GapState.java:34`), filled-on-creation inertness, WAIT/BLOCK-while-open, INERT-when-absent, the
pre-gap-candle matrix SL, the deferred SuperTrend-level SL, the deferred counter-trend gap-fill scalp,
the high/low high-prob variant (NONE), RSI/volume/strike PARTIAL (live-rail vs backtest-scoring split),
the OI/VIX/Dow confluence (PARTIAL/MANUAL_COVERED), and the no-target/no-trailing NONE rows — were each
re-traced to the cited file:line/yaml key and stand as written. No invented figures found; every doc
number (3 pts/60 ticks, ATM±3, delta 0.6–0.7, 30–60 min, ~50–60 pts) matches the cited doc lines verbatim.

## v3 review notes

Third-pass CITATION VALIDATION: every cited `file:line` / `yaml key` / `doc line` in the 26-row table
was re-opened and confirmed against the actual source. The file converged cleanly after v2 — two minor
citation line-drifts fixed, no status overturned, no still-missing doc rule found.

**Re-opened and confirmed real + accurate (no change):**
- All §3.4 doc-line cites — L596 (3 pt/60 tick), L597 (filled-on-creation), L604/L613 (WAIT bullish/bearish),
  L605/L638 (with-trend), L595 (high/low variant), L619 (ST-level SL + matrix pre-gap-candle alt; CE 9-Jan
  SL 42431=ST verbatim), L614/L639 (counter-trend toward-gap, risky/scalping-only), L598/L627 (after-9:45 +
  ideal 9:15-10:00/avoid 11-1/post-3:30), L628 (RSI<75 CE / >25 PE / 40-60 no-trade), L629 (volume BN 50K/
  Nifty 125K + "deck does not set a numeric gap-candle volume rule"), L590/L593 (primary chart = index
  FUTURE current-month 3-min), L599 (VWAP/VWMA20/ST(10,2)/RSI14), L606 (pullback near VWMA/ST/VWAP), L630
  (OI/VIX/DOW), L615 (gap-up = no short, support/long), L618 (targets 1:2.5 / 1:1.6-1.7 / ≤1-2%), L620 (trail
  ~5 pts), L631 (ATM±3, delta 0.6-0.7, premium 250-400 BN / 100-250 Nifty), L588/L636/L637 (higher-TF +
  option-price gaps don't fill), L622 ([S24] 30-60 min play / ~30-40 min give-up "on volume" + SL ~50-60 pts
  or S/R) — all present and verbatim.
- All §6.4 JSON cites — bearish[5] = L2133 (risky toward-gap), bearish[6] = L2134 (gap-up no-short),
  uncertain L2180 (no numeric gap-candle volume rule), plus setup/indicators/filters/stop_loss/edge_cases —
  all present.
- All code cites re-opened: `GapState.java:34` (MIN_POINTS "3"), `:43-67` (detect), `:59-62` (filledBy
  skip), `:52-53` (close→open); `GapTheoryGate.java:17-21` (deferred ST-level SL), `:23-25` (deferred
  counter-trend), `:53-54` (INERT), `:56-57` (BLOCK !filled), `:60-65` (preGap low/high), `:47-48`
  (evaluate signature); `ScalperConfig.java:121` (`tags.contains("gap-theory")`→`gapFill`), `:139-140`
  (`StructuralStop.GAP_TREND`); `ScalperConfluenceGate.java:112-117` (time block), `:149-152` (VWAP side),
  `:158-161` (RSI rail), `:161` (volume rail), `:173-178` (requireGapFill consume + structuralStop=stopLevel),
  `:250-253` (ConnectTheDots score feeding bias60m at L252); `ConnectTheDotsScorer.java:111` (biasAligned),
  `:114-115` (valid AND-term); `ScalperGates.java:23-25` (MIDDAY_BLOCK_FROM/TO + NO_FRESH_ENTRY_AFTER),
  `:37-42` (timeWindow 11-1 + 15:30 blocks); `ScalperManualChecks.java:26-30` (news_clear 2.13), `:46-55`
  (vix_normal 4.5 + global_cues_ok 4.7), `:36-60` (not_parabolic/regime_ok/clean_setup; level_respected is
  at L31-35 just above the cited range — all four checks real). All YAML keys (`signal_underlying`
  NIFTY-FUT-CONT L26, `primary: 3m` L33, indicators L37-43, `gate.all` L49-50, `time_stop max_bars:20` L56,
  `oi_confluence_gate.enabled:false` L70, `window.from "09:45"` L65, `strikes width:3` L29, `direction: long`
  L46, `option_types:[CE]` L30) confirmed in `scalp-gap-theory-nifty.yaml`.

**Citations corrected in place (line/key drift, status unchanged):**
1. *Strike/Delta selection* row — the live delta/premium-band cite was `ScalperConfluenceGate.java:117-119`,
   which is actually the time-window `return Optional.empty()` block, NOT the StrikePicker. Corrected to
   `ScalperConfluenceGate.java:271-276` (the `StrikePicker.pick(...)` call passing `cfg.strikeParams()`),
   plus the band's source `ScalperConfig.java:117-118` (`DELTA_LO 0.6`/`DELTA_HI 0.7` + per-index `PREMIUM`).
   Status PARTIAL unchanged (live-rail vs backtest-ATM-only split still holds).
2. *High/low high-prob variant* row — the doc-comment cite was `GapState.java:14-15`, but the phrase "is a
   stricter superset and is not used in v1" sits on L13 (L14-15 is the 3-point significance sentence).
   Corrected to `GapState.java:12-13`. Status NONE unchanged.

**Convergence:** stable. No genuinely-still-missing doc rule. The §3.4 L634 "runaway gaps may never fill —
do not force a fill trade" caveat is honoured by construction (the gate BLOCKs while a gap is unfilled and
the counter-trend gap-fill scalp is the deferred manual play of Row 28), so it needs no new row. No status
re-grade was triggered by citation validation. Both v2 corrections (`bias60m` hard-bias, 11-1/15:30 live
time blocks) re-verified against the live code and stand.
