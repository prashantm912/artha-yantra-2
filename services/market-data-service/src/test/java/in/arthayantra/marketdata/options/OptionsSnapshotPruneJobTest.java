package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guard + fail-soft control flow of {@link OptionsSnapshotPruneJob} with mocks (no DB). The real
 * {@code drop_chunks}/{@code show_chunks} SQL is exercised by {@code
 * OptionsSnapshotPruneJobIntegrationTest}; this pins the two branches an IT cannot force cleanly: a
 * non-positive horizon must NOT touch the hypertable, and a DB error must be recorded + alerted but
 * NEVER thrown out of the scheduled tick.
 */
class OptionsSnapshotPruneJobTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final IngestRunLedger ledger = mock(IngestRunLedger.class);
  private final NtfyClient ntfy = mock(NtfyClient.class);
  private final Environment env = mock(Environment.class);

  private OptionsSnapshotPruneJob job(int retentionDays) {
    when(ledger.start(OptionsSnapshotPruneJob.SOURCE)).thenReturn(1L);
    return new OptionsSnapshotPruneJob(jdbc, ledger, ntfy, env, retentionDays);
  }

  @Test
  void nonPositiveRetentionSkipsWithoutTouchingTheHypertable() {
    OptionsSnapshotPruneJob job = job(0);

    List<String> dropped = job.prune();

    assertThat(dropped).isEmpty();
    verifyNoInteractions(jdbc); // the guard fires BEFORE any show_chunks / drop_chunks call
    verify(ledger).fail(eq(1L), contains("non-positive"));
    verify(ntfy).send(contains("misconfigured"), eq("urgent"), anyString());
    verify(ledger, never()).succeed(anyLong(), anyLong());
  }

  @Test
  void aDatabaseErrorIsRecordedAndAlertedButNeverThrown() {
    OptionsSnapshotPruneJob job = job(365);
    when(jdbc.queryForList(anyString(), eq(String.class), any()))
        .thenThrow(new RuntimeException("boom"));

    List<String> dropped = job.prune(); // fail-soft: returns, does not throw

    assertThat(dropped).isEmpty();
    verify(ledger).fail(eq(1L), contains("boom"));
    verify(ntfy).send(contains("failed"), eq("urgent"), contains("boom"));
    verify(ledger, never()).succeed(anyLong(), anyLong());
  }
}
