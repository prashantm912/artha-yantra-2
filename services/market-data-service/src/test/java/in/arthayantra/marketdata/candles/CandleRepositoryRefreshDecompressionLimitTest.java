package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;

/**
 * Pins WHERE the TimescaleDB per-DML decompression cap is raised (task_e2e01a). The corporate-action
 * cagg rebuild died on that cap ({@code tuple decompression limit exceeded by operation} — CHEVIOT +
 * ULTRACEMCO, 2026-07-30) once V049 turned the candle caggs into compressed hypertables. Three
 * things have to hold, and none is visible in SQL text alone: the raise must land on the SAME pooled
 * connection that issues the CALLs (a {@code SET} through its own {@code jdbc.execute(...)} takes a
 * DIFFERENT connection and silently changes nothing), the DEFAULT refresh path must keep the
 * database guard, and a failed reset must neither hide the real failure nor return a poisoned
 * session to the pool.
 */
class CandleRepositoryRefreshDecompressionLimitTest {

  private static final String SET_SQL =
      "SET timescaledb.max_tuples_decompressed_per_dml_transaction = 5000000";
  private static final String RESET_SQL =
      "RESET timescaledb.max_tuples_decompressed_per_dml_transaction";
  private static final String CALL_PREFIX = "CALL public.refresh_continuous_aggregate(";

  /** The window the live rebuild aborted on: 100 days → ±8-day pad → 2 refresh windows × 5 caggs. */
  private static final OffsetDateTime FROM = OffsetDateTime.parse("2015-01-04T00:00:00Z");
  private static final OffsetDateTime TO = OffsetDateTime.parse("2015-04-14T00:00:00Z");
  private static final int EXPECTED_CALLS = 5 * 2;

  /** One mocked JDBC connection that records, in order, every statement executed ON IT. */
  private static final class RecordingConnection {
    private final List<String> sql = new ArrayList<>();
    private final Connection connection = mock(Connection.class);
    private boolean aborted;

    RecordingConnection(Predicate<String> failOn) throws SQLException {
      this(failOn, List.of());
    }

    RecordingConnection(Predicate<String> failOn, List<long[]> chunkDayOffsetsAndTuples)
        throws SQLException {
      Statement st = mock(Statement.class);
      when(connection.createStatement()).thenReturn(st);
      // The rebuild path reads the cagg's chunk load through executeQuery on THIS statement. A
      // FRESH result set per call, because each of the five views issues its own read. An empty
      // one is the honest "nothing compressed here" answer and plans the plain day-count windows —
      // which is what keeps EXPECTED_CALLS the same grid these tests have always pinned.
      when(st.executeQuery(anyString()))
          .thenAnswer(invocation -> chunkResultSet(chunkDayOffsetsAndTuples));
      when(st.execute(anyString()))
          .thenAnswer(
              invocation -> {
                String executed = invocation.getArgument(0);
                sql.add(executed);
                if (failOn.test(executed)) {
                  throw new SQLException("boom: " + executed);
                }
                return false;
              });
      doAnswer(
              invocation -> {
                aborted = true;
                return null;
              })
          .when(connection)
          .abort(any());
    }
  }

