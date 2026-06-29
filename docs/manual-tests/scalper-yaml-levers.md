# Manual test — scalper YAML / config levers (PRs #357-361)

Five parity-safe levers shipped 2026-06-29 that move scalper behaviour into YAML/config so future
tuning is config-only. **Every lever is default-OFF / default-preserving** — an existing strategy or
backtest is byte-identical until the lever is explicitly set. This guide is the per-lever how-to +
how-to-verify + the honest caveat.

> Drive the API from PowerShell (`Invoke-WebRequest -UseBasicParsing`) or the bash curl flow in the
> `run-artha-yantra` skill. Owner password is the `.env` owner hash; in-container DB is `artha`
> (live) / `artha_mock` (mock).

---

## 1. `scalper.params` — armable gate thresholds (#357)

Per-strategy override of the **armable** confluence-gate thresholds (all live-only gates, default-OFF
tags). Tuning an armed gate is now YAML-only — no Java change, no gate-logic redeploy.

```yaml
# in a scalper strategy document (top-level, sibling of universe/backtest)
scalper:
  params:
    vwap_distance_max_frac: 0.006     # vwap-distance pullback band (default 0.004)
    gap_suppress_pts: 400             # gap-size-side-gate (default 300)
    indicator_distance_max_pct: 0.02  # indicator-distance-veto (default 0.015)
    oi_divergence_min_pct: 30         # oi-divergence-magnitude (default 20)
    price_impulse_min_pct: 50         # oi-divergence-magnitude (default 50)
    iv_buyer_cap: 0.45                # iv-buyer-cap, 0..1 IV fraction (default 0.40)
```

**Verify:** save/publish a strategy carrying the block → it validates (the schema accepts it). Unit
proof: `ScalperConfigTest.scalperParamsBlockOverridesReachTheConfig`. An absent field falls back to
the `ScalperGates` constant (`scalperParamsDefaultToTheGateConstantsWhenAbsent`).

**Caveat:** only matters when the matching tag is **armed** on the strategy (e.g. `vwap_distance_max_frac`
does nothing unless `vwap-distance` is in `tags`). Live-only — never runs in backtest replay.

---

## 2. `scalper.params` — §0B hard rails (#361)

The always-applied §0B rails join the same block. They stay **always-ON** (an unset field keeps the
Siva default — you can't silently ship a strategy with no volume/time guard) but are now value-tunable,
and the 11:00-13:00 midday block has a clean on/off toggle.

```yaml
scalper:
  params:
    no_trade_before: "09:30"      # §0B time floor (default 09:45)
    no_fresh_entry_after: "15:25" # §0B time cap (default 15:30)
    midday_block: false           # trade THROUGH 11:00-13:00 (default true = blocked)
    volume_floor: 80000           # bar-volume floor (default = per-index map, NIFTY 125k / others 50k)
```

**Verify:** `ScalperGatesTest.timeWindowRailOverridesHonourBoundsAndTheMiddayToggle` +
`volumeFloorOverrideReplacesThePerIndexDefault`. Live behaviour: a strategy with `midday_block: false`
emits signals between 11:00-13:00 that the default rail suppresses.

**Caveat:** the `volume_floor` override is honoured by the HARD volume rail (the blocker). The SOFT
`volume` confluence dot in `ConnectTheDotsScorer` + the `TwoCandleGate` volume check still use the
per-index default floor — so on a `connect-the-dots` / `two-candle` strategy that LOWERS its floor, the
soft dot may still read "low volume" on a sub-default bar. Raising the floor is fully honoured (the rail
dominates). The midday window EDGES (11:00-13:00) are fixed constants — only the on/off is exposed.

---

## 3. `backtest.relax_session` — full-session backtest entries (#358)

The one safe lever for "an armed scalper produces ~0 backtest trades, let me at least exercise the entry
path." Disables the intraday session CLOCK rail (window / square_off / expiry-day entry restriction) so
the strategy fires its signal-driven entries across the whole series; the square_off force-close is also
lifted (positions exit on their own `exit_rules`).

```yaml
backtest:
  relax_session: true   # default false
```

**Verify:** run the SAME backtest with the flag off then on; the on-run produces entries across the full
session. Unit proof: `SessionGateTest.relaxSessionDisablesTheClockRailEntirely`. Backtest goldens stay
byte-identical with the flag off (`GoldenDeterminismTest` / `BacktestParityTest`).

**Caveat — read this.** It does NOT relax the strategy's chart SIGNAL or indicator warmup (relaxing those
would MANUFACTURE fake trades). So if a scalper still produces few trades with `relax_session: true`, the
blocker is its own selective signal on the derived/synthetic history, NOT a gate — that is a
data-fidelity artifact. **This is a functional-smoke lever; judge OI/IV-led scalpers on the live run, not
the backtest.** Verified architecture fact: the live `ScalperConfluenceGate` is firewalled OUT of replay
— there is no live OI/macro/IV gate to "skip" in backtest.

---

## 4. `backtest.oi_confluence_gate.iv` — deterministic IV-confluence backtest filter (#360)

The IV sibling of the existing opt-in OI-confluence backtest gate. Drops a long/CE leg entered when the
active-strike IV factor reads Bearish (short/PE when Bullish), reusing the same per-session
Connecting-Dots fetch (zero extra calls). Independent of `enabled` (the OI dimension).

```yaml
backtest:
  oi_confluence_gate:
    enabled: false   # the OI-trend filter (existing)
    iv: true         # NEW: the IV-confluence filter
    interval: "5m"
```

**Verify:** `OptionsPremiumReplayTest.ivConfluenceGateDropsLegsAgainstTheIvFactorIndependentOfOi`.

**Caveat:** the IV factor degrades to NEUTRAL on derived history (no captured OI/IV before ~2026-06-15),
so on a historical backtest it rarely drops anything — it is a FORWARD-paper discriminator. Default-off →
premium goldens byte-identical.

---

## 5. `artha.options.derived-iv-band` — full-chain historical IV (#359, app config)

App-level config (not per-strategy YAML — it is a market-data service property). The candle-derived
historical chain back-solves Black-76 IV from the candle premium; by default only the ATM band (3 strikes
either side). Set `<= 0` to solve the FULL chain so every strike with a valid premium carries a per-strike
historical IV (the historical OI pages then show IV across the whole ladder).

```properties
# market-data-service application.yml / env
artha.options.derived-iv-band=0   # default 3 (ATM band); <=0 = full chain
```

**Verify:** `CandleDerivedChainReaderIntegrationTest.fullChainModeSolvesPerStrikeIvBeyondTheAtmBand`
(a narrow band leaves a far strike null; the full-chain reader solves it).

**Caveat:** full-chain is one Newton-Raphson solve per strike per bucket — heavier on a wide historical
read, hence opt-in. Feeds the historical OI analytics PAGES; it does NOT feed a backtest gate (the live
E4 IV gates are firewalled from replay regardless of derived IV).

---

## What these levers do NOT do (honest scope)

- They do not make a muted-on-history backtest a strategy verdict. Armed OI/IV/Dow gates read ~0
  historical trades by design (derived-history fidelity) — the **live run is the only validation**.
- The full "replay the live `ScalperConfluenceGate` in backtest" was deliberately NOT built (a large
  parity-firewall rewrite, muted outcome, and it ADDS gating against the get-trades goal). #360 is the
  bounded realization.
- `atr-stop` stays armed nowhere — the operative doc has no ATR (Siva uses structural / fixed-points /
  premium-% stops).
