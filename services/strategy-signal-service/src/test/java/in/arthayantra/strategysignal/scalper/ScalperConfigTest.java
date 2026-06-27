package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  void openHighOiVetoTagBindsTheFlag() {
    assertThat(ScalperConfig.from(config("NIFTY 50"), List.of("scalper")).requireOpenHighOiVeto())
        .isFalse();
    assertThat(
            ScalperConfig.from(config("NIFTY 50"), List.of("scalper", "open-high-oi-veto"))
                .requireOpenHighOiVeto())
        .isTrue();
  }
}
