package in.arthayantra.strategysignal.minervini;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops/verify trigger for the Phase-9 Minervini swing batch — runs it on demand (the scheduler fires
 * it at 20:00 IST). Under {@code /api/v1/signals/**} so the edge-gateway allowlist already covers it;
 * returns a TYPED record (never a Map — the market-data/strategy Map-return ratchet). Read-safe: it
 * only generates paper signals, exactly as the scheduled run does.
 */
@RestController
@RequestMapping("/api/v1/signals/minervini-swing")
public class MinerviniSwingController {

  private final MinerviniSwingEngine engine;

  /** Wires the swing engine. */
  public MinerviniSwingController(MinerviniSwingEngine engine) {
    this.engine = engine;
  }

  /** Runs one daily swing batch now (entry pass + exit pass) and returns the counts. */
  @PostMapping("/run")
  public MinerviniSwingEngine.SwingRun run() {
    return engine.runDaily();
  }
}
