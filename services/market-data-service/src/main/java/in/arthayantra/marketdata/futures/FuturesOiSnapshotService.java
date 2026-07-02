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
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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

  /** The cron cadence; the gate shifts back by this much (see OptionsSnapshotService.CAPTURE_CADENCE). */
  private static final Duration CAPTURE_CADENCE = Duration.ofMinutes(1);

  /**
   * Boundary-aligned capture (audit 2026-07-02 §9.1, T2): cron on the IST minute grid, replacing
   * the boot-phase fixedDelay. One batched quotes() call per pass (~2 s), so every minute is
   * affordable; the shifted gate skips the 09:15:00 fire and keeps the 15:30:00 EOD fire.
   */
  @Scheduled(cron = "${artha.futures.oi-snapshot-cron:0 * * * * *}", zone = "Asia/Kolkata")
  public void scheduledSnapshot() {
    if (!isOpenSafe(clock.instant().minus(CAPTURE_CADENCE))) {
      return;
    }
    snapshotNow();
  }

  /** One pass across every configured underlying's front/next/far contracts. */
  public void snapshotNow() {
    // Entry-stamped on the minute grid (T2) — the readers' end-of-window bucketing depends on it.
    OffsetDateTime ts = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    LocalDate today = ts.atZoneSameInstant(Ist.ZONE).toLocalDate();

    // Resolve every underlying's ladder first, then batch ALL contracts into ONE quotes()
    // call. The kite-quote limiter is 1/s with a 5s timeout, so a call-per-underlying loop
    // 429s past ~5-6 underlyings; Kite accepts up to 250 instruments per quote call.
    record Pinned(String underlying, FutContract contract) {}
    List<Pinned> pinned = new ArrayList<>();
    for (String configured : underlyings) {
      String underlying = configured.trim();
      List<FutContract> ladder;
      try {
        ladder = contracts.monthlyFutures(underlying, today);
      } catch (RuntimeException resolveFailed) {
        log.warn("futures OI snapshot resolve failed for {}: {}", underlying, resolveFailed.getMessage());
        continue;
      }
      for (FutContract contract : ladder) {
        pinned.add(new Pinned(underlying, contract));
      }
    }
    if (pinned.isEmpty()) {
      return;
    }

    List<InstrumentKey> keys = pinned.stream().map(p -> p.contract().key()).toList();
    Map<InstrumentKey, QuoteGateway.Quote> quotes = quoteGateway.quotes(keys);

    List<FuturesOiSnapshotRepository.Row> out = new ArrayList<>();
    for (Pinned p : pinned) {
      QuoteGateway.Quote quote = quotes.get(p.contract().key());
      if (quote == null) {
        continue;
      }
      String symbol = p.contract().key().tradingsymbol();
      Long oi = quote.oi();
      Long previous = previousOi.get(symbol);
      Long oiChange = (oi != null && previous != null) ? oi - previous : null;
      QuoteGateway.Quote.Ohlc ohlc = quote.ohlc();
      out.add(
          new FuturesOiSnapshotRepository.Row(
              ts, p.underlying(), symbol, p.contract().expiry(),
              quote.lastPrice(), quote.volume(), oi, oiChange,
              ohlc == null ? null : ohlc.open(),
              ohlc == null ? null : ohlc.high(),
              ohlc == null ? null : ohlc.low(),
              ohlc == null ? null : ohlc.close()));
      if (oi != null) {
        previousOi.put(symbol, oi);
      }
    }
    if (!out.isEmpty()) {
      repository.insertAll(out);
      rows.increment(out.size());
    }
  }

  private boolean isOpenSafe(java.time.Instant instant) {
    try {
      return calendar.isOpen(instant);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
