package in.arthayantra.strategysignal.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * PF-01 round-6 #2 — proves the LEGACY 3-arg {@code publish(id, target, notes)} is genuinely
 * {@code @Transactional}. Round-5 delegated it to the annotated overload via SELF-invocation, which
 * bypasses Spring's proxy and silently dropped the transaction (the production Manas seeder uses the
 * 3-arg entry point). The test injects a failure AFTER the DB writes (the post-publish CHANGED
 * event) and asserts the whole publish ROLLS BACK — a non-transactional method would have committed
 * the pointer/status writes before the throw.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock"})
class RegistryPublishTransactionalIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubInstrumentClient {
    @Bean
    @Primary
    MarketDataInstrumentClient stubClient() {
      return (exchange, tradingsymbol) -> true;
    }
  }

  @Autowired private RegistryService service;
  @Autowired private StrategyRepository repository;
  // stubbed so the post-write CHANGED-event publish can throw and exercise the rollback boundary.
  @MockBean private StrategyChangedPublisher changedPublisher;

  private static final String YAML =
      """
      schema: strategy-schema/v1
      id: pf01-tx-walk
      name: "PF01 TX Walk"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: RELIANCE }
      timeframes: { primary: 1m }
      indicators:
        - { name: EMA, alias: ema_fast, timeframe: 1m, params: { period: 9 }, weight: 1.0 }
        - { name: EMA, alias: ema_slow, timeframe: 1m, params: { period: 21 }, weight: 1.0 }
      entry_rules:
        direction: long
        gate:
          all:
            - crossover: { fast: ema_fast, slow: ema_slow }
        scoring: { threshold: 0.5 }
      exit_rules:
        - { type: signal_exit, params: { rule: "crossunder(ema_fast, ema_slow)" } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: intraday }
      """;

  @Test
  void threeArgPublishIsTransactionalAndRollsBackOnFailure() {
    UUID id = (UUID) service.create("PF01 TX Walk", "tx IT", List.of("test"), YAML).get("id");
    service.publish(id, null, null); // 1.0.0 published (the champion)
    service.update(id, YAML.replace("period: 9", "period: 11"), null, "draft"); // draft 1.0.1

    // make the post-write CHANGED-event publish throw. If the 3-arg publish is @Transactional the
    // whole publish rolls back; if it were self-invoked-only (the round-5 bug) the archive + pointer
    // writes would already have committed before this throw.
    doThrow(new RuntimeException("boom"))
        .when(changedPublisher)
        .publish(any(), any(), any(), any());

    assertThatThrownBy(() -> service.publish(id, "1.0.1", null)) // the LEGACY 3-arg entry point
        .isInstanceOf(RuntimeException.class);

    // rolled back: 1.0.0 is STILL the published champion, 1.0.1 is STILL a draft (never published).
    assertThat(repository.findVersion(id, "1.0.0").orElseThrow().status()).isEqualTo("published");
    assertThat(repository.findVersion(id, "1.0.1").orElseThrow().status()).isEqualTo("draft");
  }
}
