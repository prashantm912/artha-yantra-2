package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V062 applied and {@link BlindWindowRegister} round-trips against the real table — the SQL is the
 * substance here ({@code RETURNING id}, the timestamptz binding, the half-closed CHECK), so a
 * mocked {@code JdbcTemplate} would prove none of it.
 *
 * <p>The register is built by hand rather than autowired: it shares {@link SubscriberHealthCanary}'s
 * {@code artha.signals.engine-enabled} condition, and this context runs with the engine off.
 *
 * <p>Shared singleton DB with no per-method cleanup, so every window carries a UUID-unique detail
 * and the assertions filter to it rather than counting rows.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class BlindWindowRegisterIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  private BlindWindowRegister register() {
    return new BlindWindowRegister(dataSource);
  }

  /** Read back through pgjdbc's OffsetDateTime mapping — a raw {@code Timestamp} would drag the
   * JVM's default zone into the comparison, which is exactly the class of trap this table records. */
  private record Window(Instant startedAt, Instant endedAt, String reason, String detail) {}

  private Window row(long id) {
    return jdbc.queryForObject(
        "SELECT started_at, ended_at, closed_reason, detail FROM blind_windows WHERE id = ?",
        (rs, n) -> {
          OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
          return new Window(
              rs.getObject("started_at", OffsetDateTime.class).toInstant(),
              ended == null ? null : ended.toInstant(),
              rs.getString("closed_reason"),
              rs.getString("detail"));
        },
        id);
  }

  @Test
  void openThenClose_roundTripsTheWindow() {
    String detail = "blind-roundtrip-" + UUID.randomUUID();
    Instant startedAt = Instant.ofEpochMilli(1_755_000_000_000L); // 2025-08-12T13:20Z, ms precision
    Instant endedAt = startedAt.plusSeconds(900);

    Long id = register().open(startedAt, detail);
    assertThat(id).isNotNull();

    Window open = row(id);
    assertThat(open.startedAt()).isEqualTo(startedAt);
    assertThat(open.endedAt()).isNull();
    assertThat(open.reason()).isNull();
    assertThat(open.detail()).isEqualTo(detail);

    register().close(id, endedAt, "bars-resumed");

    Window closed = row(id);
    assertThat(closed.endedAt()).isEqualTo(endedAt);
    assertThat(closed.reason()).isEqualTo("bars-resumed");
  }

  /**
   * The close is BY ID, never a blanket {@code WHERE ended_at IS NULL} — a process that died while
   * blind must keep its open row rather than have a later recovery it never witnessed stamped on it.
   */
  @Test
  void close_leavesEveryOtherOpenWindowAlone() {
    String mine = "blind-scoped-" + UUID.randomUUID();
    String abandoned = "blind-abandoned-" + UUID.randomUUID();
    Instant at = Instant.ofEpochMilli(1_755_100_000_000L);

    Long orphan = register().open(at, abandoned);
    Long id = register().open(at.plusSeconds(60), mine);

    register().close(id, at.plusSeconds(600), "bars-resumed");

    assertThat(row(orphan).endedAt()).isNull();
    assertThat(row(orphan).reason()).isNull();
  }

  /** A second close must not restamp the window with a reason that contradicts the first. */
  @Test
  void close_isIdempotentAndKeepsTheFirstReason() {
    Instant at = Instant.ofEpochMilli(1_755_200_000_000L);
    Long id = register().open(at, "blind-idempotent-" + UUID.randomUUID());

    register().close(id, at.plusSeconds(300), "bars-resumed");
    register().close(id, at.plusSeconds(9000), "session-ended");

    Window row = row(id);
    assertThat(row.reason()).isEqualTo("bars-resumed");
    assertThat(row.endedAt()).isEqualTo(at.plusSeconds(300));
  }

  /** A failed open yields a null id; the matching close must be a silent no-op, not an NPE. */
  @Test
  void closeWithNullId_isANoOp() {
    assertThatCode(() -> register().close(null, Instant.now(), "bars-resumed"))
        .doesNotThrowAnyException();
  }

  /** The CHECK forbids a half-closed row — an end with no reason, or a reason with no end. */
  @Test
  void checkConstraintForbidsAHalfClosedRow() {
    Instant at = Instant.ofEpochMilli(1_755_300_000_000L);
    Long id = register().open(at, "blind-check-" + UUID.randomUUID());

    assertThatThrownBy(
            () -> jdbc.update("UPDATE blind_windows SET ended_at = now() WHERE id = ?", id))
        .hasMessageContaining("blind_windows_closed_ck");
    assertThatThrownBy(
            () -> jdbc.update("UPDATE blind_windows SET closed_reason = 'x' WHERE id = ?", id))
        .hasMessageContaining("blind_windows_closed_ck");
  }
}
