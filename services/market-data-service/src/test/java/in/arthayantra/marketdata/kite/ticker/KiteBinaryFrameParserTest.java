package in.arthayantra.marketdata.kite.ticker;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.kite.RawTick;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The B-9 guard fixture tests: a mixed index+instrument frame parses with ZERO false unparsed
 * counts (the review's original guard missed the 28/32 B index layouts and would have alarmed all
 * session long); a deliberately corrupted packet increments the counter by exactly one. Raw frame
 * length is never judged — only per-packet sizes after the envelope split.
 */
class KiteBinaryFrameParserTest {

  private static final long INDEX_QUOTE_TOKEN = 256265; // 28 B
  private static final long INDEX_FULL_TOKEN = 260105; // 32 B
  private static final long INSTRUMENT_FULL_TOKEN = 100001; // 184 B
  private static final long INSTRUMENT_LTP_TOKEN = 100002; // 8 B
  private static final long INSTRUMENT_QUOTE_TOKEN = 100003; // 44 B

  private static final Map<Long, SubscriptionRegistry.ExpectedLayout> LAYOUTS =
      Map.of(
          INDEX_QUOTE_TOKEN, new SubscriptionRegistry.ExpectedLayout(SubscriptionMode.QUOTE, true),
          INDEX_FULL_TOKEN, new SubscriptionRegistry.ExpectedLayout(SubscriptionMode.FULL, true),
          INSTRUMENT_FULL_TOKEN,
              new SubscriptionRegistry.ExpectedLayout(SubscriptionMode.FULL, false),
          INSTRUMENT_LTP_TOKEN, new SubscriptionRegistry.ExpectedLayout(SubscriptionMode.LTP, false),
          INSTRUMENT_QUOTE_TOKEN,
              new SubscriptionRegistry.ExpectedLayout(SubscriptionMode.QUOTE, false));

  private static final Instant FIXED_NOW = Instant.parse("2026-02-03T05:45:00Z");

  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final KiteBinaryFrameParser parser =
      new KiteBinaryFrameParser(
          token -> Optional.ofNullable(LAYOUTS.get(token)),
          Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
          meters);

  private double unparsed() {
    return meters.counter("ay_kite_unparsed_frames_total").count();
  }

  // ---- fixture builders: Kite envelope = 2-byte count, then 2-byte length per packet ----

  private static byte[] frame(byte[]... packets) {
    int total = 2;
    for (byte[] p : packets) {
      total += 2 + p.length;
    }
    ByteBuffer buf = ByteBuffer.allocate(total);
    buf.putShort((short) packets.length);
    for (byte[] p : packets) {
      buf.putShort((short) p.length);
      buf.put(p);
    }
    return buf.array();
  }

  private static byte[] packet(long token, int size, int ltpPaise) {
    ByteBuffer p = ByteBuffer.allocate(size);
    p.putInt(0, (int) token);
    p.putInt(4, ltpPaise);
    return p.array();
  }

  private static byte[] instrumentFullPacket(long token, int ltpPaise, int volume, int oi, int ts) {
    byte[] raw = packet(token, 184, ltpPaise);
    ByteBuffer p = ByteBuffer.wrap(raw);
    p.putInt(16, volume);
    p.putInt(48, oi);
    p.putInt(60, ts);
    return raw;
  }

  @Test
  void mixedIndexAndInstrumentFrameParsesWithZeroFalsePositives() {
    byte[] mixed =
        frame(
            packet(INDEX_QUOTE_TOKEN, 28, 2_250_045), // NIFTY 22500.45 — 28 B index quote
            instrumentFullPacket(INSTRUMENT_FULL_TOKEN, 295_005, 123_456, 54_321, 1_770_100_000),
            packet(INSTRUMENT_LTP_TOKEN, 8, 50_000),
            packet(INDEX_FULL_TOKEN, 32, 4_810_010),
            packet(INSTRUMENT_QUOTE_TOKEN, 44, 99_995));

    List<RawTick> ticks = parser.parse(mixed);

    assertThat(ticks).hasSize(5);
    assertThat(unparsed()).as("zero false unparsed counts on a healthy mixed frame").isZero();
    assertThat(ticks.get(0).lastPrice()).isEqualByComparingTo("22500.45");
    assertThat(ticks.get(1).lastPrice()).isEqualByComparingTo("2950.05");
    assertThat(ticks.get(1).cumulativeDayVolume()).isEqualTo(123_456);
    assertThat(ticks.get(1).openInterest()).isEqualTo(54_321);
    assertThat(ticks.get(1).exchangeTimestamp()).isEqualTo(Instant.ofEpochSecond(1_770_100_000));
    assertThat(ticks.get(2).lastPrice()).isEqualByComparingTo("500.00");
    assertThat(ticks.get(4).cumulativeDayVolume()).isEqualTo(0); // quote volume offset present
  }

  @Test
  void corruptedPacketIncrementsTheCounterByExactlyOne() {
    byte[] frame =
        frame(
            packet(INDEX_QUOTE_TOKEN, 28, 2_250_045),
            // 44 B body for a token whose registry layout says 184 B FULL — corrupt
            packet(INSTRUMENT_FULL_TOKEN, 44, 295_005),
            packet(INSTRUMENT_LTP_TOKEN, 8, 50_000));

    List<RawTick> ticks = parser.parse(frame);

    assertThat(ticks).hasSize(2); // the healthy packets still parse
    assertThat(unparsed()).isEqualTo(1.0);
  }

  @Test
  void unsubscribedTokenCountsAsUnparsed() {
    byte[] frame = frame(packet(999_999, 44, 100_000));

    assertThat(parser.parse(frame)).isEmpty();
    assertThat(unparsed()).isEqualTo(1.0);
  }

  @Test
  void indexPacketsAreNeverJudgedAgainstInstrumentSizes() {
    // the FAIL criterion: a 28 B index packet must NOT be flagged because it isn't 44 B
    byte[] frame = frame(packet(INDEX_QUOTE_TOKEN, 28, 1_234_500));

    List<RawTick> ticks = parser.parse(frame);

    assertThat(ticks).hasSize(1);
    assertThat(unparsed()).isZero();
  }

  @Test
  void packetsWithoutTimestampsUseTheClock() {
    List<RawTick> ticks = parser.parse(frame(packet(INSTRUMENT_LTP_TOKEN, 8, 50_000)));

    assertThat(ticks.get(0).exchangeTimestamp()).isEqualTo(FIXED_NOW);
  }

  @Test
  void truncatedEnvelopeCountsAndStops() {
    // claims 2 packets but carries only one length prefix worth of bytes
    ByteBuffer buf = ByteBuffer.allocate(2 + 2 + 4);
    buf.putShort((short) 2);
    buf.putShort((short) 44); // promises 44 B, delivers 4
    buf.putInt((int) INSTRUMENT_QUOTE_TOKEN);

    assertThat(parser.parse(buf.array())).isEmpty();
    assertThat(unparsed()).isEqualTo(1.0);
  }
}
