package in.arthayantra.gateway.ws;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.gateway.ws.StompWebSocketHandler.Pending;
import in.arthayantra.gateway.ws.StompWebSocketHandler.SessionState;
import org.junit.jupiter.api.Test;

/**
 * The per-session passthrough cap (audit P2): a stalled consumer can't grow the queue without
 * bound (the OOM that would take the single gateway down); overflow sheds the OLDEST frame, and a
 * frame enqueued after the shed is retained (a live consumer keeps flowing).
 */
class SessionStatePassthroughCapTest {

  private static Pending frame(int i) {
    return new Pending("/topic/candles.1m.NSE.X", "sub-1", "body-" + i, i);
  }

  @Test
  void boundsAtTheCapAndShedsOldestFirst() {
    SessionState state = new SessionState();
    long shed = 0;
    for (int i = 0; i < StompWebSocketHandler.MAX_PASSTHROUGH + 500; i++) {
      shed += state.offerBounded(frame(i));
    }

    assertThat(state.passthroughSize.get()).isEqualTo(StompWebSocketHandler.MAX_PASSTHROUGH);
    assertThat(shed).isEqualTo(500);
    assertThat(state.droppedFrames.get()).isEqualTo(500);

    // the oldest survivor is frame #500 (0..499 were shed); drain confirms FIFO order held
    Pending oldest = state.pollPassthrough();
    assertThat(oldest.body()).isEqualTo("body-500");
  }

  @Test
  void underTheCapNothingIsShed() {
    SessionState state = new SessionState();
    long shed = 0;
    for (int i = 0; i < 10; i++) {
      shed += state.offerBounded(frame(i));
    }
    assertThat(shed).isZero();
    assertThat(state.droppedFrames.get()).isZero();
    assertThat(state.passthroughSize.get()).isEqualTo(10);
  }

  @Test
  void disposeResetsTheSizeCounter() {
    SessionState state = new SessionState();
    state.offerBounded(frame(1));
    state.dispose();
    assertThat(state.passthroughSize.get()).isZero();
    assertThat(state.passthrough).isEmpty();
  }
}
