package in.arthayantra.marketdata.bhavcopy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ConflictException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.bse.BseBhavcopyFetcher;
import in.arthayantra.marketdata.bse.BseBhavcopyFetcher.BseBhavRow;
import in.arthayantra.marketdata.bse.BseCorporateActionFetcher;
import in.arthayantra.marketdata.bse.BseCorporateActionFetcher.BseCaRecord;
import in.arthayantra.marketdata.bse.BseEodBhavcopyRepository;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.candles.EodCorporateActionRepository;
import in.arthayantra.marketdata.dividends.DividendRepository;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.lineage.SymbolRenameEventRepository;
import in.arthayantra.marketdata.nse.BhavcopyFetcher;
import in.arthayantra.marketdata.nse.BhavcopyFetcher.BhavcopyRow;
import in.arthayantra.marketdata.nse.NseCorporateActionFetcher;
import in.arthayantra.marketdata.nse.NseCorporateActionFetcher.CaRecord;
import in.arthayantra.marketdata.nse.NseEodBhavcopyRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Self-healing EOD bhavcopy backfill (Phase A/B). Where {@code NseEodScheduler}'s legacy pull only
 * grabbed the single latest file, this walks every missing trading day from the table watermark
 * (max {@code trade_date}) forward to today: each day's published bhavcopy is upserted into the raw
 * {@code nse_eod_bhavcopy} table AND projected into {@code candles} as 1d {@code source='BHAVCOPY'}
 * bars (DO-NOTHING, so Kite-owned bars are never clobbered). A non-trading day (404 → empty rows)
 * is skipped. So if the job missed days/weeks, the next run downloads and applies all of them.
 *
 * <p>One run at a time (an {@link AtomicBoolean} gate): the daily scheduler skips when a manual
 * trigger is in flight, and the manual trigger 409s when a run is in flight.
 */
@Service
public class BhavcopyBackfillService {

  private static final Logger log = LoggerFactory.getLogger(BhavcopyBackfillService.class);

  /**
   * How many unmatched corporate-action subjects to quote in the per-run summary.
   *
   * <p>Bounded on purpose: the 420-day lookback carries thousands of non-ratio actions, so logging
   * each one would bury the summary it is meant to explain. Three is enough to recognise a SHAPE —
   * which is all that is needed to tell "this category is absent from the feed" from "this category
   * arrives and no parser matches it".
   */
  private static final int UNMATCHED_SAMPLES = 3;

  private final BhavcopyFetcher nseFetcher;
  private final NseEodBhavcopyRepository nseRepo;
  private final BseBhavcopyFetcher bseFetcher;
  private final BseEodBhavcopyRepository bseRepo;
  private final NseCorporateActionFetcher nseCa;
  private final BseCorporateActionFetcher bseCa;
  private final EodCorporateActionRepository caRepo;
  private final DividendRepository dividendRepo;
  private final SymbolRenameEventRepository renameRepo;
  private final CandleRepository candles;
  private final Clock clock;
  private final ApplicationEventPublisher events;
  private final in.arthayantra.marketdata.alerts.NtfyClient ntfy;
  private final IngestRunLedger ledger;

  /** How far back to refresh corporate-action ratios each run (one cheap API call). */
  private final int caLookbackDays;

  /** NSE series projected to candles (cash equities). Non-equity series (GS, etc.) stay raw-only. */
  private final Set<String> candleSeries;

  /** When the table is empty, start this many days back instead of walking from the epoch. */
  private final int catchupFloorDays;

  /** Hard cap on the catch-up span so a stale/empty watermark can't unleash an unbounded NSE walk. */
  private final int catchupMaxDays;

