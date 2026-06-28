package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.Map;

/**
 * The Siva §0B universal pre-flight gates as pure functions (master plan §12.1). Each returns a
 * {@link GateOutcome} so the reason can ride the signal side-channel. These are the OI/macro and
 * time/volume rails the YAML gate grammar cannot express; the chart-only alignment also lives here so
 * a scorer can read a uniform verdict. All thresholds are the verified §0B values (see
 * {@code docs/strategy-sources.md}); tuning rides DB rows, not these constants.
 */
public final class ScalperGates {

  private ScalperGates() {}

  static final LocalTime NO_TRADE_BEFORE = LocalTime.of(9, 45);
  static final LocalTime MIDDAY_BLOCK_FROM = LocalTime.of(11, 0);
  static final LocalTime MIDDAY_BLOCK_TO = LocalTime.of(13, 0);
  static final LocalTime NO_FRESH_ENTRY_AFTER = LocalTime.of(15, 30);
  // W4 (tag s24-trade-window, S24 Shared-S2): the explicit 09:45-14:30 window — no midday block, hard
  // 14:30 cap. The 09:45 floor reuses NO_TRADE_BEFORE; this is the upper bound when armed.
  static final LocalTime S24_WINDOW_TO = LocalTime.of(14, 30);
  // W4 (tag gap-size-side-gate, S24 #9 "300-400 gap-down -> no-put"): a gap-down at/above this many
  // index points suppresses the PE (put-buy) side. Index-absolute; default-OFF until armed.
  static final BigDecimal GAP_SIDE_SUPPRESS_PTS = new BigDecimal("300");

  // Volume-candle floors (§0B): NIFTY 125k; BANKNIFTY / SENSEX / other F&O indices 50k.
  private static final BigDecimal NIFTY_VOL = new BigDecimal("125000");
  private static final BigDecimal INDEX_VOL = new BigDecimal("50000");
  private static final Map<String, BigDecimal> VOL_FLOOR = Map.of("NIFTY 50", NIFTY_VOL);

  // W4 (tag overbought-defer, S24 §3.1 "RSI>85 -> defer"): the RSI exhaustion DEFER caps — a CE buy
  // stands aside while overbought (>=85), a PE buy while oversold (<=15). The cool-to-70-80 +
  // pullback-re-entry half is live trade-management (cross-bar state), not a point-in-time gate.
  private static final BigDecimal OVERBOUGHT_CAP = new BigDecimal("85");
  private static final BigDecimal OVERSOLD_FLOOR = new BigDecimal("15");

  // W4 PARAM #5 (S24 §3.10 "indicators far from candles = avoid"): the overextension band — block when
  // the NEAREST indicator is farther than this fraction of the close. The source gives NO number; 1.5%
  // is a deliberately wide v1 default that rarely fires on an index future (forward-paper-tunable). Tag
  // default-OFF, so this constant only bites for an armed strategy. Package-visible for the seam.
  static final BigDecimal INDICATOR_DISTANCE_MAX_PCT = new BigDecimal("0.015");

  // E2 M3 (tag oi-divergence-magnitude, Trending-OI #5 "lines immediately diverge ~20-30% / >=50%
  // conviction"): the PE−CE OI gap must be at least this % of total OI, AND a corroborating price
  // impulse this %. Doc-sourced numbers; DB-promotable later. Default-OFF until armed.
  static final BigDecimal OI_DIVERGENCE_MIN_PCT = new BigDecimal("20");
  static final BigDecimal PRICE_IMPULSE_MIN_PCT = new BigDecimal("50");

  // E4 (tag iv-buyer-cap, IV-interpretation "IV>40 -> sellers' market, don't buy"): the side's 6-strike
  // average IV above which a long-premium BUY is too rich (theta bleed > expected move). ceIvAvg6 /
  // peIvAvg6 are 0..1 fractions (0.40 = "40 IV"). A pure risk veto — NOT one of the 18 scorer dots.
  static final BigDecimal IV_BUYER_CAP = new BigDecimal("0.40");

