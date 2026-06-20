package in.arthayantra.marketdata.bse;

import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock BSE bhavcopy source (non-live): empty so non-live contexts have a bean and no BSE call. */
@Component
@Profile("!live")
public class MockBseBhavcopyFetcher implements BseBhavcopyFetcher {

  @Override
  public List<BseBhavRow> fetchForDate(LocalDate date) {
    return List.of();
  }
}
