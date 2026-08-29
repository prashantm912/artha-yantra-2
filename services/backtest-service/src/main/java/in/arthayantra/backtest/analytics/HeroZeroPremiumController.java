package in.arthayantra.backtest.analytics;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only Hero-Zero per-strike PREMIUM diagnostics for a finished run, under
 * {@code /api/v1/backtests/**}: for each trade it re-reads the chosen leg's premium series and reports
 * the hero/zero excursion, the S24d per-strike %-change confirmation, and the S21b/Bearish-7 mirror
 * skew. Offline + parity-safe — it never touches the replay/golden path (see {@link
 * HeroZeroPremiumService}).
 */
@RestController
@RequestMapping("/api/v1/backtests")
public class HeroZeroPremiumController {

  private final HeroZeroPremiumService service;

  /** Wires the diagnostics service. */
  public HeroZeroPremiumController(HeroZeroPremiumService service) {
    this.service = service;
  }

  /** Hero-Zero per-strike premium diagnostics for {@code backtestId} (the run id / resultRef). */
  @GetMapping("/{backtestId}/hero-zero-premium")
  public HeroZeroPremiumService.HeroZeroPremium heroZeroPremium(@PathVariable UUID backtestId) {
    return service.diagnostics(backtestId);
  }
}
