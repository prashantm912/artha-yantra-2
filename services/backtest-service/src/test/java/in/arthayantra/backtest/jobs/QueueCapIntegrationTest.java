package in.arthayantra.backtest.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * B16 / audit A6 submission-time queue-depth cap: once the queued BACKTEST backlog is at {@code
 * artha.backtest.max-queued}, a new interactive submission is rejected with a typed 429 {@code
 * RATE_LIMIT_QUEUE} instead of piling onto the shared worker pool. The default (500) is exercised —
 * and shown NOT to reject a normal handful of runs — by {@code JobLifecycleIntegrationTest}, which
 * submits successfully under the unchanged default.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.backtest.worker-pool-enabled=false",
      "artha.backtest.max-queued=3"
    })
class QueueCapIntegrationTest extends BacktestIntegrationTestBase {

  // present so JobsService wires; never invoked on the reject path (the cap fires before resolve)
  @MockitoBean private StrategyVersionClient versions;
  @Autowired private JobsService service;
  @Autowired private JobRepository jobs;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void submitIsRejectedWith429WhenTheBacktestQueueIsSaturated() {
    // seed >= cap queued BACKTEST rows; the shared singleton DB only ADDS to the count, so seeding
    // `cap` ourselves guarantees depth >= cap regardless of what other contexts left behind.
    List<UUID> seeded = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      seeded.add(
          jobs.insertQueued(
                  JobKind.BACKTEST,
                  null,
                  UUID.randomUUID(),
                  objectMapper.createObjectNode().put("strategyId", UUID.randomUUID().toString()),
                  "corr-cap-seed-" + UUID.randomUUID(),
                  "owner")
              .id());
    }
    try {
      // the cap is checked BEFORE version-resolve / auto-warm, so a null window never gets that far
      BacktestRunRequest req =
          new BacktestRunRequest(
              UUID.randomUUID().toString(), null, null, null, null, null, null, null, null, null, null,
              null, null, null, null);
      assertThatThrownBy(() -> service.submit(req, "corr-cap"))
          .isInstanceOfSatisfying(
              ApiException.class,
              ex -> {
                assertThat(ex.httpStatus()).isEqualTo(429);
                assertThat(ex.code()).isEqualTo(ErrorCodes.RATE_LIMIT_QUEUE);
              });
    } finally {
      // keep the shared singleton DB clean for other contexts' default-cap submissions
      seeded.forEach(jobs::cancelIfQueued);
    }
  }
}
