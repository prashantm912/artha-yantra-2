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

  // E5 §3.6 S21(f)/S24(a) cool-off pullback re-entry (tag rsi-cooloff): if the PRIOR bar's RSI was HOT
  // (CE overbought >= 80 / PE oversold <= 20, the 100-complement) the entry must WAIT for a cooled
  // pullback candle — a red bar back under 75 for a CE (a green bar back over 25 for a PE). The static
  // overbought-defer (>=85 stand-aside) is the point-in-time half; this is the cross-bar pullback half.
  private static final BigDecimal RSI_HOT_LEVEL = new BigDecimal("80");
  private static final BigDecimal RSI_COOL_LEVEL = new BigDecimal("75");
  private static final BigDecimal RSI_MAX = new BigDecimal("100");

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

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  // E6 §4.14.6 PSAR-distance durability: the SuperTrend/PSAR sitting CLOSE to price = a short-lived
  // (whipsaw-prone) trend; a WIDE gap = a durable one. The minimum |close-PSAR|/close gap a durable
  // entry needs. The deck gives no number; 0.05% is a deliberately wide v1 default (rarely fires on an
  // index future) — forward-paper-tunable, package-visible for a future DB promotion.
  static final BigDecimal PSAR_DISTANCE_MIN_PCT = new BigDecimal("0.05");

  // E8 §2.2 r8 / §3.4 (tag vwap-distance): an entry must be a PULLBACK near VWAP, not an extended chase
  // — the max |close-vwap|/close FRACTION an armed entry may sit at. The deck gives no number; 0.004
  // (0.4% of price) is a cautious v1 placeholder, optimizer/forward-paper-tunable BEFORE arming. The
  // min clause (the Hero-Zero "VWAP-pin low-range sit-out") is OFF by default (ZERO) — only a variant
  // that wants the too-close skip sets it. Default-OFF tag, so both are dormant until armed. NOTE the
  // FRACTION scale (0.004 = 0.4%), unlike the percent-scale OI knobs (50 = 50%).
  static final BigDecimal VWAP_DISTANCE_MAX_FRAC = new BigDecimal("0.004");
  static final BigDecimal VWAP_DISTANCE_MIN_FRAC = BigDecimal.ZERO;

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

  /**
   * E8 §3.5 time-of-day PREFERENCE (tag {@code time-of-day-preference}): the high-probability intraday
   * window — PASS inside [{@code from}, {@code to}), FAIL (skip the entry) outside it. The Siva "best
   * 10:00-11:30, ease off after ~13:30" preference rendered as an opt-in HARD skip, layered ON TOP of
   * the §0B hard {@link #timeWindow} rails. {@code to} is EXCLUSIVE — an entry exactly at the cutoff is
   * already past the preferred window.
   */
  public static GateOutcome timeOfDayPreference(LocalTime ist, LocalTime from, LocalTime to) {
    boolean ok = !ist.isBefore(from) && ist.isBefore(to);
    return new GateOutcome(
        ok,
        null,
        (ok ? "within" : "outside") + " preferred window " + from + "-" + to);
  }

  /** Bar volume ≥ the underlying's floor (NIFTY 125k / other indices 50k). */
  public static GateOutcome volume(String underlying, BigDecimal volume) {
    BigDecimal floor = VOL_FLOOR.getOrDefault(underlying, INDEX_VOL);
    boolean ok = volume != null && volume.compareTo(floor) >= 0;
    return new GateOutcome(ok, volume, (ok ? "volume >= " : "volume < ") + floor.toPlainString());
  }

  /**
   * E6 §3.x rising-volume confirm (tag {@code rising-volume}): a real move comes WITH expanding
   * participation — the deploy bar's volume must exceed the prior bar's. The seam supplies both volumes
   * and only calls this when a prior bar exists, so a series-edge bar is never blocked.
   */
  public static GateOutcome risingVolume(long curVol, long prevVol) {
    boolean ok = curVol > prevVol;
    return new GateOutcome(
        ok,
        BigDecimal.valueOf(curVol),
        ok ? "volume rising (" + curVol + " > " + prevVol + ")"
            : "volume not rising (" + curVol + " <= " + prevVol + ")");
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

  /**
   * E5 §3.6 S21(f)/S24(a) cool-off pullback re-entry (tag {@code rsi-cooloff}). When the PRIOR bar's RSI
   * was HOT (CE overbought {@code >= 80} / PE oversold {@code <= 20}) the entry must wait for a COOLED
   * PULLBACK candle this bar — a red bar (the seam-supplied {@code pullbackCandle}) whose RSI has cooled
   * back under {@code 75} for a CE (a green bar back over {@code 25} for a PE). When the prior bar was
   * NOT hot the gate is INERT (PASS) — it never blocks a normal entry, only delays the ones chasing an
   * exhaustion spike. A null RSI on either bar FAILS (the data is required when armed). The seam computes
   * {@code pullbackCandle} (CE = red / PE = green) from the deploy bar, keeping this function series-free.
   */
  public static GateOutcome rsiCoolOff(
      BigDecimal prevRsi, BigDecimal rsi, boolean pullbackCandle, OptionType side) {
    if (prevRsi == null || rsi == null) {
      return GateOutcome.fail(rsi, "cool-off rsi unavailable");
    }
    boolean ce = side == OptionType.CE;
    boolean priorHot =
        ce
            ? prevRsi.compareTo(RSI_HOT_LEVEL) >= 0
            : prevRsi.compareTo(RSI_MAX.subtract(RSI_HOT_LEVEL)) <= 0;
    if (!priorHot) {
      return GateOutcome.pass(rsi, "prior bar not hot — cool-off inert");
    }
    boolean cooled =
        ce
            ? rsi.compareTo(RSI_COOL_LEVEL) <= 0
            : rsi.compareTo(RSI_MAX.subtract(RSI_COOL_LEVEL)) >= 0;
    boolean ok = pullbackCandle && cooled;
    return new GateOutcome(
        ok, rsi, ok ? "cooled pullback after a hot bar" : "still hot — wait for a cooled pullback candle");
  }

  /**
   * E6 §3.3 Market-Movers intraday move gate (tag {@code pct-price-move}): the index must have moved at
   * least {@code floorPct}% in the SIDE's direction since the SESSION OPEN — CE wants {@code >= +floor},
   * PE {@code <= -floor} (a "mover" scalp needs a real move, not chop). Series-free: the seam supplies the
   * deploy {@code close} + the {@code sessionOpen}. A null operand (or a zero open) PASSES — the gate
   * never blocks on missing data (the seam already guards a null future).
   */
  public static GateOutcome pctPriceMove(
      BigDecimal close, BigDecimal sessionOpen, BigDecimal floorPct, OptionType side) {
    if (close == null || sessionOpen == null || sessionOpen.signum() == 0) {
      return GateOutcome.pass(null, "session move unavailable (degrade -> pass)");
    }
    BigDecimal movePct =
        close.subtract(sessionOpen).multiply(HUNDRED).divide(sessionOpen, 4, RoundingMode.HALF_UP);
    boolean ok =
        side == OptionType.CE
            ? movePct.compareTo(floorPct) >= 0
            : movePct.compareTo(floorPct.negate()) <= 0;
    String want = side == OptionType.CE ? "move >= +" + floorPct + "%" : "move <= -" + floorPct + "%";
    return new GateOutcome(ok, movePct, ok ? want + " ok" : want + " (move too small)");
  }

  /**
   * E5 §3.2/§4.2 higher-timeframe RSI overbought/oversold cap (tags {@code rsi-5m-cap} / {@code
   * rsi-daily-cap}). CE must be BELOW {@code ceCap} (not overbought on the higher TF); PE must be ABOVE
   * {@code peFloor} (not oversold). A null RSI FAILS (fail-closed) — the higher-TF series is warmed
   * (§3.2a), so a null read means the cap data is genuinely unavailable and the strategy that OPTED IN
   * waits rather than chasing blind.
   */
  public static GateOutcome rsiHigherTfCap(
      BigDecimal rsi, OptionType side, BigDecimal ceCap, BigDecimal peFloor) {
    if (rsi == null) {
      return GateOutcome.fail(null, "higher-tf rsi unavailable");
    }
    boolean ok =
        side == OptionType.CE ? rsi.compareTo(ceCap) < 0 : rsi.compareTo(peFloor) > 0;
    String want = side == OptionType.CE ? "CE rsi < " + ceCap : "PE rsi > " + peFloor;
    return new GateOutcome(ok, rsi, ok ? want + " ok" : want + " (overbought/oversold cap)");
  }

  /**
   * E6 §3.10/§4.14.6 15-minute SuperTrend confirmation (tag {@code supertrend-15m}): the higher-TF trend
   * must AGREE with the side — CE needs {@code dir15m > 0} (15m uptrend), PE {@code dir15m < 0}. An
   * UNKNOWN direction ({@code dir15m == 0}: the 15m series unwarmed / mid-flip) PASSES — the
   * {@code bias60m}/VIX fail-OPEN convention, so a missing higher-TF trend never blocks a confirmed 3m entry.
   */
  public static GateOutcome supertrend15mAlign(int dir15m, OptionType side) {
    boolean ok = dir15m == 0 || (side == OptionType.CE ? dir15m > 0 : dir15m < 0);
    String label = dir15m == 0 ? "15m trend unknown" : (dir15m > 0 ? "15m up" : "15m down");
    return new GateOutcome(ok, null, label + (ok ? " supports " : " opposes ") + side);
  }

  /**
   * E6 §4.14.6 PSAR-distance durability (tag {@code psar-durability}): a PSAR sitting too CLOSE to price
   * marks a short-lived trend (the stop is right under price → whipsaw); require the {@code |close-PSAR|}
   * gap to be at least {@link #PSAR_DISTANCE_MIN_PCT} of the close. A null close/PSAR PASSES (fail-OPEN —
   * a missing PSAR never blocks; durability is a refinement, not a hard data dependency). Side-agnostic:
   * the distance is the trend's durability regardless of CE/PE.
   */
  public static GateOutcome psarDurable(BigDecimal close, BigDecimal psar) {
    if (close == null || psar == null || close.signum() == 0) {
      return GateOutcome.pass(null, "psar distance unavailable (degrade -> pass)");
    }
    BigDecimal distPct =
        close.subtract(psar).abs().multiply(HUNDRED).divide(close.abs(), 4, RoundingMode.HALF_UP);
    boolean ok = distPct.compareTo(PSAR_DISTANCE_MIN_PCT) >= 0;
    return new GateOutcome(
        ok, distPct, "psar gap " + distPct.toPlainString() + (ok ? "% durable" : "% too tight"));
  }

  /**
   * E8 §2.2 r8 / §3.4 VWAP-distance pullback band (tag {@code vwap-distance}): an entry must sit NEAR
   * VWAP, not extended away from it — PASS when {@code |close-vwap|/close} is at/below {@code maxFrac}
   * (a pullback). When {@code minFrac > 0} a SECOND clause ALSO requires the distance be at/above it
   * (the Hero-Zero "VWAP-pin low-range sit-out": too CLOSE = premium-erosion chop). Side-agnostic (the
   * distance is symmetric). A null close/vwap or a non-positive close DEGRADES to pass (fail-open, like
   * the sibling {@link #indicatorDistance}/{@link #psarDurable} overextension vetoes) so missing data
   * never gates an entry. The fractions are PRICE fractions (0.004 = 0.4% of close).
   */
  public static GateOutcome vwapDistance(
      BigDecimal close, BigDecimal vwap, BigDecimal minFrac, BigDecimal maxFrac) {
    if (close == null || vwap == null || close.signum() <= 0) {
      return GateOutcome.pass(null, "vwap distance unavailable (degrade -> pass)");
    }
    BigDecimal frac = close.subtract(vwap).abs().divide(close.abs(), 6, RoundingMode.HALF_UP);
    boolean tooFar = maxFrac != null && frac.compareTo(maxFrac) > 0;
    boolean tooClose = minFrac != null && minFrac.signum() > 0 && frac.compareTo(minFrac) < 0;
    boolean ok = !tooFar && !tooClose;
    return new GateOutcome(
        ok,
        frac,
        "|close-vwap|/close " + frac.toPlainString()
            + (ok ? " within band" : tooFar ? " too far from VWAP" : " too close (VWAP pin)"));
  }

  /**
   * E8 §2.2 ATR stop: a volatility-scaled structural stop — {@code entry ∓ multiple × ATR} on the index
   * future (CE: support below, PE: resistance above). Always on the protective side by construction
   * ({@code multiple × atr} ≥ 0). null when the ATR is unavailable (too few bars) → the default
   * structural stop stands.
   */
  public static BigDecimal atrStop(
      BigDecimal entryClose, BigDecimal atr, BigDecimal multiple, OptionType side) {
    if (entryClose == null || atr == null || atr.signum() <= 0) {
      return null;
    }
    BigDecimal distance = atr.multiply(multiple);
    return side == OptionType.CE ? entryClose.subtract(distance) : entryClose.add(distance);
  }

  /**
   * §3.9 Morning Trade: the PRIOR-day VWAP as the morning structural stop — but only when it sits on
   * the PROTECTIVE side of the entry (a support BELOW a CE long / a resistance ABOVE a PE short, doc
   * L539/L541 "the first support on a fall"). Returns the stop level, or null when the VWAP is on the
   * wrong side or unavailable (→ the default structural stop stands).
   */
  public static BigDecimal priorDayVwapStop(BigDecimal priorVwap, BigDecimal entryClose, OptionType side) {
    if (priorVwap == null || entryClose == null) {
      return null;
    }
    boolean protective =
        side == OptionType.CE
            ? priorVwap.compareTo(entryClose) < 0 // support below the long entry
            : priorVwap.compareTo(entryClose) > 0; // resistance above the short entry
    return protective ? priorVwap : null;
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
   * E9 D4 OI-confluence-flip exit (tag {@code oi-confluence-exit}, Day-7 "a confluence read against your
   * position = exit"): TRUE when the held option side ({@code heldSide}) and the side the OI confluence
   * NOW strongly confirms ({@code currentSide}) are BOTH directional (CE/PE) and OPPOSITE — the read has
   * flipped against the open position. A neutral/missing current read (the caller passes a non-CE/PE
   * string) never exits, so a merely-weak confluence does not trip it — only a confirmed opposite side.
   */
  public static boolean confluenceFlippedAgainst(String heldSide, String currentSide) {
    return isDirectional(heldSide) && isDirectional(currentSide) && !heldSide.equals(currentSide);
  }

  private static boolean isDirectional(String side) {
    return "CE".equals(side) || "PE".equals(side);
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
   * {@code minPct} IN THE SIDE'S DIRECTION (the SIGNED PE−CE gap as a % of total OI — CE wants it
   * {@code >= +minPct} (PE-heavy, bullish), PE {@code <= -minPct} (CE-heavy, bearish)) AND a
   * corroborating price impulse of at least {@code priceMinPct} in the same direction (CE up, PE down).
   * Stronger than the boolean trending-cross dot — a real magnitude AND the right direction, not just a
   * sign flip. Null divergence/price FAILS (fail-closed) → NEUTRAL on derived history (forward-paper gate).
   */
  public static GateOutcome oiDivergenceMagnitude(
      Oi oi, BigDecimal minPct, BigDecimal priceMinPct, OptionType side) {
    BigDecimal div = oi.oiDivergencePct();
    BigDecimal px = oi.spurtPricePct();
    if (div == null || px == null) {
      return new GateOutcome(false, div, "missing divergence/price impulse");
    }
    boolean ok =
        side == OptionType.CE
            ? div.compareTo(minPct) >= 0 && px.compareTo(priceMinPct) >= 0
            : div.compareTo(minPct.negate()) <= 0 && px.compareTo(priceMinPct.negate()) <= 0;
    return new GateOutcome(
        ok, div,
        ok ? "OI divergence + price impulse confirm " + side : "weak/opposing divergence or impulse");
  }

  /**
   * E2 M7 (tag {@code oi-interval-and-60m-trend}, Trending-OI #5 §3.5 "5-15m analytics interval + a
   * 60-min broader-trend read"): the 60-minute OI build (the slower confirmation ABOVE the 5-minute
   * cross/slope) must AGREE with the side. {@code dir60m} is the sign of the 60m CE/PE OI delta-imbalance
   * — {@code > 0} (PE building faster = put-writing support = bullish) confirms a CE, {@code < 0} a PE.
   * Fail-OPEN on {@code 0}: an unknown / flat 60m trend degrades to pass (this is a broader-trend
   * CONFIRMATION, not a hard fresh-cross requirement like M1). NEUTRAL on derived history → live gate.
   */
  public static GateOutcome oi60mAgree(int dir60m, OptionType side) {
    if (dir60m == 0) {
      return GateOutcome.pass(null, "60m OI trend unknown/flat (degrade -> pass)");
    }
    boolean ok = (side == OptionType.CE) == (dir60m > 0);
    return new GateOutcome(
        ok, BigDecimal.valueOf(dir60m), "60m OI trend " + (ok ? "supports " : "opposes ") + side);
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
