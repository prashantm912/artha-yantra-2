package in.arthayantra.marketdata.nse;

import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock NSE corporate-actions source (non-live): empty so non-live contexts have a bean, no NSE call. */
@Component
@Profile("!live")
public class MockNseCorporateActionFetcher implements NseCorporateActionFetcher {

  @Override
  public List<CaRecord> fetchRecent(LocalDate from, LocalDate to) {
    return List.of();
  }
}
