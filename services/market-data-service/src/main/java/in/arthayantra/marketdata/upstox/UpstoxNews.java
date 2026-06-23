package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Wire mirror of Upstox {@code GET /v2/news} ({@code category=instrument_keys}). {@code data} is keyed
 * by instrument key → the last-7-days news articles. {@code @JsonIgnoreProperties(ignoreUnknown=true)}
 * so a field Upstox adds never crashes the feed (the kite/wire convention).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxNews(String status, Map<String, List<Article>> data) {

  /** One news article: headline + summary + thumbnail + link + publish epoch-millis. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Article(
      String heading,
      String summary,
      String thumbnail,
      @JsonProperty("article_link") String articleLink,
      @JsonProperty("published_time") Long publishedTime) {}
}
