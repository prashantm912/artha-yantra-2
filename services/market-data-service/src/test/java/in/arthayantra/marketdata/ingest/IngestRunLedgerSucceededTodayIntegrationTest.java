package in.arthayantra.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link IngestRunLedger#succeededToday} — the gate the intra-day NSE retry keys on.
 *
 * <p>⚠️ Shared singleton DB with NO per-method cleanup, so every method uses its own UUID source.
 *
 * <p>The case that earns this an integration test rather than a unit test is {@link
 * #anEarlyMorningIstSuccessCountsAsToday}: in-container {@code now()} is UTC, so a bare {@code
 * started_at::date} rolls "today" at 05:30 IST. Only a real Postgres can prove the IST-on-both-sides
 * comparison behaves.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class IngestRunLedgerSucceededTodayIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired IngestRunLedger ledger;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;

  /**
   * Runs {@code succeededToday} with the Postgres session pinned to UTC, because THAT is production.
   *
   * <p>⚠️ Without this the test is structurally incapable of catching the bug it exists for. pgjdbc
   * sets the session {@code TimeZone} from the JVM default: on this developer host that is IST, so a
   * bare {@code started_at::date} yields an IST date and the broken query looks correct. The live
   * service runs in a UTC container, where the same SQL yields a UTC date and the boundary is real.
   * Measured 2026-08-24 — host {@code date} 18:32 IST vs {@code ay-market-data-service} 13:02 UTC.
   *
   * <p>{@code SET LOCAL} is transaction-scoped, so it reverts on commit and cannot leak into the
   * other tests sharing this singleton DB.
   */
  private boolean succeededTodayUnderUtcSession(String source) {
    return Boolean.TRUE.equals(
        new TransactionTemplate(txManager)
            .execute(
                status -> {
                  jdbc.execute("SET LOCAL TimeZone TO 'UTC'");
                  return ledger.succeededToday(source);
                }));
  }

  private String uniqueSource() {
    return "SUCCEEDED_TODAY_IT_" + UUID.randomUUID();
  }

  /**
   * ⚠️ The timestamp is built SERVER-SIDE in IST, and that is load-bearing, not style.
   *
   * <p>The first version of this fixture bound an {@code OffsetDateTime} at {@code +05:30} through
   * {@code jdbc.update}. The offset was dropped in binding, so "02:00 IST" was stored as 02:00 UTC —
   * the same calendar day either way. The boundary test therefore passed against a deliberately
   * BROKEN UTC-comparing query: a fixture that could not produce the failure it existed to detect.
   * Constructing the instant in Postgres with an explicit zone removes the binding from the picture.
   *
   * @param istHour hour of TODAY in IST, so {@code 2} really is 20:30 UTC yesterday
   */
  private void insertAtIstHour(String source, String status, int istHour, int daysAgo) {
    insertAtIstHour(source, status, istHour, daysAgo, 42L); // a real run wrote rows
  }

  private void insertAtIstHour(
      String source, String status, int istHour, int daysAgo, Long rowsWritten) {
    jdbc.update(
        "INSERT INTO ingest_runs (source, status, rows_written, started_at) VALUES (?, ?, ?,"
            + " ((((now() AT TIME ZONE 'Asia/Kolkata')::date - make_interval(days => ?))"
            + "   + make_interval(hours => ?)) AT TIME ZONE 'Asia/Kolkata'))",
        source,
        status,
        rowsWritten,
        daysAgo,
        istHour);
  }

  @Test
  @DisplayName("no rows at all means not-succeeded, so the retry runs")
  void anUnknownSourceHasNotSucceeded() {
    assertThat(succeededTodayUnderUtcSession(uniqueSource())).isFalse();
  }

  @Test
  @DisplayName("a SUCCESS earlier today means the retry must skip")
  void aSuccessTodayIsSeen() {
    String source = uniqueSource();
    insertAtIstHour(source, "SUCCESS", 8, 0);
    assertThat(succeededTodayUnderUtcSession(source)).isTrue();
  }

  @Test
  @DisplayName("a FAILURE today is NOT a success — this is the whole point of the retry")
  void aFailureTodayIsNotASuccess() {
    String source = uniqueSource();
    insertAtIstHour(source, "FAILURE", 8, 0);
    assertThat(succeededTodayUnderUtcSession(source))
        .as("the 2026-08-24 outage left exactly this row shape and the rail stayed dark all day")
        .isFalse();
  }

  @Test
  @DisplayName("yesterday's SUCCESS does not satisfy today")
  void yesterdaysSuccessDoesNotCount() {
    String source = uniqueSource();
    insertAtIstHour(source, "SUCCESS", 12, 1);
    assertThat(succeededTodayUnderUtcSession(source)).isFalse();
  }

  /**
   * THE TRAP CASE. 02:00 IST today is 20:30 UTC YESTERDAY, so a UTC-based date comparison reports it
   * as yesterday's run and the retry would re-fetch a source that already landed. The repo records
   * this same off-by-one for candle buckets; it applies to every date derived from a timestamptz.
   */
  @Test
  @DisplayName("a 02:00 IST success counts as TODAY, not yesterday")
  void anEarlyMorningIstSuccessCountsAsToday() {
    String source = uniqueSource();
    insertAtIstHour(source, "SUCCESS", 2, 0);
    assertThat(succeededTodayUnderUtcSession(source))
        .as("02:00 IST is 20:30 UTC yesterday — a UTC ::date comparison gets this wrong")
        .isTrue();
  }

  /**
   * Pins that the trap case above actually CROSSES the UTC/IST date boundary.
   *
   * <p>The review of #1451 found the fixture and the code under test both derive "today" from the
   * same {@code AT TIME ZONE 'Asia/Kolkata'} expression, so swapping that zone in both places keeps
   * every other test green while proving nothing. This asserts the property the fixture is supposed
   * to have — that a 02:00 IST row really does land on a DIFFERENT UTC date — so a zone change is
   * caught here rather than silently hollowing out the boundary test.
   */
  @Test
  @DisplayName("the 02:00 IST fixture really does straddle the UTC date boundary")
  void theEarlyMorningFixtureActuallyCrossesTheBoundary() {
    String source = uniqueSource();
    insertAtIstHour(source, "SUCCESS", 2, 0);

    Boolean straddles =
        jdbc.queryForObject(
            "SELECT (started_at AT TIME ZONE 'UTC')::date"
                + " <> (started_at AT TIME ZONE 'Asia/Kolkata')::date"
                + " FROM ingest_runs WHERE source = ?",
            Boolean.class,
            source);

    assertThat(straddles)
        .as("a 02:00 IST row must sit on the PREVIOUS UTC date, or the trap case is inert")
        .isTrue();
  }

  /**
   * THE SECOND TRAP CASE, found by the post-merge review of #1451. {@code record} stamps SUCCESS on
   * any non-throwing return, and NSE's soft failure is a 200 carrying no data:
   * {@code LiveFiiDiiFetcher} iterates an empty JSON array to an empty list, and
   * {@code LiveParticipantOiFetcher}'s {@code csv.contains("Client Type")} guard is satisfied by the
   * header line alone. On {@code status} alone, either would disarm the day's remaining retries in
   * the exact failure mode the retry exists for.
   */
  @Test
  @DisplayName("a SUCCESS that wrote ZERO rows must NOT count — a 200 is not data")
  void aZeroRowSuccessDoesNotCount() {
    String source = uniqueSource();
    insertAtIstHour(source, "SUCCESS", 8, 0, 0L);
    assertThat(succeededTodayUnderUtcSession(source))
        .as("an empty NSE response records SUCCESS; it must not disarm the retry")
        .isFalse();
  }

  /** A pre-#1451 row predates the column being load-bearing; NULL must read as "no data". */
  @Test
  @DisplayName("a SUCCESS with NULL rows_written does not count either")
  void aNullRowCountSuccessDoesNotCount() {
    String source = uniqueSource();
    insertAtIstHour(source, "SUCCESS", 8, 0, null);
    assertThat(succeededTodayUnderUtcSession(source)).isFalse();
  }
}
