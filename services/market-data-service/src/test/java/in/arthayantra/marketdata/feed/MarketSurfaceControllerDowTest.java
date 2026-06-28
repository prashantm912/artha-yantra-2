package in.arthayantra.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.marketdata.feed.MarketSurfaceController.DowQuote;
import in.arthayantra.marketdata.kite.GlobalQuoteSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * E3 {@code GET /global/dow}: the Dow global-cue quote maps LTP + prev close → a signed direction; a
 * missing global feed or quote is a 422 DATA_GAP (so the scalper Dow read degrades to neutral).
 */
class MarketSurfaceControllerDowTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  @SuppressWarnings("unchecked")
  private static MarketSurfaceController controller(GlobalQuoteSource source) {
    ObjectProvider<GlobalQuoteSource> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(source);
    return new MarketSurfaceController(null, null, null, provider, null);
  }

  private static QuoteGateway.Quote quote(String ltp, String prevClose) {
    return new QuoteGateway.Quote(
        new InstrumentKey("GLOBAL_INDEX", "DOWJONES"), bd(ltp), null, null, null, null,
        new QuoteGateway.Quote.Ohlc(null, null, null, bd(prevClose)),
        OffsetDateTime.of(2026, 6, 19, 9, 15, 0, 0, ZoneOffset.UTC));
  }

  @Test
  void dowMapsTheSignedDirectionFromLtpVsPrevClose() {
    GlobalQuoteSource source = mock(GlobalQuoteSource.class);
    when(source.latest(any())).thenReturn(Optional.of(quote("38500", "38000"))); // +500 → up
    DowQuote dow = controller(source).dow();
    assertThat(dow.ltp()).isEqualByComparingTo("38500");
    assertThat(dow.prevClose()).isEqualByComparingTo("38000");
    assertThat(dow.change()).isEqualByComparingTo("500");
    assertThat(dow.direction()).isEqualTo(1);
  }

  @Test
  void dowDirectionIsNegativeWhenBelowPrevClose() {
    GlobalQuoteSource source = mock(GlobalQuoteSource.class);
    when(source.latest(any())).thenReturn(Optional.of(quote("37800", "38000"))); // −200 → down
    assertThat(controller(source).dow().direction()).isEqualTo(-1);
  }

  @Test
  void dow422WhenTheGlobalFeedIsUnconfigured() {
    assertThatThrownBy(() -> controller(null).dow())
        .isInstanceOf(ApiException.class); // ObjectProvider returns no GlobalQuoteSource bean
  }

  @Test
  void dow422WhenTheQuoteIsAbsent() {
    GlobalQuoteSource source = mock(GlobalQuoteSource.class);
    when(source.latest(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> controller(source).dow()).isInstanceOf(ApiException.class);
  }
}
