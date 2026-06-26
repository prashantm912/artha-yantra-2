package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.constituents.StaticIndexConstituents;
import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.FuturesContractSource.FutContract;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Futures OI Buzz heatmap (oipulse "Futures Heatmap"): one tile per index constituent, each carrying
 * the near-month future's intraday % change (and day OHLC + current OI for the tooltip). The tile's
 * % change is read live from a single batched {@link QuoteGateway#quotes} call — size and colour are
 * driven by % change (the raw feed carries no index weight, so v1 sizes by |% change|; the OI-change
 * interpretation badge is deferred to v2). Advance/decline are the counts of the % change sign.
 */
@Service
public class OiBuzzService {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final StaticIndexConstituents constituents;
  private final FuturesContractSource contracts;
  private final QuoteGateway quoteGateway;
  private final PrevDayFutureOiCache prevOiCache;
  private final CandleRepository candles;
  private final Clock clock;

  public OiBuzzService(
      StaticIndexConstituents constituents,
      FuturesContractSource contracts,
      QuoteGateway quoteGateway,
      PrevDayFutureOiCache prevOiCache,
      CandleRepository candles,
      Clock clock) {
    this.constituents = constituents;
    this.contracts = contracts;
    this.quoteGateway = quoteGateway;
    this.prevOiCache = prevOiCache;
    this.candles = candles;
    this.clock = clock;
  }

  /**
   * One constituent tile: % change drives size + colour; OHLC + OI back the tooltip. {@code oiChange}
   * (current OI − prev-session close OI) + its 4-state {@code interpretation} are {@code null} until
   * the prev-day OI cache warms (v2 OI-interpretation badge).
   */
  public record Tile(
      String symbol,
      BigDecimal changePct,
      BigDecimal ltp,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      Long oi,
      Long oiChange,
      OiInterpretation interpretation) {}

  /** The heatmap for one index: tiles (gainers first) + advance/decline counters + the feed time. */
  public record Heatmap(
      String index, int advance, int decline, List<Tile> tiles, OffsetDateTime asOf) {}

  public Heatmap heatmap(String index) {
    return heatmap(index, null);
  }

  /**
   * Live (null/today date) → one batched live-quote call. A PAST date → the constituents' spot 1d
   * %change from the dense EQ bhavcopy (there is no live quote on a closed day, and the per-constituent
   * near-month-FUTURE 1d history is not backfilled — spot %change is the dense, ~basis-equal proxy).
   */
  public Heatmap heatmap(String index, LocalDate date) {
    LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(Ist.ZONE).toLocalDate();
    return (date == null || !date.isBefore(today)) ? live(index) : historical(index, date);
  }

  private Heatmap live(String index) {
    List<String> symbols = constituents.symbols(index);
    if (symbols.isEmpty()) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_RESOURCE, "no constituent reference for index '" + index + "'");
    }
    LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(Ist.ZONE).toLocalDate();

    // Resolve every constituent's near-month future first, then batch ALL of them into ONE quotes()
    // call (the Kite quote limiter 429s on a call-per-symbol loop). A symbol with no future is skipped.
    record Pin(String symbol, FutContract contract) {}
    List<Pin> pins = new ArrayList<>();
    for (String symbol : symbols) {
      List<FutContract> ladder;
      try {
        ladder = contracts.monthlyFutures(symbol, today);
      } catch (RuntimeException resolveFailed) {
        continue;
      }
      if (!ladder.isEmpty()) {
        pins.add(new Pin(symbol, ladder.get(0))); // front month
      }
    }
    if (pins.isEmpty()) {
      throw new ApiException(
          422, ErrorCodes.DATA_GAP, "no monthly futures resolved for index '" + index + "'");
    }

    List<InstrumentKey> keys = pins.stream().map(p -> p.contract().key()).toList();
    Map<InstrumentKey, QuoteGateway.Quote> quotes = quoteGateway.quotes(keys);

    List<Tile> tiles = new ArrayList<>();
    int advance = 0;
    int decline = 0;
    OffsetDateTime asOf = null;
    for (Pin p : pins) {
      QuoteGateway.Quote q = quotes.get(p.contract().key());
      if (q == null || q.lastPrice() == null) {
        continue;
      }
      QuoteGateway.Quote.Ohlc ohlc = q.ohlc();
      BigDecimal prevClose = ohlc == null ? null : ohlc.close(); // Kite ohlc.close = PREVIOUS close
      BigDecimal changePct =
          (prevClose != null && prevClose.signum() > 0)
              ? q.lastPrice().subtract(prevClose).multiply(HUNDRED).divide(prevClose, 2, RoundingMode.HALF_UP)
              : null;
      if (changePct != null) {
        if (changePct.signum() > 0) {
          advance++;
        } else if (changePct.signum() < 0) {
          decline++;
        }
      }
      // v2 OI-interpretation: oiChange vs the prev-session close OI (cached/background-warmed) →
      // Long Buildup / Short Buildup / Long Unwinding / Short Covering. null until the cache warms.
      Long prevDayOi = prevOiCache.lookup(today, p.symbol());
      Long oiChange = (prevDayOi != null && q.oi() != null) ? q.oi() - prevDayOi : null;
      OiInterpretation interpretation =
          (oiChange != null && prevClose != null)
              ? OiInterpretation.classify(q.lastPrice().subtract(prevClose), oiChange)
              : null;
      tiles.add(
          new Tile(
              p.symbol(),
              changePct,
              q.lastPrice(),
              ohlc == null ? null : ohlc.open(),
              ohlc == null ? null : ohlc.high(),
              ohlc == null ? null : ohlc.low(),
              q.oi(),
              oiChange,
              interpretation));
      if (q.timestamp() != null && (asOf == null || q.timestamp().isAfter(asOf))) {
        asOf = q.timestamp();
      }
    }

    // Kick a background warm of the prev-day OI baseline (idempotent, once/day) so the OI-interp
    // badge fills in on the next refresh without blocking this request on ~50 historical calls.
    prevOiCache.ensureWarm(
        today,
        pins.stream()
            .map(p -> new PrevDayFutureOiCache.SymKey(p.symbol(), p.contract().key()))
            .toList());

    // Gainers first; tiles with no % change (no prev close) sink to the bottom.
    tiles.sort(
        Comparator.comparing(
            Tile::changePct, Comparator.nullsLast(Comparator.reverseOrder())));
    return new Heatmap(index, advance, decline, tiles, asOf);
  }

  /** Past-date heatmap: each constituent's spot close-vs-prior-close %change from the EQ 1d bhavcopy. */
  private Heatmap historical(String index, LocalDate date) {
    List<String> symbols = constituents.symbols(index);
    if (symbols.isEmpty()) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_RESOURCE, "no constituent reference for index '" + index + "'");
    }
    // A ~12-day window of EQ 1d bars (covers the prior session even across a long holiday weekend).
    OffsetDateTime from = date.minusDays(12).atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = date.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);
    List<Tile> tiles = new ArrayList<>();
    int advance = 0;
    int decline = 0;
    for (String symbol : symbols) {
      List<Candle> bars;
      try {
        bars = candles.range("NSE", symbol, "1d", from, to);
      } catch (RuntimeException read) {
        continue;
      }
      Candle onDate = null;
      Candle prior = null;
      for (Candle b : bars) { // ascending by bucket
        LocalDate bd = b.bucket().atZoneSameInstant(Ist.ZONE).toLocalDate();
        if (bd.isAfter(date)) {
          break;
        }
        if (bd.equals(date)) {
          onDate = b;
        } else {
          prior = b; // last session strictly before the date
        }
      }
      if (onDate == null || prior == null || prior.close() == null || prior.close().signum() == 0) {
        continue;
      }
      BigDecimal changePct =
          onDate
              .close()
              .subtract(prior.close())
              .multiply(HUNDRED)
              .divide(prior.close(), 2, RoundingMode.HALF_UP);
      if (changePct.signum() > 0) {
        advance++;
      } else if (changePct.signum() < 0) {
        decline++;
      }
      tiles.add(
          new Tile(
              symbol, changePct, onDate.close(), onDate.open(), onDate.high(), onDate.low(),
              onDate.oi(), null, null));
    }
    if (tiles.isEmpty()) {
      throw new ApiException(
          422, ErrorCodes.DATA_GAP, "no 1d candles for index '" + index + "' on " + date);
    }
    tiles.sort(
        Comparator.comparing(Tile::changePct, Comparator.nullsLast(Comparator.reverseOrder())));
    return new Heatmap(index, advance, decline, tiles, to);
  }
}
