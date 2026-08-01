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

  /** A USABLE quote: positive LTP AND a prev close in the OHLC close slot. */
  private static Quote quote(String ltp, String prevClose) {
    return new Quote(
        DOW,
        ltp == null ? null : new BigDecimal(ltp),
        null,
        null,
        null,
        null,
        prevClose == null ? null : new Quote.Ohlc(null, null, null, new BigDecimal(prevClose)),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  @Test
  void aResolvedQuotePassesThroughUntouchedAndDegradesNothing() {
    Quote quote = quote("52548.91", "52229.06");
    GlobalQuoteSource source =
        new OpenAlgoGlobalQuoteClient(keys -> Map.of(DOW, quote), meters);

    assertThat(source.latest(DOW)).contains(quote);
    assertThat(meters.find(GlobalQuoteSource.DEGRADED_METRIC).counters()).isEmpty();
    assertThat(logs.list).isEmpty();
  }

  /**
   * The one that matters: {@code OpenAlgoMappers.toQuote} maps a MISSING {@code ltp} to
   * {@code BigDecimal.ZERO}, so an unserved DOWJONES arrives as "price 0" against a real prev close.
   * Passed through, {@code dowFactor} computes {@code 0 − 52229.06 < 0} and scores a violently
   * BEARISH Dow out of thin air. It must be refused and counted, never forwarded.
   */
  @Test
  void aZeroLtpIsRefusedRatherThanManufacturingABearishDow() {
    GlobalQuoteSource source =
        new OpenAlgoGlobalQuoteClient(keys -> Map.of(DOW, quote("0", "52229.06")), meters);

    assertThat(source.latest(DOW)).isEmpty();
    assertThat(degradations("no-ltp")).isEqualTo(1.0);
    assertWarned("reason=no-ltp");
  }

  @Test
  void anLtpOnlyQuoteWithNoPrevCloseIsRefusedInsteadOfLookingHealthy() {
    GlobalQuoteSource source =
        new OpenAlgoGlobalQuoteClient(keys -> Map.of(DOW, quote("52548.91", null)), meters);

    assertThat(source.latest(DOW)).isEmpty();
    assertThat(degradations("no-prev-close")).isEqualTo(1.0);
    assertWarned("reason=no-prev-close");
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
