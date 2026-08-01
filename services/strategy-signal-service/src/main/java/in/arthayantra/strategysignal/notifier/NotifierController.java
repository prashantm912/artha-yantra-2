package in.arthayantra.strategysignal.notifier;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 41 test-send route: {@code POST /api/v1/strategies/{id}/notifications/test} (422 disabled). */
@RestController
@RequestMapping("/api/v1/strategies")
public class NotifierController {

  private final NotifierService notifier;

  /** Wires the notifier service. */
  public NotifierController(NotifierService notifier) {
    this.notifier = notifier;
  }

  /**
   * The test-send outcome (D3 Map-return burn-down). Declared here rather than at the service
   * because {@link NotifierService#sendTest} returns {@code void} — there is no service-layer map to
   * relocate; the constant is synthesized by this handler and nowhere else.
   */
  public record TestSendResult(String status) {}

  /** Send a test push for a strategy; 422 when notifications are disabled/unconfigured. */
  @PostMapping("/{id}/notifications/test")
  public TestSendResult test(@PathVariable UUID id) {
    notifier.sendTest(id);
    return new TestSendResult("SENT");
  }
}