  // E3 (tag fii-bias, §4.6 "the FII Long/Short ratio gates direction"): the FII long % of (long+short)
  // index-future positions; 50 is the net-flat pivot — above = net long (bullish), below = net short.
  static final BigDecimal FII_NEUTRAL_PCT = new BigDecimal("50");

  /** ≥09:45 (ideal 09:15–10:00), block the 11:00–13:00 sideways window, no fresh entry after 15:30. */
  public static GateOutcome timeWindow(LocalTime ist) {
    if (ist.isBefore(NO_TRADE_BEFORE)) {
      return GateOutcome.fail(null, "before 09:45 open-noise window");
    }
    if (!ist.isBefore(MIDDAY_BLOCK_FROM) && ist.isBefore(MIDDAY_BLOCK_TO)) {
      return GateOutcome.fail(null, "11:00-13:00 sideways block");
    }
    if (!ist.isBefore(NO_FRESH_ENTRY_AFTER)) {
      return GateOutcome.fail(null, "no fresh entry after 15:30");
    }
    return GateOutcome.pass(null, "within scalp window");
  }

  /**
   * #9 (section 3.9) Morning Trade window-aware overload: FAIL before {@code from} and at/after {@code
   * to}, using the strategy's own opening-tick bounds instead of the default 09:45 floor. The default's
   * 11:00-13:00 midday block is intentionally NOT applied here — an opening-tick window (e.g.
   * 09:15-09:30) never reaches it, so the extra clause would be dead. Only the opening-tick path uses
   * this; the 4 core strategies keep the no-arg {@link #timeWindow(LocalTime)} unchanged.
   */
  public static GateOutcome timeWindow(LocalTime ist, LocalTime from, LocalTime to) {
    if (ist.isBefore(from)) {
      return GateOutcome.fail(null, "before " + from + " opening-tick window");
    }
    if (!ist.isBefore(to)) {
      return GateOutcome.fail(null, "at/after " + to + " opening-tick window");
    }
    return GateOutcome.pass(null, "within opening-tick window");
  }

  /** Bar volume ≥ the underlying's floor (NIFTY 125k / other indices 50k). */
  public static GateOutcome volume(String underlying, BigDecimal volume) {
    BigDecimal floor = VOL_FLOOR.getOrDefault(underlying, INDEX_VOL);
    boolean ok = volume != null && volume.compareTo(floor) >= 0;
    return new GateOutcome(ok, volume, (ok ? "volume >= " : "volume < ") + floor.toPlainString());
  }

  /**
   * RSI(3m,14): 40–60 is NO-TRADE; CE trades 60–80, PE trades 20–40 (exhaustion caps at 80/20).
   * These follow §4.2 "Indicator Set &amp; Exact Settings" (the doc's designated single source of
   * thresholds: no-trade 40–60, CE &gt;60, PE &lt;40). §3.10/§6.10 render the band as "buy 50–75 /
   * no-trade 40–50", but a 50–60 CE floor would collide with the 40–60 no-trade zone, so §4.2 governs.
   */
  public static GateOutcome rsiBand(BigDecimal rsi, OptionType side) {
    if (rsi == null) {
      return GateOutcome.fail(null, "rsi unavailable");
    }
    double v = rsi.doubleValue();
    boolean ok = side == OptionType.CE ? (v > 60.0 && v < 80.0) : (v > 20.0 && v < 40.0);
    String want = side == OptionType.CE ? "CE wants 60-80" : "PE wants 20-40";
    return new GateOutcome(ok, rsi, ok ? want + " ok" : want + " (40-60 no-trade / exhaustion)");
  }

  /**
   * #2 (section 3.2) Open=High relaxed RSI gate: the source asks only for "RSI &gt;50" on the
   * open-high path (a directional-momentum confirmation), NOT the {@link #rsiBand} 60-80 / 20-40 band
   * the other strategies use. PASS when the RSI is strictly above {@code floor}; a null RSI FAILS
   * (the data is required, like the band). Only #2 uses this; the shared band is untouched.
   */
  public static GateOutcome rsiAbove(BigDecimal rsi, BigDecimal floor) {
    if (rsi == null) {
      return GateOutcome.fail(null, "rsi unavailable");
    }
    boolean ok = rsi.compareTo(floor) > 0;
    return new GateOutcome(
        ok, rsi, "rsi " + rsi.toPlainString() + (ok ? " > " : " <= ") + floor.toPlainString());
  }

