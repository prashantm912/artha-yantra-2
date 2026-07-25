package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T15 (bug queue B7): the reload ledger persists the boot line and NEVER breaks the reload path —
 * a write failure is swallowed, because losing one ledger row is nothing next to failing a reload.
 */
class EngineReloadLedgerTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-25T05:30:00Z"), ZoneOffset.UTC);

  @Test
  void recordInsertsTheReloadOutcome() {
    new EngineReloadLedger(jdbc, clock).record(38, 0, 0, true);
    verify(jdbc)
        .update(
            contains("INSERT INTO engine_reloads"),
            eq(java.sql.Timestamp.from(clock.instant())), eq(38), eq(0), eq(0), eq(true));
  }

  @Test
  void aFailedInsertIsSwallowedNeverThrown() {
    when(jdbc.update(anyString(), any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("db down"));
    assertThatCode(() -> new EngineReloadLedger(jdbc, clock).record(0, 38, 0, true))
        .doesNotThrowAnyException();
  }
}
