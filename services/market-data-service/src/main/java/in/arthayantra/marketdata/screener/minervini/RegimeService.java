package in.arthayantra.marketdata.screener.minervini;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Minervini general-market-direction gate (MV-6.9, §4.12): a favorable / neutral / hostile regime
 * read from EOD breadth (advances vs declines over a trailing session window on {@code
 * nse_eod_bhavcopy}). Minervini buys only WITH the market — the SEPA funnel surfaces this so the
 * owner holds off pressing new breakouts in a hostile tape. Config-tunable
 * ({@code artha.minervini.regime.*}); fail-soft — an empty/absent breadth window reads NEUTRAL.
 */
@Service
public class RegimeService {

  /** The regime verdict for a date + the trailing advance ratio it was derived from. */
  public record Regime(
      String regime, @Schema(type = "string", types = {"string", "null"}) BigDecimal advanceRatio, int sessions) {}

  private static final String SQL =
      """
      SELECT
        count(*) FILTER (WHERE close_price > prev_close) AS adv,
        count(*) FILTER (WHERE close_price < prev_close) AS dec,
        count(DISTINCT trade_date) AS sessions
      FROM nse_eod_bhavcopy
      WHERE series = 'EQ' AND trade_date <= ?::date AND trade_date > (?::date - ?)
      """;

  private final JdbcTemplate jdbc;
  private final int windowDays;
  private final BigDecimal favorableFloor; // advance ratio >= this → favorable
  private final BigDecimal hostileCeiling; // advance ratio <= this → hostile

  /** Wires the marketdata datasource + the config-tunable regime bands. */
  public RegimeService(
      JdbcTemplate jdbc,
      @Value("${artha.minervini.regime.window-days:10}") int windowDays,
      @Value("${artha.minervini.regime.favorable-floor:0.52}") BigDecimal favorableFloor,
      @Value("${artha.minervini.regime.hostile-ceiling:0.45}") BigDecimal hostileCeiling) {
    this.jdbc = jdbc;
    this.windowDays = windowDays;
    this.favorableFloor = favorableFloor;
    this.hostileCeiling = hostileCeiling;
  }

  /** The regime as of {@code date} from the trailing {@code windowDays} of breadth. */
  public Regime regime(LocalDate date) {
    java.sql.Date d = java.sql.Date.valueOf(date);
    return jdbc.query(
            SQL,
            rs -> {
              if (!rs.next()) {
                return neutral();
              }
              long adv = rs.getLong("adv");
              long dec = rs.getLong("dec");
              int sessions = rs.getInt("sessions");
              long total = adv + dec;
              if (total == 0) {
                return neutral();
              }
              BigDecimal ratio =
                  BigDecimal.valueOf(adv).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
              String regime =
                  ratio.compareTo(favorableFloor) >= 0
                      ? "FAVORABLE"
                      : ratio.compareTo(hostileCeiling) <= 0 ? "HOSTILE" : "NEUTRAL";
              return new Regime(regime, ratio, sessions);
            },
            d, d, windowDays);
  }

  private static Regime neutral() {
    return new Regime("NEUTRAL", null, 0);
  }
}
