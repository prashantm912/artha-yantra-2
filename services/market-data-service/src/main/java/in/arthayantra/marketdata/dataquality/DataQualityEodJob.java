package in.arthayantra.marketdata.dataquality;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.candles.TradingBuckets;
import in.arthayantra.marketdata.dataquality.DataQualityRepository.DataQualityRow;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.nse.NseEodBhavcopyRepository;
import in.arthayantra.marketdata.options.IvDailySummaryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly per-symbol completeness materialization for chain, 1m, and NSE EQ bhavcopy data. */
@Component
public class DataQualityEodJob {

  private static final Logger log = LoggerFactory.getLogger(DataQualityEodJob.class);

  private final DataQualityRepository repository;
  private final NseEodBhavcopyRepository bhavcopy;
  private final IvDailySummaryRepository ivSummary;
  private final CandleRepository candles;
  private final TradingBuckets buckets;
  private final IngestRunLedger ledger;
  private final Clock clock;
  private final boolean enabled;
  private final List<String> chainUnderlyings;
  private final long chainExpectedPasses;
  private final BigDecimal chainMinCoveragePct;
  private final List<String> intradayInstruments;
  private final BigDecimal intradayMinCoveragePct;
  private final BigDecimal bhavcopyMinRatio;

  /** Wires the three data readers, persistence, ledger, clock, and completeness thresholds. */
  public DataQualityEodJob(
      DataQualityRepository repository,
      NseEodBhavcopyRepository bhavcopy,
      IvDailySummaryRepository ivSummary,
      CandleRepository candles,
      TradingBuckets buckets,
      IngestRunLedger ledger,
      Clock clock,
      @Value("${artha.data-quality.enabled:true}") boolean enabled,
      @Value("${artha.options.snapshot-underlyings:NIFTY 50}") List<String> chainUnderlyings,
      @Value("${artha.data-quality.chain-expected-passes:187}") long chainExpectedPasses,
      @Value("${artha.data-quality.chain-min-coverage-pct:80}") BigDecimal chainMinCoveragePct,
      @Value(
              "${artha.data-quality.intraday-1m-instruments:"
                  + "NSE:NIFTY 50,NSE:NIFTY BANK,BSE:SENSEX}")
          List<String> intradayInstruments,
      @Value("${artha.data-quality.intraday-min-coverage-pct:95}")
          BigDecimal intradayMinCoveragePct,
      @Value("${artha.data-quality.bhavcopy-min-ratio:0.98}") BigDecimal bhavcopyMinRatio) {
    this.repository = repository;
    this.bhavcopy = bhavcopy;
    this.ivSummary = ivSummary;
    this.candles = candles;
    this.buckets = buckets;
    this.ledger = ledger;
    this.clock = clock;
    this.enabled = enabled;
    this.chainUnderlyings = chainUnderlyings;
    this.chainExpectedPasses = chainExpectedPasses;
    this.chainMinCoveragePct = chainMinCoveragePct;
    this.intradayInstruments = intradayInstruments;
    this.intradayMinCoveragePct = intradayMinCoveragePct;
    this.bhavcopyMinRatio = bhavcopyMinRatio;
  }

  /** Boot one-shot so a stack started after the EOD cron still materializes the settled day. */
  @EventListener(ApplicationReadyEvent.class)
  void onStartup() {
    runQuietly("startup");
  }

  /** Nightly run at 19:50 IST, after the settled bhavcopy is expected to have landed. */
  @Scheduled(cron = "${artha.data-quality.eod-cron:0 7,22,37,52 18-19 * * MON-FRI}", zone = "Asia/Kolkata")
  void scheduled() {
    runQuietly("scheduled");
  }

  private void runQuietly(String trigger) {
    if (!enabled) {
      return;
    }
    Long runId = null;
    try {
      LocalDate day = bhavcopy.maxTradeDate();
      if (day == null) {
        log.info("data-quality report skipped ({}) — no bhavcopy rows yet", trigger);
        return;
      }
      if (repository.hasRowsFor(day)) {
        log.debug("data-quality report already exists for {} — skipped ({})", day, trigger);
        return;
      }
      runId = ledger.start(IngestRunLedger.SOURCE_DATA_QUALITY);
      List<DataQualityRow> rows = compute(day);
      int written = repository.upsertAll(day, rows);
      ledger.succeed(runId, written);
      log.info("data-quality report wrote {} row(s) for {} ({})", written, day, trigger);
    } catch (RuntimeException e) {
      ledger.fail(runId, e.getMessage());
      log.warn("data-quality report failed ({}) — non-fatal", trigger, e);
    }
  }

