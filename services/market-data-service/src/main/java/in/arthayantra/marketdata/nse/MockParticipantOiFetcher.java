package in.arthayantra.marketdata.nse;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock participant-OI source (non-live): empty so non-live contexts have a bean and no NSE call. */
@Component
@Profile("!live")
public class MockParticipantOiFetcher implements ParticipantOiFetcher {

  @Override
  public List<ParticipantOiRow> fetchLatest() {
    return List.of();
  }
}
