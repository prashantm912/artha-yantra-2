package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** RestClient lookup against {@code GET /api/v1/instruments/{exchange}/{tradingsymbol}}. */
@Component
public class RestInstrumentMetaClient implements InstrumentMetaClient {

  private static final Logger log = LoggerFactory.getLogger(RestInstrumentMetaClient.class);
  private static final InstrumentMeta EQUITY_PROXY =
      new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
  /**
   * The SAME unresolved-metadata proxy, but reporting lot {@code 0} instead of {@code 1} — used when
   * the lookup fails for a symbol on a DERIVATIVE segment.
   *
   * <p>Cross-vendor review Critical 1. The fix below covers a row we DID read; this covers the one we
   * could NOT (404 / transport / parse). The old reasoning — "on a failure we cannot know the
   * instrument class, and blocking every equity fill during a market-data blip is the worse trade" —
   * is half right and was incomplete: the ORDER'S EXCHANGE already identifies the segment. NFO/BFO is
   * never a lot-1 instrument, so filling one at lot 1 because the lookup failed is the same wrong
   * QUANTITY by a different route. Equities keep the lot-1 proxy exactly as before, so the blip
   * argument still holds where it actually applied.
   *
   * <p>The CLASS stays EQUITY deliberately: we genuinely do not know it, and
   * {@code PaperEmissionGuard#unresolvedDerivative} already keys its first arm on exactly that
   * "derivatives symbol that did not resolve as an OPTION" shape. Only the lot changes, so the blast
   * radius of this hunk is one field.
   */
  private static final InstrumentMeta UNRESOLVED_DERIVATIVE_PROXY =
      new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 0);

  private final RestClient restClient;

  /** Wires the configured market-data base URL. */
  public RestInstrumentMetaClient(
      RestClient.Builder builder, @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  @Override
  public InstrumentMeta meta(String exchange, String tradingsymbol) {
    try {
      InstrumentDto dto =
          restClient
              .get()
              .uri("/api/v1/instruments/{exchange}/{tradingsymbol}", exchange, tradingsymbol)
              .retrieve()
              .body(InstrumentDto.class);
      if (dto == null) {
        return proxyFor(exchange);
      }
      InstrumentClass instrumentClass = classOf(dto.instrumentType());
      return new InstrumentMeta(
          instrumentClass,
          dto.tickSize() == null ? new BigDecimal("0.05") : new BigDecimal(dto.tickSize()),
          lotOf(instrumentClass, dto.lotSize(), exchange, tradingsymbol));
    } catch (RestClientException | NumberFormatException e) {
      log.warn(
          "instrument meta lookup failed for {}:{} — unresolved proxy (lot {}): {}",
          exchange, tradingsymbol, proxyFor(exchange).lotSize(), e.getMessage());
      return proxyFor(exchange);
    }
  }

  /**
   * The contract lot, or {@code 0} — "the master does not know" — on a DERIVATIVE whose row carries
   * no lot size. NEVER {@code 1} there.
   *
   * <p>The asymmetry with {@code tickSize} is the whole point. A missing tick defaults to {@code
   * 0.05}: a benign ROUNDING default, wrong only in the last paisa. A missing lot defaulted to
   * {@code 1}, which is a wrong QUANTITY on a fill-sizing path — paper sizing, and broker order
   * routing once {@code artha.scalper.execution=live}. 15000/776 = 19 units of a 20-lot SENSEX
   * option is a position no broker will take (Upstox rejects a non-lot-multiple qty with {@code
   * UDAPI1104}), and it fills silently precisely because {@code 1} divides everything.
   *
   * <p>Why a row can look like a real option and still have no lot: {@code marketdata.instruments}
   * holds 182,491 placeholder rows written by {@code tools/historical-import} ({@code ingest.py}
   * {@code _UPSERT_INSTRUMENT}) so imported candles have an instrument to hang off. On every one
   * {@code lot_size} and {@code tick_size} are NULL while {@code instrument_type} is POPULATED
   * (computed 2026-08-25: 182,491 of 182,491), so the row classifies as a genuine CE/PE and only the
   * lot is missing. The by-key read answers 200 for them (it is not {@code is_active}-filtered), so
   * nothing upstream refuses first. Latent today only because all 182,189 dated ones are PAST
   * expiry (max {@code 2026-05-27}; 0 rows with {@code expiry >= CURRENT_DATE}, computed
   * 2026-08-25) — a data property that can change without a commit, which is why it is fixed here
   * rather than left to the calendar.
   *
   * <p>{@code 0} rather than an exception because every OTHER lot consumer in this codebase already
   * reads {@code lotSize <= 0} as "unknown", and matching them is the smallest correct change:
   * {@code ShadowCostModel} and {@code PartialBucketCanary} both DECLINE on a null/non-positive lot
   * instead of substituting; {@code PaperSignalListener#openStraddle} already degrades to the single
   * leg on lot {@code 0}; and {@code LtpSlippageV1:122} guards {@code req.lotSize() <= 0} so the
   * cost model cannot divide by it. This client was the only one of the three that fabricated. The
   * ENTRY paths turn the {@code 0} into a real refusal ({@code PaperService#openOrder} 422 DATA_GAP,
   * {@code PaperEmissionGuard#unresolvedDerivative} zero-size); the EXIT and DISPLAY paths keep
   * working, deliberately — <b>entries need fresh truth (you can always NOT enter), exits need the
   * best available truth (you cannot refuse to leave forever)</b>.
   *
   * <p>EQUITY keeps the {@code 1} default and that is CORRECT, not an oversight: a cash equity
   * trades in single units, so {@code 1} is the instrument's real lot rather than a substitute for
   * an unknown one. 510 EQ rows carry a NULL lot today and two of them back OPEN paper positions
   * (NSE:KANORICHEM, both computed 2026-08-25) — refusing those would break a live book for no gain.
   */
  private static long lotOf(
      InstrumentClass instrumentClass, Long lotSize, String exchange, String tradingsymbol) {
    if (lotSize != null && lotSize > 0) {
      return lotSize;
    }
    if (instrumentClass == InstrumentClass.EQUITY) {
      return 1;
    }
    log.warn(
        "instrument master carries no lot size for {} {}:{} — reporting lot 0 (unknown); the entry"
            + " paths refuse rather than size against a fabricated lot of 1",
        instrumentClass, exchange, tradingsymbol);
    return 0;
  }

  /**
   * The unresolved-metadata proxy for this exchange: lot {@code 1} on a cash segment (unchanged), lot
   * {@code 0} — unknown, and refused by the entry paths — on a derivative segment.
   */
  private static InstrumentMeta proxyFor(String exchange) {
    return isDerivativeSegment(exchange) ? UNRESOLVED_DERIVATIVE_PROXY : EQUITY_PROXY;
  }

  /**
   * The F&O segments this service trades. Deliberately the SAME two-exchange test
   * {@code PaperEmissionGuard#unresolvedDerivative} already uses rather than a new notion of
   * "derivative" — one rule, one place to extend. (Verified 2026-08-25: no MCX/CDS/BCD literal exists
   * anywhere in this service's main sources, so the pair is complete for what we actually route.)
   */
  private static boolean isDerivativeSegment(String exchange) {
    return "NFO".equals(exchange) || "BFO".equals(exchange);
  }

  private static InstrumentClass classOf(String instrumentType) {
    if (instrumentType == null) {
      return InstrumentClass.EQUITY;
    }
    return switch (instrumentType) {
      case "CE", "PE" -> InstrumentClass.OPTION;
      case "FUT" -> InstrumentClass.FUTURE;
      default -> InstrumentClass.EQUITY;
    };
  }

  /** The slice of the instrument-master row this client needs. */
  private record InstrumentDto(String instrumentType, String tickSize, Long lotSize) {}
}
