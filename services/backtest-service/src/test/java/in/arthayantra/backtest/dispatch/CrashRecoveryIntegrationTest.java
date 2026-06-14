package in.arthayantra.backtest.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobStatus;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The D12 crash-safety guarantee: a {@code running} job orphaned by a container kill (no terminal
 * write, no XACK) is re-queued on startup and completes exactly once. Postgres is authoritative;
 * Redis is never the source of truth.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.backtest.workers=1",
      "artha.backtest.stub-step-millis=120",
      "artha.backtest.summary-refresh-ms=1000"
    })
class CrashRecoveryIntegrationTest extends BacktestIntegrationTestBase {

  @Autowired private JobRepository repository;
  @Autowired private StreamBootstrap bootstrap;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private StrategyVersionClient versions;

  @BeforeEach
  void stubVersions() {
    // an empty config routes the orphan job through the progress stub (the spine path)
    when(versions.resolve(any(), any()))
        .thenReturn(
            new ResolvedVersion(UUID.randomUUID(), null, "1.0.0", "chk", objectMapper.createObjectNode()));
  }

  @Test
  void orphanedRunningJobIsRequeuedAndCompletes() {
    Job job =
        repository.insertQueued(
            JobKind.BACKTEST,
            null,
            UUID.randomUUID(),
            objectMapper.createObjectNode().put("strategyId", "orphan"),
            "corr-crash");
    // a worker grabbed it then the container was killed: running, no terminal write, no XACK
    assertThat(repository.claim(job.id(), "dead-worker")).isTrue();
    assertThat(repository.find(job.id()).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);

    // service restart: recovery re-queues the orphan and re-dispatches it
    bootstrap.run(null);

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              Job recovered = repository.find(job.id()).orElseThrow();
              assertThat(recovered.status()).isEqualTo(JobStatus.COMPLETED);
              assertThat(recovered.progress()).isEqualTo(100);
            });
  }
}
