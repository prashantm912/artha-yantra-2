package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * Pins the archive-coverage denominator invariant without a database: a full NSE session contributes
 * exactly 75 five-minute slot-starts in {@code [09:15, 15:30)} (09:15, 09:20, ... 15:25), guarding
 * against a regression toward the incorrect 76.
 */
class SnapshotPremiumReaderSlotsTest {

  // expectedSnapshotSlots does not touch JDBC, so a null template is fine here.
  private final SnapshotPremiumReader reader = new SnapshotPremiumReader(null);

  @Test
  void aFullRegularSessionHas75FiveMinuteSlots() {
    Instant open = OffsetDateTime.parse("2026-01-05T09:15:00+05:30").toInstant();
    Instant close = OffsetDateTime.parse("2026-01-05T15:30:00+05:30").toInstant();
    assertThat(reader.expectedSnapshotSlots(open, close)).isEqualTo(75L);
  }

  @Test
  void theCloseSlotStartIsExcludedByTheStrictHiBound() {
    // [09:15, 15:25) excludes the 15:25 slot-start too → 74; [09:15, 15:30) re-includes it → 75.
    Instant open = OffsetDateTime.parse("2026-01-05T09:15:00+05:30").toInstant();
    Instant before1525 = OffsetDateTime.parse("2026-01-05T15:25:00+05:30").toInstant();
    assertThat(reader.expectedSnapshotSlots(open, before1525)).isEqualTo(74L);
  }

  @Test
  void aOneHourIntraSessionWindowHas12Slots() {
    Instant from = OffsetDateTime.parse("2026-01-05T10:00:00+05:30").toInstant();
    Instant to = OffsetDateTime.parse("2026-01-05T11:00:00+05:30").toInstant();
    assertThat(reader.expectedSnapshotSlots(from, to)).isEqualTo(12L);
  }
}
