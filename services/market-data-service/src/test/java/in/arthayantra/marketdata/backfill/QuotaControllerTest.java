package in.arthayantra.marketdata.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.upstox.UpstoxRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * EXT-02 item 4: the quota widget reads the ONE shared {@link UpstoxRateLimiter} bean directly, so it
 * reports {@code configured=true} in ANY Upstox config (including a quote-only / chain-only stack where
 * the expired-backfill client is absent) — not only when the backfill client is bound.
 */
class QuotaControllerTest {

  @SuppressWarnings("unchecked")
  private static ObjectProvider<UpstoxRateLimiter> providerOf(UpstoxRateLimiter limiter) {
    ObjectProvider<UpstoxRateLimiter> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(limiter);
    return provider;
  }

  @Test
  void reportsConfiguredWheneverTheSharedBudgetIsBound() {
    // A quote-only stack binds the shared limiter but NOT the expired-backfill client — the old
    // controller reported configured=false here; reading the limiter directly fixes it.
    UpstoxRateLimiter limiter = new UpstoxRateLimiter();
    limiter.tryAcquire();
    QuotaController controller = new QuotaController(providerOf(limiter));

    QuotaController.QuotaStatus status = controller.quota();

    assertThat(status.configured()).isTrue();
    assertThat(status.windows()).extracting(UpstoxRateLimiter.WindowStat::window).contains("30m");
  }

  @Test
  void reportsNotConfiguredWhenNoUpstoxConsumerIsBound() {
    QuotaController controller = new QuotaController(providerOf(null));

    QuotaController.QuotaStatus status = controller.quota();

    assertThat(status.configured()).isFalse();
    assertThat(status.windows()).isEmpty();
  }
}
