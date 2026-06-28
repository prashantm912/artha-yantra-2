package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code artha.scalper.oi.*} binding: documented defaults + a partial override (T2.1/2.6/2.7/2.8). */
class ScalperOiPropsTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              org.springframework.boot.autoconfigure.AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(Reg.class);

  @EnableConfigurationProperties(ScalperOiProps.class)
  static class Reg {}

  @Test
  void bindsDocumentedDefaultsWhenUnset() {
    runner.run(
        ctx -> {
          ScalperOiProps p = ctx.getBean(ScalperOiProps.class);
          assertThat(p.crossFilterPct()).isEqualByComparingTo("50");
          assertThat(p.drasticFloor()).isEqualByComparingTo("50000");
          // IV levels on the 0..1 fraction scale: 10 IV points = 0.10, 40/40 both-high = 0.40.
          assertThat(p.ivPairMinGap()).isEqualByComparingTo("0.10");
          assertThat(p.ivBothHighFloor()).isEqualByComparingTo("0.40");
          assertThat(p.spurtOiPct()).isEqualByComparingTo("50");
          assertThat(p.spurtPricePct()).isEqualByComparingTo("50");
          // E4: the absolute ATM-IV "trend-play" band on the 0..1 fraction scale = 0.10-0.12.
          assertThat(p.ivAbsBandLow()).isEqualByComparingTo("0.10");
          assertThat(p.ivAbsBandHigh()).isEqualByComparingTo("0.12");
          // E6: the Market-Movers >1% session-move floor.
          assertThat(p.pctPriceMoveFloor()).isEqualByComparingTo("1.0");
        });
  }

  @Test
  void aPartialOverrideKeepsTheOtherDefaults() {
    runner
        .withPropertyValues("artha.scalper.oi.drastic-floor=75000", "artha.scalper.oi.iv-pair-min-gap=0.15")
        .run(
            ctx -> {
              ScalperOiProps p = ctx.getBean(ScalperOiProps.class);
              assertThat(p.drasticFloor()).isEqualByComparingTo("75000");
              assertThat(p.ivPairMinGap()).isEqualByComparingTo("0.15");
              assertThat(p.crossFilterPct()).isEqualByComparingTo("50"); // untouched -> default
            });
  }

  @Test
  void defaultsFactoryMatchesTheBoundDefaults() {
    ScalperOiProps p = ScalperOiProps.defaults();
    assertThat(p.crossFilterPct()).isEqualByComparingTo("50");
    assertThat(p.ivPairMinGap()).isEqualByComparingTo("0.10");
    assertThat(p.ivBothHighFloor()).isEqualByComparingTo("0.40");
  }
}
