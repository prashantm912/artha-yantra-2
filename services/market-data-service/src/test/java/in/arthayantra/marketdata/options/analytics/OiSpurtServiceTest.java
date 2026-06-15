package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OiSpurtServiceTest {

  @Test
  void classifiesAndComputesSpurtPct() {
    OiSpurtService svc = new OiSpurtService();
    // ltp up (+5), oi up (+200 from prior 1000) → LONG_BUILDUP, spurt 20.00%
    OiSpurtService.SpurtRow r = svc.classify(new BigDecimal("5"), 200, 1000);
    assertThat(r.interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(r.spurtPct()).isEqualByComparingTo("20.00");
  }

  @Test
  void spurtPctNullWhenNoPriorOi() {
    assertThat(new OiSpurtService().classify(BigDecimal.ONE, 50, 0).spurtPct()).isNull();
  }
}
