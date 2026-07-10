package in.arthayantra.marketdata.nse;

import in.arthayantra.marketdata.feeds.FiiDerivativeFetcher;
import in.arthayantra.marketdata.feeds.FiiDiiFetcher;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily NSE EOD pull (B-1b). FII/DII cash + participant-wise OI; later non-candle sources add their
 * fetch here. Pulls once on startup (immediate data + the live-fetch canary) and daily after close.
 * Each source has its own try/catch — a fetch failure is logged, never fatal, and never blocks the
 * other sources (NSE anti-bot/outage must not break the service).
 *
 * <p>Security-wise bhavcopy moved to {@code BhavcopyBackfillService} (Phase A): it now self-heals
 * across missed days and projects into {@code candles}, so it owns its own schedule.
 */
@Component
public class NseEodScheduler {

  private static final Logger log = LoggerFactory.getLogger(NseEodScheduler.class);

  private final FiiDiiFetcher fiiDii;
  private final NseEodFiiDiiRepository fiiDiiRepo;
  private final ParticipantOiFetcher participantOi;
  private final NseEodParticipantOiRepository participantOiRepo;
  private final ObjectProvider<FiiDerivativeFetcher> fiiDerivative;
  private final NseEodFiiDerivativeRepository fiiDerivativeRepo;
  private final IngestRunLedger ledger;

  public NseEodScheduler(
      FiiDiiFetcher fiiDii,
      NseEodFiiDiiRepository fiiDiiRepo,
      ParticipantOiFetcher participantOi,
      NseEodParticipantOiRepository participantOiRepo,
      ObjectProvider<FiiDerivativeFetcher> fiiDerivative,
      NseEodFiiDerivativeRepository fiiDerivativeRepo,
      IngestRunLedger ledger) {
    this.fiiDii = fiiDii;
    this.fiiDiiRepo = fiiDiiRepo;
    this.participantOi = participantOi;
    this.participantOiRepo = participantOiRepo;
    this.fiiDerivative = fiiDerivative;
    this.fiiDerivativeRepo = fiiDerivativeRepo;
    this.ledger = ledger;
  }

  /** Pull once on startup so data is present immediately and the NSE fetch path is exercised. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    pullAll();
  }

  /** Daily after close (default 19:00 IST). */
  @Scheduled(cron = "${artha.nse.eod-cron:0 0 19 * * MON-FRI}", zone = "Asia/Kolkata")
  public void scheduledPull() {
    pullAll();
  }

  private void pullAll() {
    pullFiiDii();
    pullParticipantOi();
    pullFiiDerivative();
  }

  private void pullFiiDii() {
    try {
      ledger.record(
          IngestRunLedger.SOURCE_NSE_FII_DII,
          () -> {
            var rows = fiiDii.fetchLatest();
            fiiDiiRepo.upsertAll(rows);
            log.info("NSE FII/DII EOD upserted {} rows", rows.size());
            return rows.size();
          });
    } catch (RuntimeException failed) {
      log.warn("NSE FII/DII EOD pull failed (will retry next schedule): {}", failed.getMessage());
    }
  }

  private void pullParticipantOi() {
    try {
      ledger.record(
          IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI,
          () -> {
            var rows = participantOi.fetchLatest();
            participantOiRepo.upsertAll(rows);
            log.info("NSE participant-OI EOD upserted {} rows", rows.size());
            return rows.size();
          });
    } catch (RuntimeException failed) {
      log.warn(
          "NSE participant-OI EOD pull failed (will retry next schedule): {}", failed.getMessage());
    }
  }

  /**
   * FII derivative net stats (ADR-0002 U6). Upstox-only — no NSE source — so the fetcher bean is
   * present in {@code live} only when the Upstox analytics token is enabled; its absence is a no-op,
   * not a failure.
   */
  private void pullFiiDerivative() {
    FiiDerivativeFetcher fetcher = fiiDerivative.getIfAvailable();
    if (fetcher == null) {
      return;
    }
    try {
      ledger.record(
          IngestRunLedger.SOURCE_NSE_FII_DERIVATIVE,
          () -> {
            var rows = fetcher.fetchLatest();
            fiiDerivativeRepo.upsertAll(rows);
            log.info("FII derivative-stats EOD upserted {} rows", rows.size());
            return rows.size();
          });
    } catch (RuntimeException failed) {
      log.warn(
          "FII derivative-stats EOD pull failed (will retry next schedule): {}",
          failed.getMessage());
    }
  }
}
