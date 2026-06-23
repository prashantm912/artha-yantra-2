package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.constituents.StaticIndexConstituents;
import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.FuturesContractSource.FutContract;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
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
 * oipulse "Open=High / Open=Low": which index constituents opened at their day HIGH (O=H, bearish —
 * sellers capped it at the open) or day LOW (O=L, bullish). Computed live from the near-month future's
 * quote (day open/high/low + LTP); {@code farPct} is how far the LTP has moved from that level. The
 * triggered-time + probability columns of the full oipulse page need intraday tick history we don't
 * capture, so v1 surfaces the core O=H/O=L setups (no trigger time).
 */
@Service
public class OpenHighService {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final StaticIndexConstituents constituents;
  private final FuturesContractSource contracts;
  private final QuoteGateway quoteGateway;
  private final Clock clock;

  public OpenHighService(
      StaticIndexConstituents constituents,
      FuturesContractSource contracts,
      QuoteGateway quoteGateway,
      Clock clock) {
    this.constituents = constituents;
    this.contracts = contracts;
    this.quoteGateway = quoteGateway;
    this.clock = clock;
  }

  /** One O=H or O=L setup: the day OHL + LTP + how far the LTP is from the matched level (%). */
  public record Setup(
      String symbol,
      BigDecimal dayOpen,
      BigDecimal dayHigh,
      BigDecimal dayLow,
      BigDecimal ltp,
      BigDecimal farPct) {}

  /** The two mirrored lists for an index: opened-at-high (bearish) + opened-at-low (bullish). */
  public record OpenHigh(
      String index, List<Setup> openHigh, List<Setup> openLow, OffsetDateTime asOf) {}

  public OpenHigh openHighLow(String index) {
    List<String> symbols = constituents.symbols(index);
    if (symbols.isEmpty()) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_RESOURCE, "no constituent reference for index '" + index + "'");
    }
    LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(Ist.ZONE).toLocalDate();

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
        pins.add(new Pin(symbol, ladder.get(0)));
      }
    }
    if (pins.isEmpty()) {
      throw new ApiException(
          422, ErrorCodes.DATA_GAP, "no monthly futures resolved for index '" + index + "'");
    }

    List<InstrumentKey> keys = pins.stream().map(p -> p.contract().key()).toList();
    Map<InstrumentKey, QuoteGateway.Quote> quotes = quoteGateway.quotes(keys);

    List<Setup> openHigh = new ArrayList<>();
    List<Setup> openLow = new ArrayList<>();
    OffsetDateTime asOf = null;
    for (Pin p : pins) {
      QuoteGateway.Quote q = quotes.get(p.contract().key());
      QuoteGateway.Quote.Ohlc o = q == null ? null : q.ohlc();
      if (o == null || o.open() == null || o.high() == null || o.low() == null || q.lastPrice() == null) {
        continue;
      }
      if (o.open().compareTo(o.high()) == 0) { // never traded above the open → bearish
        openHigh.add(
            new Setup(p.symbol(), o.open(), o.high(), o.low(), q.lastPrice(), farPct(q.lastPrice(), o.high())));
      }
      if (o.open().compareTo(o.low()) == 0) { // never traded below the open → bullish
        openLow.add(
            new Setup(p.symbol(), o.open(), o.high(), o.low(), q.lastPrice(), farPct(q.lastPrice(), o.low())));
      }
      if (q.timestamp() != null && (asOf == null || q.timestamp().isAfter(asOf))) {
        asOf = q.timestamp();
      }
    }
    // Closest to the level first (smallest |farPct|) — the freshest setups.
    openHigh.sort(Comparator.comparing(s -> s.farPct().abs()));
    openLow.sort(Comparator.comparing(s -> s.farPct().abs()));
    return new OpenHigh(index, openHigh, openLow, asOf);
  }

  /** Percent the LTP sits from the matched level: (ltp − level) / level × 100. */
  private static BigDecimal farPct(BigDecimal ltp, BigDecimal level) {
    if (level.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return ltp.subtract(level).multiply(HUNDRED).divide(level, 2, RoundingMode.HALF_UP);
  }
}