  /**
   * Trailing window (days, below the watermark) re-scanned every run. The catch-up anti-joins against
   * the dates already stored, so a day missed by a transient fetch error — which is indistinguishable
   * from a holiday (both yield an empty list) — is left MISSING and re-probed on a later run instead
   * of being skipped forever once the watermark advances past it. Keep ≤ catchup-floor-days so the
   * steady-state window never re-fetches history below the first-boot floor.
   */
  private final int reconcileLookbackDays;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<Status> status = new AtomicReference<>(Status.neverRun());
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "eod-bhavcopy-backfill");
            t.setDaemon(true);
            return t;
          });

  public BhavcopyBackfillService(
      BhavcopyFetcher nseFetcher,
      NseEodBhavcopyRepository nseRepo,
      BseBhavcopyFetcher bseFetcher,
      BseEodBhavcopyRepository bseRepo,
      NseCorporateActionFetcher nseCa,
      BseCorporateActionFetcher bseCa,
      EodCorporateActionRepository caRepo,
      DividendRepository dividendRepo,
      SymbolRenameEventRepository renameRepo,
      CandleRepository candles,
      Clock clock,
      ApplicationEventPublisher events,
      in.arthayantra.marketdata.alerts.NtfyClient ntfy,
      IngestRunLedger ledger,
      @Value("${artha.nse.bhavcopy.candle-series:EQ,BE}") String candleSeriesCsv,
      @Value("${artha.bhavcopy.catchup-floor-days:10}") int catchupFloorDays,
      @Value("${artha.bhavcopy.catchup-max-days:90}") int catchupMaxDays,
      @Value("${artha.bhavcopy.reconcile-lookback-days:7}") int reconcileLookbackDays,
      @Value("${artha.bhavcopy.ca-lookback-days:420}") int caLookbackDays) {
    this.nseFetcher = nseFetcher;
    this.nseRepo = nseRepo;
    this.bseFetcher = bseFetcher;
    this.bseRepo = bseRepo;
    this.nseCa = nseCa;
    this.bseCa = bseCa;
    this.caRepo = caRepo;
    this.dividendRepo = dividendRepo;
    this.renameRepo = renameRepo;
    this.candles = candles;
    this.clock = clock;
    this.events = events;
    this.ntfy = ntfy;
    this.ledger = ledger;
    this.reconcileLookbackDays = reconcileLookbackDays;
    this.caLookbackDays = caLookbackDays;
    this.candleSeries =
        Arrays.stream(candleSeriesCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    this.catchupFloorDays = catchupFloorDays;
    this.catchupMaxDays = catchupMaxDays;
  }

  // ---- run control -------------------------------------------------------------------------

  /** Daily after both exchanges publish (NSE ~19:00, BSE ~18:00 IST); default 19:30 IST. */
  @Scheduled(cron = "${artha.bhavcopy.eod-cron:0 45 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void scheduledBackfill() {
    runIfFree();
  }

  /** On-demand async trigger (202 + jobId). 409 {@code CONFLICT_BACKFILL_RUNNING} when in flight. */
  @SuppressWarnings("FutureReturnValueIgnored")
  public String triggerAsync() {
    if (!running.compareAndSet(false, true)) {
      throw new ConflictException(
          ErrorCodes.CONFLICT_BACKFILL_RUNNING, "EOD bhavcopy backfill already running");
    }
    String jobId = UUID.randomUUID().toString();
    status.set(Status.running(jobId, clock.instant()));
    executor.submit(() -> runLocked(jobId));
    return jobId;
  }

  /**
   * Scheduler/startup entry — submits the catch-up to the service's OWN executor (never the Spring
   * scheduler thread, which a multi-day walk would block), or skips silently if a run is in flight.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void runIfFree() {
    if (!running.compareAndSet(false, true)) {
      log.info("EOD bhavcopy backfill already running — schedule skip");
      return;
    }
    String jobId = UUID.randomUUID().toString();
    status.set(Status.running(jobId, clock.instant()));
    executor.submit(() -> runLocked(jobId));
  }

  public Status status() {
    return status.get();
  }

  /**
   * Targeted re-fetch of ONE explicit trading day (§8d) — the correction path the self-healing
   * catch-up cannot reach. The catch-up anti-joins against the dates already stored, so a day that
   * was PARTIALLY captured (e.g. a truncated 2026-07-02 NSE bhavcopy: ~167 of ~2380 EQ rows) is
   * {@code present} and therefore skipped forever, even inside the reconcile window. This bypasses the
   * present-check and re-pulls the day outright: the raw upsert fills the missing rows (existing rows
   * are re-written identically) and the DO-NOTHING candle projection back-fills the newly-seen symbols
   * (a Kite-owned bar is never clobbered — the same write path the catch-up uses). Runs synchronously
   * under the one-run-at-a-time gate (409 when a catch-up is in flight) and returns the per-exchange
   * tally. A non-trading / not-yet-published date simply fetches empty (0 rows), never an error.
   */
  public RefetchResult refetchDate(LocalDate date) {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    if (date == null || date.isAfter(today)) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED, "date must be a past or current trading day");
    }
    if (!running.compareAndSet(false, true)) {
      throw new ConflictException(
          ErrorCodes.CONFLICT_BACKFILL_RUNNING, "EOD bhavcopy backfill already running");
    }
    Long runId = ledger.start(IngestRunLedger.SOURCE_BHAVCOPY);
    try {
      ExchangeResult nse = safe("NSE refetch " + date, () -> runNseDay(date));
      ExchangeResult bse = safe("BSE refetch " + date, () -> runBseDay(date));
      ledger.succeed(runId, (long) nse.bhavRows() + bse.bhavRows());
      log.info(
          "EOD bhavcopy targeted re-fetch {} ok: NSE {}r/{}c, BSE {}r/{}c",
          date, nse.bhavRows(), nse.candleRows(), bse.bhavRows(), bse.candleRows());
      return new RefetchResult(date.toString(), nse, bse);
    } catch (RuntimeException e) {
      ledger.fail(runId, e.getMessage());
      throw e;
    } finally {
      running.set(false);
    }
  }

  private void runLocked(String jobId) {
    Instant started = clock.instant();
    status.set(Status.running(jobId, started));
    // Ingest-run ledger (audit §7.2.3): rows_written = raw bhavcopy rows upserted across both
    // exchanges. Window is left null — the catch-up spans differ per exchange and are derived deep
    // inside runNse/runBse; started_at/finished_at carry the run timing.
    Long runId = ledger.start(IngestRunLedger.SOURCE_BHAVCOPY);
    try {
      // Each exchange is isolated — an NSE outage must not block the BSE catch-up (and vice versa).
      ExchangeResult nse = safe("NSE", this::runNse);
      ExchangeResult bse = safe("BSE", this::runBse);
      int ratios = 0;
      try {
        ratios = runRatios();
      } catch (RuntimeException e) {
        log.warn("EOD corporate-action ratio sync failed: {}", e.getMessage());
      }
      Status ok = Status.ok(jobId, started, clock.instant(), nse, bse, ratios);
      status.set(ok);
      ledger.succeed(runId, (long) nse.bhavRows() + bse.bhavRows());
      log.info(
          "EOD bhavcopy backfill {} ok: NSE {}d/{}r/{}c, BSE {}d/{}r/{}c in {} ms",
          jobId,
          nse.days(), nse.bhavRows(), nse.candleRows(),
          bse.days(), bse.bhavRows(), bse.candleRows(),
          ok.durationMs());
      publishCompleted(jobId);
    } catch (RuntimeException e) {
      // Audit P0-4/H10: a failed backfill silently starves the whole EOD chain (no completion
      // event → screens stay stale → the swing batches trade yesterday's funnel). NtfyClient
      // never throws.
      log.error("EOD bhavcopy backfill {} failed", jobId, e);
      status.set(Status.failed(jobId, started, clock.instant(), e.getMessage()));
      ledger.fail(runId, e.getMessage());
      ntfy.send(
          "EOD bhavcopy backfill FAILED", "high",
          "Job " + jobId + ": " + e.getMessage()
              + " — screens/funnel/swing batches will run on the stale watermark.");
    } finally {
      running.set(false);
    }
  }

  /**
   * Notifies listeners (the Minervini/Manas screen schedulers) that the backfill run completed.
   * Fired AFTER the ok status is set; a listener failure must never flip the backfill status to
   * failed, so the publish is isolated. Listeners run synchronously on this (bhavcopy-executor)
   * thread — the screen SQL is fast but the per-passer geometry fan-out can take minutes; nothing
   * queues behind this executor (next cron is a day away), so that is acceptable. NOTE:
   * "completed" ≠ "fresh data" — {@code safe()} swallows per-exchange failures, so a run where
   * both exchanges returned nothing still publishes (the listeners' watermark guard makes that a
   * cheap no-op). The screens' fallback crons therefore exist for a CRASHED/HUNG run, not for a
   * fetch failure.
   */
  private void publishCompleted(String jobId) {
    try {
      events.publishEvent(new BhavcopyBackfillCompleted(jobId));
    } catch (RuntimeException e) {
      log.warn("bhavcopy-completed listeners failed for {} — non-fatal", jobId, e);
    }
  }

  private ExchangeResult safe(String label, java.util.function.Supplier<ExchangeResult> run) {
    try {
      return run.get();
    } catch (RuntimeException e) {
      log.warn("EOD bhavcopy {} catch-up failed: {}", label, e.getMessage());
      return ExchangeResult.none();
    }
  }

  // ---- NSE catch-up ------------------------------------------------------------------------

  /**
   * Lower bound of the catch-up window. A trailing reconcile window below the watermark (so a
   * recently-missed day is re-probed), or a bounded recent window when the table is empty; never
   * older than the hard cap.
   */
  private LocalDate catchupStart(LocalDate watermark, LocalDate today) {
    LocalDate start =
        watermark != null
            ? watermark.minusDays(reconcileLookbackDays)
            : today.minusDays(catchupFloorDays);
    LocalDate floor = today.minusDays(catchupMaxDays);
    if (start.isBefore(floor)) {
      if (watermark != null) {
        log.warn(
            "bhavcopy watermark {} is older than the {}-day cap; starting from {}",
            watermark, catchupMaxDays, floor);
      }
      start = floor;
    }
    return start;
  }

  /**
   * Fills every MISSING trading day in the catch-up window. Anti-joins against the dates already
   * stored, so a day that an earlier run missed (a transient fetch error reads the same as a
   * holiday — both empty) is re-attempted here rather than left as a permanent hole once the
   * watermark advances past it.
   */
  ExchangeResult runNse() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    LocalDate start = catchupStart(nseRepo.maxTradeDate(), today);
    Set<LocalDate> present = Set.copyOf(nseRepo.presentTradeDates(start, today));

    int days = 0;
    int bhavRows = 0;
    int candleRows = 0;
    for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
      DayOfWeek dow = d.getDayOfWeek();
      if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY || present.contains(d)) {
        continue; // weekend, or already stored (anti-join leaves a missing day to be re-probed)
      }
      List<BhavcopyRow> rows = nseFetcher.fetchForDate(d);
      if (rows.isEmpty()) {
        continue; // not published (holiday / not yet posted) OR a transient error — retried next run
      }
      nseRepo.upsertAll(rows);
      candleRows += candles.insertIgnoreAll(projectNse(rows));
      bhavRows += rows.size();
      days++;
    }
    log.info("NSE bhavcopy catch-up {}..{}: {} days, {} rows, {} candles", start, today, days, bhavRows, candleRows);
    return new ExchangeResult(days, bhavRows, candleRows);
  }

  /**
   * Re-pulls ONE explicit NSE trading day unconditionally (the §8d partial-day correction) — the
   * catch-up's per-day body without the present-check anti-join, so a partially-stored day is filled.
   * Same write path as {@link #runNse}: raw upsert (re-writes existing rows identically, inserts the
   * missing ones) + DO-NOTHING candle projection (never clobbers a Kite-owned bar).
   */
  ExchangeResult runNseDay(LocalDate date) {
    List<BhavcopyRow> rows = nseFetcher.fetchForDate(date);
    if (rows.isEmpty()) {
      return ExchangeResult.none();
    }
    nseRepo.upsertAll(rows);
    int candleRows = candles.insertIgnoreAll(projectNse(rows));
    log.info("NSE bhavcopy re-fetch {}: {} rows, {} candles", date, rows.size(), candleRows);
    return new ExchangeResult(1, rows.size(), candleRows);
  }

  private List<Candle> projectNse(List<BhavcopyRow> rows) {
    List<Candle> out = new ArrayList<>(rows.size());
    for (BhavcopyRow r : rows) {
      if (!candleSeries.contains(r.series())) {
        continue;
      }
      Candle c =
          BhavcopyCandles.of(
              "NSE", r.symbol(), r.date(), r.open(), r.high(), r.low(), r.close(), r.totalTradedQty());
      if (c != null) {
        out.add(c);
      }
    }
    return out;
  }

  // ---- BSE catch-up ------------------------------------------------------------------------

  /** Same self-healing anti-join walk for BSE (the fetcher content-sniffs non-trading days, not 404). */
  ExchangeResult runBse() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    LocalDate start = catchupStart(bseRepo.maxTradeDate(), today);
    Set<LocalDate> present = Set.copyOf(bseRepo.presentTradeDates(start, today));

    int days = 0;
    int bhavRows = 0;
    int candleRows = 0;
    for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
      DayOfWeek dow = d.getDayOfWeek();
      if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY || present.contains(d)) {
        continue;
      }
      List<BseBhavRow> rows = bseFetcher.fetchForDate(d);
      if (rows.isEmpty()) {
        continue;
      }
      bseRepo.upsertAll(rows);
      candleRows += candles.insertIgnoreAll(projectBse(rows));
      bhavRows += rows.size();
      days++;
    }
    log.info("BSE bhavcopy catch-up {}..{}: {} days, {} rows, {} candles", start, today, days, bhavRows, candleRows);
    return new ExchangeResult(days, bhavRows, candleRows);
  }

  /** Re-pulls ONE explicit BSE trading day unconditionally — the BSE twin of {@link #runNseDay}. */
  ExchangeResult runBseDay(LocalDate date) {
    List<BseBhavRow> rows = bseFetcher.fetchForDate(date);
    if (rows.isEmpty()) {
      return ExchangeResult.none();
    }
    bseRepo.upsertAll(rows);
    int candleRows = candles.insertIgnoreAll(projectBse(rows));
    log.info("BSE bhavcopy re-fetch {}: {} rows, {} candles", date, rows.size(), candleRows);
    return new ExchangeResult(1, rows.size(), candleRows);
  }

  private List<Candle> projectBse(List<BseBhavRow> rows) {
    List<Candle> out = new ArrayList<>(rows.size());
    for (BseBhavRow r : rows) {
      if (r.ticker() == null || r.ticker().isBlank()) {
        continue; // can't key a candle without a tradingsymbol
      }
      Candle c =
          BhavcopyCandles.of(
              "BSE", r.ticker(), r.date(), r.open(), r.high(), r.low(), r.close(), r.totalTradedQty());
      if (c != null) {
        out.add(c);
      }
    }
    return out;
  }

  // ---- corporate-action ratios (Phase C) ---------------------------------------------------

  /**
   * Refreshes split/bonus ratios from the NSE corporate-actions feed and stores them for the
   * read-time adjuster. Each parsed action is keyed on the NSE listing and cross-applied to the BSE
   * listing by ISIN. Returns the number of (exchange, symbol, ex-date) ratios written.
   */
  int runRatios() {
    int applied = 0;
    try {
      applied += runNseRatios().ratios();
    } catch (RuntimeException e) {
      log.warn("NSE corporate-action ratio sync failed: {}", e.getMessage());
    }
    try {
      applied += runBseRatios();
    } catch (RuntimeException e) {
      log.warn("BSE corporate-action ratio sync failed: {}", e.getMessage());
    }
    return applied;
  }

  /** NSE corporate actions → ratios, keyed on the NSE listing and cross-mapped to BSE by ISIN. */
  CaPartition runNseRatios() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    LocalDate from = today.minusDays(caLookbackDays);
    List<CaRecord> actions = nseCa.fetchRecent(from, today);
    int applied = 0;
    // ⚠️ N2 OBSERVABILITY. Every non-ratio subject that matches NEITHER parser falls through the
    // `continue` below, and until now it left NO TRACE AT ALL — not a log line, not a count. That
    // is why the symbol-rename capture (#1299, merged 2026-08-10) could sit at ZERO rows for 24
    // days without anything noticing, and why "the feed carries no name changes" and "the parser
    // matches no name changes" were INDISTINGUISHABLE from outside: both look like silence.
    //
    // `computed` 2026-09-03: this loop sees ~5,063 non-ratio actions inside the 420-day lookback on
    // every run — the dividend arm wrote 5,481 rows — while `symbol_rename_events` held 0. At the
    // ~59 renames/year this feature was sized against, that is not rarity.
    //
    // Counting is the whole change. It decides the question on the next run instead of requiring
    // someone to re-derive it from three tables.
    int dividends = 0;
    int nameChanges = 0;
    int unmatched = 0;
    // ⚠️ THE DISCRIMINATOR, counted rather than hoped for. Review, 2026-09-03: keeping the first
    // three unmatched subjects in FEED ORDER is biased toward the answer I already believed.
    // Buybacks and AGMs vastly outnumber renames in this feed, so the samples would almost
    // certainly all be buybacks — which I would then have read as "the feed genuinely lacks name
    // changes", confirming my own hypothesis with evidence that could never have contradicted it.
    // An instrument that can only produce one of the two answers it was built to distinguish is
    // not an instrument.
    //
    // Counting the name-ish subjects directly IS decisive on the next run: >0 means they arrive
    // and no parser claims them (widen the parser); 0 means the feed does not carry them (a
    // different NSE endpoint is needed). The same ILIKE '%name%' probe was run by hand against the
    // dividend table while investigating — it simply never made it into the instrument.
    int unmatchedNameish = 0;
    List<String> unmatchedSamples = new ArrayList<>();
    for (CaRecord a : actions) {
      Optional<CorporateActionSubjectParser.Parsed> parsed =
          CorporateActionSubjectParser.parse(a.subject());
      if (parsed.isEmpty()) {
        if (DividendSubjectParser.isDividend(a.subject())) {
          dividends++;
          dividendRepo.upsert(
              "NSE",
              a.symbol(),
              a.exDate(),
              DividendSubjectParser.parseAmount(a.subject()).orElse(null),
              a.subject(),
              a.isin(),
              "NSE");
        } else if (NameChangeSubjectParser.isNameChange(a.subject())) {
          // N2 / #1285: a "Change in Name" arrives here WITH its ISIN attached and used to fall
          // straight through the continue below — not logged, not counted, not stored. It is the
          // best rename signal available and we were already paying to fetch it; capturing it is
          // what keeps symbol_lineage current instead of going stale at ~59 renames/year.
          nameChanges++;
          NameChangeSubjectParser.Names names =
              NameChangeSubjectParser.parseNames(a.subject()).orElse(null);
          renameRepo.upsert(
              "NSE",
              a.symbol(),
              a.exDate(),
              a.isin(),
              names == null ? null : names.fromName(),
              names == null ? null : names.toName(),
              a.subject(),
              "NSE");
        } else {
          // Buyback, AGM, EGM, listing changes — and anything a parser SHOULD have matched but
          // does not. Sampled rather than logged per row: the lookback holds thousands of these.
          unmatched++;
          String subject = a.subject() == null ? "" : a.subject();
          // ⚠️ "renam" as well as "name", and the test is what found this: "Renaming" does NOT
          // contain "name" (r-e-n-a-m-i-n-g). A discriminator that misses the word most likely to
          // appear in a rename subject would have reported 0 and been read as "the feed carries
          // none" — the same false-confirmation this counter exists to prevent, one level down.
          String lower = subject.toLowerCase(Locale.ROOT);
          if (lower.contains("name") || lower.contains("renam")) {
            unmatchedNameish++;
            // A name-ish subject that no parser claimed is the interesting one, so it displaces a
            // plain sample rather than queuing behind thousands of buybacks.
            if (unmatchedSamples.size() < UNMATCHED_SAMPLES) {
              unmatchedSamples.add(truncate(subject));
            } else {
              unmatchedSamples.set(UNMATCHED_SAMPLES - 1, truncate(subject));
            }
          } else if (unmatchedSamples.size() < UNMATCHED_SAMPLES) {
            unmatchedSamples.add(truncate(subject));
          }
        }
        continue; // dividend / name-change / buyback / AGM — not a price adjustment
      }
      CorporateActionSubjectParser.Parsed p = parsed.get();
      caRepo.upsert("NSE", a.symbol(), a.exDate(), p.ratio(), p.kind(), a.isin(), a.subject());
      applied++;
      String bseTicker = caRepo.bseTickerForIsin(a.isin());
      if (bseTicker != null) {
        caRepo.upsert("BSE", bseTicker, a.exDate(), p.ratio(), p.kind(), a.isin(), a.subject());
        applied++;
      }
    }
    // ⚠️ Report the WHOLE partition, not just the arm that succeeded. The previous line named only
    // `applied`, so a feed that stopped carrying an entire category read exactly like a quiet week.
    log.info(
        "NSE corporate-actions {}..{}: {} actions seen — {} split/bonus applied, {} dividends,"
            + " {} name-changes, {} unmatched{}",
        from,
        today,
        actions.size(),
        applied,
        dividends,
        nameChanges,
        unmatched,
        unmatchedNameish,
        unmatchedSamples.isEmpty() ? "" : " (e.g. " + String.join(" | ", unmatchedSamples) + ")");
    return new CaPartition(
        actions.size(),
        applied,
        dividends,
        nameChanges,
        unmatched,
        unmatchedNameish,
        List.copyOf(unmatchedSamples));
  }

  /**
   * How one run of the NSE corporate-action feed partitioned, returned rather than only logged.
   *
   * <p>⚠️ <b>Returned so it can be ASSERTED.</b> A log line is the right place to read this
   * operationally, but a log line cannot be tested — and the defect that motivated this change was
   * precisely a category going to zero with nothing to notice. Pinning the partition in a test is
   * what stops the observability itself from silently mis-attributing.
   *
   * @param seen every record the feed returned inside the lookback
   * @param ratios split/bonus ROWS written — deliberately not an arm of the partition. An action
   *     whose ISIN has a BSE twin writes TWO rows, so this does not sum with the others against
   *     {@code seen}, and the log line says so rather than inviting the arithmetic
   * @param dividends dividend rows written
   * @param nameChanges rename events written — the arm that has been at ZERO live
   * @param unmatched subjects no parser claimed: buybacks, AGMs, and anything a parser SHOULD have
   *     matched but does not
   * @param unmatchedNameish how many of {@code unmatched} mention "name" — THE discriminator.
   *     {@code >0} means rename subjects arrive and no parser claims them; {@code 0} means the feed
   *     does not carry them at all. Raw samples cannot answer this, because the common categories
   *     crowd them out
   * @param unmatchedSamples up to {@link #UNMATCHED_SAMPLES} truncated examples, enough to
   *     recognise a shape
   */
  record CaPartition(
      int seen,
      int ratios,
      int dividends,
      int nameChanges,
      int unmatched,
      int unmatchedNameish,
      List<String> unmatchedSamples) {}

  /** Bounds one logged subject. Public NSE text, but the line still must not sprawl. */
  private static String truncate(String subject) {
    return subject.length() > 120 ? subject.substring(0, 120) + "…" : subject;
  }

  /**
   * BSE corporate actions → ratios for BSE listings, including BSE-only scrips with no NSE twin
   * (those the NSE→ISIN cross-map can't reach). Keyed by the scrip's bhavcopy ticker, or the CA
   * short name when the scrip hasn't been seen in bhavcopy yet.
   */
  private int runBseRatios() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    LocalDate from = today.minusDays(caLookbackDays);
    List<BseCaRecord> actions = bseCa.fetchRecent(from, today);
    int applied = 0;
    for (BseCaRecord a : actions) {
      Optional<CorporateActionSubjectParser.Parsed> parsed =
          CorporateActionSubjectParser.parse(a.purpose());
      if (parsed.isEmpty()) {
        if (DividendSubjectParser.isDividend(a.purpose())) {
          String dividendTicker = bseRepo.tickerForScrip(a.scripCode());
          if (dividendTicker == null) {
            dividendTicker = a.shortName();
          }
          if (dividendTicker != null && !dividendTicker.isBlank()) {
            dividendRepo.upsert(
                "BSE",
                dividendTicker,
                a.exDate(),
                DividendSubjectParser.parseAmount(a.purpose()).orElse(null),
                a.purpose(),
                null,
                "BSE");
          }
        }
        continue;
      }
      String ticker = bseRepo.tickerForScrip(a.scripCode());
      if (ticker == null) {
        ticker = a.shortName();
      }
      if (ticker == null || ticker.isBlank()) {
        continue;
      }
      CorporateActionSubjectParser.Parsed p = parsed.get();
      caRepo.upsert("BSE", ticker, a.exDate(), p.ratio(), p.kind(), null, a.purpose());
      applied++;
    }
    log.info("BSE corporate-actions {}..{}: {} split/bonus ratios applied", from, today, applied);
    return applied;
  }

  // ---- status/result types -----------------------------------------------------------------

  /** The targeted single-day re-fetch tally, returned by {@code POST .../eod-backfill/refetch}. */
  public record RefetchResult(String date, ExchangeResult nse, ExchangeResult bse) {}

  /**
   * Per-exchange catch-up tally (raw rows upserted + candles newly inserted across the span).
   *
   * <p>{@code @Schema(name)} is load-bearing: {@code KiteSessionService.ExchangeResult} shares this
   * simple name with a COMPLETELY different field set, and springdoc keys components by simple name.
   * This twin won the scan, so the Kite-session endpoint published THESE three int fields. All-int,
   * so nothing here is nullable.
   */
  @Schema(name = "BhavcopyExchangeResult")
  public record ExchangeResult(int days, int bhavRows, int candleRows) {
    static ExchangeResult none() {
      return new ExchangeResult(0, 0, 0);
    }
  }

  /**
   * Last-run audit, surfaced by {@code GET /api/v1/market/eod-backfill/status}.
   *
   * <p>{@code @Schema(name)} is load-bearing: three sibling backfill services declare their own
   * {@code Status} record with a DIFFERENT field set, and springdoc keys components by simple name —
   * without the explicit name all four collapse to one spec schema (scan-order-dependent).
   *
   * <p>{@code jobId}/{@code lastRun}/{@code error} are null on the never-run shell (:591) and
   * {@code error} again while running (:595). {@code nse}/{@code bse} are {@code $ref}s and are never
   * null (every factory passes {@link ExchangeResult#none()} at minimum).
   */
  @Schema(name = "BhavcopyBackfillStatus")
  public record Status(
      @Schema(types = {"string", "null"}) String jobId,
      String state, // NEVER_RUN | RUNNING | OK | FAILED
      @Schema(types = {"string", "null"}) Instant lastRun,
      long durationMs,
      ExchangeResult nse,
      ExchangeResult bse,
      int ratiosDetected,
      @Schema(types = {"string", "null"}) String error) {

    static Status neverRun() {
      return new Status(null, "NEVER_RUN", null, 0, ExchangeResult.none(), ExchangeResult.none(), 0, null);
    }

    static Status running(String jobId, Instant started) {
      return new Status(jobId, "RUNNING", started, 0, ExchangeResult.none(), ExchangeResult.none(), 0, null);
    }

    static Status ok(String jobId, Instant started, Instant ended, ExchangeResult nse, ExchangeResult bse, int ratios) {
      return new Status(jobId, "OK", started, java.time.Duration.between(started, ended).toMillis(), nse, bse, ratios, null);
    }

    static Status failed(String jobId, Instant started, Instant ended, String error) {
      return new Status(jobId, "FAILED", started, java.time.Duration.between(started, ended).toMillis(),
          ExchangeResult.none(), ExchangeResult.none(), 0, error);
    }
  }
}
