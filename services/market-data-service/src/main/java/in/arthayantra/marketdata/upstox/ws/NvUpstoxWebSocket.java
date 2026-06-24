package in.arthayantra.marketdata.upstox.ws;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * nv-websocket-client wrapper for one Upstox v3 market-data socket (Wave U3) — the ONLY class
 * touching the WS library surface (the analog of {@code KiteTickerHandle}). Untested by design
 * (thin glue); all reconnect/backoff/decode logic lives in {@code UpstoxMarketFeedClient} against
 * the {@link UpstoxWebSocket} seam. nv-websocket-client is already on the classpath (transitive via
 * kiteconnect), so no new WS dependency. The wss URL is the single-use, pre-authorized {@code
 * authorized_redirect_uri} from the authorize-GET — no auth header on the socket itself.
 */
public final class NvUpstoxWebSocket implements UpstoxWebSocket {

  /** Connection-handshake timeout; the supervisor's backoff handles a slow/refused connect. */
  private static final int CONNECT_TIMEOUT_MILLIS = 15_000;

  private final WebSocket socket;
  private volatile Consumer<byte[]> binarySink = b -> {};
  private volatile Runnable onDisconnected = () -> {};
  private volatile Consumer<String> onError = m -> {};

  /** Builds (but does not connect) a socket to the pre-authorized wss URL. */
  public NvUpstoxWebSocket(String authorizedWssUrl) {
    try {
      this.socket =
          new WebSocketFactory()
              .setConnectionTimeout(CONNECT_TIMEOUT_MILLIS)
              .createSocket(authorizedWssUrl);
      this.socket.addListener(
          new WebSocketAdapter() {
            @Override
            public void onBinaryMessage(WebSocket ws, byte[] binary) {
              binarySink.accept(binary);
            }

            @Override
            public void onDisconnected(
                WebSocket ws,
                WebSocketFrame serverClose,
                WebSocketFrame clientClose,
                boolean closedByServer) {
              onDisconnected.run();
            }

            @Override
            public void onError(WebSocket ws, WebSocketException cause) {
              onError.accept(cause.getMessage());
            }
          });
    } catch (IOException e) {
      throw new IllegalStateException("failed to build Upstox WS to " + authorizedWssUrl, e);
    }
  }

  @Override
  public void connect() {
    try {
      socket.connect();
    } catch (WebSocketException e) {
      throw new IllegalStateException("Upstox WS connect failed: " + e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    socket.disconnect();
  }

  @Override
  public boolean isOpen() {
    return socket.isOpen();
  }

  @Override
  public void sendBinary(byte[] message) {
    socket.sendBinary(message);
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
  public void onError(Consumer<String> callback) {
    this.onError = callback;
  }
}
