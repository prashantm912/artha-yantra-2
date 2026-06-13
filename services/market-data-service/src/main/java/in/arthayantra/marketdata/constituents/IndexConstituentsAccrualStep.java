package in.arthayantra.marketdata.constituents;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Accrues configured indices' membership on every instrument sync (the daily 08:30 IST sync is
 * the production cadence — history is time-sensitive, S8). Append-only: re-running on the same
 * IST date inserts nothing new; a new date accrues a fresh snapshot.
 */
@Component
public class IndexConstituentsAccrualStep implements InstrumentSyncService.SyncStep {

  private static final Logger log = LoggerFactory.getLogger(IndexConstituentsAccrualStep.class);

  private final IndexConstituentsFetcher fetcher;
  private final IndexConstituentsRepository repository;
  private final Clock clock;
  private final List<String> indices;

  /** Wires the accrual. */
  public IndexConstituentsAccrualStep(
      IndexConstituentsFetcher fetcher,
      IndexConstituentsRepository repository,
      Clock clock,
      @Value("${artha.constituents.indices:NIFTY 100}") List<String> indices) {
    this.fetcher = fetcher;
    this.repository = repository;
    this.clock = clock;
    this.indices = indices;
  }

  @Override
  public void afterStaging() {
    LocalDate asOf = LocalDate.now(clock.withZone(Ist.ZONE));
    for (String index : indices) {
      Optional<List<IndexConstituentsFetcher.Constituent>> membership = fetcher.fetch(index);
      if (membership.isEmpty()) {
        log.warn("constituents source knows nothing about index '{}' — skipped", index);
        continue;
      }
      int inserted = repository.accrue(index, asOf, membership.get());
      log.info(
          "index constituents accrued: {} as of {} — {} rows ({} new)",
          index, asOf, membership.get().size(), inserted);
    }
  }
}
