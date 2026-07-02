package in.arthayantra.marketdata.nse.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.marketdata.constituents.StaticIndexWeights;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Audit §9.4 (Wave 5): the intraday quote-based index-contribution fold + its EOD fallback. */
class EquityIndexContributionLiveTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final StaticIndexWeights weights = mock(StaticIndexWeights.class);
  private final QuoteGateway quotes = mock(QuoteGateway.class);
  private final EquityIndexContributionService service =
      new EquityIndexContributionService(jdbc, weights, quotes);

  private static QuoteGateway.Quote quote(String symbol, String ltp, String prevClose) {
    return new QuoteGateway.Quote(
        new InstrumentKey("NSE", symbol),
        new BigDecimal(ltp),
        null,
        null,
        null,
        null,
        new QuoteGateway.Quote.Ohlc(null, null, null, prevClose == null ? null : new BigDecimal(prevClose)),
        OffsetDateTime.parse("2026-07-02T10:00:00+05:30"));
  }

  @Test
  void liveFoldUsesQuotePrevCloseAndTheLiveIndexLevel() {
    when(weights.weights("NIFTY 50"))
        .thenReturn(Map.of("AAA", new BigDecimal("50"), "BBB", new BigDecimal("50")));
    when(quotes.quotes(any()))
        .thenReturn(
            Map.of(
                new InstrumentKey("NSE", "AAA"), quote("AAA", "110", "100"), // +10% × 50% = +5.00
                new InstrumentKey("NSE", "BBB"), quote("BBB", "95", "100"), // −5% × 50% = −2.50
                new InstrumentKey("NSE", "NIFTY 50"), quote("NIFTY 50", "20000", null)));

    EquityIndexContributionService.IndexContribution c = service.liveContribution("NIFTY 50");

    assertThat(c.live()).isTrue();
    assertThat(c.indexLevel()).isEqualByComparingTo("20000");
    assertThat(c.advances()).hasSize(1);
    assertThat(c.advances().get(0).symbol()).isEqualTo("AAA");
    assertThat(c.advances().get(0).contribution()).isEqualByComparingTo("5.0000");
    // points = contribution × live index level / 100 = 5 × 20000 / 100 = 1000
    assertThat(c.advances().get(0).points()).isEqualByComparingTo("1000.00");
    assertThat(c.declines().get(0).contribution()).isEqualByComparingTo("-2.5000");
    assertThat(c.indexChangePct()).isEqualByComparingTo("2.50");
  }

  @Test
  void noLiveQuotesFallsBackToTheEodRead() {
    when(weights.weights("NIFTY 50")).thenReturn(Map.of("AAA", new BigDecimal("50")));
    when(quotes.quotes(any())).thenReturn(Map.of());
    // the EOD path runs against the (empty) mocked bhavcopy → its own DATA_GAP proves the fallback
    when(jdbc.queryForObject(anyString(), eq(LocalDate.class))).thenReturn(LocalDate.of(2026, 7, 1));

    assertThatThrownBy(() -> service.liveContribution("NIFTY 50"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("no bhavcopy");
  }
}
