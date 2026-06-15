package in.arthayantra.marketdata.nse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock FII/DII source (non-live): one deterministic row so non-live contexts have a bean. */
@Component
@Profile("!live")
public class MockFiiDiiFetcher implements FiiDiiFetcher {

  @Override
  public List<FiiDiiRow> fetchLatest() {
    return List.of(
        new FiiDiiRow(
            LocalDate.now(ZoneOffset.UTC),
            "FII/FPI",
            new BigDecimal("100.00"),
            new BigDecimal("90.00"),
            new BigDecimal("10.00")));
  }
}
