package in.arthayantra.marketdata.kite.session;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The daily OAuth ritual endpoints (B-1 catalog / Phase 12). */
@RestController
@RequestMapping("/api/v1/auth/kite")
public class KiteAuthController {

  /** Manual exchange fallback body. */
  public record SessionRequest(String requestToken) {}

  private final KiteSessionService sessionService;
  private final String appOrigin;

  /** Wires the profile-bound session service. */
  public KiteAuthController(
      KiteSessionService sessionService,
      @Value("${artha.kite.app-origin:https://127.0.0.1:8080}") String appOrigin) {
    this.sessionService = sessionService;
    this.appOrigin = appOrigin;
  }

  /** The Zerodha login URL embedding the API key. */
  @GetMapping("/login-url")
  public Map<String, String> loginUrl() {
    return Map.of("url", sessionService.loginUrl());
  }

  /**
   * OAuth redirect target: exchange immediately (the request_token is single-use and dies in
   * minutes), then a minimal page that notifies the opener via ORIGIN-PINNED postMessage (fixing
   * v1's {@code '*'}) and closes the popup.
   */
  @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
  public String callback(@RequestParam("request_token") String requestToken) {
    sessionService.exchange(requestToken);
    String page =
        """
        <!doctype html>
        <html><body>
        <p>Kite connected — you can close this window.</p>
        <script>
          if (window.opener) {
            window.opener.postMessage({ type: 'kite-connected' }, '%s');
          }
          window.close();
        </script>
        </body></html>
        """;
    return page.formatted(appOrigin);
  }

  /** Manual token exchange fallback. */
  @PostMapping("/session")
  public KiteSessionService.ExchangeResult session(@RequestBody SessionRequest request) {
    return sessionService.exchange(request.requestToken());
  }

  /** Token/connection health. */
  @GetMapping("/status")
  public KiteSessionService.KiteStatus status() {
    return sessionService.status();
  }

  /** Invalidate the stored token. */
  @DeleteMapping("/session")
  public ResponseEntity<Void> invalidate() {
    sessionService.invalidate();
    return ResponseEntity.noContent().build();
  }
}
