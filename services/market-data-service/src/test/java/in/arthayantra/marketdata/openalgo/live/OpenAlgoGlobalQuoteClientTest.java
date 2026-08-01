package in.arthayantra.marketdata.openalgo.live;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.arthayantra.marketdata.kite.GlobalQuoteSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.QuoteGateway.Quote;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The OpenAlgo {@link GlobalQuoteSource} keeps its fail-soft behaviour (degrade to "no quote" so the
 * Dow factor reads Neutral rather than failing the matrix) but is no longer SILENT about it: the bare
 * {@code catch → Optional.empty()} is why a permanently-dark Dow dot survived months of live sessions.
 * Every degradation now logs WARN and counts {@link GlobalQuoteSource#DEGRADED_METRIC}.
 */
class OpenAlgoGlobalQuoteClientTest {

  private static final InstrumentKey DOW = new InstrumentKey("GLOBAL_INDEX", "DOWJONES");

  private SimpleMeterRegistry meters;
  private ListAppender<ILoggingEvent> logs;
  private ch.qos.logback.classic.Logger clientLog;

  @BeforeEach
  void setUp() {
    meters = new SimpleMeterRegistry();
    clientLog =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OpenAlgoGlobalQuoteClient.class);
    logs = new ListAppender<>();
    logs.start();
    clientLog.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    clientLog.detachAppender(logs);
  }

  private double degradations(String reason) {
    return meters
        .counter(GlobalQuoteSource.DEGRADED_METRIC, "source", "openalgo", "reason", reason)
        .count();
  }

  @Test
  void aResolvedQuotePassesThroughUntouchedAndDegradesNothing() {
    Quote quote =
        new Quote(DOW, new BigDecimal("52548.91"), OffsetDateTime.now(ZoneOffset.UTC));
    GlobalQuoteSource source =
        new OpenAlgoGlobalQuoteClient(keys -> Map.of(DOW, quote), meters);

    assertThat(source.latest(DOW)).contains(quote);
    assertThat(meters.find(GlobalQuoteSource.DEGRADED_METRIC).counters()).isEmpty();
    assertThat(logs.list).isEmpty();
  }

  @Test
  void anUpstreamFailureDegradesLoudlyInsteadOfSilentlyNeutral() {
    GlobalQuoteSource source =
        new OpenAlgoGlobalQuoteClient(
            failing("appliance is Zerodha-backed; DOWJONES is not served"), meters);

    assertThat(source.latest(DOW)).isEmpty();
    assertThat(degradations("error")).isEqualTo(1.0);
    assertWarned("reason=error");
  }

  @Test
  void anAbsentQuoteDegradesLoudlyInsteadOfSilentlyNeutral() {
    GlobalQuoteSource source = new OpenAlgoGlobalQuoteClient(keys -> Map.of(), meters);

    assertThat(source.latest(DOW)).isEmpty();
    assertThat(degradations("absent")).isEqualTo(1.0);
    assertWarned("reason=absent");
  }

  private void assertWarned(String fragment) {
    assertThat(logs.list)
        .as("a dark Dow dot must be visible in the log, not just in the counter")
        .anySatisfy(
            e -> {
              assertThat(e.getLevel()).isEqualTo(Level.WARN);
              assertThat(e.getFormattedMessage()).contains("degraded to neutral").contains(fragment);
            });
  }

  private static QuoteGateway failing(String message) {
    return new QuoteGateway() {
      @Override
      public Map<InstrumentKey, Quote> quotes(Collection<InstrumentKey> keys) {
        throw new IllegalStateException(message);
      }
    };
  }
}
