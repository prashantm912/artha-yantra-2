package in.arthayantra.marketdata.bse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Minimal anti-bot BSE fetcher: a browser User-Agent + a {@code bseindia.com} referer (BSE's Akamai
 * front blocks bare requests). Unlike NSE, BSE needs no cookie seeding for the bhavcopy download —
 * but on a non-trading day it returns HTTP 200 with the SPA homepage HTML rather than a 404, so the
 * caller must content-sniff the body (not the status) to decide "no file for this date".
 */
@Component
public class BseHttpClient {

  private static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/120.0.0.0 Safari/537.36";

  private final RestClient http;

  public BseHttpClient(
      RestClient.Builder builder,
      @Value("${artha.bse.base-url:https://www.bseindia.com}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(8_000);
    factory.setReadTimeout(20_000); // the UDiFF CSV is ~800 KB
    this.http =
        builder
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.USER_AGENT, UA)
            .defaultHeader(HttpHeaders.ACCEPT, "*/*")
            .defaultHeader(HttpHeaders.REFERER, baseUrl + "/")
            .build();
  }

  /** GET an absolute BSE URL, returning the raw body (CSV on a trading day, HTML otherwise). */
  public String getAbsolute(String url) {
    return http.get().uri(url).retrieve().body(String.class);
  }
}
