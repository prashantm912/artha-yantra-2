package in.arthayantra.marketdata.mockfeed;

import in.arthayantra.marketdata.feed.LastTickStore;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.SessionGateway;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The remaining D13 mock port impls (A.7a): always-valid session, quotes from the last-tick map,
 * empty historical candles (their consumers arrive with the Stage-B candle cache). Together with
 * {@link MockMarketFeed} and {@link MockInstrumentDumpGateway}, exactly one impl per port is
 * bound under the mock profile.
 */
@Configuration
@Profile("mock")
public class MockKitePorts {

  /** Mock sessions are always valid (D13 — no credentials exist to expire). */
  @Bean
  public SessionGateway mockSessionGateway() {
    return new SessionGateway() {
      @Override
      public boolean sessionActive() {
        return true;
      }

      @Override
      public String statusLabel() {
        return "MOCK";
      }
    };
  }

  /** Quotes straight from the conflated last-tick map. */
  @Bean
  public QuoteGateway mockQuoteGateway(LastTickStore lastTickStore) {
    return (Collection<InstrumentKey> keys) -> {
      Map<InstrumentKey, QuoteGateway.Quote> quotes = new LinkedHashMap<>();
      for (InstrumentKey key : keys) {
        lastTickStore
            .latest(key)
            .ifPresent(
                tick ->
                    quotes.put(
                        key, new QuoteGateway.Quote(key, tick.lastPrice(), tick.timestamp())));
      }
      return quotes;
    };
  }

  /** Deterministic seeded mock history (Phase 11) with the Phase-16A planted-split knobs. */
  @Bean
  public HistoricalCandleGateway mockHistoricalCandleGateway(
      in.arthayantra.marketdata.candles.TradingBuckets tradingBuckets,
      @Value("${artha.mock.seed:42}") long seed,
      @Value("${artha.mock.corporate-action.symbol:MOCK003}") String caSymbol,
      @Value("${artha.mock.corporate-action.ratio:0.5}") java.math.BigDecimal caRatio,
      @Value("${artha.mock.corporate-action.boundary:2026-06-01}") java.time.LocalDate caBoundary,
      @Value("${artha.mock.corporate-action.active:false}") boolean caActive) {
    return new MockHistoricalCandleGateway(
        tradingBuckets, seed, caSymbol, caRatio, caBoundary, caActive);
  }

  /** Mock never holds a token (D13 — credential-free). */
  @Bean
  public in.arthayantra.marketdata.kite.AccessTokenProvider mockAccessTokenProvider() {
    return java.util.Optional::empty;
  }
}
