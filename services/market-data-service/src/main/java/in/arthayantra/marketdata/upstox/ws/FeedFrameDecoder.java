package in.arthayantra.marketdata.upstox.ws;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decodes one Upstox v3 {@code MarketDataFeedV3.FeedResponse} binary protobuf frame into a
 * domain-free {@link DecodedFeed} (Wave U3). Walks the wire format with protobuf-java's low-level
 * {@link CodedInputStream} rather than generated classes — so there is NO {@code protoc} step, no
 * generated source committed, and no new build plugin (protoc download is fragile on the
 * TLS-intercepting-AV box). The reference schema (field numbers) is the sibling
 * {@code MarketDataFeedV3.proto}; keep the two in lockstep.
 *
 * <p>Only the fields the tick sink needs are extracted ({@code ltpc.ltp/ltt/cp}, {@code
 * marketFF.vtt} volume, {@code marketFF.oi} open-interest); every other field (depth, greeks, OHLC,
 * market-info, the {@code firstLevelWithGreeks} / {@code full_d30} branches) is skipped via {@link
 * CodedInputStream#skipField(int)}, so an unknown or future field can never crash the decode (the
 * same liberal-accept discipline as the REST wire DTOs). Subscribing in {@code full} mode routes
 * data through {@code Feed.fullFeed.{marketFF|indexFF}}; the bare {@code Feed.ltpc} branch
 * ({@code ltpc} mode) is also handled so the decoder is mode-agnostic.
 */
public final class FeedFrameDecoder {

  // FeedResponse
  private static final int FR_FEEDS = 2; // map<string, Feed>
  private static final int FR_CURRENT_TS = 3; // int64

  // map<string, Feed> entry
  private static final int MAP_KEY = 1; // string
  private static final int MAP_VALUE = 2; // Feed

  // Feed (oneof) + requestMode
  private static final int FEED_LTPC = 1; // LTPC
  private static final int FEED_FULL = 2; // FullFeed

  // FullFeed (oneof)
  private static final int FULL_MARKET = 1; // MarketFullFeed
  private static final int FULL_INDEX = 2; // IndexFullFeed

  // MarketFullFeed
  private static final int MFF_LTPC = 1; // LTPC
  private static final int MFF_VTT = 6; // int64 volume traded today
  private static final int MFF_OI = 7; // double open interest

  // IndexFullFeed
  private static final int IFF_LTPC = 1; // LTPC

  // LTPC
  private static final int LTPC_LTP = 1; // double
  private static final int LTPC_LTT = 2; // int64
  private static final int LTPC_CP = 4; // double

  private FeedFrameDecoder() {}

  /** Decodes a full {@code FeedResponse} frame; transport corruption surfaces as {@link UncheckedIOException}. */
  public static DecodedFeed decode(byte[] frame) {
    CodedInputStream in = CodedInputStream.newInstance(frame);
    Map<String, DecodedFeed.Tick> feeds = new LinkedHashMap<>();
    long currentTs = 0L;
    try {
      for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
        switch (WireFormat.getTagFieldNumber(tag)) {
          case FR_FEEDS -> readMapEntry(in, feeds);
          case FR_CURRENT_TS -> currentTs = in.readInt64();
          default -> in.skipField(tag);
        }
      }
      return new DecodedFeed(feeds, currentTs);
    } catch (IOException e) {
      throw new UncheckedIOException("malformed Upstox v3 market-data frame", e);
    }
  }

  /** One {@code map<string, Feed>} entry: {@code key} (string) + {@code value} (Feed). */
  private static void readMapEntry(CodedInputStream in, Map<String, DecodedFeed.Tick> out)
      throws IOException {
    int len = in.readRawVarint32();
    int limit = in.pushLimit(len);
    String key = null;
    Feed feed = new Feed();
    for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
      switch (WireFormat.getTagFieldNumber(tag)) {
        case MAP_KEY -> key = in.readString();
        case MAP_VALUE -> feed = readFeed(in);
        default -> in.skipField(tag);
      }
    }
    in.popLimit(limit);
    if (key != null) {
      out.put(key, feed.toTick());
    }
  }

  /** A {@code Feed}: either a bare {@code ltpc} or a nested {@code fullFeed}; other branches skipped. */
  private static Feed readFeed(CodedInputStream in) throws IOException {
    int len = in.readRawVarint32();
    int limit = in.pushLimit(len);
    Feed feed = new Feed();
    for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
      switch (WireFormat.getTagFieldNumber(tag)) {
        case FEED_LTPC -> readLtpc(in, feed);
        case FEED_FULL -> readFullFeed(in, feed);
        default -> in.skipField(tag);
      }
    }
    in.popLimit(limit);
    return feed;
  }

  /** {@code FullFeed}: pick whichever of {@code marketFF} / {@code indexFF} is present. */
  private static void readFullFeed(CodedInputStream in, Feed feed) throws IOException {
    int len = in.readRawVarint32();
    int limit = in.pushLimit(len);
    for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
      switch (WireFormat.getTagFieldNumber(tag)) {
        case FULL_MARKET -> readMarketFullFeed(in, feed);
        case FULL_INDEX -> readIndexFullFeed(in, feed);
        default -> in.skipField(tag);
      }
    }
    in.popLimit(limit);
  }

  /** {@code MarketFullFeed}: {@code ltpc} + {@code vtt} (volume) + {@code oi}. */
  private static void readMarketFullFeed(CodedInputStream in, Feed feed) throws IOException {
    int len = in.readRawVarint32();
    int limit = in.pushLimit(len);
    for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
      switch (WireFormat.getTagFieldNumber(tag)) {
        case MFF_LTPC -> readLtpc(in, feed);
        case MFF_VTT -> feed.volume = in.readInt64();
        case MFF_OI -> feed.openInterest = (long) in.readDouble();
        default -> in.skipField(tag);
      }
    }
    in.popLimit(limit);
  }

  /** {@code IndexFullFeed}: {@code ltpc} only (indices carry no volume/OI). */
  private static void readIndexFullFeed(CodedInputStream in, Feed feed) throws IOException {
    int len = in.readRawVarint32();
    int limit = in.pushLimit(len);
    for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
      if (WireFormat.getTagFieldNumber(tag) == IFF_LTPC) {
        readLtpc(in, feed);
      } else {
        in.skipField(tag);
      }
    }
    in.popLimit(limit);
  }

  /** {@code LTPC}: {@code ltp} + {@code ltt} + {@code cp}. */
  private static void readLtpc(CodedInputStream in, Feed feed) throws IOException {
    int len = in.readRawVarint32();
    int limit = in.pushLimit(len);
    for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
      switch (WireFormat.getTagFieldNumber(tag)) {
        case LTPC_LTP -> feed.lastPrice = in.readDouble();
        case LTPC_LTT -> feed.lastTradeTimeMillis = in.readInt64();
        case LTPC_CP -> feed.closePrice = in.readDouble();
        default -> in.skipField(tag);
      }
    }
    in.popLimit(limit);
  }

  /** Mutable per-instrument accumulator while a {@code Feed} sub-tree is walked. */
  private static final class Feed {
    private double lastPrice;
    private double closePrice;
    private long lastTradeTimeMillis;
    private Long volume;
    private Long openInterest;

    private DecodedFeed.Tick toTick() {
      return new DecodedFeed.Tick(
          lastPrice, closePrice, lastTradeTimeMillis, volume, openInterest);
    }
  }
}
