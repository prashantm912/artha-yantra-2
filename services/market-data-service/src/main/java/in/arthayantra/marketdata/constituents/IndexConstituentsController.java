package in.arthayantra.marketdata.constituents;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point-in-time membership resolution (S8): latest snapshot by default, exact date with
 * {@code ?asOf=}. The checksum is over the canonical ordered list — the Stage-F submitter copies
 * the list into the job request and pins this hash ({@code universe_checksum}), soft references
 * only. Pre-accrual windows carry the survivorship-bias caveat (the data simply starts at
 * capture start).
 */
@RestController
public class IndexConstituentsController {

  /** Ordered list, resolved as-of date, and checksum for a membership snapshot. */
  public record ConstituentsResponse(
      String index,
      LocalDate asOf,
      String checksum,
      List<IndexConstituentsFetcher.Constituent> items) {}

  private final IndexConstituentsRepository repository;

  /** Wires the repository. */
  public IndexConstituentsController(IndexConstituentsRepository repository) {
    this.repository = repository;
  }

  /** Ordered list + resolved as-of date + checksum. */
  @GetMapping("/api/v1/instruments/indices/{index}/constituents")
  public ConstituentsResponse constituents(
      @PathVariable("index") String index,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOf) {
    LocalDate resolved =
        asOf != null
            ? asOf
            : repository
                .latestAsOf(index)
                .orElseThrow(
                    () ->
                        new NotFoundException(
                            ErrorCodes.NOT_FOUND_RESOURCE,
                            "no constituent snapshots accrued for index '" + index + "'"));
    List<IndexConstituentsFetcher.Constituent> items = repository.membership(index, resolved);
    if (items.isEmpty()) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_RESOURCE,
          "no snapshot of '" + index + "' as of " + resolved);
    }
    return new ConstituentsResponse(
        index, resolved, IndexConstituentsRepository.checksum(items), items);
  }
}
