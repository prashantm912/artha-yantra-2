package in.arthayantra.marketdata.nse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock FII-derivative source (non-live): one deterministic row so non-live contexts have a bean. */
@Component
@Profile("!live")
public class MockFiiDerivativeFetcher implements FiiDerivativeFetcher {

  @Override
  public List<FiiDerivativeRow> fetchLatest() {
    return List.of(
        new FiiDerivativeRow(
            LocalDate.now(ZoneOffset.UTC),
            "INDEX_FUTURES",
            new BigDecimal("500.00"),
            new BigDecimal("420.00"),
            new BigDecimal("80.00")));
  }
}
