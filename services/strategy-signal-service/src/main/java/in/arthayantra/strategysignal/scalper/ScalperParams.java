package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalTime;

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
 *
 * <p>The §0B HARD RAILS are also exposed here (the "make every always-applied gate armable" goal): the
 * time-window bounds + the 11:00-13:00 midday block (toggle) + the bar-volume floor. They stay
 * ALWAYS-ON (an unset field keeps the Siva default — you cannot accidentally ship a strategy with no
 * volume/time guard) but are VALUE-tunable per-strategy, and the midday block is the one rail with a
 * clean on/off ({@code midday_block: false} lets a strategy trade through the sideways window). An
 * absent {@code scalper.params} block keeps every rail byte-identical to the {@link ScalperGates}
 * constants.
 */
public record ScalperParams(
    BigDecimal vwapDistanceMinFrac,
    BigDecimal vwapDistanceMaxFrac,
    BigDecimal gapSuppressPts,
    BigDecimal indicatorDistanceMaxPct,
    BigDecimal oiDivergenceMinPct,
    BigDecimal priceImpulseMinPct,
    BigDecimal ivBuyerCap,
    LocalTime noTradeBefore,
    LocalTime noFreshEntryAfter,
    Boolean middayBlock,
    BigDecimal volumeFloor,
    RsiBand rsiBand) {

  /**
   * E5 §3.5 per-strategy RSI band override (YAML {@code scalper.params.rsi_band}). When present it
   * REPLACES the shared §4.2 60-80 / 20-40 band on the hard RSI rail (and takes precedence over the
   * fixed {@code rsi-s24-bands} band); absent ⇒ {@code null} ⇒ the existing band chain is byte-identical.
   */
  public record RsiBand(BigDecimal ceLo, BigDecimal ceHi, BigDecimal peLo, BigDecimal peHi) {

    /**
     * Parses {@code rsi_band: { ce: { lo, hi }, pe: { lo, hi } }}. Returns {@code null} when the block
     * is absent OR any of the four bounds is missing (degrade to the shared band, never a broken one).
     */
    public static RsiBand from(JsonNode node) {
      if (node == null || node.isMissingNode() || !node.isObject()) {
        return null;
      }
      BigDecimal ceLo = num(node.path("ce"), "lo");
      BigDecimal ceHi = num(node.path("ce"), "hi");
      BigDecimal peLo = num(node.path("pe"), "lo");
      BigDecimal peHi = num(node.path("pe"), "hi");
      if (ceLo == null || ceHi == null || peLo == null || peHi == null) {
        return null;
      }
      return new RsiBand(ceLo, ceHi, peLo, peHi);
    }
  }

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
    // §0B rails: the time bounds default to the Siva constants; the midday block defaults ON (it can only
    // be turned OFF, never silently absent); the volume floor stays NULL ⇒ the per-index default map.
    noTradeBefore = noTradeBefore == null ? ScalperGates.NO_TRADE_BEFORE : noTradeBefore;
    noFreshEntryAfter = noFreshEntryAfter == null ? ScalperGates.NO_FRESH_ENTRY_AFTER : noFreshEntryAfter;
    middayBlock = middayBlock == null ? Boolean.TRUE : middayBlock;
  }

  /** The all-defaults instance (no {@code scalper.params} block — every shipped strategy today). */
  public static ScalperParams defaults() {
    return new ScalperParams(
        null, null, null, null, null, null, null, null, null, null, null, null);
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
        num(params, "iv_buyer_cap"),
        time(params, "no_trade_before"),
        time(params, "no_fresh_entry_after"),
        bool(params, "midday_block"),
        num(params, "volume_floor"),
        RsiBand.from(params.path("rsi_band")));
  }

  /** A numeric (or numeric-string) override, or null when the field is absent/blank (⇒ the default). */
  private static BigDecimal num(JsonNode node, String field) {
    JsonNode n = node.path(field);
    if (n.isNumber()) {
      return n.decimalValue();
    }
    return n.isTextual() && !n.asText().isBlank() ? new BigDecimal(n.asText().trim()) : null;
  }

  /** A {@code HH:mm} IST time override, or null when absent/blank (⇒ the default). */
  private static LocalTime time(JsonNode node, String field) {
    JsonNode n = node.path(field);
    return n.isTextual() && !n.asText().isBlank() ? LocalTime.parse(n.asText().trim()) : null;
  }

  /** A boolean override, or null when absent (⇒ the default). */
  private static Boolean bool(JsonNode node, String field) {
    JsonNode n = node.path(field);
    return n.isBoolean() ? n.asBoolean() : null;
  }
}