  /**
   * A chunk-load result set: {@code {dayOffsetFromFrom, spanDays, tuples}} per materialization
   * chunk, as {@code CandleRepository.chunkLoad} reads it.
   */
  private static ResultSet chunkResultSet(List<long[]> chunks) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    int[] row = {-1};
    when(rs.next()).thenAnswer(invocation -> ++row[0] < chunks.size());
    when(rs.getObject(1, OffsetDateTime.class))
        .thenAnswer(invocation -> FROM.plusDays(chunks.get(row[0])[0]));
    when(rs.getObject(2, OffsetDateTime.class))
        .thenAnswer(invocation -> FROM.plusDays(chunks.get(row[0])[0] + chunks.get(row[0])[1]));
    when(rs.getLong(3)).thenAnswer(invocation -> chunks.get(row[0])[2]);
    return rs;
  }

  /** A DataSource handing out a FRESH recording connection per {@code getConnection()}. */
  private static final class RecordingDataSource {
    private final List<RecordingConnection> handedOut = new ArrayList<>();
    private final DataSource dataSource = mock(DataSource.class);

    RecordingDataSource(Predicate<String> failOn) throws SQLException {
      this(failOn, List.of());
    }

    RecordingDataSource(Predicate<String> failOn, List<long[]> chunks) throws SQLException {
      when(dataSource.getConnection())
          .thenAnswer(
              invocation -> {
                RecordingConnection fresh = new RecordingConnection(failOn, chunks);
                handedOut.add(fresh);
                return fresh.connection;
              });
    }

    CandleRepository repository() {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      // DataSource-free translator: the error-code one would open ANOTHER connection for metadata
      jdbc.setExceptionTranslator(new SQLStateSQLExceptionTranslator());
      return new CandleRepository(jdbc);
    }

    RecordingConnection onlyConnection() {
      assertThat(handedOut).as("ONE pinned connection — a SET on any other session is a no-op").hasSize(1);
      return handedOut.get(0);
    }
  }

  // ---- the DEFAULT path (gap backfill, futures roller) must not weaken the database guard

  @Test
  void defaultRefreshNeverTouchesTheDecompressionCap() throws SQLException {
    RecordingDataSource ds = new RecordingDataSource(sql -> false);

    ds.repository().refreshDerivedAggregates(FROM, TO);

    List<String> executed = ds.onlyConnection().sql;
    assertThat(executed)
        .as("recent-window refreshes decompress nothing — they keep the DB's 100k guard")
        .hasSize(EXPECTED_CALLS)
        .allMatch(s -> s.startsWith(CALL_PREFIX));
  }

  // ---- the REBUILD path opts in explicitly, to a FINITE ceiling, on the pinned connection

  @Test
  void rebuildRefreshRaisesTheCapToTheFiniteCeilingOnTheSameConnectionAsTheCalls()
      throws SQLException {
    RecordingDataSource ds = new RecordingDataSource(sql -> false);

    ds.repository().refreshDerivedAggregatesForRebuild(FROM, TO);

    List<String> executed = ds.onlyConnection().sql;
    assertThat(executed).hasSize(1 + EXPECTED_CALLS + 1);
    assertThat(executed.get(0)).as("finite, never 0/unlimited").isEqualTo(SET_SQL);
    assertThat(executed.get(executed.size() - 1)).isEqualTo(RESET_SQL);
    assertThat(executed.subList(1, executed.size() - 1))
        .as("every cagg CALL sits BETWEEN the raise and the reset, on this one connection")
        .allMatch(s -> s.startsWith(CALL_PREFIX));
  }

  // ---- the defect itself: a DENSE cagg must be sliced FINER than the day cap alone would

  @Test
  void rebuildRefreshSlicesADenseCaggFinerThanTheDayCapAlone() throws SQLException {
    // one 70-day materialization chunk at the live 2026-08-04 candles_5m peak (5,558,488 tuples).
    // Against the 1.25M budget that is ~5 windows; the 92-day day-cap alone would give this
    // 116-day range just 2. If the rebuild ever stops planning from the measured chunk load, this
    // count falls back to 2 and the window is back over the decompression ceiling.
    RecordingDataSource ds =
        new RecordingDataSource(sql -> false, List.of(new long[] {0, 70, 5_558_488}));

    ds.repository()
        .refreshDerivedAggregatesForRebuild(
            FROM, TO, List.of(CandleRepository.DerivedAggregate.CANDLES_5M));

    List<String> calls =
        ds.onlyConnection().sql.stream().filter(s -> s.startsWith(CALL_PREFIX)).toList();
    assertThat(calls)
        .as("a 5.5M-tuple chunk must be split by the TUPLE budget, not just the 92-day span cap")
        .hasSizeGreaterThan(EXPECTED_CALLS / 5);
    assertThat(calls).allMatch(s -> s.contains("candles_5m"));
  }

  @Test
  void rebuildRefreshLeavesASparseCaggOnTheDayCap() throws SQLException {
    // candles_1w's densest live chunk is 19,076 tuples — nothing to split, so the sparse cagg must
    // NOT inherit the dense one's window count
    RecordingDataSource ds =
        new RecordingDataSource(sql -> false, List.of(new long[] {0, 70, 19_076}));

    ds.repository()
        .refreshDerivedAggregatesForRebuild(
            FROM, TO, List.of(CandleRepository.DerivedAggregate.CANDLES_1W));

    List<String> calls =
        ds.onlyConnection().sql.stream().filter(s -> s.startsWith(CALL_PREFIX)).toList();
    assertThat(calls).hasSize(EXPECTED_CALLS / 5); // the same 2 windows the day cap gives
  }

  // ---- the selectable subset: explicit, ordered, and byte-identical by default

  @Test
  void rebuildRefreshDefaultsToEveryCaggSoExistingCallersAreUnchanged() throws SQLException {
    RecordingDataSource ds = new RecordingDataSource(sql -> false);

    ds.repository().refreshDerivedAggregatesForRebuild(FROM, TO);

    assertThat(ds.onlyConnection().sql)
        .as("the two-argument form still refreshes all five caggs")
        .hasSize(1 + EXPECTED_CALLS + 1);
  }

  @Test
  void rebuildRefreshHonoursTheRequestedSubsetAndSkipsTheRest() throws SQLException {
    RecordingDataSource ds = new RecordingDataSource(sql -> false);

    // the owner-scoped repair subset: the intraday planes only (1d is served by the dense native
    // candles@1d path, 1w is rarely a gate input)
    ds.repository()
        .refreshDerivedAggregatesForRebuild(
            FROM,
            TO,
            List.of(
                CandleRepository.DerivedAggregate.CANDLES_5M,
                CandleRepository.DerivedAggregate.CANDLES_15M,
                CandleRepository.DerivedAggregate.CANDLES_1H));

    List<String> calls =
        ds.onlyConnection().sql.stream().filter(s -> s.startsWith(CALL_PREFIX)).toList();
    assertThat(calls).hasSize(3 * 2);
    assertThat(calls).allMatch(s -> !s.contains("candles_1d") && !s.contains("candles_1w"));
    assertThat(calls.get(0)).contains("candles_5m");
    assertThat(calls.get(calls.size() - 1)).contains("candles_1h");
  }

  @Test
  void rebuildRefreshRefreshesParentsBeforeChildrenWhateverOrderTheSubsetIsGivenIn()
      throws SQLException {
    RecordingDataSource ds = new RecordingDataSource(sql -> false);

    // 1w reads candles_1d (V004) — asking for it first must NOT refresh it from a stale parent
    ds.repository()
        .refreshDerivedAggregatesForRebuild(
            FROM,
            TO,
            List.of(
                CandleRepository.DerivedAggregate.CANDLES_1W,
                CandleRepository.DerivedAggregate.CANDLES_1D));

    List<String> calls =
        ds.onlyConnection().sql.stream().filter(s -> s.startsWith(CALL_PREFIX)).toList();
    assertThat(calls).hasSize(2 * 2);
    assertThat(calls.get(0)).contains("candles_1d");
    assertThat(calls.get(calls.size() - 1)).contains("candles_1w");
  }

  @Test
  void rebuildRefreshResetsTheCapWhenTheCallFails() throws SQLException {
    // mimics the live abort: a CALL blows up part-way through the view × window grid
    RecordingDataSource ds = new RecordingDataSource(sql -> sql.contains("candles_1h"));

    assertThatThrownBy(() -> ds.repository().refreshDerivedAggregatesForRebuild(FROM, TO))
        .isInstanceOf(DataAccessException.class);

    RecordingConnection only = ds.onlyConnection();
    assertThat(only.sql.get(0)).isEqualTo(SET_SQL);
    assertThat(only.sql.get(only.sql.size() - 1)).isEqualTo(RESET_SQL);
    assertThat(only.sql).anyMatch(s -> s.contains("candles_1h")); // it really did fail mid-grid
    assertThat(only.sql).noneMatch(s -> s.contains("candles_1w")); // and stopped there
    assertThat(only.aborted).as("reset succeeded, so the connection stays usable").isFalse();
  }

  // ---- a failing RESET must not swallow the refresh failure, nor return a poisoned session

  @Test
  void failedResetKeepsTheOriginalFailureAndAbortsTheConnection() throws SQLException {
    RecordingDataSource ds =
        new RecordingDataSource(sql -> sql.contains("candles_1h") || sql.startsWith("RESET"));

    Throwable thrown =
        catchThrowable(() -> ds.repository().refreshDerivedAggregatesForRebuild(FROM, TO));

    assertThat(thrown).isInstanceOf(DataAccessException.class);
    assertThat(thrown.getCause())
        .as("the REFRESH failure survives — not the cleanup failure that followed it")
        .hasMessageContaining("candles_1h");
    assertThat(thrown.getCause().getSuppressed())
        .as("the cleanup failure is preserved alongside, never dropped")
        .hasSize(1);
    assertThat(thrown.getCause().getSuppressed()[0]).hasMessageContaining("RESET");
    assertThat(ds.onlyConnection().aborted)
        .as("cap could not be reset — the physical connection must not go back to the pool")
        .isTrue();
  }

  @Test
  void failedResetOnAnOtherwiseCleanRefreshStillAbortsTheConnection() throws SQLException {
    RecordingDataSource ds = new RecordingDataSource(sql -> sql.startsWith("RESET"));

    assertThatThrownBy(() -> ds.repository().refreshDerivedAggregatesForRebuild(FROM, TO))
        .isInstanceOf(DataAccessException.class)
        .hasRootCauseMessage("boom: " + RESET_SQL);

    assertThat(ds.onlyConnection().aborted).isTrue();
  }
}
