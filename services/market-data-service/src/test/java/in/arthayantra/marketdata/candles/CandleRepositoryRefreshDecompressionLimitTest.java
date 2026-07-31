package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
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
 * ULTRACEMCO, 2026-07-30) once V049 turned the candle caggs into compressed hypertables. The fix raises
 * {@link CandleRepository#MAX_TUPLES_DECOMPRESSED_GUC}, and the whole point is WHERE it is raised: a
 * {@code SET} issued through its own {@code jdbc.execute(...)} takes a DIFFERENT pooled connection
 * than the {@code CALL}s, so it would apply to a session that never refreshes anything — correct-
 * looking code that changes nothing. These tests pin connection IDENTITY, not just SQL text.
 */
class CandleRepositoryRefreshDecompressionLimitTest {

  private static final String SET_SQL =
      "SET timescaledb.max_tuples_decompressed_per_dml_transaction = 0";
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

    RecordingConnection(Predicate<String> failOn) throws SQLException {
      Statement st = mock(Statement.class);
      when(connection.createStatement()).thenReturn(st);
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
    }
  }

  /** A DataSource handing out a FRESH recording connection per {@code getConnection()}. */
  private static final class RecordingDataSource {
    private final List<RecordingConnection> handedOut = new ArrayList<>();
    private final DataSource dataSource = mock(DataSource.class);

    RecordingDataSource(Predicate<String> failOn) throws SQLException {
      when(dataSource.getConnection())
          .thenAnswer(
              invocation -> {
                RecordingConnection fresh = new RecordingConnection(failOn);
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

    List<String> onlyConnection() {
      assertThat(handedOut).as("ONE pinned connection — a SET on any other session is a no-op").hasSize(1);
      return handedOut.get(0).sql;
    }
  }

  @Test
  void raisesTheDecompressionCapOnTheSameConnectionThatIssuesTheRefreshCalls() throws SQLException {
    RecordingDataSource ds = new RecordingDataSource(sql -> false);

    ds.repository().refreshDerivedAggregates(FROM, TO);

    List<String> executed = ds.onlyConnection();
    assertThat(executed).hasSize(1 + EXPECTED_CALLS + 1);
    assertThat(executed.get(0)).isEqualTo(SET_SQL);
    assertThat(executed.get(executed.size() - 1)).isEqualTo(RESET_SQL);
    assertThat(executed.subList(1, executed.size() - 1))
        .as("every cagg CALL sits BETWEEN the raise and the reset, on this one connection")
        .allMatch(s -> s.startsWith(CALL_PREFIX));
  }

  @Test
  void resetsTheCapOnTheExceptionPathSoNoPooledConnectionGoesBackUncapped() throws SQLException {
    // mimics the live abort: a CALL blows up part-way through the view × window grid
    RecordingDataSource ds = new RecordingDataSource(sql -> sql.contains("candles_1h"));

    assertThatThrownBy(() -> ds.repository().refreshDerivedAggregates(FROM, TO))
        .isInstanceOf(DataAccessException.class);

    List<String> executed = ds.onlyConnection();
    assertThat(executed.get(0)).isEqualTo(SET_SQL);
    assertThat(executed.get(executed.size() - 1)).isEqualTo(RESET_SQL);
    assertThat(executed).anyMatch(s -> s.contains("candles_1h")); // it really did fail mid-grid
    assertThat(executed).noneMatch(s -> s.contains("candles_1w")); // and stopped there
  }
}
