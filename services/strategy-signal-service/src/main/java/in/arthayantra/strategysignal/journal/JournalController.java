package in.arthayantra.strategysignal.journal;

import in.arthayantra.common.web.csv.CsvExport;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.strategysignal.journal.JournalRepository.Entry;
import in.arthayantra.strategysignal.journal.JournalRepository.EntryInput;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The F-44A trade-journal CRUD family, routed by edge-gateway under {@code /api/v1/journal/**}.
 * Same-schema link targets are validated; soft backtest references are accepted verbatim.
 */
@RestController
@RequestMapping("/api/v1/journal")
public class JournalController {

  /** Create/update body. */
  public record JournalBody(
      Long signalId,
      Long paperPositionId,
      UUID backtestRunId,
      Long backtestTradeId,
      String note,
      List<String> tags,
      Integer disciplineRating,
      Integer emotionRating) {}

  /**
   * {@code GET /api/v1/journal} — the paged review list (ledger D3 slice 1).
   *
   * <p>A PURE RETYPING of {@code Map.of("items", …, "limit", …, "offset", …)}. {@link Entry} was
   * already a record, so the envelope was the only part springdoc could not enumerate — typing it
   * pulls the item schema into the spec instead of leaving an {@code array of object}. {@code Map.of}
   * iteration order is unspecified, so no client could depend on it; the record additionally makes it
   * deterministic.
   */
  public record JournalPage(List<Entry> items, int limit, int offset) {}

  private final JournalRepository repository;

  /** Wires the repository. */
  public JournalController(JournalRepository repository) {
    this.repository = repository;
  }

  /** Paged review list with tag / window / min-rating / linked-entity filters. */
  @GetMapping
  public JournalPage list(
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false) Integer minRating,
      @RequestParam(required = false) String linkedTo,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    int boundedOffset = Math.max(offset, 0);
    List<Entry> items =
        repository.list(tag, from, to, minRating, linkedTo, boundedLimit, boundedOffset);
    return new JournalPage(items, boundedLimit, boundedOffset);
  }

  /**
   * The filtered journal as a CSV download (audit §10 Phase-3 CSV export standard). Same filters as
   * {@link #list}; {@code tags} are joined with {@code |}. Loud truncation via the shared {@link
   * CsvExport} headers.
   */
  @GetMapping("/export")
  public ResponseEntity<byte[]> export(
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false) Integer minRating,
      @RequestParam(required = false) String linkedTo) {
    List<Entry> rows =
        repository.list(tag, from, to, minRating, linkedTo, CsvExport.DEFAULT_MAX_ROWS + 1, 0);
    CsvExport.Writer w =
        CsvExport.writer(
            CsvExport.DEFAULT_MAX_ROWS,
            "id", "created_at", "updated_at", "signal_id", "paper_position_id", "backtest_run_id",
            "backtest_trade_id", "discipline_rating", "emotion_rating", "tags", "note");
    for (Entry e : rows) {
      w.row(
          e.id(), e.createdAt(), e.updatedAt(), e.signalId(), e.paperPositionId(), e.backtestRunId(),
          e.backtestTradeId(), e.disciplineRating(), e.emotionRating(),
          e.tags() == null ? "" : String.join("|", e.tags()), e.note());
    }
    return w.download("journal.csv");
  }

  /** Create — validates same-schema link targets exist (unknown id → 422). */
  @PostMapping
  public ResponseEntity<Entry> create(@RequestBody JournalBody body) {
    validateLinks(body);
    long id = repository.insert(toInput(body));
    return ResponseEntity.status(HttpStatus.CREATED).body(get(id));
  }

  /** One entry. */
  @GetMapping("/{id}")
  public Entry get(@PathVariable long id) {
    return repository
        .find(id)
        .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no journal entry " + id));
  }

  /** Update the note/tags/ratings (links are immutable). */
  @PutMapping("/{id}")
  public Entry update(@PathVariable long id, @RequestBody JournalBody body) {
    get(id); // 404 if missing
    validateRatings(body); // links are immutable on update, but the 1–5 rating range still applies
    repository.update(id, toInput(body));
    return get(id);
  }

  /** Delete an entry. */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    if (repository.delete(id) == 0) {
      throw new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no journal entry " + id);
    }
    return ResponseEntity.noContent().build();
  }

  private void validateLinks(JournalBody body) {
    if (body.signalId() != null && !repository.signalExists(body.signalId())) {
      throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, "signalId " + body.signalId() + " does not exist");
    }
    if (body.paperPositionId() != null && !repository.paperPositionExists(body.paperPositionId())) {
      throw new ApiException(
          422, ErrorCodes.VALIDATION_FAILED, "paperPositionId " + body.paperPositionId() + " does not exist");
    }
    validateRatings(body);
  }

  private void validateRatings(JournalBody body) {
    if (body.disciplineRating() != null && (body.disciplineRating() < 1 || body.disciplineRating() > 5)) {
      throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, "disciplineRating must be 1–5");
    }
    if (body.emotionRating() != null && (body.emotionRating() < 1 || body.emotionRating() > 5)) {
      throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, "emotionRating must be 1–5");
    }
  }

  private static EntryInput toInput(JournalBody body) {
    return new EntryInput(
        body.signalId(),
        body.paperPositionId(),
        body.backtestRunId(),
        body.backtestTradeId(),
        body.note(),
        body.tags(),
        body.disciplineRating(),
        body.emotionRating());
  }
}
