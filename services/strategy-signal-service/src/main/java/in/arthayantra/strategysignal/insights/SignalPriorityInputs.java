package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.util.List;

/**
 * The pure inputs to the signal priority model (INT design §3.2). Assembled at the edge by the
 * engine from the {@code SignalEmitted} payload, the trust snapshot, and (later waves) graduation +
 * risk reads; passed to {@link PriorityModel} which is a pure function of these + the resolved
 * config. Every nullable field has a documented neutral fallback in the model, so a not-yet-wired
 * input degrades honestly (never a silent fake): e.g. Manas RS absent → context RS neutral 0.5 with
 * the "RS unavailable on API" note (§13 row 12).
 *
 * @param family {@code "scalper"} or {@code "swing"} — selects the edge band + freshness half-life
 * @param scalperConfluence 0..1 confluence-dots agreement (scalper), else {@code null}
 * @param swingRsPercentile 0..1 RS-rank percentile (swing), else {@code null}
 * @param contextNote optional evidence note when a context input is degraded/absent
 * @param trackPf strategy profit factor from the graduation board, or {@code null} (unwired → neutral)
 * @param closedTrades closed paper trades; {@code <20} → track-record neutral 0.5 prior (§3.2)
 * @param heatPct book heat % at the time of the signal, or {@code null} (unpriced → neutral)
 * @param advisedLots pre-trade advisory lots; {@code 0} → risk component 0 (§3.2), {@code null} → neutral
 * @param minutesToClose minutes to session close (scalper urgency taper after 15:00), or {@code null}
 */
public record SignalPriorityInputs(
    long signalId,
    String family,
    String exchange,
    String tradingsymbol,
    String side,
    BigDecimal strike,
    BigDecimal composite,
    BigDecimal threshold,
    BigDecimal scalperConfluence,
    BigDecimal swingRsPercentile,
    String contextNote,
    BigDecimal trackPf,
    BigDecimal trackExpectancy,
    Integer closedTrades,
    boolean takeEligible,
    BigDecimal heatPct,
    BigDecimal capPct,
    boolean sameUnderlyingSideOpen,
    Integer advisedLots,
    long ageSeconds,
    Integer minutesToClose,
    DataTrust trust,
    List<String> trustReasons,
    String asOf) {}
