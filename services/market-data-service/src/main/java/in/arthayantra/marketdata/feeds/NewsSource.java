package in.arthayantra.marketdata.feeds;

import java.util.List;

/**
 * Port for per-stock news / announcements (the {@code upstox} adapter wraps {@code GET /v2/news}).
 * Source-agnostic so the {@code nse} news service depends on this neutral port, not the Upstox client
 * — keeping {@code nse → upstox} out of the module graph.
 */
public interface NewsSource {

  /** One news article: headline + summary + thumbnail + link + publish epoch-millis. */
  record NewsArticle(
      String heading, String summary, String thumbnail, String articleLink, Long publishedTime) {}

  /** The latest articles for an Upstox instrument key (empty when the source returns none). */
  List<NewsArticle> news(String instrumentKey, int pageSize);
}
