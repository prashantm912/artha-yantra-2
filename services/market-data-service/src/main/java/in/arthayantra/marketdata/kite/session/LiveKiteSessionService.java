package in.arthayantra.marketdata.kite.session;

import in.arthayantra.marketdata.kite.KiteCallExecutor;

/** Live OAuth ritual (B-2 / Flow 1): exchange → encrypt → persist → status CONNECTED. */
public class LiveKiteSessionService implements KiteSessionService {

  private final SessionWireClient wireClient;
  private final KiteSessionStore store;
  private final SessionStatusPublisher statusPublisher;
  private final KiteCallExecutor executor;
  private final String loginUrlBase;
  private final String apiKey;

  /** Wires the ritual. */
  public LiveKiteSessionService(
      SessionWireClient wireClient,
      KiteSessionStore store,
      SessionStatusPublisher statusPublisher,
      KiteCallExecutor executor,
      String loginUrlBase,
      String apiKey) {
    this.wireClient = wireClient;
    this.store = store;
    this.statusPublisher = statusPublisher;
    this.executor = executor;
    this.loginUrlBase = loginUrlBase;
    this.apiKey = apiKey;
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
    return new ExchangeResult(true, session.kiteUserId(), store.tokenValidUntil());
  }

  @Override
  public KiteStatus status() {
    KiteSessionStore.State state = store.state();
    return new KiteStatus(
        state == KiteSessionStore.State.CONNECTED,
        "LIVE",
        state.name(),
        store.kiteUserId(),
        store.tokenValidUntil(),
        store.lastValidatedAt(),
        "NOT_CONNECTED", // live KiteTicker lands in Phase 13
        executor.kiteRestBreaker().getState().name());
  }

  @Override
  public void invalidate() {
    store.clear();
    statusPublisher.publish(KiteSessionStore.State.DISCONNECTED);
  }
}
