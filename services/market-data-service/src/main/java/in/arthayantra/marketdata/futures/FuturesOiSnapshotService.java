package in.arthayantra.marketdata.futures;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.FuturesContractSource.FutContract;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Phase-F futures OI snapshotter (mirrors {@code OptionsSnapshotService}). Resolves the front/next/
 * far monthly FUT of each configured underlying, batch-quotes them, and persists the OI/LTP/volume
 * time-series with a per-pass {@code oi_change}. Live profile only; market-hours-gated.
 */
@Service
@Profile("live")
public class FuturesOiSnapshotService {

  private static final Logger log = LoggerFactory.getLogger(FuturesOiSnapshotService.class);

  private final FuturesContractSource contracts;
  private final QuoteGateway quoteGateway;
  private final FuturesOiSnapshotRepository repository;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final List<String> underlyings;
  private final Counter rows;
  private final Map<String, Long> previousOi = new ConcurrentHashMap<>();

  public FuturesOiSnapshotService(
      FuturesContractSource contracts,
      QuoteGateway quoteGateway,
      FuturesOiSnapshotRepository repository,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.futures.oi-snapshot-underlyings:NIFTY 50,NIFTY BANK}")
          List<String> underlyings,
      MeterRegistry meterRegistry) {
    this.contracts = contracts;
    this.quoteGateway = quoteGateway;
    this.repository = repository;
    this.calendar = calendar;
    this.clock = clock;
    this.underlyings = underlyings;
    this.rows = meterRegistry.counter("ay_futures_oi_snapshot_rows_total");
  }

  /** Configurable cadence (default 3 min), market hours only. */
  @Scheduled(
      fixedDelayString = "${artha.futures.oi-snapshot-interval-ms:180000}",
      initialDelay = 70_000)
  public void scheduledSnapshot() {
    if (!isOpenSafe()) {
      return;
    }
    snapshotNow();
  }

  /** One pass across every configured underlying's front/next/far contracts. */
  public void snapshotNow() {
    OffsetDateTime ts = OffsetDateTime.now(clock);
    LocalDate today = ts.atZoneSameInstant(Ist.ZONE).toLocalDate();
    List<FuturesOiSnapshotRepository.Row> out = new ArrayList<>();
    for (String configured : underlyings) {
      String underlying = configured.trim();
      List<FutContract> ladder;
      try {
        ladder = contracts.monthlyFutures(underlying, today);
      } catch (RuntimeException resolveFailed) {
        log.warn("futures OI snapshot resolve failed for {}: {}", underlying, resolveFailed.getMessage());
        continue;
      }
      if (ladder.isEmpty()) {
        continue;
      }
      List<InstrumentKey> keys = ladder.stream().map(FutContract::key).toList();
      Map<InstrumentKey, QuoteGateway.Quote> quotes = quoteGateway.quotes(keys);
      for (FutContract contract : ladder) {
        QuoteGateway.Quote quote = quotes.get(contract.key());
        if (quote == null) {
          continue;
        }
        String symbol = contract.key().tradingsymbol();
        Long oi = quote.oi();
        Long previous = previousOi.get(symbol);
        Long oiChange = (oi != null && previous != null) ? oi - previous : null;
        QuoteGateway.Quote.Ohlc ohlc = quote.ohlc();
        out.add(
            new FuturesOiSnapshotRepository.Row(
                ts, underlying, symbol, contract.expiry(),
                quote.lastPrice(), quote.volume(), oi, oiChange,
                ohlc == null ? null : ohlc.open(),
                ohlc == null ? null : ohlc.high(),
                ohlc == null ? null : ohlc.low(),
                ohlc == null ? null : ohlc.close()));
        if (oi != null) {
          previousOi.put(symbol, oi);
        }
      }
    }
    if (!out.isEmpty()) {
      repository.insertAll(out);
      rows.increment(out.size());
    }
  }

  private boolean isOpenSafe() {
    try {
      return calendar.isOpen(clock.instant());
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
