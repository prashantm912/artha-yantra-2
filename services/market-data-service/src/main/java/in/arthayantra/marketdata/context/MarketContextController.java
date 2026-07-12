package in.arthayantra.marketdata.context;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.options.OiQuery;
import in.arthayantra.marketdata.options.OptionsDigestService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Intelligence-layer context plane (design 2026-07-10 §6.1 / §6.4) — the market-data digest endpoints
 * under the existing {@code /api/v1/market/**} gateway prefix (edge-gateway allowlist,
 * application.yml line 50). I1 shipped the two foundation reads (options digest + day-context);
 * increment I2 adds the futures / equity / FII digests and the cross-expiry compare (§6.1.2–4,
 * §6.4). Every response is a typed record (never a Map — the ratchet) folding the EXISTING analytics
 * tables (§13 row 4). Digests never 5xx on missing data; they carry a {@code dataTrust} state and
 * {@code trustReasons} / {@code notes} instead (§6.5 / §7).
 */
@RestController
@RequestMapping("/api/v1/market/context")
public class MarketContextController {

  private final OptionsDigestService optionsDigest;
  private final DayContextService dayContext;
  private final FuturesDigestService futuresDigest;
  private final EquityDigestService equityDigest;
  private final FiiDigestService fiiDigest;
  private final ExpiryCompareService expiryCompare;

  /** Wires the I1 + I2 digest services. */
  public MarketContextController(
      OptionsDigestService optionsDigest,
      DayContextService dayContext,
      FuturesDigestService futuresDigest,
      EquityDigestService equityDigest,
      FiiDigestService fiiDigest,
      ExpiryCompareService expiryCompare) {
    this.optionsDigest = optionsDigest;
    this.dayContext = dayContext;
    this.futuresDigest = futuresDigest;
    this.equityDigest = equityDigest;
    this.fiiDigest = fiiDigest;
    this.expiryCompare = expiryCompare;
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

  /**
   * GET /futures-digest — per-captured-underlying price%/ΔOI quadrant + banks summary + the primary
   * index's live term-structure (§6.1.2). {@code date} null = live newest bucket; a past IST date
   * reads that session. Fail-soft: a {@code DEGRADED}/{@code BLOCKED} shell, never a 5xx.
   */
  @GetMapping("/futures-digest")
  public FuturesDigestService.FuturesDigest futuresDigest(@RequestParam(required = false) String date) {
    return futuresDigest.digest(optionalDate(date));
  }

  /**
   * GET /equity-digest — bhavcopy breadth context (§6.1.3): A/D + above-MA counts + breadth thrust +
   * sector rotation + delivery-z outliers + returns leaders + index concentration. {@code date} null
   * = the latest EQ session.
   */
  @GetMapping("/equity-digest")
  public EquityDigestService.EquityDigest equityDigest(@RequestParam(required = false) String date) {
    return equityDigest.digest(optionalDate(date));
  }

  /**
   * GET /fii-digest — FII/DII flow context (§6.1.4): cash net + streak/flip, DII divergence, FII
   * index-futures long/short + percentile, participant positioning, and the analytics-gated
   * derivative-stats availability. {@code date} null = the latest FII/DII session.
   */
  @GetMapping("/fii-digest")
  public FiiDigestService.FiiDigest fiiDigest(@RequestParam(required = false) String date) {
    return fiiDigest.digest(optionalDate(date));
  }

  /**
   * GET /expiry-compare — two options {@code /trending} folds for {@code name}, one per expiry,
   * server-zipped by bucket (§6.4). {@code mode=history} + {@code date} reads a past session.
   * {@code expiryA} + {@code expiryB} are required ISO dates. Fail-soft (never a 5xx).
   */
  @GetMapping("/expiry-compare")
  public ExpiryCompareService.ExpiryCompare expiryCompare(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam String expiryA,
      @RequestParam String expiryB) {
    OiQuery q = OiQuery.of(mode, name, date, interval, null);
    return expiryCompare.compare(
        q.name(),
        q.date(),
        q.interval(),
        ExpiryCompareService.requireExpiry(expiryA, "expiryA"),
        ExpiryCompareService.requireExpiry(expiryB, "expiryB"));
  }

  /** Parse an optional ISO {@code date} param (400 on a bad value; null ⇒ live/latest). */
  private static LocalDate optionalDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException ex) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "date must be ISO yyyy-MM-dd");
    }
  }
}
