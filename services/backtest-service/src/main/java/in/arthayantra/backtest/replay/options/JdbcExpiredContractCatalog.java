package in.arthayantra.backtest.replay.options;

import in.arthayantra.backtest.replay.options.OptionContractSelector.Catalog;
import in.arthayantra.backtest.replay.options.OptionContractSelector.Expiry;
import in.arthayantra.backtest.replay.options.OptionContractSelector.OptionContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the backfilled {@code marketdata.expired_contracts} registry (D10 read; the CD-1 grant model,
 * same as {@link SnapshotPremiumReader}) to back {@link OptionContractSelector}. The nearest ATM strike
 * is resolved IN the database ({@code ORDER BY abs(strike - spot) LIMIT 1}) over the strikes actually
 * listed for the expiry — so the band/step never has to be inferred client-side.
 */
@Component
public class JdbcExpiredContractCatalog implements Catalog {

  private final JdbcTemplate jdbc;

  /** Wires JDBC (connected as the owner; the marketdata read is via the CD-1 grant model). */
  public JdbcExpiredContractCatalog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Expiry> expiriesOnOrAfter(String underlying, LocalDate date) {
    return jdbc.query(
        "SELECT DISTINCT expiry, weekly FROM marketdata.expired_contracts "
            + "WHERE underlying_symbol = ? AND expiry >= ? ORDER BY expiry",
        (rs, n) -> new Expiry(rs.getObject("expiry", LocalDate.class), rs.getBoolean("weekly")),
        underlying,
        java.sql.Date.valueOf(date));
  }

  @Override
  public Optional<OptionContract> nearestStrike(
      String underlying, LocalDate expiry, String optionType, BigDecimal spot) {
    List<OptionContract> rows =
        jdbc.query(
            "SELECT exchange, tradingsymbol, strike, lot_size FROM marketdata.expired_contracts "
                + "WHERE underlying_symbol = ? AND expiry = ? AND instrument_type = ? "
                + "AND strike IS NOT NULL ORDER BY abs(strike - ?) LIMIT 1",
            (rs, n) ->
                new OptionContract(
                    rs.getString("exchange"),
                    rs.getString("tradingsymbol"),
                    rs.getBigDecimal("strike"),
                    expiry,
                    optionType,
                    rs.getInt("lot_size")),
            underlying,
            java.sql.Date.valueOf(expiry),
            optionType,
            spot);
    return rows.stream().findFirst();
  }
}