  /**
   * The S24-ratified RSI band (tag {@code rsi-s24-bands}, owner rulings U1/U2/U3): CE trades the
   * 50–75 buy band, PE the 25–40 sell band, with 40–50 as the no-trade complement. This is the
   * additive sibling of {@link #rsiBand} — the legacy 60–80 / 20–40 band stays byte-identical for
   * every strategy that does NOT carry the tag, so no golden moves; only an armed strategy routes
   * here (the same threshold-swap shape #2 open-high-low uses with {@link #rsiAbove}). A null RSI
   * FAILS (the data is required). The HeroZero exhaustion caps (80/20) stay their own distinct path.
   */
  public static GateOutcome rsiS24Band(BigDecimal rsi, OptionType side) {
    if (rsi == null) {
      return GateOutcome.fail(null, "rsi unavailable");
    }
    double v = rsi.doubleValue();
    boolean ok = side == OptionType.CE ? (v > 50.0 && v < 75.0) : (v > 25.0 && v < 40.0);
    String want = side == OptionType.CE ? "CE wants 50-75" : "PE wants 25-40";
    return new GateOutcome(ok, rsi, ok ? want + " ok" : want + " (40-50 no-trade)");
  }

  /** Bull (CE): PSAR, VWMA, ST and VWAP all below price; bear (PE): all above. */
  public static GateOutcome indicatorAlignment(Chart c, OptionType side) {
    boolean ok;
    if (side == OptionType.CE) {
      ok =
          gt(c.close(), c.vwap())
              && gt(c.close(), c.vwma20())
              && gt(c.close(), c.psar())
              && c.supertrendDir() > 0;
    } else {
      ok =
          gt(c.vwap(), c.close())
              && gt(c.vwma20(), c.close())
              && gt(c.psar(), c.close())
              && c.supertrendDir() < 0;
    }
    return new GateOutcome(ok, c.close(), ok ? "indicators aligned" : "indicators not aligned");
  }

  /** Futures OI quadrant: CE needs LB/SC (bullish), PE needs SB/LU (bearish). */
  public static GateOutcome oiQuadrant(Oi oi, OptionType side) {
    boolean ok = side == OptionType.CE ? oi.futures().bullish() : oi.futures().bearish();
    return new GateOutcome(
        ok, oi.sentimentPct(), "futures " + oi.futures() + (ok ? " supports " : " opposes ") + side);
  }

  /** Adv > 32 favours CE; Dec > 32 favours PE. */
  public static GateOutcome breadth(Macro m, OptionType side) {
    int count = side == OptionType.CE ? m.advances() : m.declines();
    boolean ok = count > 32;
    String label = side == OptionType.CE ? "advances " : "declines ";
    return new GateOutcome(ok, BigDecimal.valueOf(count), label + count + (ok ? " > 32" : " <= 32"));
  }

  /** VIX falling favours CE; rising favours PE (unknown direction never blocks). */
  public static GateOutcome vix(Macro m, OptionType side) {
    if (m.vixRising() == null) {
      return GateOutcome.pass(m.vixLevel(), "vix direction unknown");
    }
    boolean ok = side == OptionType.CE ? !m.vixRising() : m.vixRising();
    String dir = m.vixRising() ? "rising" : "falling";
    return new GateOutcome(ok, m.vixLevel(), "vix " + dir + (ok ? " supports " : " opposes ") + side);
  }

  /**
   * #5 (T2.1): the trending-OI call-put delta-imbalance HARD pre-gate. PASS when the imbalance %
   * (|peDelta-ceDelta|/max(|peDelta|,|ceDelta|)*100) is at/above {@code floorPct}; FAIL when it is
   * present and below. A {@code null} imbalance (data unavailable or the flat-OI caveat the producer
   * documents) DEGRADES to PASS — it never blocks, so a missing derivation can't gate out an entry.
   */
  public static GateOutcome callPutDeltaFilter(Oi oi, BigDecimal floorPct) {
    BigDecimal imbalance = oi.callPutDeltaImbalancePct();
    if (imbalance == null) {
      return GateOutcome.pass(null, "call-put dOI imbalance unavailable (degrade -> pass)");
    }
    boolean ok = imbalance.compareTo(floorPct) >= 0;
    return new GateOutcome(
        ok,
        imbalance,
        "call-put dOI imbalance " + imbalance.toPlainString() + (ok ? " >= " : " < ") + floorPct.toPlainString());
  }

