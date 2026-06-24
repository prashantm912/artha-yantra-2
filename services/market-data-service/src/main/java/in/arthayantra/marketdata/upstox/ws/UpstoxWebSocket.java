package in.arthayantra.marketdata.upstox.ws;

import java.util.function.Consumer;

/**
 * The thin seam over ONE Upstox v3 WebSocket connection (Wave U3) — the analog of the Kite {@code
 * TickerHandle}: the nv-websocket-client wrapper implements it for live, a fake implements it in
 * tests, so the reconnect/backoff supervision in {@code UpstoxMarketFeedClient} is plain testable
 * code against this interface (no real socket in CI). One instance == one socket; the supervisor
 * builds a fresh one per (re)connect via a {@code Supplier<UpstoxWebSocket>} factory.
 */
public interface UpstoxWebSocket {

  /** Opens the socket (blocking handshake); throws on immediate failure. */
  void connect();

  /** Closes the socket. Idempotent. */
  void close();

  /** Whether the socket is currently open. */
  boolean isOpen();

  /** Sends a control message (the subscribe/unsubscribe JSON) as a BINARY frame. */
  void sendBinary(byte[] message);

  /** Registers the binary-frame (protobuf {@code FeedResponse}) sink. */
  void onBinaryMessage(Consumer<byte[]> sink);

  /** Registers the disconnect callback (fires when the socket drops). */
  void onDisconnected(Runnable callback);

  /** Registers the error sink (message only). */
  void onError(Consumer<String> callback);
}
