package in.arthayantra.strategysignal.paper;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F7 graduation board — the per-strategy "is this ready to graduate?" read. MEASUREMENT ONLY: it
 * reports computed paper-trading metrics vs configurable thresholds and a {@code PAPER} /
 * {@code TAKE_ELIGIBLE} stage; it never promotes or arms anything (promotion is an owner action). The
 * response is a TYPED record — a {@code Map<String,Object>} would be invisible to the springdoc
 * contract gate and would trip the strategy-gateway {@code MapReturnRatchetTest}.
 */
@RestController
@RequestMapping("/api/v1/strategies")
public class GraduationController {

  private final GraduationService graduation;
  private final StrategyGraduationRepository graduations;

  /** Wires the graduation service + the GRADUATED-marker store. */
  public GraduationController(
      GraduationService graduation, StrategyGraduationRepository graduations) {
    this.graduation = graduation;
    this.graduations = graduations;
  }

  /** The thresholds each strategy is scored against (echoed so the board is self-describing). */
  public record Thresholds(
      int minTrades,
      @Schema(type = "string") BigDecimal minProfitFactor,
      @Schema(type = "string") BigDecimal minExpectancy,
      @Schema(type = "string") BigDecimal maxDrawdownPct) {}

  /** One scored criterion: its name, the required bound, the actual value, and pass/fail. */
  public record Criterion(String name, String required, String actual, boolean pass) {}

  /** One strategy's readiness row: metrics, criteria breakdown, and derived stage. */
  public record StrategyGraduation(
      UUID strategyId,
      String slug,
      String name,
      String stage,
      int trades,
      @Schema(type = "string") BigDecimal netRealized,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal winRate,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal profitFactor,
      @Schema(type = "string") BigDecimal expectancy,
      @Schema(type = "string") BigDecimal maxDrawdownPct,
      List<Criterion> criteria) {}

  /** The whole board + the thresholds used + the compute time. */
  public record GraduationBoard(
      List<StrategyGraduation> strategies, Thresholds thresholds, OffsetDateTime asOf) {}

  /** Scores every published+enabled strategy's CLOSED paper positions against the thresholds. */
  @GetMapping("/graduation")
  public GraduationBoard graduation() {
    GraduationService.GraduationBoard b = graduation.board();
    List<StrategyGraduation> rows =
        b.strategies().stream()
            .map(
                s ->
                    new StrategyGraduation(
                        s.strategyId(),
                        s.slug(),
                        s.name(),
                        s.stage(),
                        s.trades(),
                        s.netRealized(),
                        s.winRate(),
                        s.profitFactor(),
                        s.expectancy(),
                        s.maxDrawdownPct(),
                        s.criteria().stream()
                            .map(c -> new Criterion(c.name(), c.required(), c.actual(), c.pass()))
                            .toList()))
            .toList();
    GraduationService.Thresholds t = b.thresholds();
    return new GraduationBoard(
        rows,
        new Thresholds(t.minTrades(), t.minProfitFactor(), t.minExpectancy(), t.maxDrawdownPct()),
        b.asOf());
  }

  /** One GRADUATED strategy (F7): the marker + the metrics snapshot captured at graduation. */
  public record Promotion(
      UUID strategyId,
      OffsetDateTime graduatedAt,
      int trades,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal expectancy,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal sharpe,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal maxDrawdownPct) {}

  /** The strategies the F7 evaluator has marked GRADUATED, newest first (measurement only). */
  @GetMapping("/graduation/promotions")
  public List<Promotion> promotions() {
    return graduations.list().stream()
        .map(
            r ->
                new Promotion(
                    r.strategyId(), r.graduatedAt(), r.trades(), r.expectancy(), r.sharpe(),
                    r.maxDrawdownPct()))
        .toList();
  }
}
