package in.arthayantra.strategysignal;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Modulith boundary verification (registry / signals / paper / notifier). */
class ModularityTest {

  @Test
  void modulesVerify() {
    ApplicationModules.of(StrategySignalServiceApplication.class).verify();
  }
}
