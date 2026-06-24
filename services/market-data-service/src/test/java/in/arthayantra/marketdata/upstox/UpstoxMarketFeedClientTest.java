package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.google.protobuf.CodedOutputStream;
import in.arthayantra.marketdata.upstox.ws.UpstoxReconnectPolicy;
import in.arthayantra.marketdata.upstox.ws.UpstoxWebSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Supervision tests for {@link UpstoxMarketFeedClient} against a fake {@link UpstoxWebSocket} — no
 * real socket, no wall-clock sleep. Covers: first-connect fires onConnected (the replay trigger),
 * a drop drives a backoff-reconnect that re-fetches the authorize URL and fires onConnected AGAIN
 * (so the supervisor re-subscribes), the backoff schedule, and protobuf-frame → Tick delivery.
 */
class UpstoxMarketFeedClientTest {

  /** A scriptable socket: records sends, lets the test drive frames / disconnects. */
  static final class FakeSocket implements UpstoxWebSocket {
    final List<byte[]> sent = new ArrayList<>();
    boolean open;
    Consumer<byte[]> binarySink = b -> {};
    Runnable onDisconnected = () -> {};

    @Override
    public void connect() {
      open = true;
    }

    @Override
    public void close() {
      open = false;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void sendBinary(byte[] message) {
      sent.add(message);
    }

    @Override
    public void onBinaryMessage(Consumer<byte[]> sink) {
      this.binarySink = sink;
    }

    @Override
    public void onDisconnected(Runnable callback) {
      this.onDisconnected = callback;
    }

    @Override
    public void onError(Consumer<String> callback) {}

    void deliverFrame(byte[] frame) {
      binarySink.accept(frame);
    }
  }

  /** Builds a minimal full-mode FeedResponse frame for one instrument. */
  private static byte[] frame(String key, double ltp, long vtt, double oi) throws IOException {
    ByteArrayOutputStream ltpcBytes = new ByteArrayOutputStream();
    CodedOutputStream ltpc = CodedOutputStream.newInstance(ltpcBytes);
    ltpc.writeDouble(1, ltp);
    ltpc.flush();
    ByteArrayOutputStream mffBytes = new ByteArrayOutputStream();
    CodedOutputStream mff = CodedOutputStream.newInstance(mffBytes);
    mff.writeByteArray(1, ltpcBytes.toByteArray()); // ltpc
    mff.writeInt64(6, vtt); // vtt
    mff.writeDouble(7, oi); // oi
    mff.flush();
    ByteArrayOutputStream fullBytes = new ByteArrayOutputStream();
    CodedOutputStream full = CodedOutputStream.newInstance(fullBytes);
    full.writeByteArray(1, mffBytes.toByteArray()); // marketFF
    full.flush();
    ByteArrayOutputStream feedBytes = new ByteArrayOutputStream();
    CodedOutputStream feed = CodedOutputStream.newInstance(feedBytes);
    feed.writeByteArray(2, fullBytes.toByteArray()); // fullFeed
    feed.flush();
    ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
    CodedOutputStream entry = CodedOutputStream.newInstance(entryBytes);
    entry.writeString(1, key);
    entry.writeByteArray(2, feedBytes.toByteArray());
    entry.flush();
    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(frame);
    out.writeByteArray(2, entryBytes.toByteArray()); // FeedResponse.feeds
    out.flush();
    return frame.toByteArray();
  }

  @Test
  void firstConnectFiresOnConnectedSoTheSupervisorCanReplay() {
    AtomicInteger authorizeCalls = new AtomicInteger();
    FakeSocket socket = new FakeSocket();
    AtomicInteger connectedFires = new AtomicInteger();
    UpstoxMarketFeedClient client =
        new UpstoxMarketFeedClient(
            () -> {
              authorizeCalls.incrementAndGet();
              return "wss://x";
            },
            url -> socket,
            new UpstoxReconnectPolicy(),
            millis -> {});
    client.onConnected(connectedFires::incrementAndGet);

    client.start();

    assertThat(authorizeCalls.get()).isEqualTo(1);
    assertThat(socket.isOpen()).isTrue();
    assertThat(connectedFires.get()).as("onConnected fires on first connect").isEqualTo(1);
  }

