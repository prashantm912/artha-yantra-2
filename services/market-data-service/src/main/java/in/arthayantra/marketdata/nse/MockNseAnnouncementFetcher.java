package in.arthayantra.marketdata.nse;

import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock NSE announcements source (non-live): empty so non-live contexts have a bean, no NSE call. */
@Component
@Profile("!live")
public class MockNseAnnouncementFetcher implements NseAnnouncementFetcher {

  @Override
  public List<Announcement> fetch(LocalDate from, LocalDate to, String symbol) {
    return List.of();
  }
}
