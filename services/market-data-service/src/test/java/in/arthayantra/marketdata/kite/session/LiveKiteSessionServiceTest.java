package in.arthayantra.marketdata.kite.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** B-2: a successful login persists the token, flips status, and re-arms the live ticker. */
class LiveKiteSessionServiceTest {

  /** Records the token written by exchange() without touching crypto/DB. */
  static class RecordingStore extends KiteSessionStore {
    String storedToken;
    String storedUser;

    RecordingStore() {
      super(null, null);
    }

    @Override
    public void store(String accessToken, String userId) {
      this.storedToken = accessToken;
      this.storedUser = userId;
    }

    @Override
    public OffsetDateTime tokenValidUntil() {
      return OffsetDateTime.of(2026, 6, 16, 6, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    }
  }

  /** Records the published state without touching Redis. */
  static class RecordingPublisher extends SessionStatusPublisher {
    KiteSessionStore.State published;

    RecordingPublisher() {
      super(null);
    }

    @Override
    public void publish(KiteSessionStore.State state) {
      this.published = state;
    }
  }

  private static SessionWireClient wireReturning(String token, String user) {
    return new SessionWireClient() {
      @Override
      public TokenSession exchange(String requestToken) {
        return new TokenSession(token, user);
      }

      @Override
      public ProbeResult probe(String accessToken) {
        return ProbeResult.VALID;
      }
    };
  }

  @Test
  void exchangeStoresTheTokenPublishesConnectedAndReArmsTheFeed() {
    RecordingStore store = new RecordingStore();
    RecordingPublisher publisher = new RecordingPublisher();
    AtomicInteger reArms = new AtomicInteger();

    LiveKiteSessionService service =
        new LiveKiteSessionService(
            wireReturning("fresh-access-token", "KU42"),
            store,
            publisher,
            null,
            "https://kite/login",
            "api-key",
            null,
            () -> false,
            reArms::incrementAndGet);

    KiteSessionService.ExchangeResult result = service.exchange("req-token");

    assertThat(result.connected()).isTrue();
    assertThat(result.kiteUserId()).isEqualTo("KU42");
    assertThat(store.storedToken).isEqualTo("fresh-access-token");
    assertThat(publisher.published).isEqualTo(KiteSessionStore.State.CONNECTED);
    assertThat(reArms.get()).as("login must re-arm the live ticker exactly once").isEqualTo(1);
  }

  @Test
  void aFeedReArmHiccupDoesNotFailTheLogin() {
    LiveKiteSessionService service =
        new LiveKiteSessionService(
            wireReturning("tok", "KU1"),
            new RecordingStore(),
            new RecordingPublisher(),
            null,
            "u",
            "k",
            null,
            () -> false,
            () -> {
              throw new IllegalStateException("ws down");
            });

    // The login still succeeds — the token is stored and the ticker retries on its own.
    assertThat(service.exchange("req").connected()).isTrue();
  }
}
