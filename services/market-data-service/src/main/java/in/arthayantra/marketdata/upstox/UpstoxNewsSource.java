package in.arthayantra.marketdata.upstox;

import in.arthayantra.marketdata.feeds.NewsSource;
import java.util.List;

/**
 * Upstox adapter for the neutral {@link NewsSource} port — wraps {@link UpstoxAnalyticsClient#news}
 * ({@code GET /v2/news}) and maps each {@link UpstoxNews.Article} to a source-agnostic {@link
 * NewsSource.NewsArticle}. Bound only in {@code live} with the analytics token enabled (same gating
 * as the client); its absence is what makes {@code EquityNewsService} report {@code available=false}.
 */
public final class UpstoxNewsSource implements NewsSource {

  private final UpstoxAnalyticsClient client;

  public UpstoxNewsSource(UpstoxAnalyticsClient client) {
    this.client = client;
  }

  @Override
  public List<NewsArticle> news(String instrumentKey, int pageSize) {
    return client.news(instrumentKey, pageSize).stream()
        .map(
            (UpstoxNews.Article a) ->
                new NewsArticle(
                    a.heading(), a.summary(), a.thumbnail(), a.articleLink(), a.publishedTime()))
        .toList();
  }
}
