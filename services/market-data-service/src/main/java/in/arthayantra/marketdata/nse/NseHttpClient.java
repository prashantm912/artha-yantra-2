package in.arthayantra.marketdata.nse;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Minimal anti-bot NSE fetcher: a browser User-Agent + referer. Reused by every NSE-EOD source.
 * NSE's data/api endpoints serve with a UA; the homepage 403s and is not needed for these.
 */
@Component
public class NseHttpClient {

  private static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/120.0.0.0 Safari/537.36";

  private final RestClient http;
  private final String baseUrl;

  public NseHttpClient(
      RestClient.Builder builder,
      @Value("${artha.nse.base-url:https://www.nseindia.com}") String baseUrl) {
    this.baseUrl = baseUrl;
    // Fail fast — a blocked/blackholed NSE must never hang a startup pull or an IT.
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(8_000);
    factory.setReadTimeout(12_000);
    this.http =
        builder
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.USER_AGENT, UA)
            .defaultHeader(HttpHeaders.ACCEPT, "*/*")
            .defaultHeader(HttpHeaders.REFERER, baseUrl + "/")
            .build();
  }

  /** GET a path under the NSE base URL, returning the raw body (JSON/CSV). */
  public String get(String path) {
    return http.get().uri(baseUrl + path).retrieve().body(String.class);
  }

  /**
   * GET an absolute URL (e.g. the {@code nsearchives.nseindia.com} archive host), reusing the same
   * anti-bot headers + fail-fast timeouts. The archive host needs the browser UA + referer too.
   */
  public String getAbsolute(String url) {
    return http.get().uri(url).retrieve().body(String.class);
  }

  /**
   * GET an {@code /api/...} path that NSE gates behind anti-bot cookies (e.g.
   * {@code corporates-corporateActions}): first hit the base "/" to harvest {@code Set-Cookie}
   * (tolerating its 403 via {@code exchange}, which does not throw on 4xx), then replay those
   * cookies on the API call. {@code referer} overrides the default for endpoints that check it.
   */
  public String getWithCookieSeed(String apiPath, String referer) {
    List<String> setCookies =
        http.get()
            .uri(baseUrl + "/")
            .exchange((request, response) -> response.getHeaders().get(HttpHeaders.SET_COOKIE));
    String cookieHeader =
        setCookies == null
            ? ""
            : setCookies.stream().map(c -> c.split(";", 2)[0]).collect(Collectors.joining("; "));
    return http.get()
        .uri(baseUrl + apiPath)
        .header(HttpHeaders.COOKIE, cookieHeader)
        .header(HttpHeaders.REFERER, referer)
        .header(HttpHeaders.ACCEPT, "application/json")
        .retrieve()
        .body(String.class);
  }
}
