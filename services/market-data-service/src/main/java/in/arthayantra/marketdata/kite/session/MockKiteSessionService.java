package in.arthayantra.marketdata.kite.session;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Mock profile bypasses the OAuth ritual entirely (D13 — no credentials exist): status is always
 * connected, the ritual endpoints answer 503 {@code NOT_CONFIGURED}, and no Kite variable is ever
 * demanded (Phase 12 FAIL criterion).
 */
@Service
@Profile("mock")
public class MockKiteSessionService implements KiteSessionService {

  private static ApiException notConfigured() {
    return new ApiException(
        503, ErrorCodes.NOT_CONFIGURED, "the Kite OAuth ritual does not exist in mock mode");
  }

  @Override
  public String loginUrl() {
    throw notConfigured();
  }

  @Override
  public ExchangeResult exchange(String requestToken) {
    throw notConfigured();
  }

  @Override
  public KiteStatus status() {
    return new KiteStatus(
        true, "MOCK", "CONNECTED", null, null, null, "MOCK", "CLOSED", null, java.util.List.of());
  }

  @Override
  public void invalidate() {
    throw notConfigured();
  }
}