  /**
   * E2 M1 (tag {@code oi-cross-required}, Trending-OI #5 defining trigger): a HARD requirement for a
   * COMPLETED fresh cross favouring the side — CE wants {@code peΔ>0 && ceΔ<0} (put writers building
   * support while call writers unwind), PE the mirror, AND {@code crossedThisWindow} (a real sign
   * transition, not a widening gap). Unlike the soft {@code trending_cross} dot, {@code gapWidening}
   * alone does NOT satisfy it. Null deltas FAIL (fail-closed) — a missing derivation cannot pass a
   * fresh-cross requirement, and a stalled (not-yet-flipped) cross reads {@code crossedThisWindow=false}
   * so it is rejected for free (P16 incomplete-cross). NEUTRAL on derived history → forward-paper gate.
   */
  public static GateOutcome oiCrossRequired(Oi oi, OptionType side) {
    boolean ce = side == OptionType.CE;
    boolean realCross =
        oi.crossedThisWindow()
            && oi.ceOiDelta() != null
            && oi.peOiDelta() != null
            && (ce
                ? oi.peOiDelta().signum() > 0 && oi.ceOiDelta().signum() < 0
                : oi.ceOiDelta().signum() > 0 && oi.peOiDelta().signum() < 0);
    return new GateOutcome(
        realCross,
        oi.callPutDeltaImbalancePct(),
        realCross ? "fresh OI cross favours " + side : "no completed OI cross for " + side);
  }

  /**
   * E2 M2 (tag {@code oi-slope-agree}, Trending-OI #5): the active-strike sentiment LEVEL and its SLOPE
   * must BOTH favour the side (a hard conjunction of the two independent soft sentiment dots) — CE wants
   * both {@code > 0}, PE both {@code < 0}. Null level/slope FAILS (fail-closed; the conjunction is
   * required). NEUTRAL on derived history → forward-paper gate.
   */
  public static GateOutcome oiSlopeAgree(Oi oi, OptionType side) {
    boolean ce = side == OptionType.CE;
    BigDecimal slope = oi.sentimentSlope();
    BigDecimal level = oi.sentimentPct();
    boolean ok =
        slope != null
            && level != null
            && (ce
                ? slope.signum() > 0 && level.signum() > 0
                : slope.signum() < 0 && level.signum() < 0);
    return new GateOutcome(ok, slope, ok ? "sentiment level+slope agree" : "level/slope disagree");
  }

  /**
   * E2 M3 (tag {@code oi-divergence-magnitude}, Trending-OI #5): the OI lines must DIVERGE by at least
   * {@code minPct} (the PE−CE gap as a % of total OI) AND a corroborating price impulse of at least
   * {@code priceMinPct} must confirm it. Stronger than the boolean trending-cross dot — it requires a
   * real magnitude, not just a sign flip. Null divergence/price FAILS (fail-closed) → NEUTRAL on derived
   * history, so it's a forward-paper gate.
   */
  public static GateOutcome oiDivergenceMagnitude(Oi oi, BigDecimal minPct, BigDecimal priceMinPct) {
    BigDecimal div = oi.oiDivergencePct();
    BigDecimal px = oi.spurtPricePct();
    boolean ok =
        div != null
            && div.compareTo(minPct) >= 0
            && px != null
            && px.abs().compareTo(priceMinPct) >= 0;
    return new GateOutcome(
        ok, div, ok ? "OI divergence + price impulse confirm" : "weak divergence/impulse");
  }

