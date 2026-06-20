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
    BigDecimal spurtPricePct) {

  // T2.1: the #5 call-put delta-imbalance HARD pre-gate floor (>= 50% of the larger leg).
  private static final BigDecimal DEFAULT_CROSS_FILTER_PCT = new BigDecimal("50");
  // T2.6: a conservative, index-agnostic "drastic" absolute dOI magnitude. The doc gives NO number;
  // 50000 contracts is a deliberately cautious v1 placeholder — DB-tunable (and ideally per-index)
  // once the live dOI distribution is observed. Set high so a sleepy chain never trips this dot.
  private static final BigDecimal DEFAULT_DRASTIC_FLOOR = new BigDecimal("50000");
  // T2.8: "10 IV points" on the 0..1 fraction scale = 0.10 (see class javadoc).
  private static final BigDecimal DEFAULT_IV_PAIR_MIN_GAP = new BigDecimal("0.10");
  // T2.8: the "40/40 both-high" stand-aside level on the 0..1 fraction scale = 0.40.
  private static final BigDecimal DEFAULT_IV_BOTH_HIGH_FLOOR = new BigDecimal("0.40");
  // T2.7: the OI-spurt magnitudes (% change) the spurt dot needs on BOTH legs.
  private static final BigDecimal DEFAULT_SPURT_OI_PCT = new BigDecimal("50");
  private static final BigDecimal DEFAULT_SPURT_PRICE_PCT = new BigDecimal("50");

  /** Fills any unset field with its documented default (so a partial yaml override is honoured). */
  public ScalperOiProps {
    crossFilterPct = crossFilterPct == null ? DEFAULT_CROSS_FILTER_PCT : crossFilterPct;
    drasticFloor = drasticFloor == null ? DEFAULT_DRASTIC_FLOOR : drasticFloor;
    ivPairMinGap = ivPairMinGap == null ? DEFAULT_IV_PAIR_MIN_GAP : ivPairMinGap;
    ivBothHighFloor = ivBothHighFloor == null ? DEFAULT_IV_BOTH_HIGH_FLOOR : ivBothHighFloor;
    spurtOiPct = spurtOiPct == null ? DEFAULT_SPURT_OI_PCT : spurtOiPct;
    spurtPricePct = spurtPricePct == null ? DEFAULT_SPURT_PRICE_PCT : spurtPricePct;
  }

  /** The all-defaults instance (used where config is absent — tests, the pure-scorer fallback). */
  public static ScalperOiProps defaults() {
    return new ScalperOiProps(null, null, null, null, null, null);
  }
}
