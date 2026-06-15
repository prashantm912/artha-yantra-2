package in.arthayantra.marketdata.nse;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock bhavcopy source (non-live): empty so non-live contexts have a bean and no NSE call. */
@Component
@Profile("!live")
public class MockBhavcopyFetcher implements BhavcopyFetcher {

  @Override
  public List<BhavcopyRow> fetchLatest() {
    return List.of();
  }
}
