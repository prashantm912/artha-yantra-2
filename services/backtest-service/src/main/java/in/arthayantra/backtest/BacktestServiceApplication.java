package in.arthayantra.backtest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * backtest-service entry point (Stage D Phase 28). The backtest engine: an authoritative Postgres
 * {@code jobs} spine (ADR D12), crash-safe Redis Streams dispatch, and a bounded worker pool that
 * replays cached candles through the shared {@code strategy-engine} JAR. No Spring Modulith
 * (CD-17 / D7 — a single-purpose engine). {@code @EnableScheduling} drives the stale-terminal-jobs
 * pruning task (plan §6.5).
 */
@SpringBootApplication
@EnableScheduling
public class BacktestServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(BacktestServiceApplication.class, args);
  }
}