  private List<DataQualityRow> compute(LocalDate day) {
    OffsetDateTime computedAt = OffsetDateTime.now(clock);
    List<DataQualityRow> rows = new ArrayList<>();
    addChainRows(day, computedAt, rows);
    addIntradayRows(day, computedAt, rows);
    addBhavcopyRows(day, computedAt, rows);
    return rows;
  }

  private void addChainRows(
      LocalDate day, OffsetDateTime computedAt, List<DataQualityRow> rows) {
    for (String configured : chainUnderlyings) {
      String underlying = configured.trim();
      long present = ivSummary.snapshotCountOn(underlying, day);
      BigDecimal coverage = coverage(present, chainExpectedPasses);
      rows.add(
          new DataQualityRow(
              day,
              "chain_capture",
              underlying,
              chainExpectedPasses,
              present,
              coverage,
              coverage != null && coverage.compareTo(chainMinCoveragePct) >= 0,
              null,
              computedAt));
    }
  }

  private void addIntradayRows(
      LocalDate day, OffsetDateTime computedAt, List<DataQualityRow> rows) {
    OffsetDateTime from = day.atTime(MarketCalendar.SESSION_OPEN).atOffset(Ist.OFFSET);
    OffsetDateTime to = day.atTime(MarketCalendar.SESSION_CLOSE).atOffset(Ist.OFFSET);
    long expected = buckets.minuteBuckets(from, to).size();
    for (String configured : intradayInstruments) {
      InstrumentRef instrument = parseInstrument(configured);
      long present =
          candles
              .presentBuckets(instrument.exchange(), instrument.symbol(), "1m", from, to)
              .size();
      BigDecimal coverage = coverage(present, expected);
      rows.add(
          new DataQualityRow(
              day,
              "intraday_1m",
              instrument.symbol(),
              expected,
              present,
              coverage,
              coverage != null && coverage.compareTo(intradayMinCoveragePct) >= 0,
              null,
              computedAt));
    }
  }

  private void addBhavcopyRows(
      LocalDate day, OffsetDateTime computedAt, List<DataQualityRow> rows) {
    Set<String> today = bhavcopy.eqSymbolsOn(day);
    LocalDate priorDay = bhavcopy.prevTradeDate(day);
    Set<String> prior = priorDay == null ? Set.of() : bhavcopy.eqSymbolsOn(priorDay);
    Set<String> dropped = new TreeSet<>(prior);
    dropped.removeAll(today);
    for (String symbol : dropped) {
      rows.add(
          new DataQualityRow(
              day,
              "bhavcopy_eq",
              symbol,
              1,
              0,
              BigDecimal.ZERO.setScale(2),
              false,
              "absent vs prior day " + priorDay,
              computedAt));
    }

    long expected = prior.size();
    long present = today.size();
    BigDecimal coverage = coverage(present, expected);
    boolean ok =
        expected == 0
            || BigDecimal.valueOf(present)
                    .compareTo(BigDecimal.valueOf(expected).multiply(bhavcopyMinRatio))
                >= 0;
    rows.add(
        new DataQualityRow(
            day,
            "bhavcopy_eq",
            "__SUMMARY__",
            expected,
            present,
            coverage,
            ok,
            priorDay == null ? "no prior settled trade date" : null,
            computedAt));
  }

  private static BigDecimal coverage(long present, long expected) {
    if (expected == 0) {
      return null;
    }
    return BigDecimal.valueOf(present)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(expected), 2, RoundingMode.HALF_UP);
  }

  private static InstrumentRef parseInstrument(String configured) {
    int separator = configured.indexOf(':');
    if (separator <= 0 || separator == configured.length() - 1) {
      throw new IllegalArgumentException(
          "intraday-1m instrument must be exchange:tradingsymbol: " + configured);
    }
    return new InstrumentRef(
        configured.substring(0, separator).trim(), configured.substring(separator + 1).trim());
  }

  private record InstrumentRef(String exchange, String symbol) {}
}