  /**
   * E2 M4 (tag {@code flat-oi-stand-aside}, the doc's flat-OI trap): when the chain's OI is flat the
   * producer returns a {@code null} call-put imbalance. This is the DELIBERATE INVERSE of the #5
   * {@link #callPutDeltaFilter} fail-open — here a null/flat imbalance must STAND ASIDE (block), the
   * operative intent that a directionless chain carries no edge; a present (non-flat) imbalance PASSES.
   * Keep mutually exclusive with {@code oi-cross-filter} on the same strategy (one fail-opens, this
   * stands aside).
   */
  public static GateOutcome flatOiStandAside(Oi oi) {
    BigDecimal imb = oi.callPutDeltaImbalancePct();
    boolean ok = imb != null;
    return new GateOutcome(ok, imb, ok ? "OI not flat" : "flat OI — stand aside");
  }

  /**
   * E2 M6 (tag {@code max-oi-sr-gate}): the entry must not trade INTO the dominant standing-OI wall on
   * its side — the strike with the largest CE OI is overhead resistance for a CE (block when {@code
   * spot >= ceWall}), the largest PE OI is support for a PE (block when {@code spot <= peWall}). The two
   * wall strikes are the {@code argmax(oi)} over the chain's per-strike ladder (the LARGEST STANDING OI,
   * NOT the biggest mover). A null wall or spot DEGRADES to pass — a missing ladder never blocks.
   */
  public static GateOutcome oiWallClear(
      BigDecimal ceWall, BigDecimal peWall, BigDecimal spot, OptionType side) {
    BigDecimal wall = side == OptionType.CE ? ceWall : peWall;
    if (wall == null || spot == null) {
      return GateOutcome.pass(spot, "no OI wall / spot (degrade -> pass)");
    }
    boolean ok = side == OptionType.CE ? spot.compareTo(wall) < 0 : spot.compareTo(wall) > 0;
    return new GateOutcome(ok, wall, ok ? "clear of OI wall " + wall : "into OI wall " + wall);
  }

  /**
   * E4 (tag {@code iv-buyer-cap}, IV-interpretation "IV&gt;40 → sellers' market, don't buy"): a
   * long-premium BUY is too rich when the traded side's 6-strike average IV exceeds {@code capFraction}
   * — block it (theta bleed outweighs the expected move). Reads {@code ceIvAvg6}/{@code peIvAvg6}
   * (already on Macro); a null side IV DEGRADES to pass (a risk veto never blocks on missing data). This
   * is NOT one of the scored dots — it's a standalone IV risk rail, so it does not double-count.
   */
  public static GateOutcome ivBuyerCap(Macro m, OptionType side, BigDecimal capFraction) {
    BigDecimal iv = side == OptionType.CE ? m.ceIvAvg6() : m.peIvAvg6();
    if (iv == null) {
      return GateOutcome.pass(null, "side IV unavailable (degrade -> pass)");
    }
    boolean ok = iv.compareTo(capFraction) <= 0;
    return new GateOutcome(
        ok, iv, ok ? "side IV <= cap (buyable)" : "side IV > cap (too rich, sellers' market)");
  }

  /**
   * E3 (tag {@code volume-pump}, §4.15.3 dark-green / dark-red attribution): the deploy candle must be a
   * REAL directional pump — it clears the §0B volume floor AND closes in the side's direction (CE wants
   * {@code close > open}, PE {@code close < open}). The scorer's separate {@code volume} dot stays
   * floor-only (unchanged) — this is an ADDITIONAL hard precondition, so the aggregate is byte-identical
   * when unarmed. A null open/close DEGRADES to pass. (Below-floor is also caught by the hard volume rail.)
   */
  public static GateOutcome volumePump(
      BigDecimal close, BigDecimal open, BigDecimal volume, String underlying, OptionType side) {
    if (close == null || open == null) {
      return GateOutcome.pass(close, "candle open/close unavailable (degrade -> pass)");
    }
    boolean floorCleared = volume(underlying, volume).pass();
    boolean directional =
        side == OptionType.CE ? close.compareTo(open) > 0 : close.compareTo(open) < 0;
    boolean ok = floorCleared && directional;
    return new GateOutcome(ok, volume, ok ? "volume pump confirms " + side : "no volume pump for " + side);
  }

