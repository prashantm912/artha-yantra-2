package in.arthayantra.marketdata.kite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Startup fail-fast for the {@link GlobalQuoteSource} port's two mutually-exclusive implementations.
 *
 * <p>{@code artha.openalgo.global-quotes-enabled} (OpenAlgo) and {@code
 * artha.upstox.global-quotes-enabled} (Upstox) each bind a {@code GlobalQuoteSource}. Both consumers
 * — {@code ConnectingDotsService} and {@code MarketSurfaceController} — resolve the port through
 * {@code ObjectProvider.getIfAvailable()}, which throws {@code NoUniqueBeanDefinitionException} when
 * TWO candidates exist. That failure would land at REQUEST time, on the live Dow read, with a
 * bean-plumbing message: the worst possible place to discover a two-line {@code .env} mistake.
 *
 * <p>This guard is an ordinary eager singleton, so a both-flags-on stack refuses to start with a
 * message naming the two flags instead. It is deliberately dependency-free (only the two property
 * values) so the invariant can be proven by loading THIS class in a context test.
 */
@Configuration(proxyBeanMethods = false)
@Profile("live")
public class GlobalQuoteSourceExclusivityGuard {

  /** Refuses the context when both global-quote flags are armed at once. */
  public GlobalQuoteSourceExclusivityGuard(
      @Value("${artha.openalgo.global-quotes-enabled:false}") boolean openAlgoEnabled,
      @Value("${artha.upstox.global-quotes-enabled:false}") boolean upstoxEnabled) {
    if (openAlgoEnabled && upstoxEnabled) {
      throw new IllegalStateException(
          "artha.openalgo.global-quotes-enabled and artha.upstox.global-quotes-enabled are mutually "
              + "exclusive — both bind a GlobalQuoteSource and the Dow consumers resolve exactly one. "
              + "Pick ONE global-quote backend and set the other to false.");
    }
  }
}
