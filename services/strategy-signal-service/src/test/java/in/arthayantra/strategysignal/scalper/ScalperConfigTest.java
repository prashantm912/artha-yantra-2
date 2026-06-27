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
}
