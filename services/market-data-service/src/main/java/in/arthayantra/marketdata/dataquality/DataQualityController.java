package in.arthayantra.marketdata.dataquality;

import in.arthayantra.marketdata.dataquality.DataQualityRepository.DataQualityRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Typed read API for the nightly data-completeness report. */
@RestController
@RequestMapping("/api/v1/market/health")
public class DataQualityController {

  /** API row projected from the persisted report. */
  public record CompletenessRow(
      String scope,
      String symbol,
      long expected,
      long present,
      @Schema(types = {"number", "null"}) BigDecimal coveragePct,
      boolean ok,
      @Schema(types = {"string", "null"}) String detail,
      OffsetDateTime computedAt) {}

  /** One report day and its per-scope rows. */
  public record CompletenessReport(
      @Schema(types = {"string", "null"}) LocalDate date, List<CompletenessRow> items) {}

  private final DataQualityRepository repository;

  /** Wires the persisted completeness reader. */
  public DataQualityController(DataQualityRepository repository) {
    this.repository = repository;
  }

  /** Reads an explicit ISO date, or the latest materialized report when omitted. */
  @GetMapping("/completeness")
  public CompletenessReport completeness(@RequestParam(required = false) String date) {
    LocalDate requested =
        date == null || date.isBlank()
            ? repository.latestDate().orElse(null)
            : LocalDate.parse(date);
    List<CompletenessRow> items =
        requested == null
            ? List.of()
            : repository.findByDate(requested).stream().map(DataQualityController::project).toList();
    return new CompletenessReport(requested, items);
  }

  private static CompletenessRow project(DataQualityRow row) {
    return new CompletenessRow(
        row.scope(),
        row.symbol(),
        row.expected(),
        row.present(),
        row.coveragePct(),
        row.ok(),
        row.detail(),
        row.computedAt());
  }
}
