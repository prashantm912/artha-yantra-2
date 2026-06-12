package in.arthayantra.marketdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The market-data Spring Modulith (Phase 7, spec A-7a). Modules: {@code kite} (the five ports),
 * {@code mockfeed} (D13 credential-free impls), {@code feed} (ingress queue → normalizer → Redis
 * publisher), {@code instruments} (CD-14 fixture registry), and the Stage-B placeholders
 * {@code candles} / {@code options}.
 */
@SpringBootApplication
public class MarketDataServiceApplication {

  /** Boot entry point. */
  public static void main(String[] args) {
    SpringApplication.run(MarketDataServiceApplication.class, args);
  }
}
