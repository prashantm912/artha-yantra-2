package in.arthayantra.marketdata.bse;

import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock BSE corporate-actions source (non-live): empty so non-live contexts have a bean, no BSE call. */
@Component
@Profile("!live")
public class MockBseCorporateActionFetcher implements BseCorporateActionFetcher {

  @Override
  public List<BseCaRecord> fetchRecent(LocalDate from, LocalDate to) {
    return List.of();
  }
}
