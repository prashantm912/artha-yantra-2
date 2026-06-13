package in.arthayantra.strategysignal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * strategy-signal-service (COMMON 5.2.3): the D18 versioned strategy registry + the live signal
 * engine. Modulith modules: registry (Phase 21), signals (Phase 23), paper (STUB - Stage F
 * Phase 43), notifier (STUB - Stage E Phase 41).
 */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class StrategySignalServiceApplication {

  /** Boot entry point. */
  public static void main(String[] args) {
    SpringApplication.run(StrategySignalServiceApplication.class, args);
  }
}
