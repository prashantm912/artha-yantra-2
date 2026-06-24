package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seam test for the Wave-U2 quote-source flag: {@code artha.marketdata.source.quotes} selects exactly
 * ONE {@link QuoteGateway} bean — the Upstox path when {@code =upstox}, the Kite default otherwise
 * (unset or {@code =kite}). This pins the SAME {@code @ConditionalOnProperty} predicates production
 * uses ({@code LiveKiteConfig#liveQuoteGateway} with {@code matchIfMissing=true} and {@code
 * UpstoxQuoteGateway} with {@code havingValue="upstox"}) without booting the heavyweight live beans,
 * proving the flag default leaves the Kite quote path untouched.
 */
class UpstoxQuoteSeamTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(SeamConfig.class);

  @Test
  void defaultFlagBindsTheKiteQuoteGateway() {
    runner.run(
        ctx -> {
          assertThat(ctx.getBeanNamesForType(QuoteGateway.class)).hasSize(1);
          assertThat(ctx.getBean(QuoteGateway.class)).isInstanceOf(StubKiteQuoteGateway.class);
        });
  }

  @Test
  void explicitKiteFlagBindsTheKiteQuoteGateway() {
    runner
        .withPropertyValues("artha.marketdata.source.quotes=kite")
        .run(
            ctx -> {
              assertThat(ctx.getBeanNamesForType(QuoteGateway.class)).hasSize(1);
              assertThat(ctx.getBean(QuoteGateway.class)).isInstanceOf(StubKiteQuoteGateway.class);
            });
  }

  @Test
  void upstoxFlagBindsTheUpstoxQuoteGateway() {
    runner
        .withPropertyValues("artha.marketdata.source.quotes=upstox")
        .run(
            ctx -> {
              assertThat(ctx.getBeanNamesForType(QuoteGateway.class)).hasSize(1);
              assertThat(ctx.getBean(QuoteGateway.class)).isInstanceOf(StubUpstoxQuoteGateway.class);
            });
  }

  /** Mirrors the production conditions: kite default (matchIfMissing) xor upstox (havingValue). */
  @Configuration
  static class SeamConfig {

    @Bean
    @ConditionalOnProperty(
        name = "artha.marketdata.source.quotes",
        havingValue = "kite",
        matchIfMissing = true)
    QuoteGateway kiteQuoteGateway() {
      return new StubKiteQuoteGateway();
    }

    @Bean
    @ConditionalOnProperty(name = "artha.marketdata.source.quotes", havingValue = "upstox")
    QuoteGateway upstoxQuoteGateway() {
      return new StubUpstoxQuoteGateway();
    }
  }

  static final class StubKiteQuoteGateway implements QuoteGateway {
    @Override
    public Map<InstrumentKey, Quote> quotes(Collection<InstrumentKey> keys) {
      return Map.of();
    }
  }

  static final class StubUpstoxQuoteGateway implements QuoteGateway {
    @Override
    public Map<InstrumentKey, Quote> quotes(Collection<InstrumentKey> keys) {
      return Map.of();
    }
  }
}
