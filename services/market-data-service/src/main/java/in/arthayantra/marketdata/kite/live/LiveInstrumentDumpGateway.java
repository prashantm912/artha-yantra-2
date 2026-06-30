package in.arthayantra.marketdata.kite.live;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.AccessTokenProvider;
import in.arthayantra.marketdata.kite.InstrumentDumpGateway;
import in.arthayantra.marketdata.kite.wire.KiteInstrument;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Live instrument dump (B-6 port 5/5): {@code GET {base}/instruments/{exchange}} per exchange —
 * NSE, BSE, NFO, BFO sequentially (B-3 step 2) — parsing Kite's (optionally gzipped) CSV. BSE
 * carries the SENSEX/BANKEX spot indices + BSE-only cash, needed to resolve spot for the BFO
 * options chain. Speaks the wire format so WireMock can verify it (Phase 9 acceptance).
 */
public class LiveInstrumentDumpGateway implements InstrumentDumpGateway {

  private static final Logger log = LoggerFactory.getLogger(LiveInstrumentDumpGateway.class);
  private static final List<String> EXCHANGES = List.of("NSE", "BSE", "NFO", "BFO");

  private final RestClient restClient;
  private final String apiKey;
  private final AccessTokenProvider tokenProvider;
  private final in.arthayantra.marketdata.kite.KiteCallExecutor executor;

  /** Wires the wire client; the whole dump rides ONE kite-dump permit (1/30m, B-3). */
  public LiveInstrumentDumpGateway(
      RestClient.Builder builder,
      String baseUrl,
      String apiKey,
      AccessTokenProvider tokenProvider,
      in.arthayantra.marketdata.kite.KiteCallExecutor executor) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.apiKey = apiKey;
    this.tokenProvider = tokenProvider;
    this.executor = executor;
  }

  @Override
  public List<InstrumentRecord> fetchDump() {
    // one DUMP permit covers the full three-exchange sweep — the 1/30m budget guards the
    // manual sync trigger from hammering Kite; idempotent GETs, so executor retry is safe
    return executor.execute(in.arthayantra.marketdata.kite.KiteCallExecutor.Family.DUMP, this::fetchAllExchanges);
  }

  private List<InstrumentRecord> fetchAllExchanges() {
    String token =
        tokenProvider
            .currentToken()
            .orElseThrow(
                () ->
                    new ApiException(
                        401, ErrorCodes.KITE_TOKEN_EXPIRED, "no live Kite session for dump sync"));
    List<InstrumentRecord> all = new ArrayList<>();
    for (String exchange : EXCHANGES) {
      byte[] body =
          restClient
              .get()
              .uri("/instruments/{exchange}", exchange)
              .header("X-Kite-Version", "3")
              .header("Authorization", "token " + apiKey + ":" + token)
              .retrieve()
              .body(byte[].class);
      List<KiteInstrument> rows = parseCsv(body);
      log.info("instrument dump {}: {} rows", exchange, rows.size());
      for (KiteInstrument row : rows) {
        all.add(toRecord(row));
      }
    }
    return all;
  }

  /** Maps a full wire row to the domain record; {@code exchange_token} now rides onto the domain
   * record (persisted), {@code last_price} stays mirrored on {@link KiteInstrument} only. */
  private static InstrumentRecord toRecord(KiteInstrument k) {
    return new InstrumentRecord(
        k.instrumentToken(),
        k.exchangeToken(),
        k.exchange(),
        k.tradingsymbol(),
        k.name(),
        k.instrumentType(),
        k.segment(),
        k.expiry(),
        k.strike(),
        k.lotSize() == null ? 0 : k.lotSize(),
        k.tickSize());
  }

  /** Parses the Kite dump CSV (gzip-sniffed) into full wire rows; header-driven column mapping. */
  static List<KiteInstrument> parseCsv(byte[] body) {
    try (InputStream raw = maybeGunzip(body);
        BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
      String headerLine = reader.readLine();
      if (headerLine == null) {
        return List.of();
      }
      Map<String, Integer> col = new HashMap<>();
      String[] headers = headerLine.split(",", -1);
      for (int i = 0; i < headers.length; i++) {
        col.put(headers[i].trim(), i);
      }
      List<KiteInstrument> rows = new ArrayList<>();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        String[] f = line.split(",", -1);
        rows.add(
            new KiteInstrument(
                parseLong(get(f, col, "instrument_token")),
                parseLong(get(f, col, "exchange_token")),
                get(f, col, "tradingsymbol"),
                get(f, col, "name"),
                parseDecimal(get(f, col, "last_price")),
                parseDate(get(f, col, "expiry")),
                parseDecimal(get(f, col, "strike")),
                parseDecimal(get(f, col, "tick_size")),
                parseInt(get(f, col, "lot_size")),
                get(f, col, "instrument_type"),
                get(f, col, "segment"),
                get(f, col, "exchange")));
      }
      return rows;
    } catch (IOException e) {
      throw new UncheckedIOException("instrument dump CSV parse failed", e);
    }
  }

  private static InputStream maybeGunzip(byte[] body) throws IOException {
    if (body.length >= 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
      return new GZIPInputStream(new ByteArrayInputStream(body));
    }
    return new ByteArrayInputStream(body);
  }

  private static String get(String[] fields, Map<String, Integer> col, String name) {
    Integer index = col.get(name);
    if (index == null || index >= fields.length) {
      return "";
    }
    // the dump quotes the name column; strip plain wrapping quotes
    return fields[index].replace("\"", "").trim();
  }

  private static long parseLong(String value) {
    return value.isEmpty() ? 0L : Long.parseLong(value);
  }

  private static Integer parseInt(String value) {
    return value.isEmpty() ? null : Integer.valueOf(value);
  }

  private static BigDecimal parseDecimal(String value) {
    return value.isEmpty() ? null : new BigDecimal(value);
  }

  private static LocalDate parseDate(String value) {
    return value.isEmpty() ? null : LocalDate.parse(value);
  }
}
