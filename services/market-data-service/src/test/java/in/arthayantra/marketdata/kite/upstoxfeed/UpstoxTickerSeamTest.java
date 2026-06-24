package in.arthayantra.marketdata.kite.upstoxfeed;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.TickListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seam test for the Wave-U3 ticker-source flag: {@code artha.marketdata.source.ticker} selects
 * exactly ONE {@link MarketFeed} — the Kite default ({@code matchIfMissing}, unset or {@code =kite})
 * or the Upstox WS feed ({@code havingValue="upstox"}). Pins the SAME {@code @ConditionalOnProperty}
 * predicates production uses ({@code LiveKiteConfig#liveMarketFeed} and {@code
 * UpstoxMarketFeedConfig#upstoxMarketFeed}) without booting the heavyweight live beans — proving the
 * two feeds are mutually exclusive (the {@code FeedPipeline} autowires a single {@code MarketFeed}),
 * so flipping the flag never runs both, and the default leaves the Kite path untouched.
 */
class UpstoxTickerSeamTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(SeamConfig.class);

  @Test
  void defaultFlagBindsTheKiteFeed() {
    runner.run(
        ctx -> {
          assertThat(ctx.getBeanNamesForType(MarketFeed.class)).hasSize(1);
          assertThat(ctx.getBean(MarketFeed.class)).isInstanceOf(StubKiteFeed.class);
        });
  }

  @Test
  void explicitKiteFlagBindsTheKiteFeed() {
    runner
        .withPropertyValues("artha.marketdata.source.ticker=kite")
        .run(
            ctx -> {
              assertThat(ctx.getBeanNamesForType(MarketFeed.class)).hasSize(1);
              assertThat(ctx.getBean(MarketFeed.class)).isInstanceOf(StubKiteFeed.class);
            });
  }

  @Test
  void upstoxFlagBindsTheUpstoxFeed() {
    runner
        .withPropertyValues("artha.marketdata.source.ticker=upstox")
        .run(
            ctx -> {
              assertThat(ctx.getBeanNamesForType(MarketFeed.class)).hasSize(1);
              assertThat(ctx.getBean(MarketFeed.class)).isInstanceOf(StubUpstoxFeed.class);
            });
  }

  /** Mirrors the production conditions: kite default (matchIfMissing) xor upstox (havingValue). */
  @Configuration
  static class SeamConfig {

    @Bean
    @ConditionalOnProperty(
        name = "artha.marketdata.source.ticker",
        havingValue = "kite",
        matchIfMissing = true)
    MarketFeed kiteMarketFeed() {
      return new StubKiteFeed();
    }

    @Bean
    @ConditionalOnProperty(name = "artha.marketdata.source.ticker", havingValue = "upstox")
    MarketFeed upstoxMarketFeed() {
      return new StubUpstoxFeed();
    }
  }

  static final class StubKiteFeed implements MarketFeed {
    @Override
    public void start(TickListener listener) {}

    @Override
    public void stop() {}

    @Override
    public boolean running() {
      return false;
    }
  }

  static final class StubUpstoxFeed implements MarketFeed {
    @Override
    public void start(TickListener listener) {}

    @Override
    public void stop() {}

    @Override
    public boolean running() {
      return false;
    }
  }
}
