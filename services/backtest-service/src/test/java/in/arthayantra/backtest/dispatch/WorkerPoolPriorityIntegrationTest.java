package in.arthayantra.backtest.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.jobs.JobStatus;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * B16 / audit A6 end-to-end: a TRIAL storm on {@code jobs.backtest.trials} does not starve an
 * interactive backtest on {@code jobs.backtest}. With a 2-worker pool and {@code
 * interactive-reserved=1} the trial budget is 1, so at most one worker ever runs trials — the other
 * always stays free for the owner's run. Empty (mocked) configs route every job through the fast
 * Phase-28 stub so this exercises the scheduler, not a real replay.
 *
 * <p>The singleton Redis is shared across IT contexts, so ambient worker pools may also consume
 * these streams; the load-bearing assertions are therefore per-THIS-pool invariants (its own {@code
 * trialsInFlightCount()} never exceeds its own budget) plus terminal-state reachability, both robust
 * to an ambient consumer stealing a record. The discriminating budget arithmetic is proven
 * deterministically in {@link WorkerPoolAdmissionTest}.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.backtest.workers=2",
      "artha.backtest.interactive-reserved=1",
      "artha.backtest.worker-pool-enabled=true",
      "artha.backtest.stub-step-millis=150",
      "artha.backtest.summary-refresh-ms=1000"
    })
class WorkerPoolPriorityIntegrationTest extends BacktestIntegrationTestBase {

  private static final UUID STRATEGY_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

  @MockitoBean private StrategyVersionClient versions;
  @Autowired private JobRepository jobs;
  @Autowired private JobStreamDispatcher dispatcher;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private WorkerPool pool;

  @BeforeEach
  void stubVersions() {
    // an empty config routes every job through the fast stub (no real strategy / candles needed)
    when(versions.resolve(any(), any()))
        .thenReturn(
            new ResolvedVersion(
                STRATEGY_ID, null, "1.0.0", "chk", objectMapper.createObjectNode()));
  }

  @Test
  void trialStormDoesNotStarveInteractiveAndTheTrialBudgetHolds() {
    assertThat(pool.maxTrialWorkers()).isEqualTo(1); // 2 workers − 1 reserved

    String run = UUID.randomUUID().toString();
    Job sweep =
        jobs.insertQueued(JobKind.OPTIMIZATION, null, STRATEGY_ID, node(run, "sweep"),
            "corr-sweep-" + run, "optimizer");

    // saturate the trials stream FIRST so trials are competing for the pool when interactive lands
    List<UUID> trialIds = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      Job trial =
          jobs.insertQueued(JobKind.TRIAL, sweep.id(), STRATEGY_ID, node(run, "trial-" + i),
              "corr-trial-" + run + "-" + i, "optimizer:" + sweep.id());
      trialIds.add(trial.id());
      redis.opsForStream().add(Streams.TRIALS, Map.of("jobId", trial.id().toString()));
    }

    // an interactive owner backtest arrives behind the storm
    Job interactive =
        jobs.insertQueued(JobKind.BACKTEST, null, STRATEGY_ID, node(run, "interactive"),
            "corr-interactive-" + run, "owner");
    dispatcher.dispatchBacktest(interactive.id());

    AtomicInteger maxTrialsSeen = new AtomicInteger();
    // interactive reaches COMPLETED without waiting for the whole trial backlog; and at EVERY sample
    // this pool keeps trials within its budget (=1), i.e. it always leaves a worker for interactive.
    await()
        .atMost(Duration.ofSeconds(40))
        .pollInterval(Duration.ofMillis(20))
        .untilAsserted(
            () -> {
              maxTrialsSeen.accumulateAndGet(pool.trialsInFlightCount(), Math::max);
              assertThat(pool.trialsInFlightCount()).isLessThanOrEqualTo(pool.maxTrialWorkers());
              assertThat(status(interactive.id())).isEqualTo(JobStatus.COMPLETED);
            });

    // the reservation never deadlocks the trials — they all drain to terminal
    await()
        .atMost(Duration.ofSeconds(40))
        .untilAsserted(
            () ->
                assertThat(trialIds)
                    .allSatisfy(id -> assertThat(status(id)).isEqualTo(JobStatus.COMPLETED)));

    // the interactive-reservation invariant held throughout the storm
    assertThat(maxTrialsSeen.get()).isLessThanOrEqualTo(pool.maxTrialWorkers());
  }

  private JobStatus status(UUID id) {
    return jobs.find(id).orElseThrow().status();
  }

  private ObjectNode node(String run, String tag) {
    return objectMapper
        .createObjectNode()
        .put("strategyId", STRATEGY_ID.toString())
        .put("run", run)
        .put("tag", tag);
  }
}
