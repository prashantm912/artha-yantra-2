package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;

/**
 * The per-strategy scalper knobs (master plan §12.2/§12.4). The underlying + exchange are read from
 * the strategy YAML {@code universe.underlying}; the delta/premium/threshold/rate values are the
 * verified §0B constants keyed by underlying — held HERE, not in YAML (the same single-source rule as
 * {@link ScalperGates}), so a scalper strategy YAML declares only {@code mode: options_of_underlying}
 * + the underlying + the scalper indicator aliases. Tuning later rides DB rows, never the YAML.
 */
public record ScalperConfig(
    String underlyingExchange,
    String underlying,
    int rollDays,
    StrikePicker.Params strikeParams,
    BigDecimal confluenceThreshold) {

  // §0B delta band — uniform across indices (the slightly-ITM 0.6–0.7 Siva favours).
  private static final double DELTA_LO = 0.6;
  private static final double DELTA_HI = 0.7;
  // Black-76 risk-free rate; delta is near rate-insensitive for short-dated options, so a fixed
  // value is immaterial to strike selection.
  private static final double RATE = 0.065;
  // v1 confluence aggregate a valid signal must reach (≥ ~60% of the weighted dots).
  private static final BigDecimal THRESHOLD = new BigDecimal("0.6");
  // §0B premium bands (VERIFIED: NIFTY 100–250, BANKNIFTY 250–400). Unknown indices fall to NIFTY's
  // band conservatively (a narrower band rejects more strikes — never falsely admits one).
  private static final BigDecimal[] NIFTY_PREMIUM = {new BigDecimal("100"), new BigDecimal("250")};
  private static final Map<String, BigDecimal[]> PREMIUM =
      Map.of(
          "NIFTY 50", NIFTY_PREMIUM,
          "NIFTY BANK", new BigDecimal[] {new BigDecimal("250"), new BigDecimal("400")});

  /** Builds the config from a strategy's {@code universe} block (options_of_underlying). */
  public static ScalperConfig from(JsonNode universe) {
    JsonNode u = universe.path("underlying");
    String underlying = u.path("tradingsymbol").asText();
    String exchange = u.path("exchange").asText("NSE");
    int rollDays = universe.path("futures").path("roll_days_before_expiry").asInt(2);
    BigDecimal[] premium = PREMIUM.getOrDefault(underlying, NIFTY_PREMIUM);
    StrikePicker.Params params =
        new StrikePicker.Params(DELTA_LO, DELTA_HI, premium[0], premium[1], RATE);
    return new ScalperConfig(exchange, underlying, rollDays, params, THRESHOLD);
  }
}
