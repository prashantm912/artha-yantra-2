package in.arthayantra.marketdata.kite.session;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The 5-min session health probe (B-2 step 4): lightweight {@code getProfile} → LIVE / EXPIRED /
 * ERROR → Redis key + {@code ay_kite_session_valid} gauge. EXPIRED clears the in-memory token so
 * every outbound Kite call short-circuits with {@code KITE_TOKEN_EXPIRED} and retries are
 * suppressed — re-login is the cure (B-2 step 5).
 */
public class SessionHealthProbe {

  private final SessionWireClient wireClient;
  private final KiteSessionStore store;
  private final SessionStatusPublisher statusPublisher;

  /** Wires the probe + the validity gauge. */
  public SessionHealthProbe(
      SessionWireClient wireClient,
      KiteSessionStore store,
      SessionStatusPublisher statusPublisher,
      MeterRegistry meterRegistry) {
    this.wireClient = wireClient;
    this.store = store;
    this.statusPublisher = statusPublisher;
    meterRegistry.gauge(
        "ay_kite_session_valid",
        store,
        s -> s.state() == KiteSessionStore.State.CONNECTED ? 1.0 : 0.0);
  }

  /** 5-min cadence; the first run lands shortly after startup so restarts re-validate fast. */
  @Scheduled(fixedDelay = 300_000, initialDelay = 10_000)
  public void scheduledProbe() {
    probeNow();
  }

  /** One probe pass — also callable from tests and the Phase 16 schedulers. */
  public KiteSessionStore.State probeNow() {
    Optional<String> token = store.currentToken();
    if (token.isEmpty()) {
      // nothing to validate: keep TOKEN_EXPIRED sticky, otherwise we are simply disconnected
      KiteSessionStore.State state =
          store.state() == KiteSessionStore.State.TOKEN_EXPIRED
              ? KiteSessionStore.State.TOKEN_EXPIRED
              : KiteSessionStore.State.DISCONNECTED;
      statusPublisher.publish(state);
      return state;
    }
    switch (wireClient.probe(token.get())) {
      case VALID -> store.markValid();
      case EXPIRED -> store.markExpired();
      default -> store.markError();
    }
    statusPublisher.publish(store.state());
    return store.state();
  }
}
