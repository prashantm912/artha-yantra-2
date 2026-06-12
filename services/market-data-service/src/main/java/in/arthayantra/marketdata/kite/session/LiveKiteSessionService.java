package in.arthayantra.marketdata.kite.session;

import in.arthayantra.marketdata.kite.KiteCallExecutor;
import in.arthayantra.marketdata.kite.canary.ContractCanary;

/** Live OAuth ritual (B-2 / Flow 1): exchange → encrypt → persist → status CONNECTED. */
public class LiveKiteSessionService implements KiteSessionService {

  private final SessionWireClient wireClient;
  private final KiteSessionStore store;
  private final SessionStatusPublisher statusPublisher;
  private final KiteCallExecutor executor;
  private final String loginUrlBase;
  private final String apiKey;
  private final ContractCanary canary;

  /** Wires the ritual. */
  public LiveKiteSessionService(
      SessionWireClient wireClient,
      KiteSessionStore store,
      SessionStatusPublisher statusPublisher,
      KiteCallExecutor executor,
      String loginUrlBase,
      String apiKey,
      ContractCanary canary) {
    this.wireClient = wireClient;
    this.store = store;
    this.statusPublisher = statusPublisher;
    this.executor = executor;
    this.loginUrlBase = loginUrlBase;
    this.apiKey = apiKey;
    this.canary = canary;
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
    var canaryResult = canary.lastResult();
    return new KiteStatus(
        state == KiteSessionStore.State.CONNECTED,
        "LIVE",
        state.name(),
        store.kiteUserId(),
        store.tokenValidUntil(),
        store.lastValidatedAt(),
        "NOT_CONNECTED", // ticker state surfaces once the live feed is started (Phase 13 wiring)
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
