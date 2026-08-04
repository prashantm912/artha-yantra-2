package in.arthayantra.marketdata.screener;

import in.arthayantra.marketdata.screener.manas.ManasCandidate;
import in.arthayantra.marketdata.screener.manas.ManasScreenService;
import in.arthayantra.marketdata.screener.minervini.TrendCandidate;
import in.arthayantra.marketdata.screener.minervini.TrendTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>The opt-in door</b> for symbol lineage (N2 / #1285), and the ONLY caller anywhere that asks a
 * screen for the lineage-expanded price plane.
 *
 * <p>Runs one screen TWICE for the same date with the same gates — once on the plain plane, once
 * with a renamed symbol's pre-rename bars stitched onto the ticker that carries the series today —
 * and reports both counts plus the candidate-set difference. <b>Read-only: neither run is
 * persisted</b>, and the daily published screen is computed by the schedulers through the plain
 * one-arg {@code screen(asOf)}. So an owner can see exactly what stitching would buy before
 * anything changes for readers who did not ask for it.
 *
 * <p>Lives in {@code screener} rather than beside the rest of the lineage surface because {@code
 * screener.minervini} / {@code screener.manas} are internal packages of this Modulith module.
 *
 * <p><b>Cost warning:</b> two full screen computations per call. It is an owner-triggered
 * measurement, not a polled surface.
 */
@RestController
@RequestMapping("/api/v1/market/screener")
public class LineageScreenImpactController {

  /**
   * The A/B a reader gets by opting into lineage on one screen.
   *
   * @param entering symbols that become candidates only with lineage stitched
   * @param leaving symbols that were candidates without lineage and are not with it. This list is
   *     NOT expected to be empty: the RS-rank is a cross-sectional percentile, so a larger universe
   *     re-ranks everyone and a borderline incumbent can fall below the gate. "More history" does
   *     not mean "strictly more candidates", and the net is what matters.
   */
  @Schema(name = "LineageScreenImpact")
  public record ScreenImpact(
      String screen,
      @Schema(types = {"string", "null"}) LocalDate screenDate,
      int coverage,
      int candidates,
      int lineageCoverage,
      int lineageCandidates,
      List<String> entering,
      List<String> leaving) {}

  private final TrendTemplateService minervini;
  private final ManasScreenService manas;

  /** Wires both equity screens. */
  public LineageScreenImpactController(TrendTemplateService minervini, ManasScreenService manas) {
    this.minervini = minervini;
    this.manas = manas;
  }

  /** {@code screen} is {@code minervini} (default) or {@code manas}; {@code asOf} defaults to latest. */
  @Operation(operationId = "lineageScreenImpact")
  @GetMapping("/lineage-impact")
  public ScreenImpact lineageImpact(
      @RequestParam(defaultValue = "minervini") String screen,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOf) {
    if ("manas".equalsIgnoreCase(screen)) {
      ManasScreenService.ScreenResult plain = manas.screen(asOf, false);
      ManasScreenService.ScreenResult expanded = manas.screen(asOf, true);
      return impact(
          "manas",
          plain.screenDate(),
          plain.coverage(),
          passers(plain.candidates(), ManasCandidate::passesAll, ManasCandidate::symbol),
          expanded.coverage(),
          passers(expanded.candidates(), ManasCandidate::passesAll, ManasCandidate::symbol));
    }
    TrendTemplateService.ScreenResult plain = minervini.screen(asOf, false);
    TrendTemplateService.ScreenResult expanded = minervini.screen(asOf, true);
    return impact(
        "minervini",
        plain.screenDate(),
        plain.coverage(),
        passers(plain.candidates(), TrendCandidate::passesAll, TrendCandidate::symbol),
        expanded.coverage(),
        passers(expanded.candidates(), TrendCandidate::passesAll, TrendCandidate::symbol));
  }

  private static <T> Set<String> passers(
      List<T> rows, Predicate<T> passesAll, Function<T, String> symbol) {
    Set<String> out = new TreeSet<>();
    for (T r : rows) {
      if (passesAll.test(r)) {
        out.add(symbol.apply(r));
      }
    }
    return out;
  }

  private static ScreenImpact impact(
      String screen,
      LocalDate date,
      int coverage,
      Set<String> before,
      int lineageCoverage,
      Set<String> after) {
    return new ScreenImpact(
        screen,
        date,
        coverage,
        before.size(),
        lineageCoverage,
        after.size(),
        after.stream().filter(s -> !before.contains(s)).toList(),
        before.stream().filter(s -> !after.contains(s)).toList());
  }
}
