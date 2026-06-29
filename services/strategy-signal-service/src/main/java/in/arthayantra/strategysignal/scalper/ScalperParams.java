package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * Per-strategy YAML overrides ({@code scalper.params}) for the ARMABLE confluence-gate thresholds that
 * were previously hard-coded as {@link ScalperGates} constants — the first slice of the "the YAML
 * represents 100% of the strategy" goal (§5). Each field is OPTIONAL; an unset field falls back to the
 * exact {@link ScalperGates} default constant, so a strategy that declares no {@code scalper.params}
 * block (every shipped one today) builds the SAME values it did before and stays byte-identical (the
 * gates are live-only and default-OFF, so the goldens never move regardless). A strategy that DOES arm
 * one of these gates can now tune its threshold in the YAML — no Java change, no redeploy of the gate
 * logic.
 *
 * <p>Scale notes (the same the gate methods document): {@code vwapDistance*}/{@code
 * indicatorDistanceMaxPct} are PRICE FRACTIONS (0.004 = 0.4% of close); {@code ivBuyerCap} is a 0..1 IV
 * FRACTION (0.40 = "40 IV"); {@code oiDivergenceMinPct}/{@code priceImpulseMinPct} are PERCENTAGES (20 =
 * 20%); {@code gapSuppressPts} is index POINTS (300).
 */
public record ScalperParams(
    BigDecimal vwapDistanceMinFrac,
    BigDecimal vwapDistanceMaxFrac,
    BigDecimal gapSuppressPts,
    BigDecimal indicatorDistanceMaxPct,
    BigDecimal oiDivergenceMinPct,
    BigDecimal priceImpulseMinPct,
    BigDecimal ivBuyerCap) {

  /**
   * Fills any unset override with its {@link ScalperGates} default constant (the SINGLE source of the
   * value), so a partial or absent {@code scalper.params} block is honoured field-by-field and the
   * unarmed/untuned path is byte-identical.
   */
  public ScalperParams {
    vwapDistanceMinFrac = vwapDistanceMinFrac == null ? ScalperGates.VWAP_DISTANCE_MIN_FRAC : vwapDistanceMinFrac;
    vwapDistanceMaxFrac = vwapDistanceMaxFrac == null ? ScalperGates.VWAP_DISTANCE_MAX_FRAC : vwapDistanceMaxFrac;
    gapSuppressPts = gapSuppressPts == null ? ScalperGates.GAP_SIDE_SUPPRESS_PTS : gapSuppressPts;
    indicatorDistanceMaxPct =
        indicatorDistanceMaxPct == null ? ScalperGates.INDICATOR_DISTANCE_MAX_PCT : indicatorDistanceMaxPct;
    oiDivergenceMinPct = oiDivergenceMinPct == null ? ScalperGates.OI_DIVERGENCE_MIN_PCT : oiDivergenceMinPct;
    priceImpulseMinPct = priceImpulseMinPct == null ? ScalperGates.PRICE_IMPULSE_MIN_PCT : priceImpulseMinPct;
    ivBuyerCap = ivBuyerCap == null ? ScalperGates.IV_BUYER_CAP : ivBuyerCap;
  }

  /** The all-defaults instance (no {@code scalper.params} block — every shipped strategy today). */
  public static ScalperParams defaults() {
    return new ScalperParams(null, null, null, null, null, null, null);
  }

  /**
   * Reads the optional {@code scalper.params} block. Each field optional (absent ⇒ the gate default);
   * a missing/non-object node yields the all-defaults instance.
   */
  public static ScalperParams from(JsonNode params) {
    if (params == null || params.isMissingNode() || !params.isObject()) {
      return defaults();
    }
    return new ScalperParams(
        num(params, "vwap_distance_min_frac"),
        num(params, "vwap_distance_max_frac"),
        num(params, "gap_suppress_pts"),
        num(params, "indicator_distance_max_pct"),
        num(params, "oi_divergence_min_pct"),
        num(params, "price_impulse_min_pct"),
        num(params, "iv_buyer_cap"));
  }

  /** A numeric (or numeric-string) override, or null when the field is absent/blank (⇒ the default). */
  private static BigDecimal num(JsonNode node, String field) {
    JsonNode n = node.path(field);
    if (n.isNumber()) {
      return n.decimalValue();
    }
    return n.isTextual() && !n.asText().isBlank() ? new BigDecimal(n.asText().trim()) : null;
  }
}
