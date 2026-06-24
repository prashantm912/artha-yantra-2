package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.upstox.ExpiredBackfillService.Status;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit test for {@link ExpiredBackfillAutoResume}: the boot event and the hourly self-heal each fire
 * the coverage-aware {@link ExpiredBackfillService#triggerAsync} only when enabled, the analytics
 * client is present, the job is idle, and work remains. The service + repo + client provider are
 * mocked — no Spring context, no DB, no scheduling.
 */
class ExpiredBackfillAutoResumeTest {

  private final ExpiredBackfillService service = mock(ExpiredBackfillService.class);
  private final ExpiredBackfillRepository repo = mock(ExpiredBackfillRepository.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<UpstoxExpiredInstrumentsClient> provider = mock(ObjectProvider.class);

  private ExpiredBackfillAutoResume autoResume(boolean enabled) {
    return new ExpiredBackfillAutoResume(
        service, repo, provider, new ExpiredBackfillProperties(enabled, "0 17 * * * *", 20));
  }

  private void clientPresent() {
    when(provider.getIfAvailable()).thenReturn(mock(UpstoxExpiredInstrumentsClient.class));
  }

  private static Status status(String state, Instant lastRun) {
    return new Status(null, state, null, lastRun, 0, 0, 0, 0, 0, 0, 0L, null, null, List.of());
  }

  private void verifyResumed(int times) {
    verify(service, times(times)).triggerAsync(any(), any(), any(), isNull(), eq(false));
  }

  // ---- startup ----------------------------------------------------------------------------------

  @Test
  void onReady_firesResumeWhenEnabledAndClientPresent() {
    clientPresent();
    autoResume(true).onReady();
    verifyResumed(1);
  }

  @Test
  void onReady_noopWhenAutoResumeDisabled() {
    autoResume(false).onReady();
    verifyResumed(0);
  }

  @Test
  void onReady_noopWhenClientAbsent() {
    when(provider.getIfAvailable()).thenReturn(null);
    autoResume(true).onReady();
    verifyResumed(0);
  }

  // ---- self-heal --------------------------------------------------------------------------------

  @Test
  void selfHeal_noopWhileRunning() {
    clientPresent();
    when(service.status()).thenReturn(status("RUNNING", null));
    autoResume(true).selfHeal();
    verifyResumed(0);
  }

  @Test
  void selfHeal_firesWhenNeverRun() {
    clientPresent();
    when(service.status()).thenReturn(status("NEVER_RUN", null));
    autoResume(true).selfHeal();
    verifyResumed(1);
  }

  @Test
  void selfHeal_firesAfterFailure() {
    clientPresent();
    when(service.status()).thenReturn(status("FAILED", Instant.now()));
    autoResume(true).selfHeal();
    verifyResumed(1);
  }

  @Test
  void selfHeal_firesWhenCoverageStillIncomplete() {
    clientPresent();
    when(service.status()).thenReturn(status("OK", Instant.now()));
    when(repo.hasIncompleteCoverage()).thenReturn(true);
    autoResume(true).selfHeal();
    verifyResumed(1);
  }

  @Test
  void selfHeal_noopWhenCompleteAndFresh() {
    clientPresent();
    when(service.status()).thenReturn(status("OK", Instant.now()));
    when(repo.hasIncompleteCoverage()).thenReturn(false);
    autoResume(true).selfHeal();
    verifyResumed(0);
  }

  @Test
  void selfHeal_firesDailyCatchupWhenCompleteButStale() {
    clientPresent();
    when(service.status())
        .thenReturn(status("OK", Instant.now().minus(25, ChronoUnit.HOURS)));
    when(repo.hasIncompleteCoverage()).thenReturn(false);
    autoResume(true).selfHeal();
    verifyResumed(1);
  }

  // ---- resilience -------------------------------------------------------------------------------

  @Test
  void trigger_swallowsServiceException() {
    clientPresent();
    when(service.triggerAsync(any(), any(), any(), isNull(), eq(false)))
        .thenThrow(new RuntimeException("409 already running"));
    // The boot hook must never propagate a trigger failure (would crash app startup).
    assertThatCode(() -> autoResume(true).onReady()).doesNotThrowAnyException();
  }
}
