package in.arthayantra.strategysignal.swing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The latest durable catch-up row for one swing family; every field but {@code batch} is null when
 * no row exists yet. Read by the manual-run confirm dialog so the "only run this when
 * swing_catchup_runs shows ABANDONED/refused" precondition is checkable where the decision is made.
 *
 * <p><b>Deliberately ONE shared record, not a nested twin per controller</b> (cross-vendor review
 * A1). springdoc names components by SIMPLE name, so two nested {@code CatchUpStatus} records —
 * one per controller — collapse into a single published schema. While the twins are identical the
 * spec stays honest, but the moment someone adds a field to only one of them, springdoc publishes
 * ONE shape for BOTH endpoints and the other contract silently claims a key it never sends. That
 * exact twin-name collapse published a live lie once already (#1010), so the shape lives here where
 * it cannot diverge.
 */
public record SwingCatchUpStatus(
    String batch,
    @Schema(types = {"string", "null"}, format = "date") LocalDate sessionDate,
    @Schema(types = {"string", "null"}) String status,
    @Schema(types = {"integer", "null"}) Integer attempts,
    @Schema(types = {"string", "null"}) String reason,
    @Schema(types = {"string", "null"}, format = "date-time") OffsetDateTime updatedAt) {

  /** No catch-up row exists for this family yet — nothing has ever been claimed. */
  public static SwingCatchUpStatus empty(String batch) {
    return new SwingCatchUpStatus(batch, null, null, null, null, null);
  }
}
