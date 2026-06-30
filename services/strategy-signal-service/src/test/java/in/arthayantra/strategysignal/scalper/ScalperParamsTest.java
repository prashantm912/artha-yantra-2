package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** E5 §3.5 per-strategy RSI band parse: the optional {@code scalper.params.rsi_band} block. */
class ScalperParamsTest {

  private static final ObjectMapper OM = new ObjectMapper();

  @Test
  void absentBlockYieldsNullBand() {
    // No scalper.params block at all -> defaults() -> the band is null (the shared RSI band fires).
    assertThat(ScalperParams.defaults().rsiBand()).isNull();
    assertThat(ScalperParams.from(MissingNode.getInstance()).rsiBand()).isNull();
  }

  @Test
  void parsesAFullRsiBandBlock() throws Exception {
    ScalperParams p =
        ScalperParams.from(
            OM.readTree("{\"rsi_band\":{\"ce\":{\"lo\":50,\"hi\":75},\"pe\":{\"lo\":25,\"hi\":50}}}"));
    ScalperParams.RsiBand b = p.rsiBand();
    assertThat(b).isNotNull();
    assertThat(b.ceLo()).isEqualByComparingTo(new BigDecimal("50"));
    assertThat(b.ceHi()).isEqualByComparingTo(new BigDecimal("75"));
    assertThat(b.peLo()).isEqualByComparingTo(new BigDecimal("25"));
    assertThat(b.peHi()).isEqualByComparingTo(new BigDecimal("50"));
  }

  @Test
  void partialBlockDegradesToNull() throws Exception {
    // A bound missing -> null (degrade to the shared band, never a half-built band).
    ScalperParams p =
        ScalperParams.from(OM.readTree("{\"rsi_band\":{\"ce\":{\"lo\":50},\"pe\":{\"lo\":25,\"hi\":50}}}"));
    assertThat(p.rsiBand()).isNull();
  }

  @Test
  void rsiBandIsIndependentOfTheOtherParamDefaults() throws Exception {
    // Declaring only rsi_band leaves every other param at its ScalperGates default (byte-identical path).
    ScalperParams p =
        ScalperParams.from(
            OM.readTree("{\"rsi_band\":{\"ce\":{\"lo\":50,\"hi\":75},\"pe\":{\"lo\":25,\"hi\":50}}}"));
    assertThat(p.rsiBand()).isNotNull();
    assertThat(p.volumeFloor()).isEqualTo(ScalperParams.defaults().volumeFloor());
    assertThat(p.middayBlock()).isEqualTo(ScalperParams.defaults().middayBlock());
  }
}
