package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * W3: the S24-ratified strike-band tags on {@link ScalperConfig#from} are default-OFF — an untagged
 * strategy builds the legacy {@link StrikePicker.Params}, byte-identical, so the deterministic seam is
 * unchanged; only a strategy carrying the tag gets the ratified band.
 */
class ScalperConfigTest {

  private static final ObjectMapper M = new ObjectMapper();

  private static JsonNode config(String underlying) {
    try {
      return M.readTree(
          "{\"universe\":{\"underlying\":{\"exchange\":\"NSE\",\"tradingsymbol\":\"" + underlying + "\"}}}");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void deltaBandIsLegacy060To070WithoutTheTag() {
    ScalperConfig cfg = ScalperConfig.from(config("NIFTY 50"), List.of("scalper"));
    assertThat(cfg.strikeParams().deltaLo()).isEqualTo(0.6);
    assertThat(cfg.strikeParams().deltaHi()).isEqualTo(0.7);
  }

  @Test
  void deltaS24FloorTagShiftsBandTo070To080() {
    ScalperConfig cfg = ScalperConfig.from(config("NIFTY 50"), List.of("scalper", "delta-s24-floor"));
    assertThat(cfg.strikeParams().deltaLo()).isEqualTo(0.7);
    assertThat(cfg.strikeParams().deltaHi()).isEqualTo(0.8);
  }

  @Test
  void premiumBandIsLegacyWithoutTheTag() {
    ScalperConfig nifty = ScalperConfig.from(config("NIFTY 50"), List.of("scalper"));
    assertThat(nifty.strikeParams().premiumLo()).isEqualByComparingTo("100");
    assertThat(nifty.strikeParams().premiumHi()).isEqualByComparingTo("250");
    ScalperConfig bank = ScalperConfig.from(config("NIFTY BANK"), List.of("scalper"));
    assertThat(bank.strikeParams().premiumLo()).isEqualByComparingTo("250");
    assertThat(bank.strikeParams().premiumHi()).isEqualByComparingTo("400");
  }

  @Test
  void premiumS24BandTagShiftsNiftyAndBankNotSensex() {
    ScalperConfig nifty = ScalperConfig.from(config("NIFTY 50"), List.of("scalper", "premium-s24-band"));
    assertThat(nifty.strikeParams().premiumLo()).isEqualByComparingTo("150");
    assertThat(nifty.strikeParams().premiumHi()).isEqualByComparingTo("350");
    ScalperConfig bank = ScalperConfig.from(config("NIFTY BANK"), List.of("scalper", "premium-s24-band"));
    assertThat(bank.strikeParams().premiumLo()).isEqualByComparingTo("250");
    assertThat(bank.strikeParams().premiumHi()).isEqualByComparingTo("550");
    // SENSEX is unchanged (no S24 ruling) — 300-800 with or without the tag.
    ScalperConfig sensex = ScalperConfig.from(config("SENSEX"), List.of("scalper", "premium-s24-band"));
    assertThat(sensex.strikeParams().premiumLo()).isEqualByComparingTo("300");
    assertThat(sensex.strikeParams().premiumHi()).isEqualByComparingTo("800");
  }

  @Test
  void w4BehaviourTagsBindThroughHas() {
    // from() carries the raw tag list (canonical 19-arg ctor) so the W4 extension gates read has().
    ScalperConfig armed =
        ScalperConfig.from(config("NIFTY 50"), List.of("scalper", "indicator-distance-veto"));
    assertThat(armed.has("indicator-distance-veto")).isTrue();
    assertThat(armed.has("divergence-vol-gate")).isFalse();
    // an untagged scalper arms nothing.
    assertThat(ScalperConfig.from(config("NIFTY 50"), List.of("scalper")).has("indicator-distance-veto"))
        .isFalse();
    // the legacy 18-arg constructor (test fixtures / back-compat) defaults tags to empty -> has() false.
    ScalperConfig legacy =
        new ScalperConfig(
            "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
            new StrikePicker.Params(0.6, 0.7, new BigDecimal("100"), new BigDecimal("250"), 0.065),
            new BigDecimal("0.6"), false, ScalperConfig.StructuralStop.NONE, false, false, false, false,
            false, false, false, false, false);
    assertThat(legacy.has("indicator-distance-veto")).isFalse();
  }

  @Test
  void openHighOiVetoTagBindsTheFlag() {
    assertThat(ScalperConfig.from(config("NIFTY 50"), List.of("scalper")).requireOpenHighOiVeto())
        .isFalse();
    assertThat(
            ScalperConfig.from(config("NIFTY 50"), List.of("scalper", "open-high-oi-veto"))
                .requireOpenHighOiVeto())
        .isTrue();
  }

  @Test
  void scalperParamsDefaultToTheGateConstantsWhenAbsent() {
    // §5: no scalper.params block ⇒ every armable-gate threshold equals its ScalperGates default, so an
    // untuned strategy is byte-identical (the values the gate used before this feature).
    ScalperParams p = ScalperConfig.from(config("NIFTY 50"), List.of("scalper")).params();
    assertThat(p.vwapDistanceMinFrac()).isEqualByComparingTo(ScalperGates.VWAP_DISTANCE_MIN_FRAC);
    assertThat(p.vwapDistanceMaxFrac()).isEqualByComparingTo(ScalperGates.VWAP_DISTANCE_MAX_FRAC);
    assertThat(p.gapSuppressPts()).isEqualByComparingTo(ScalperGates.GAP_SIDE_SUPPRESS_PTS);
    assertThat(p.indicatorDistanceMaxPct()).isEqualByComparingTo(ScalperGates.INDICATOR_DISTANCE_MAX_PCT);
    assertThat(p.oiDivergenceMinPct()).isEqualByComparingTo(ScalperGates.OI_DIVERGENCE_MIN_PCT);
    assertThat(p.priceImpulseMinPct()).isEqualByComparingTo(ScalperGates.PRICE_IMPULSE_MIN_PCT);
    assertThat(p.ivBuyerCap()).isEqualByComparingTo(ScalperGates.IV_BUYER_CAP);
    // §0B rails default to the Siva constants; the midday block defaults ON; the volume floor stays
    // null (⇒ the per-index default map).
    assertThat(p.noTradeBefore()).isEqualTo(ScalperGates.NO_TRADE_BEFORE);
    assertThat(p.noFreshEntryAfter()).isEqualTo(ScalperGates.NO_FRESH_ENTRY_AFTER);
    assertThat(p.middayBlock()).isTrue();
    assertThat(p.volumeFloor()).isNull();
    // the legacy fixture constructors also default params (never null) so the gate read is safe.
    assertThat(
            new ScalperConfig(
                    "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
                    new StrikePicker.Params(0.6, 0.7, new BigDecimal("100"), new BigDecimal("250"), 0.065),
                    new BigDecimal("0.6"), false, ScalperConfig.StructuralStop.NONE, false, false, false,
                    false, false, false, false, false, false)
                .params()
                .ivBuyerCap())
        .isEqualByComparingTo(ScalperGates.IV_BUYER_CAP);
  }

  @Test
  void scalperParamsBlockOverridesReachTheConfig() throws Exception {
    JsonNode cfg =
        M.readTree(
            "{\"universe\":{\"underlying\":{\"exchange\":\"NSE\",\"tradingsymbol\":\"NIFTY 50\"}},"
                + "\"scalper\":{\"params\":{"
                + "\"vwap_distance_max_frac\":0.006,"
                + "\"gap_suppress_pts\":400,"
                + "\"indicator_distance_max_pct\":0.02,"
                + "\"oi_divergence_min_pct\":30,"
                + "\"iv_buyer_cap\":0.45,"
                + "\"no_trade_before\":\"09:30\","
                + "\"midday_block\":false,"
                + "\"volume_floor\":80000}}}");
    ScalperParams p = ScalperConfig.from(cfg, List.of("scalper")).params();
    assertThat(p.vwapDistanceMaxFrac()).isEqualByComparingTo("0.006");
    assertThat(p.gapSuppressPts()).isEqualByComparingTo("400");
    assertThat(p.indicatorDistanceMaxPct()).isEqualByComparingTo("0.02");
    assertThat(p.oiDivergenceMinPct()).isEqualByComparingTo("30");
    assertThat(p.ivBuyerCap()).isEqualByComparingTo("0.45");
    // §0B rail overrides reach the config: an earlier floor, the midday block OFF, a custom volume floor.
    assertThat(p.noTradeBefore()).isEqualTo(java.time.LocalTime.of(9, 30));
    assertThat(p.middayBlock()).isFalse();
    assertThat(p.volumeFloor()).isEqualByComparingTo("80000");
    // an UNSET field inside a partial block still falls back to the gate default.
    assertThat(p.priceImpulseMinPct()).isEqualByComparingTo(ScalperGates.PRICE_IMPULSE_MIN_PCT);
    assertThat(p.vwapDistanceMinFrac()).isEqualByComparingTo(ScalperGates.VWAP_DISTANCE_MIN_FRAC);
    assertThat(p.noFreshEntryAfter()).isEqualTo(ScalperGates.NO_FRESH_ENTRY_AFTER); // unset → default
  }
}
