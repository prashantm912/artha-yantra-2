package in.arthayantra.marketdata.nse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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
    this.http =
        builder
            .defaultHeader(HttpHeaders.USER_AGENT, UA)
            .defaultHeader(HttpHeaders.ACCEPT, "*/*")
            .defaultHeader(HttpHeaders.REFERER, baseUrl + "/")
            .build();
  }

  /** GET a path under the NSE base URL, returning the raw body (JSON/CSV). */
  public String get(String path) {
    return http.get().uri(baseUrl + path).retrieve().body(String.class);
  }
}
