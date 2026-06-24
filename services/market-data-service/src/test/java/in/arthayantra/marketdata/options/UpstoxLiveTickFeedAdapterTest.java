package in.arthayantra.marketdata.options;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.google.protobuf.CodedOutputStream;
import in.arthayantra.marketdata.constituents.StockUpstoxKeyMap;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRegistry;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.RawTick;
import in.arthayantra.marketdata.upstox.UpstoxAnalyticsProperties;
import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;
import in.arthayantra.marketdata.upstox.UpstoxMarketFeedClient;
import in.arthayantra.marketdata.upstox.ws.UpstoxReconnectPolicy;
import in.arthayantra.marketdata.upstox.ws.UpstoxWebSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The Wave-U3 bridge ({@link UpstoxLiveTickFeedAdapter}): asserts it translates Kite tokens to Upstox
 * {@code instrument_key}s on subscribe (via the instrument master + the U2 index/stock map), and maps
 * decoded Upstox ticks back to token-keyed {@link RawTick}s for the shared sink. Drives a real {@link
 * UpstoxMarketFeedClient} through a fake socket (no network).
 */
class UpstoxLiveTickFeedAdapterTest {

  /** A scriptable socket. */
  static final class FakeSocket implements UpstoxWebSocket {
    final List<byte[]> sent = new ArrayList<>();
    boolean open;
    Consumer<byte[]> binarySink = b -> {};

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
    public void onDisconnected(Runnable callback) {}

    @Override
    public void onError(Consumer<String> callback) {}

    void deliverFrame(byte[] frame) {
      binarySink.accept(frame);
    }
  }

  private static final long NIFTY_TOKEN = 256_265L;
  private static final InstrumentKey NIFTY = new InstrumentKey("NSE", "NIFTY 50");
  private static final String NIFTY_UPSTOX_KEY = "NSE_INDEX|Nifty 50";

  private record Fixture(
      UpstoxLiveTickFeedAdapter adapter,
      FakeSocket socket,
      io.micrometer.core.instrument.MeterRegistry meters) {}

  private static Fixture fixture() {
    FakeSocket socket = new FakeSocket();
    UpstoxMarketFeedClient client =
        new UpstoxMarketFeedClient(
            () -> "wss://x", url -> socket, new UpstoxReconnectPolicy(), millis -> {});
    InstrumentRegistry instruments =
        InstrumentRegistry.fixedForTesting(Map.of(NIFTY_TOKEN, NIFTY));
    StockUpstoxKeyMap stockKeys = new StockUpstoxKeyMap(new ObjectMapper());
    io.micrometer.core.instrument.MeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    UpstoxLiveTickFeedAdapter adapter =
        new UpstoxLiveTickFeedAdapter(client, instruments, stockKeys, meters);
    client.start();
    return new Fixture(adapter, socket, meters);
  }

