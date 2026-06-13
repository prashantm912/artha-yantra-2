package in.arthayantra.marketdata.constituents;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Live placeholder: the real NSE published-CSV fetcher is gated on the source-verification owner
 * action (decisions-log §4.2 — format/URL stability/cadence/ToS) and lands as a follow-up slice.
 * Until then live accrues nothing and says so loudly — never a silent fake list.
 */
@Component
@Profile("live")
public class PendingLiveIndexConstituentsFetcher implements IndexConstituentsFetcher {

  private static final Logger log =
      LoggerFactory.getLogger(PendingLiveIndexConstituentsFetcher.class);

  @Override
  public Optional<List<Constituent>> fetch(String indexName) {
    log.warn(
        "live NSE constituents fetcher not built yet (source verification pending, "
            + "decisions-log 4.2) — no accrual for '{}'",
        indexName);
    return Optional.empty();
  }
}
