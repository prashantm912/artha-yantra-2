package in.arthayantra.marketdata.kite.session;

import in.arthayantra.marketdata.kite.KiteCallExecutor;
import in.arthayantra.marketdata.kite.canary.ContractCanary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Live OAuth ritual (B-2 / Flow 1): exchange → encrypt → persist → status CONNECTED. */
public class LiveKiteSessionService implements KiteSessionService {

  private static final Logger log = LoggerFactory.getLogger(LiveKiteSessionService.class);

  private final SessionWireClient wireClient;
  private final KiteSessionStore store;
  private final SessionStatusPublisher statusPublisher;
  private final KiteCallExecutor executor;
  private final String loginUrlBase;
  private final String apiKey;
  private final ContractCanary canary;
  private final java.util.function.BooleanSupplier tickerRunning;
  private final Runnable onSessionEstablished;

  /**
   * Wires the ritual; {@code tickerRunning} reads the live feed state lazily (no bean cycle) and
   * {@code onSessionEstablished} re-arms the ticker with the freshly-stored token after a login.
   */
  public LiveKiteSessionService(
      SessionWireClient wireClient,
      KiteSessionStore store,
      SessionStatusPublisher statusPublisher,
      KiteCallExecutor executor,
      String loginUrlBase,
      String apiKey,
      ContractCanary canary,
      java.util.function.BooleanSupplier tickerRunning,
      Runnable onSessionEstablished) {
    this.wireClient = wireClient;
    this.store = store;
    this.statusPublisher = statusPublisher;
    this.executor = executor;
    this.loginUrlBase = loginUrlBase;
    this.apiKey = apiKey;
    this.canary = canary;
    this.tickerRunning = tickerRunning;
    this.onSessionEstablished = onSessionEstablished;
  }

  @Override
  public String loginUrl() {
    return loginUrlBase + "?v=3&api_key=" + apiKey;
  }

  @Override
  public ExchangeResult exchange(String requestToken) {
    SessionWireClient.TokenSession session = wireClient.exchange(requestToken);
    store.store(session.accessToken(), session.kiteUserId());
    statusPublisher.publish(KiteSessionStore.State.CONNECTED);
    rearmFeed();
    return new ExchangeResult(true, session.kiteUserId(), store.tokenValidUntil());
  }

  /**
   * Arm the live ticker with the just-stored token. The login already succeeded by here, so a feed
   * hiccup must not fail the ritual — the token is persisted and the ticker's own reconnect retries.
   */
  private void rearmFeed() {
    try {
      onSessionEstablished.run();
    } catch (RuntimeException feedHiccup) {
      log.warn(
          "feed re-arm after login failed (token stored; ticker will retry): {}",
          feedHiccup.getMessage());
    }
  }

  @Override
  public KiteStatus status() {
    KiteSessionStore.State state = store.state();
    var canaryResult = canary.lastResult();
    return new KiteStatus(
        state == KiteSessionStore.State.CONNECTED,
        "LIVE",
        state.name(),
        store.kiteUserId(),
        store.tokenValidUntil(),
        store.lastValidatedAt(),
        tickerRunning.getAsBoolean() ? "CONNECTED" : "DISCONNECTED",
        executor.kiteRestBreaker().getState().name(),
        canaryResult.map(ContractCanary.CanaryResult::lastContractCheck).orElse(null),
        canaryResult.map(ContractCanary.CanaryResult::drift).orElse(java.util.List.of()));
  }

  @Override
  public void invalidate() {
    store.clear();
    statusPublisher.publish(KiteSessionStore.State.DISCONNECTED);
  }
}