  /**
   * E3 (tag {@code fii-bias}, §4.6 "the FII Long/Short ratio gates direction"): the FII index-future flow
   * must not oppose the side — CE needs the FII net long ({@code fiiLongPct >= 50}), PE net short
   * ({@code <= 50}). At exactly 50 (net flat) BOTH pass — a neutral read never blocks. A null read
   * DEGRADES to pass (the macro confirm is best-effort, like the other macro gates). Reads the existing
   * {@code Macro.fiiLongPct} (no new feed); the simple long%-vs-50 form of the §4.6 ratio.
   */
  public static GateOutcome fiiBias(Macro m, OptionType side) {
    BigDecimal longPct = m.fiiLongPct();
    if (longPct == null) {
      return GateOutcome.pass(null, "FII L/S unavailable (degrade -> pass)");
    }
    int cmp = longPct.compareTo(FII_NEUTRAL_PCT);
    boolean ok = side == OptionType.CE ? cmp >= 0 : cmp <= 0;
    return new GateOutcome(
        ok, longPct, ok ? "FII flow favours " + side : "FII flow opposes " + side);
  }

  /**
   * E3 (tag {@code constituent-gate}, §4.6 "the heavyweights must support the direction"): the index
   * constituents' net weighted push ({@code Macro.constituentBias}, the {@code /equity/index-contribution}
   * {@code indexChangePct}) must not oppose the side — CE needs it positive (heavyweights pushing up), PE
   * negative. A null or exactly-zero (flat) push DEGRADES to pass (a neutral read never blocks).
   */
  public static GateOutcome constituent(Macro m, OptionType side) {
    BigDecimal bias = m.constituentBias();
    if (bias == null || bias.signum() == 0) {
      return GateOutcome.pass(bias, "constituent push neutral/unavailable (degrade -> pass)");
    }
    boolean ok = side == OptionType.CE ? bias.signum() > 0 : bias.signum() < 0;
    return new GateOutcome(
        ok, bias, ok ? "heavyweights push favours " + side : "heavyweights push opposes " + side);
  }

  /** Futures basis: future > spot (premium) is bullish → CE; future < spot (discount) bearish → PE. */
  public static GateOutcome futuresBasis(Oi oi, OptionType side) {
    BigDecimal basis = oi.futuresBasis();
    if (basis == null) {
      return GateOutcome.pass(null, "basis unavailable");
    }
    boolean ok = side == OptionType.CE ? basis.signum() > 0 : basis.signum() < 0;
    return new GateOutcome(ok, basis, "basis " + basis.toPlainString() + (ok ? " supports " : " opposes ") + side);
  }

  /**
   * W4 PARAM #5 (tag {@code indicator-distance-veto}, S24 §3.10 "indicators far from candles = avoid"):
   * price is OVEREXTENDED when it has run away from the whole {@code vwap / vwma20 / psar} cluster, so a
   * mean-reversion snap is likely. PASS (ok to trade) when the NEAREST available indicator sits within
   * {@code maxPct} (a fraction of close) of the close; FAIL (block) when every available indicator is
   * farther. A null/zero close or a fully-absent cluster DEGRADES to pass — missing data never blocks.
   * (SuperTrend is excluded: its indicator outputs only the +-1 direction, no price level.)
   */
  public static GateOutcome indicatorDistance(Chart c, BigDecimal maxPct) {
    BigDecimal close = c.close();
    if (close == null || close.signum() == 0) {
      return GateOutcome.pass(null, "close unavailable (degrade -> pass)");
    }
    BigDecimal abs = close.abs();
    BigDecimal nearest = null;
    for (BigDecimal ind : new BigDecimal[] {c.vwap(), c.vwma20(), c.psar()}) {
      if (ind == null) {
        continue;
      }
      BigDecimal distPct = ind.subtract(close).abs().divide(abs, 6, RoundingMode.HALF_UP);
      if (nearest == null || distPct.compareTo(nearest) < 0) {
        nearest = distPct;
      }
    }
    if (nearest == null) {
      return GateOutcome.pass(null, "no indicator cluster (degrade -> pass)");
    }
    boolean ok = nearest.compareTo(maxPct) <= 0;
    return new GateOutcome(
        ok,
        nearest,
        "nearest-indicator dist " + nearest.toPlainString() + (ok ? " <= " : " > ") + maxPct.toPlainString());
  }