  @Test
  void subscribeTranslatesAnIndexTokenToItsUpstoxKeyInFullMode() {
    Fixture f = fixture();

    f.adapter().subscribe(List.of(NIFTY_TOKEN));

    assertThat(f.socket().sent).hasSize(1);
    String msg = new String(f.socket().sent.get(0), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(msg).contains("\"mode\":\"full\"").contains(NIFTY_UPSTOX_KEY);
  }

  @Test
  void unmappableTokenIsSkippedAndNotSubscribed() {
    Fixture f = fixture();

    f.adapter().subscribe(List.of(999_999L)); // no master entry — left to the Kite path

    assertThat(f.socket().sent).isEmpty();
  }

  // ---- Wave-U4: a FUT/option token now RESOLVES to its Upstox key (previously logged + skipped) ----

  private static final long FUT_TOKEN = 510_001L;

  @Test
  void futureTokenNowResolvesToItsUpstoxFnoKeyAndSubscribes() {
    WireMockServer wireMock =
        new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
    try {
      String masterJson =
          """
          [{"segment":"NSE_FO","asset_symbol":"NIFTY","instrument_key":"NSE_FO|61093",
            "instrument_type":"FUT","expiry":1785263399000,"strike_price":0.0}]
          """;
      wireMock.stubFor(
          get(urlPathEqualTo("/market-quote/instruments/exchange/complete.json.gz"))
              .willReturn(aResponse().withBody(gzip(masterJson))));

      FakeSocket socket = new FakeSocket();
      UpstoxMarketFeedClient client =
          new UpstoxMarketFeedClient(
              () -> "wss://x", url -> socket, new UpstoxReconnectPolicy(), millis -> {});
      InstrumentKey futKey = new InstrumentKey("NFO", "NIFTY26JULFUT");
      InstrumentRegistry registry = InstrumentRegistry.fixedForTesting(Map.of(FUT_TOKEN, futKey));
      InstrumentRepository repo = mock(InstrumentRepository.class);
      when(repo.findByKey("NFO", "NIFTY26JULFUT"))
          .thenReturn(
              Optional.of(
                  new Instrument(
                      "NFO", "NIFTY26JULFUT", FUT_TOKEN, "NIFTY", "NFO-FUT", "FUT", "NSE", "NIFTY",
                      LocalDate.of(2026, 7, 28), null, null, 75, true)));
      UpstoxFnoMasterClient master =
          new UpstoxFnoMasterClient(
              RestClient.builder(),
              new ObjectMapper(),
              new UpstoxAnalyticsProperties(null, null, null, wireMock.baseUrl()));
      UpstoxLiveTickFeedAdapter adapter =
          new UpstoxLiveTickFeedAdapter(
              client,
              registry,
              repo,
              new StockUpstoxKeyMap(new ObjectMapper()),
              new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
              master);
      client.start();

      adapter.subscribe(List.of(FUT_TOKEN));

      assertThat(socket.sent).as("the FUT token is now subscribed over Upstox").hasSize(1);
      String msg = new String(socket.sent.get(0), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(msg).contains("\"mode\":\"full\"").contains("NSE_FO|61093");
    } finally {
      wireMock.stop();
    }
  }

  private static byte[] gzip(String json) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  @Test
  void decodedTickIsMappedBackToTheKiteTokenForTheSharedSink() throws IOException {
    Fixture f = fixture();
    List<RawTick> received = new ArrayList<>();
    f.adapter().onTicks(received::addAll);
    f.adapter().subscribe(List.of(NIFTY_TOKEN));

    f.socket().deliverFrame(indexFrame(NIFTY_UPSTOX_KEY, 24_812.65));

    assertThat(received).hasSize(1);
    RawTick tick = received.get(0);
    assertThat(tick.instrumentToken()).as("re-keyed to the Kite token").isEqualTo(NIFTY_TOKEN);
    assertThat(tick.lastPrice().doubleValue()).isEqualTo(24_812.65, within(1e-4));
    assertThat(tick.openInterest()).as("index carries no OI").isNull();
  }

  @Test
  void marketTickCarriesVolumeAndOpenInterest() throws IOException {
    Fixture f = fixture();
    List<RawTick> received = new ArrayList<>();
    f.adapter().onTicks(received::addAll);
    f.adapter().subscribe(List.of(NIFTY_TOKEN));

    f.socket().deliverFrame(marketFrame(NIFTY_UPSTOX_KEY, 100.5, 7_777L, 55_000.0));

    RawTick tick = received.get(0);
    assertThat(tick.cumulativeDayVolume()).isEqualTo(7_777L);
    assertThat(tick.openInterest()).isEqualTo(55_000L);
  }

  @Test
  void unsubscribeDropsTheTokenMappingSoLaterTicksAreNotEmitted() throws IOException {
    Fixture f = fixture();
    List<RawTick> received = new ArrayList<>();
    f.adapter().onTicks(received::addAll);
    f.adapter().subscribe(List.of(NIFTY_TOKEN));
    f.adapter().unsubscribe(List.of(NIFTY_TOKEN));

    f.socket().deliverFrame(indexFrame(NIFTY_UPSTOX_KEY, 24_800.0));

    assertThat(received).isEmpty();
  }

  @Test
  void aTickForANeverSubscribedKeyIsDropped() throws IOException {
    Fixture f = fixture();
    List<RawTick> received = new ArrayList<>();
    f.adapter().onTicks(received::addAll);

    f.socket().deliverFrame(indexFrame(NIFTY_UPSTOX_KEY, 24_800.0));

    assertThat(received).isEmpty();
  }

  @Test
  void runningReflectsTheUnderlyingSocketState() {
    Fixture f = fixture();
    assertThat(f.adapter().running()).isTrue();
    f.adapter().stop();
    assertThat(f.adapter().running()).isFalse();
  }

  @Test
  void recordsTickLatencyAndCountForTheLatencyGate() throws IOException {
    Fixture f = fixture();
    f.adapter().subscribe(List.of(NIFTY_TOKEN));

    f.socket().deliverFrame(indexFrame(NIFTY_UPSTOX_KEY, 24_812.65)); // carries a real ltt

    assertThat(f.meters().counter("ay_upstox_ws_ticks_received_total").count()).isEqualTo(1.0);
    assertThat(f.meters().timer("ay_upstox_ws_tick_latency").count())
        .as("a tick with a valid ltt is timed for the §17.3 A/B")
        .isEqualTo(1L);
  }

  // ---- protobuf fixtures ----

  private static byte[] indexFrame(String key, double ltp) throws IOException {
    ByteArrayOutputStream ltpcBytes = new ByteArrayOutputStream();
    CodedOutputStream ltpc = CodedOutputStream.newInstance(ltpcBytes);
    ltpc.writeDouble(1, ltp);
    ltpc.writeInt64(2, 1_700_000_000_000L);
    ltpc.flush();
    ByteArrayOutputStream iffBytes = new ByteArrayOutputStream();
    CodedOutputStream iff = CodedOutputStream.newInstance(iffBytes);
    iff.writeByteArray(1, ltpcBytes.toByteArray());
    iff.flush();
    return wrap(key, 2 /* indexFF */, iffBytes.toByteArray());
  }

  private static byte[] marketFrame(String key, double ltp, long vtt, double oi) throws IOException {
    ByteArrayOutputStream ltpcBytes = new ByteArrayOutputStream();
    CodedOutputStream ltpc = CodedOutputStream.newInstance(ltpcBytes);
    ltpc.writeDouble(1, ltp);
    ltpc.flush();
    ByteArrayOutputStream mffBytes = new ByteArrayOutputStream();
    CodedOutputStream mff = CodedOutputStream.newInstance(mffBytes);
    mff.writeByteArray(1, ltpcBytes.toByteArray());
    mff.writeInt64(6, vtt);
    mff.writeDouble(7, oi);
    mff.flush();
    return wrap(key, 1 /* marketFF */, mffBytes.toByteArray());
  }

  /** Wraps an inner marketFF/indexFF message into a one-instrument FeedResponse frame. */
  private static byte[] wrap(String key, int fullBranchField, byte[] inner) throws IOException {
    ByteArrayOutputStream fullBytes = new ByteArrayOutputStream();
    CodedOutputStream full = CodedOutputStream.newInstance(fullBytes);
    full.writeByteArray(fullBranchField, inner);
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
    out.writeByteArray(2, entryBytes.toByteArray());
    out.flush();
    return frame.toByteArray();
  }
}
