package in.arthayantra.marketdata.nse.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketdata.constituents.StockUpstoxKeyMap;
import in.arthayantra.marketdata.feeds.NewsSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EquityNewsServiceTest {

  @SuppressWarnings("unchecked")
  private final ObjectProvider<NewsSource> provider = mock(ObjectProvider.class);
  private final NewsSource source = mock(NewsSource.class);
  private final StockUpstoxKeyMap keyMap = mock(StockUpstoxKeyMap.class);
  private final EquityNewsService service = new EquityNewsService(provider, keyMap);

  @Test
  void mapsUpstoxNewsWhenClientPresent() {
    when(keyMap.key("RELIANCE")).thenReturn("NSE_EQ|INE002A01018");
    when(provider.getIfAvailable()).thenReturn(source);
    when(source.news(eq("NSE_EQ|INE002A01018"), anyInt()))
        .thenReturn(
            List.of(
                new NewsSource.NewsArticle(
                    "Reliance up", "summary", "thumb", "http://x", 1782124170137L)));

    EquityNewsService.News n = service.news("reliance");

    assertThat(n.symbol()).isEqualTo("RELIANCE");
    assertThat(n.available()).isTrue();
    assertThat(n.items()).hasSize(1);
    assertThat(n.items().get(0).heading()).isEqualTo("Reliance up");
    assertThat(n.items().get(0).publishedTime()).isEqualTo(1782124170137L);
  }

  @Test
  void unavailableWhenClientAbsent() {
    when(keyMap.key("RELIANCE")).thenReturn("NSE_EQ|INE002A01018");
    when(provider.getIfAvailable()).thenReturn(null); // mock / Upstox news source off

    EquityNewsService.News n = service.news("RELIANCE");

    assertThat(n.available()).isFalse();
    assertThat(n.items()).isEmpty();
  }

  @Test
  void unknownSymbolIsNotFound() {
    when(keyMap.key("BOGUS")).thenReturn(null);
    assertThatThrownBy(() -> service.news("BOGUS")).isInstanceOf(NotFoundException.class);
  }
}
