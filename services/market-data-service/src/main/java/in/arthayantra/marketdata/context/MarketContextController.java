package in.arthayantra.marketdata.context;

import in.arthayantra.marketdata.options.OiQuery;
import in.arthayantra.marketdata.options.OptionsDigestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Intelligence-layer context plane (design 2026-07-10 §6.1) — the market-data digest endpoints under
 * the existing {@code /api/v1/market/**} gateway prefix (edge-gateway allowlist, application.yml). I1
 * ships ONLY the two foundation reads: the options digest and the day-context one-call. Both return
 * typed records (never a Map — the ratchet) and are folds over the existing analytics folds (§13 row
 * 4). Digests never 5xx on missing data; they carry a {@code dataTrust} / {@code notes} state instead
 * (§6.5 / §7).
 */
@RestController
@RequestMapping("/api/v1/market/context")
public class MarketContextController {

  private final OptionsDigestService optionsDigest;
  private final DayContextService dayContext;

  /** Wires the two digest services. */
  public MarketContextController(OptionsDigestService optionsDigest, DayContextService dayContext) {
    this.optionsDigest = optionsDigest;
    this.dayContext = dayContext;
  }

  /**
   * GET /options-digest — the options context digest for one (name, expiry). {@code expiry} null
   * resolves to the nearest listed expiry on/after today; {@code mode=history} + {@code date} reads a
   * past session (live never time-travels — {@link OiQuery}). Never 422s on an empty session: returns
   * a {@code BLOCKED} digest so the day-context one-call can still render.
   */
  @GetMapping("/options-digest")
  public OptionsDigestService.OptionsDigest optionsDigest(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, null, expiry);
    return optionsDigest.digest(q.name(), q.expiry(), q.date());
  }

  /** GET /day-context — the dashboard one-call: options headline + VIX + index action + cues + trust. */
  @GetMapping("/day-context")
  public DayContextService.DayContext dayContext() {
    return dayContext.dayContext();
  }
}
