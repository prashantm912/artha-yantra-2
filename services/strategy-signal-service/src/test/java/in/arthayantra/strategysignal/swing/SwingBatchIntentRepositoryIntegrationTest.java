package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Pins the schedule-time arming ledger against the real V048 strategy schema. */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingBatchIntentRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SwingBatchIntentRepository intents;

  @Test
  void scheduleIntentIsImmutableForOneSessionAndMissingRowsAreEmpty() {
    String batch = "intent-it-" + System.nanoTime();
    LocalDate session = LocalDate.of(2026, 7, 17);

    intents.recordScheduled(batch, session, true);
    intents.recordScheduled(batch, session, false);

    assertThat(intents.find(batch, session)).map(SwingBatchIntentRepository.Intent::armed).contains(true);
    assertThat(intents.find(batch, session.plusDays(1))).isEqualTo(Optional.empty());
  }

  @Test
  void scheduleIntentCapturesDisabledState() {
    String batch = "intent-it-disabled-" + System.nanoTime();
    LocalDate session = LocalDate.of(2026, 7, 20);

    intents.recordScheduled(batch, session, false);

    assertThat(intents.find(batch, session)).map(SwingBatchIntentRepository.Intent::armed).contains(false);
  }
}
