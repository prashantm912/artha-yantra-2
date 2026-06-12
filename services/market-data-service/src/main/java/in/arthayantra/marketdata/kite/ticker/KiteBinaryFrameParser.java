package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.marketdata.kite.RawTick;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * The B-9 binary-frame guard + parser. Two rules keep it honest: (1) SPLIT FIRST — a Kite frame
 * is a 2-byte packet count then a 2-byte length prefix per packet; raw frame length is NEVER
 * judged. (2) The expected packet size comes from the registry {@code (mode, is_index)} — known
 * layouts are {8, 28, 32, 44, 184} bytes, and index instruments use 28/32 B, not 44/184 B (the
 * review's original guard missed those and would have alarmed all session long). Packets matching
 * no expected layout count into {@code ay_kite_unparsed_frames_total} and are skipped.
 */
public class KiteBinaryFrameParser {

  /** Kite quotes NSE/NFO prices in paise — divide by 100. */
  private static final BigDecimal PRICE_DIVISOR = new BigDecimal(100);

  private final LongFunction<Optional<SubscriptionRegistry.ExpectedLayout>> layoutResolver;
  private final Clock clock;
  private final Counter unparsedFrames;

  /** Wires the per-token layout lookup + the guard counter. */
  public KiteBinaryFrameParser(
      LongFunction<Optional<SubscriptionRegistry.ExpectedLayout>> layoutResolver,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.layoutResolver = layoutResolver;
    this.clock = clock;
    this.unparsedFrames = meterRegistry.counter("ay_kite_unparsed_frames_total");
  }

  /** Splits and parses one WS frame; guard mismatches are counted, never thrown. */
  public List<RawTick> parse(byte[] frame) {
    List<RawTick> ticks = new ArrayList<>();
    if (frame == null || frame.length < 2) {
      unparsedFrames.increment();
      return ticks;
    }
    ByteBuffer buffer = ByteBuffer.wrap(frame); // big-endian, Kite wire order
    int packetCount = Short.toUnsignedInt(buffer.getShort());
    for (int i = 0; i < packetCount; i++) {
      if (buffer.remaining() < 2) {
        unparsedFrames.increment();
        return ticks;
      }
      int length = Short.toUnsignedInt(buffer.getShort());
      if (buffer.remaining() < length) {
        unparsedFrames.increment();
        return ticks;
      }
      byte[] packet = new byte[length];
      buffer.get(packet);
      parsePacket(packet).ifPresent(ticks::add);
    }
    return ticks;
  }

  private Optional<RawTick> parsePacket(byte[] packet) {
    if (packet.length < 8) {
      unparsedFrames.increment();
      return Optional.empty();
    }
    ByteBuffer buf = ByteBuffer.wrap(packet);
    long token = Integer.toUnsignedLong(buf.getInt(0));
    Optional<SubscriptionRegistry.ExpectedLayout> layout = layoutResolver.apply(token);
    if (layout.isEmpty() || packet.length != layout.get().expectedBytes()) {
      unparsedFrames.increment();
      return Optional.empty();
    }
    BigDecimal lastPrice = price(buf.getInt(4));
    boolean index = layout.get().index();
    return Optional.of(
        switch (layout.get().mode()) {
          case LTP -> new RawTick(token, lastPrice, 0, null, clock.instant());
          case QUOTE ->
              index
                  ? new RawTick(token, lastPrice, 0, null, clock.instant())
                  : new RawTick(token, lastPrice, Integer.toUnsignedLong(buf.getInt(16)), null, clock.instant());
          case FULL ->
              index
                  ? new RawTick(token, lastPrice, 0, null, epochSeconds(buf.getInt(28)))
                  : new RawTick(
                      token,
                      lastPrice,
                      Integer.toUnsignedLong(buf.getInt(16)),
                      Integer.toUnsignedLong(buf.getInt(48)),
                      epochSeconds(buf.getInt(60)));
        });
  }

  private static BigDecimal price(int paise) {
    return new BigDecimal(paise).divide(PRICE_DIVISOR);
  }

  private Instant epochSeconds(int seconds) {
    return seconds > 0 ? Instant.ofEpochSecond(Integer.toUnsignedLong(seconds)) : clock.instant();
  }
}
