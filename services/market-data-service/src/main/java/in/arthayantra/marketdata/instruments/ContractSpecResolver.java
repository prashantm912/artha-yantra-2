package in.arthayantra.marketdata.instruments;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The B-20 as-of resolution rule: spec for {@code (exchange, tradingsymbol)} at date <i>D</i> =
 * the latest accrued row with {@code as_of_date <= D}. Windows predating accrual resolve to the
 * CURRENT master spec and carry the mandatory {@code spec_asof_estimated} honesty flag — the
 * accrue-from-today posture; the archive never pretends to know what it cannot. Consumed by
 * backtest-service replay sizing (Stage D) over its read-only grant.
 */
@Service
public class ContractSpecResolver {

  /** A resolved spec; {@code specAsofEstimated} is the B-20 honesty flag. */
  public record SpecAsOf(
      Integer lotSize, BigDecimal tickSize, LocalDate asOfDate, boolean specAsofEstimated) {}

  private final JdbcTemplate jdbc;

  /** Wires the resolver queries. */
  public ContractSpecResolver(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Spec in force for the instrument at {@code date}. */
  public SpecAsOf resolve(String exchange, String tradingsymbol, LocalDate date) {
    List<SpecAsOf> accrued =
        jdbc.query(
            """
            SELECT lot_size, tick_size, as_of_date FROM contract_spec_history
            WHERE exchange = ? AND tradingsymbol = ? AND as_of_date <= ?
            ORDER BY as_of_date DESC LIMIT 1
            """,
            (rs, n) ->
                new SpecAsOf(
                    rs.getObject("lot_size", Integer.class),
                    rs.getBigDecimal("tick_size"),
                    rs.getDate("as_of_date").toLocalDate(),
                    false),
            exchange,
            tradingsymbol,
            Date.valueOf(date));
    if (!accrued.isEmpty()) {
      return accrued.get(0);
    }
    // pre-accrual window: current spec + honesty flag (mandatory — B-20)
    List<SpecAsOf> current =
        jdbc.query(
            "SELECT lot_size, tick_size FROM instruments WHERE exchange = ? AND tradingsymbol = ?",
            (rs, n) ->
                new SpecAsOf(
                    rs.getObject("lot_size", Integer.class), rs.getBigDecimal("tick_size"), null, true),
            exchange,
            tradingsymbol);
    if (current.isEmpty()) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_INSTRUMENT, "no instrument " + exchange + ":" + tradingsymbol);
    }
    return current.get(0);
  }
}
