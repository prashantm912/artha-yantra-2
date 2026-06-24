package in.arthayantra.marketdata.upstox.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the hand-rolled {@link FeedFrameDecoder} against the v3 {@code FeedResponse} wire
 * format. The fixtures are built with protobuf-java's symmetric low-level {@link CodedOutputStream}
 * (the exact encoder the Upstox server uses), so a passing decode proves byte-level field-number
 * fidelity without needing {@code protoc}-generated classes.
 */
class FeedFrameDecoderTest {

  // ---- minimal protobuf encoders (mirror the field numbers in MarketDataFeedV3.proto) ----

  private static byte[] ltpc(double ltp, long ltt, double cp) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeDouble(1, ltp); // ltp
    out.writeInt64(2, ltt); // ltt
    out.writeDouble(4, cp); // cp
    out.flush();
    return bytes.toByteArray();
  }

  private static byte[] marketFullFeed(byte[] ltpc, long vtt, double oi) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeByteArray(1, ltpc); // ltpc (length-delimited message)
    out.writeInt64(6, vtt); // vtt
    out.writeDouble(7, oi); // oi
    out.flush();
    return bytes.toByteArray();
  }

  private static byte[] indexFullFeed(byte[] ltpc) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeByteArray(1, ltpc); // ltpc
    out.flush();
    return bytes.toByteArray();
  }

  /** A {@code FullFeed} carrying {@code marketFF} (field 1) or {@code indexFF} (field 2). */
  private static byte[] fullFeed(int branchField, byte[] inner) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeByteArray(branchField, inner);
    out.flush();
    return bytes.toByteArray();
  }

  /** A {@code Feed} carrying {@code fullFeed} (field 2) + {@code requestMode} (field 4 enum). */
  private static byte[] feedWithFullFeed(byte[] fullFeed, int requestMode) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeByteArray(2, fullFeed); // fullFeed
    out.writeEnum(4, requestMode); // requestMode — an unknown-to-us scalar the decoder must skip
    out.flush();
    return bytes.toByteArray();
  }

  /** A {@code Feed} carrying a bare {@code ltpc} (field 1) — the ltpc-mode shape. */
  private static byte[] feedWithLtpc(byte[] ltpc) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeByteArray(1, ltpc);
    out.flush();
    return bytes.toByteArray();
  }

  /** Writes one {@code map<string, Feed>} entry under {@code FeedResponse.feeds} (field 2). */
  private static void writeFeedEntry(CodedOutputStream out, String key, byte[] feed)
      throws IOException {
    ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
    CodedOutputStream entry = CodedOutputStream.newInstance(entryBytes);
    entry.writeString(1, key); // key
    entry.writeByteArray(2, feed); // value
    entry.flush();
    out.writeByteArray(2, entryBytes.toByteArray()); // FeedResponse.feeds (repeated map entry)
  }

  /** A FeedResponse with the given {@code instrument_key → encoded-Feed} entries + a currentTs. */
  private static byte[] feedResponse(long currentTs, java.util.Map<String, byte[]> entries)
      throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeEnum(1, 1); // FeedResponse.type = live_feed — a scalar the decoder must skip
    for (java.util.Map.Entry<String, byte[]> e : entries.entrySet()) {
      writeFeedEntry(out, e.getKey(), e.getValue());
    }
    out.writeInt64(3, currentTs); // currentTs
    out.flush();
    return bytes.toByteArray();
  }

  // ----------------------------------- tests -----------------------------------

  @Test
  void decodesAMarketFullFeedInstrumentWithVolumeAndOpenInterest() throws IOException {
    byte[] mff = marketFullFeed(ltpc(123.45, 1_700_000_000_000L, 120.0), 98_765L, 4_321.0);
    byte[] frame =
        feedResponse(
            1_700_000_000_500L,
            java.util.Map.of(
                "NSE_FO|55512", feedWithFullFeed(fullFeed(1, mff), 1 /* full_d5 */)));

    DecodedFeed decoded = FeedFrameDecoder.decode(frame);

    assertThat(decoded.serverTimestampMillis()).isEqualTo(1_700_000_000_500L);
    DecodedFeed.Tick tick = decoded.feeds().get("NSE_FO|55512");
    assertThat(tick).isNotNull();
    assertThat(tick.lastPrice()).isEqualTo(123.45, within(1e-9));
    assertThat(tick.closePrice()).isEqualTo(120.0, within(1e-9));
    assertThat(tick.lastTradeTimeMillis()).isEqualTo(1_700_000_000_000L);
    assertThat(tick.volume()).isEqualTo(98_765L);
    assertThat(tick.openInterest()).isEqualTo(4_321L);
  }

  @Test
  void decodesAnIndexFullFeedWithNoVolumeOrOpenInterest() throws IOException {
    byte[] iff = indexFullFeed(ltpc(24_812.65, 1_700_000_000_000L, 24_700.0));
    byte[] frame =
        feedResponse(
            1_700_000_000_500L,
            java.util.Map.of("NSE_INDEX|Nifty 50", feedWithFullFeed(fullFeed(2, iff), 1)));

    DecodedFeed.Tick tick = FeedFrameDecoder.decode(frame).feeds().get("NSE_INDEX|Nifty 50");

    assertThat(tick).isNotNull();
    assertThat(tick.lastPrice()).isEqualTo(24_812.65, within(1e-9));
    assertThat(tick.volume()).as("indices carry no volume").isNull();
    assertThat(tick.openInterest()).as("indices carry no OI").isNull();
  }

  @Test
  void decodesABareLtpcModeFeed() throws IOException {
    byte[] frame =
        feedResponse(
            42L,
            java.util.Map.of("NSE_EQ|INE002A01018", feedWithLtpc(ltpc(2_950.0, 99L, 2_900.0))));

    DecodedFeed.Tick tick = FeedFrameDecoder.decode(frame).feeds().get("NSE_EQ|INE002A01018");

    assertThat(tick).isNotNull();
    assertThat(tick.lastPrice()).isEqualTo(2_950.0, within(1e-9));
    assertThat(tick.lastTradeTimeMillis()).isEqualTo(99L);
    assertThat(tick.volume()).isNull();
  }

  @Test
  void decodesMultipleInstrumentsInOneFrame() throws IOException {
    byte[] frame =
        feedResponse(
            7L,
            java.util.Map.of(
                "A",
                feedWithFullFeed(fullFeed(1, marketFullFeed(ltpc(1.0, 1L, 0.5), 10L, 5.0)), 1),
                "B",
                feedWithFullFeed(fullFeed(1, marketFullFeed(ltpc(2.0, 2L, 1.5), 20L, 6.0)), 1)));

    DecodedFeed decoded = FeedFrameDecoder.decode(frame);

    assertThat(decoded.feeds()).hasSize(2);
    assertThat(decoded.feeds().get("A").lastPrice()).isEqualTo(1.0, within(1e-9));
    assertThat(decoded.feeds().get("B").volume()).isEqualTo(20L);
  }

  @Test
  void skipsUnknownFieldsAndForwardCompatibleAdditions() throws IOException {
    // A FeedResponse with an unknown high-numbered field (e.g. a future Upstox addition) plus an
    // unknown field inside the LTPC must NOT crash — liberal-accept discipline.
    ByteArrayOutputStream ltpcBytes = new ByteArrayOutputStream();
    CodedOutputStream ltpc = CodedOutputStream.newInstance(ltpcBytes);
    ltpc.writeDouble(1, 50.0); // ltp
    ltpc.writeInt64(3, 7L); // ltq — a field we don't consume; must be skipped
    ltpc.flush();
    byte[] mff = marketFullFeed(ltpcBytes.toByteArray(), 11L, 3.0);

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    writeFeedEntry(out, "X", feedWithFullFeed(fullFeed(1, mff), 1));
    out.writeInt64(3, 1L); // currentTs
    out.writeString(99, "future-field"); // unknown top-level field — must be skipped
    out.flush();

    DecodedFeed.Tick tick = FeedFrameDecoder.decode(bytes.toByteArray()).feeds().get("X");
    assertThat(tick.lastPrice()).isEqualTo(50.0, within(1e-9));
    assertThat(tick.volume()).isEqualTo(11L);
  }

  @Test
  void emptyFrameDecodesToNoFeeds() {
    DecodedFeed decoded = FeedFrameDecoder.decode(new byte[0]);
    assertThat(decoded.feeds()).isEmpty();
    assertThat(decoded.serverTimestampMillis()).isZero();
  }

  @Test
  void malformedFrameSurfacesAsUncheckedIoException() {
    // A truncated length-delimited field (declares 10 bytes, supplies 1) must fail loudly, not
    // silently produce a half-decoded tick.
    // tag = (fieldNumber << 3) | wireType; feeds is field 2, length-delimited (wiretype 2) => 0x12
    byte[] truncated = {(byte) ((2 << 3) | WireFormat.WIRETYPE_LENGTH_DELIMITED), 10, 0x01};
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> FeedFrameDecoder.decode(truncated))
        .isInstanceOf(UncheckedIOException.class);
  }
}