  /**
   * W4 PARAM #10 (tag {@code divergence-vol-gate}, S24 Day-21 Trend-Change counter-trend confirm): a
   * counter-trend divergence entry needs a heavyweight ~125k bar REGARDLESS of the index's own §0B floor
   * (NIFTY 125k / others 50k) — so it is a stricter sibling of {@link #volume}. PASS at/above 125k; a
   * null volume FAILS (the confirm is required, unlike the degrade-to-pass OI gates).
   */
  public static GateOutcome divergenceVolume(BigDecimal volume) {
    boolean ok = volume != null && volume.compareTo(NIFTY_VOL) >= 0;
    return new GateOutcome(
        ok,
        volume,
        (ok ? "volume >= " : "volume < ") + NIFTY_VOL.toPlainString() + " (divergence confirm)");
  }

  /**
   * W4 (tag {@code overbought-defer}, S24 §3.1 "RSI&gt;85 -&gt; defer"): PASS (ok) unless the tape is at
   * the exhaustion extreme for the side — a CE buy blocks at {@code rsi >= 85}, a PE buy at {@code rsi <=
   * 15}. A null RSI DEGRADES to pass (a veto never blocks on missing data; the distinct hard RSI rail
   * already requires a non-null RSI). The cool-to-70-80 + pullback-re-entry half is live trade-management.
   */
  public static GateOutcome overboughtDefer(BigDecimal rsi, OptionType side) {
    if (rsi == null) {
      return GateOutcome.pass(null, "rsi unavailable (degrade -> pass)");
    }
    boolean extreme =
        side == OptionType.CE
            ? rsi.compareTo(OVERBOUGHT_CAP) >= 0
            : rsi.compareTo(OVERSOLD_FLOOR) <= 0;
    String want = side == OptionType.CE ? "CE defers >= 85" : "PE defers <= 15";
    return new GateOutcome(!extreme, rsi, extreme ? want + " (exhaustion defer)" : want + " ok");
  }

  /**
   * W4 (tag {@code directional-change-gate}, S24 Day-20 "directional-change precondition"): require the OI
   * to have flipped direction this window before entering — PASS only when the PE-CE tilt crossed within
   * the window ({@code oi.crossedThisWindow()}). A short/absent series reads {@code false} and BLOCKS (the
   * precondition is unmet) — the intended "only enter on a confirmed OI directional change".
   */
  public static GateOutcome directionalChange(Oi oi) {
    boolean ok = oi != null && oi.crossedThisWindow();
    return new GateOutcome(
        ok,
        oi == null ? null : oi.sentimentPct(),
        ok ? "OI tilt crossed this window" : "no OI directional change this window");
  }

  /**
   * W4 (tag {@code gap-size-side-gate}, S24 #9 Morning Trade "300-400 gap-down -&gt; no-put"): a large
   * gap-DOWN (a present, bearish, {@code >= suppressPts}-point session gap) suppresses the PE (put-buy)
   * side — the down-move is over-extended / mean-reverting, so a fresh PUT is stood aside. PASS for the
   * CE side, for the unarmed/no-gap case, and for a small or up gap; a {@code null} gap PASSES. (The
   * "30-40 gap-up short-once" cap is per-session state, deferred to live management.)
   */
  public static GateOutcome gapSizeSide(GapState.Gap gap, OptionType side, BigDecimal suppressPts) {
    boolean suppressed =
        side == OptionType.PE
            && gap != null
            && gap.present()
            && !gap.bullish()
            && gap.sizePoints() != null
            && gap.sizePoints().compareTo(suppressPts) >= 0;
    return new GateOutcome(
        !suppressed,
        gap == null ? null : gap.sizePoints(),
        suppressed ? "large gap-down suppresses PE (no-put)" : "gap-size side ok");
  }

  private static boolean gt(BigDecimal a, BigDecimal b) {
    return a != null && b != null && a.compareTo(b) > 0;
  }
}
