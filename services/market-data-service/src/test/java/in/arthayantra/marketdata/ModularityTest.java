package in.arthayantra.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Phase-7 deliverable: module boundaries verify (no cycles, no internal reach-ins). */
class ModularityTest {

  @Test
  void modulesVerify() {
    ApplicationModules.of(MarketDataServiceApplication.class).verify();
  }
}