  @Test
  void aDropDrivesBackoffReconnectThatReauthorizesAndFiresOnConnectedAgain() {
    AtomicInteger authorizeCalls = new AtomicInteger();
    // a NEW socket per (re)connect — mirrors the live one-socket-per-connect model
    List<FakeSocket> sockets = new ArrayList<>();
    Function<String, UpstoxWebSocket> factory =
        url -> {
          FakeSocket s = new FakeSocket();
          sockets.add(s);
          return s;
        };
    AtomicInteger connectedFires = new AtomicInteger();
    UpstoxMarketFeedClient client =
        new UpstoxMarketFeedClient(
            () -> {
              authorizeCalls.incrementAndGet();
              return "wss://x";
            },
            factory,
            new UpstoxReconnectPolicy(1, 1, 50),
            millis -> {}); // no real sleep
    client.onConnected(connectedFires::incrementAndGet);

    client.start();
    assertThat(connectedFires.get()).isEqualTo(1);

    // Drop the first socket (a real drop closes it, THEN fires onDisconnected): the reconnect loop
    // runs on a daemon thread; it re-authorizes + reopens a fresh socket.
    sockets.get(0).open = false;
    sockets.get(0).onDisconnected.run();

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              assertThat(authorizeCalls.get())
                  .as("reconnect re-fetches the authorize URL")
                  .isGreaterThanOrEqualTo(2);
              assertThat(connectedFires.get())
                  .as("onConnected fires again so the supervisor re-subscribes")
                  .isGreaterThanOrEqualTo(2);
              assertThat(client.isOpen()).isTrue();
            });

    client.stop();
  }

  @Test
  void decodesAFrameAndDeliversTheMappedTick() throws IOException {
    FakeSocket socket = new FakeSocket();
    List<UpstoxMarketFeedClient.Tick> received = new ArrayList<>();
    UpstoxMarketFeedClient client =
        new UpstoxMarketFeedClient(
            () -> "wss://x", url -> socket, new UpstoxReconnectPolicy(), millis -> {});
    client.onTicks(received::addAll);
    client.start();

    socket.deliverFrame(frame("NSE_FO|55512", 123.5, 4_200L, 9_100.0));

    assertThat(received).hasSize(1);
    UpstoxMarketFeedClient.Tick tick = received.get(0);
    assertThat(tick.instrumentKey()).isEqualTo("NSE_FO|55512");
    assertThat(tick.lastPrice()).isEqualTo(123.5, within(1e-9));
    assertThat(tick.volume()).isEqualTo(4_200L);
    assertThat(tick.openInterest()).isEqualTo(9_100L);
  }

  @Test
  void subscribeSendsAFullModeControlFrameWhenConnected() {
    FakeSocket socket = new FakeSocket();
    UpstoxMarketFeedClient client =
        new UpstoxMarketFeedClient(
            () -> "wss://x", url -> socket, new UpstoxReconnectPolicy(), millis -> {});
    client.start();

    client.subscribe(List.of("NSE_INDEX|Nifty 50"), "full");

    assertThat(socket.sent).hasSize(1);
    assertThat(new String(socket.sent.get(0), java.nio.charset.StandardCharsets.UTF_8))
        .contains("\"method\":\"sub\"")
        .contains("\"mode\":\"full\"")
        .contains("NSE_INDEX|Nifty 50");
  }

  @Test
  void stopClosesTheSocketAndHaltsReconnect() {
    FakeSocket socket = new FakeSocket();
    UpstoxMarketFeedClient client =
        new UpstoxMarketFeedClient(
            () -> "wss://x", url -> socket, new UpstoxReconnectPolicy(), millis -> {});
    client.start();
    assertThat(client.isOpen()).isTrue();

    client.stop();

    assertThat(client.isOpen()).isFalse();
    // a drop AFTER stop must not reconnect
    socket.onDisconnected.run();
    assertThat(client.isOpen()).isFalse();
  }
}
