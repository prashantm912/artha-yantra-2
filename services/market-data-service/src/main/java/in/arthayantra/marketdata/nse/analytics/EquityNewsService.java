package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketdata.constituents.StockUpstoxKeyMap;
import in.arthayantra.marketdata.feeds.NewsSource;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Per-stock news / announcements via the neutral {@link NewsSource} port (the Upstox adapter wraps
 * {@code GET /v2/news} — the analytics token covers it, verified live). The symbol → Upstox
 * instrument key comes from the static {@link StockUpstoxKeyMap}. The Upstox news source is {@code
 * live}-only ({@code @Profile("live")} + {@code analytics.enabled}); when it is absent (mock / not
 * configured) or the call fails, {@code available=false} with no items so the page degrades cleanly
 * rather than erroring.
 */
@Service
public class EquityNewsService {

  private static final int PAGE_SIZE = 30;

  private final ObjectProvider<NewsSource> newsSource;
  private final StockUpstoxKeyMap keyMap;

  public EquityNewsService(ObjectProvider<NewsSource> newsSource, StockUpstoxKeyMap keyMap) {
    this.newsSource = newsSource;
    this.keyMap = keyMap;
  }

  /** One news article (Upstox), publishedTime = epoch-millis. */
  public record NewsItem(
      String heading, String summary, String thumbnail, String articleLink, Long publishedTime) {}

  /** A symbol's news; {@code available=false} when the Upstox news source isn't live. */
  public record News(String symbol, boolean available, List<NewsItem> items) {}

  public News news(String symbolRaw) {
    String symbol = symbolRaw.trim().toUpperCase();
    String key = keyMap.key(symbol);
    if (key == null) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_INSTRUMENT, "no Upstox instrument key for symbol " + symbol);
    }
    NewsSource source = newsSource.getIfAvailable();
    if (source == null) {
      return new News(symbol, false, List.of()); // news source not configured (mock / off)
    }
    try {
      List<NewsItem> items =
          source.news(key, PAGE_SIZE).stream()
              .map(
                  (NewsSource.NewsArticle a) ->
                      new NewsItem(
                          a.heading(), a.summary(), a.thumbnail(), a.articleLink(), a.publishedTime()))
              .toList();
      return new News(symbol, true, items);
    } catch (RuntimeException e) {
      return new News(symbol, false, List.of()); // transport/HTTP error → degrade cleanly
    }
  }
}
