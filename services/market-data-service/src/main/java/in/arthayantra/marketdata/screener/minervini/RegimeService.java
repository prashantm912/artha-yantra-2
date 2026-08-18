package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.equitydaily.CashEquityUniverse;
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
 *
 * <p>⚠️ The breadth population is the EQ+BE cash universe ({@link CashEquityUniverse}), not EQ
 * alone. It read {@code series = 'EQ'} until H24 PR-2, which advised the owner off a regime
 * computed over a population 9.2% narrower than the one the screens it gates actually rank
 * ({@code TrendTemplateService:81} and {@code ManasScreenService:84} are both EQ+BE) and than the
 * one the paper books trade. This value is carried in the funnel payload
 * ({@code MinerviniFunnelService:62,129}), so it gates the OWNER's judgement, not the engine.
 *
 * <p>The widening is numerically small but structurally required: measured on live 2026-08-18 over
 * the shipped 10-day window, EQ-only read adv 6,477 / dec 8,226 = 0.4405 and EQ+BE reads adv 7,127
 * / dec 9,057 = 0.4404 — the same HOSTILE verdict, because BE names declined in roughly the same
 * proportion as EQ ones. A near-identical ratio is the expected result, not evidence the change is
 * inert: the defect was that 250 tradeable names had no vote at all.
 *
 * <p>NULL columns cannot distort the counts: {@code count(*) FILTER (WHERE ...)} skips a row whose
 * comparison is NULL, so a bar with no {@code prev_close} lands in neither adv nor dec. (Measured
 * the same day, BE carries {@code prev_close} on 3,522 of 3,522 recent rows, so it is a real vote
 * rather than a silent abstention.) There is no {@code ORDER BY} here, so the NULLs-sort-first
 * hazard that constrains {@code BreadthService:92} does not apply to this site.
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
      WHERE %s AND trade_date <= ?::date AND trade_date > (?::date - ?)
      """
          .formatted(CashEquityUniverse.SERIES_PREDICATE);

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
