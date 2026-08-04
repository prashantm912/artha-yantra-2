package in.arthayantra.strategysignal.scalper;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tier-1 OI-analytics thresholds (Phase-3.5 T2.1/T2.6/T2.7/T2.8) the confluence scorer and the #5
 * trending-OI pre-gate read. Held here (DB-/config-tunable), not as Java constants like
 * {@link ScalperGates}, because these are the freshly-derived OI/IV knobs the owner is expected to
 * tune as the index-option core is calibrated. The scorer stays pure: it receives a bound instance
 * rather than reading config itself.
 *
 * <p>The IV-pair levels are on the SAME scale as the producer's {@code ceIvAvg6}/{@code peIvAvg6}
 * (see {@code MarketOiClient.deriveIvPair}), which are 0..1 FRACTIONS (e.g. 0.14), so "10 IV points"
 * is {@code 0.10} and the "40/40 both-high" level is {@code 0.40} — NOT 10/40 on a percentage scale.
 */
@ConfigurationProperties(prefix = "artha.scalper.oi")
public record ScalperOiProps(
    BigDecimal crossFilterPct,
    BigDecimal drasticFloor,
    BigDecimal ivPairMinGap,
    BigDecimal ivBothHighFloor,
    BigDecimal spurtOiPct,
    BigDecimal spurtPricePct,
    BigDecimal openHighMinStrikes,
    BigDecimal openHighFallVolumeFloor,
    BigDecimal openHighMaxPrevCloseFallPct,
    BigDecimal openHighWindow,
    BigDecimal openHighRsiFloor,
    BigDecimal ivAbsBandLow,
    BigDecimal ivAbsBandHigh,
    BigDecimal pctPriceMoveFloor,
    BigDecimal rsi5mCeCap,
    BigDecimal rsi5mPeFloor,
    BigDecimal rsiDailyCeCap,
    BigDecimal rsiDailyPeFloor,
    BigDecimal rsiOversoldTrough,
    BigDecimal rsiRecoveryLevel,
    BigDecimal rsiRecoveryLookback,
    BigDecimal relativeVolumeMultiplier,
    BigDecimal relativeVolumeWindow,
    BigDecimal relativeVolumeMinBars,
    BigDecimal timeOfDayProfileSessions,
    BigDecimal vwapMinDistanceBps,
    BigDecimal dotCoverageFloor) {

  // T2.1: the #5 call-put delta-imbalance HARD pre-gate floor (>= 50% of the larger leg).
  private static final BigDecimal DEFAULT_CROSS_FILTER_PCT = new BigDecimal("50");
  // T2.6: a conservative, index-agnostic "drastic" absolute dOI magnitude. The doc gives NO number;
  // 50000 contracts is a deliberately cautious v1 placeholder — DB-tunable (and ideally per-index)
  // once the live dOI distribution is observed. Set high so a sleepy chain never trips this dot.
  private static final BigDecimal DEFAULT_DRASTIC_FLOOR = new BigDecimal("50000");
  // T2.8: the iv-pair min gap on the 0..1 fraction scale. Recalibrated 0.10 -> 0.02 (signal-analysis
  // rollup §Proposals P1, owner-approved 2026-07-10): a >=0.10 (10-IV-pt) CE-vs-PE gap never occurs on
  // NIFTY weeklies, so the 0.8-weight iv_pair dot was permanently dead; 0.02 (2 IV pts) makes it
  // reachable. Ops-tunable via ARTHA_SCALPER_OI_IV_PAIR_MIN_GAP (application.yml default mirrors this).
  private static final BigDecimal DEFAULT_IV_PAIR_MIN_GAP = new BigDecimal("0.02");
  // T2.8: the "40/40 both-high" stand-aside level on the 0..1 fraction scale = 0.40.
  private static final BigDecimal DEFAULT_IV_BOTH_HIGH_FLOOR = new BigDecimal("0.40");
  // T2.7: the OI-spurt magnitudes (% change) the spurt dot needs on BOTH legs. Recalibrated
  // (50,8) -> (15,3) per T22 (owner-approved 2026-07-25): the 2026-07-10 pass lowered only the
  // PRICE floor, but the OI floor 50 sat at the p95 of its own operand (|spurtOiPct| p50=11 /
  // p80=20 / p95=50 over 4,118 context rows, 07-21..24), so the joint pass rate was 1.26%
  // pre-quadrant and the dot decayed to fully dead. (15,3) measures 15.5% joint — the
  // selective-but-alive target. Ops-tunable via ARTHA_SCALPER_OI_SPURT_OI_PCT /
  // ARTHA_SCALPER_OI_SPURT_PRICE_PCT (application.yml defaults mirror these). Judge the revival on
  // 2 forward sessions — the iv_pair lesson: verify, never assume.
  private static final BigDecimal DEFAULT_SPURT_OI_PCT = new BigDecimal("15");
  private static final BigDecimal DEFAULT_SPURT_PRICE_PCT = new BigDecimal("3");
  // #2 (section 3.2) Table-1: the per-side OH-strike count that makes the footprint a HIGH (>=3).
  private static final BigDecimal DEFAULT_OPEN_HIGH_MIN_STRIKES = new BigDecimal("3");
  // #2 Table-2: the declineVolume floor on the representative OH strike that downgrades a fall to LOW
  // (fell on heavy volume = a real opposite player). 50000 contracts is the doc's reference floor.
  private static final BigDecimal DEFAULT_OPEN_HIGH_FALL_VOLUME_FLOOR = new BigDecimal("50000");
  // #2 extra-LOW rule: a >50% premium fall from the previous close on the OH strike = a bigger player.
  private static final BigDecimal DEFAULT_OPEN_HIGH_MAX_PREV_CLOSE_FALL_PCT = new BigDecimal("50");
  // #2: the ATM+-window of listed strikes the per-strike footprint is read over.
  private static final BigDecimal DEFAULT_OPEN_HIGH_WINDOW = new BigDecimal("3");
  // #2: the relaxed RSI floor (source "RSI >50") that replaces the 60-80 band for the open-high path.
  private static final BigDecimal DEFAULT_OPEN_HIGH_RSI_FLOOR = new BigDecimal("50");
  // E4 §4.6: the absolute ATM-IV "trend-play" band (low IV = most of the move still ahead). "10-12 IV"
  // on the 0..1 fraction scale (see class javadoc) = 0.10-0.12. Only read by the iv_abs_band dot.
  private static final BigDecimal DEFAULT_IV_ABS_BAND_LOW = new BigDecimal("0.10");
  private static final BigDecimal DEFAULT_IV_ABS_BAND_HIGH = new BigDecimal("0.12");
  // E6 §3.3 Market-Movers: the intraday session-open->now %-move floor a "mover" entry needs. The deck
  // says >1% (for a stock); on the index it is a strict-but-tunable default.
  private static final BigDecimal DEFAULT_PCT_PRICE_MOVE_FLOOR = new BigDecimal("1.0");
  // E5 §3.2/§4.2 higher-TF RSI caps (the §4.2 "5m < 75/80, daily < 75 for CE; mirror PE" values).
  private static final BigDecimal DEFAULT_RSI_5M_CE_CAP = new BigDecimal("75");
  private static final BigDecimal DEFAULT_RSI_5M_PE_FLOOR = new BigDecimal("25");
  private static final BigDecimal DEFAULT_RSI_DAILY_CE_CAP = new BigDecimal("75");
  private static final BigDecimal DEFAULT_RSI_DAILY_PE_FLOOR = new BigDecimal("25");
  // E5 §3.7 post-vertical RSI-recovery (RATIFICATION-PACK row 51 AUTOMATE_PKG): after a vertical fall the
  // RSI crashes OVERSOLD (a trough at/under this level within the lookback), and a reversal long is only
  // taken once it has RECOVERED back to >= the recovery level. The trough/recovery levels + the lookback
  // bar-count are the §3.7 defaults (20 trough / 40 recovery / 10 bars), owner-tunable here.
  private static final BigDecimal DEFAULT_RSI_OVERSOLD_TROUGH = new BigDecimal("20");
  private static final BigDecimal DEFAULT_RSI_RECOVERY_LEVEL = new BigDecimal("40");
  private static final BigDecimal DEFAULT_RSI_RECOVERY_LOOKBACK = new BigDecimal("10");
  // signal-analysis rollup 2026-07-06 §7#1 (tag relative-volume-floor, default-OFF): the RELATIVE
  // volume floor = k × median(prior-N bar volumes). k=1.5 → a real participation spike vs the recent
  // norm; N=20 ≈ the last hour on 3m; below minBars=10 prior bars the rail falls back to the fixed §0B
  // floor (session warmup). Starting values — owner-tunable as the live veto rate is observed.
  private static final BigDecimal DEFAULT_RELATIVE_VOLUME_MULTIPLIER = new BigDecimal("1.5");
  private static final BigDecimal DEFAULT_RELATIVE_VOLUME_WINDOW = new BigDecimal("20");
  private static final BigDecimal DEFAULT_RELATIVE_VOLUME_MIN_BARS = new BigDecimal("10");
  // G10 (tag time-of-day-volume-floor, default-OFF): how many PRIOR sessions the time-of-day volume
  // profile samples at the current bar's offset. Deliberately shallow — the live 3m series warms 4
  // CALENDAR days (LiveSeriesStore.warmupDays has no 3m case → default 4), so 2-3 sessions is all
  // that exists, and the bake-off found shallow is BETTER: open-vs-rest pass ratio 0.94 at 2
  // sessions, 0.85 at 3, 0.79 at 5 (1.00 = perfectly unbiased) — a shallow profile tracks the
  // current regime more closely. 3 is the middle: near-uniform, and still satisfiable on a Monday.
  private static final BigDecimal DEFAULT_TIME_OF_DAY_PROFILE_SESSIONS = new BigDecimal("3");
  // T6 (owner-approved 2026-07-25): the vwap DOT's minimum |close−vwap|/close distance in bps. The
  // entry gate already enforces the VWAP SIDE, so a side-only dot supported 100% of 5,225 rows over
  // six sessions — a free 2.5-weight dot is an unlabelled −12.8% threshold cut. 15 bps ≈ the
  // measured distance median (16.3 over 4,118 rows) restores ~50/50 discrimination. The DECISIVE
  // hard gate (Confluence.vwapAligned) is untouched — this floors the DOT only. Ops-tunable via
  // ARTHA_SCALPER_OI_VWAP_MIN_DISTANCE_BPS.
  private static final BigDecimal DEFAULT_VWAP_MIN_DISTANCE_BPS = new BigDecimal("15");
  // F5 U4b §5.3: the dot-plane data-coverage floor, read ONLY when the DEFAULT-OFF
  // `dot-coverage-floor` / `dot-null-withheld` tag arms it. 0.90 is an EMPIRICAL natural break, not
  // a fitted knob: over 11,068 post-P3 scored evaluations the measured coverage distribution is
  // 9,207 rows at 1.000, 45 at 0.947-0.961 (one dot missing), ZERO rows in [0.828, 0.947], then the
  // 748 Timescale-outage rows and the 1,068 monthly-expiry rows below. Any floor in [0.85, 0.94]
  // separates "one dot happened to be missing" from "a whole data plane is gone" with no row in the
  // gap; 0.90 sits dead centre. Ops-tunable via ARTHA_SCALPER_OI_DOT_COVERAGE_FLOOR.
  private static final BigDecimal DEFAULT_DOT_COVERAGE_FLOOR = new BigDecimal("0.90");

  /** Fills any unset field with its documented default (so a partial yaml override is honoured). */
  public ScalperOiProps {
    crossFilterPct = crossFilterPct == null ? DEFAULT_CROSS_FILTER_PCT : crossFilterPct;
    drasticFloor = drasticFloor == null ? DEFAULT_DRASTIC_FLOOR : drasticFloor;
    ivPairMinGap = ivPairMinGap == null ? DEFAULT_IV_PAIR_MIN_GAP : ivPairMinGap;
    ivBothHighFloor = ivBothHighFloor == null ? DEFAULT_IV_BOTH_HIGH_FLOOR : ivBothHighFloor;
    spurtOiPct = spurtOiPct == null ? DEFAULT_SPURT_OI_PCT : spurtOiPct;
    spurtPricePct = spurtPricePct == null ? DEFAULT_SPURT_PRICE_PCT : spurtPricePct;
    openHighMinStrikes = openHighMinStrikes == null ? DEFAULT_OPEN_HIGH_MIN_STRIKES : openHighMinStrikes;
    openHighFallVolumeFloor =
        openHighFallVolumeFloor == null ? DEFAULT_OPEN_HIGH_FALL_VOLUME_FLOOR : openHighFallVolumeFloor;
    openHighMaxPrevCloseFallPct =
        openHighMaxPrevCloseFallPct == null
            ? DEFAULT_OPEN_HIGH_MAX_PREV_CLOSE_FALL_PCT
            : openHighMaxPrevCloseFallPct;
    openHighWindow = openHighWindow == null ? DEFAULT_OPEN_HIGH_WINDOW : openHighWindow;
    openHighRsiFloor = openHighRsiFloor == null ? DEFAULT_OPEN_HIGH_RSI_FLOOR : openHighRsiFloor;
    ivAbsBandLow = ivAbsBandLow == null ? DEFAULT_IV_ABS_BAND_LOW : ivAbsBandLow;
    ivAbsBandHigh = ivAbsBandHigh == null ? DEFAULT_IV_ABS_BAND_HIGH : ivAbsBandHigh;
    pctPriceMoveFloor = pctPriceMoveFloor == null ? DEFAULT_PCT_PRICE_MOVE_FLOOR : pctPriceMoveFloor;
    rsi5mCeCap = rsi5mCeCap == null ? DEFAULT_RSI_5M_CE_CAP : rsi5mCeCap;
    rsi5mPeFloor = rsi5mPeFloor == null ? DEFAULT_RSI_5M_PE_FLOOR : rsi5mPeFloor;
    rsiDailyCeCap = rsiDailyCeCap == null ? DEFAULT_RSI_DAILY_CE_CAP : rsiDailyCeCap;
    rsiDailyPeFloor = rsiDailyPeFloor == null ? DEFAULT_RSI_DAILY_PE_FLOOR : rsiDailyPeFloor;
    rsiOversoldTrough = rsiOversoldTrough == null ? DEFAULT_RSI_OVERSOLD_TROUGH : rsiOversoldTrough;
    rsiRecoveryLevel = rsiRecoveryLevel == null ? DEFAULT_RSI_RECOVERY_LEVEL : rsiRecoveryLevel;
    rsiRecoveryLookback = rsiRecoveryLookback == null ? DEFAULT_RSI_RECOVERY_LOOKBACK : rsiRecoveryLookback;
    relativeVolumeMultiplier =
        relativeVolumeMultiplier == null ? DEFAULT_RELATIVE_VOLUME_MULTIPLIER : relativeVolumeMultiplier;
    relativeVolumeWindow =
        relativeVolumeWindow == null ? DEFAULT_RELATIVE_VOLUME_WINDOW : relativeVolumeWindow;
    relativeVolumeMinBars =
        relativeVolumeMinBars == null ? DEFAULT_RELATIVE_VOLUME_MIN_BARS : relativeVolumeMinBars;
    timeOfDayProfileSessions =
        timeOfDayProfileSessions == null
            ? DEFAULT_TIME_OF_DAY_PROFILE_SESSIONS
            : timeOfDayProfileSessions;
    vwapMinDistanceBps =
        vwapMinDistanceBps == null ? DEFAULT_VWAP_MIN_DISTANCE_BPS : vwapMinDistanceBps;
    dotCoverageFloor = dotCoverageFloor == null ? DEFAULT_DOT_COVERAGE_FLOOR : dotCoverageFloor;
  }

  /** The all-defaults instance (used where config is absent — tests, the pure-scorer fallback). */
  public static ScalperOiProps defaults() {
    return new ScalperOiProps(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null);
  }
}
