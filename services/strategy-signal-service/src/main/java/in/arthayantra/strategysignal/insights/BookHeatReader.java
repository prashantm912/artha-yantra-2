package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Assembles per-book {@link BookHeat} readings for the RISK_HEAT sweep (INT design §3.2 risk row) —
 * a READ-ONLY view over the persisted paper-margin annotations. It reads {@code paper_positions}
 * (open rows: {@code book}, {@code tradingsymbol}, {@code side}, {@code margin_pct}, stamped
 * advisory-only by the F9 annotator) and the {@code risk_settings} heat cap. It NEVER imports or
 * mutates the {@code paper} module and NEVER makes a live margin call — heat is derived from data the
 * paper flow already persisted.
 *
 * <p><b>Caveat:</b> {@code heatPct} is the SUM of per-position {@code margin_pct} (each is SPAN
 * margin as % of book equity AT OPEN), so it is an approximation of current portfolio heat — good
 * enough for a display-only warning, and {@code null} when no open position was priced (analytics
 * off / cash book). Fail-soft: any read error yields an empty list (the sweep no-ops, never throws).
 */
@Component
public class BookHeatReader {

  private static final Logger log = LoggerFactory.getLogger(BookHeatReader.class);

  private final JdbcTemplate jdbc;

  public BookHeatReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** One {@link BookHeat} per book with at least one open position. */
  public List<BookHeat> read() {
    try {
      Map<String, Agg> byBook = new LinkedHashMap<>();
      // Per (book, symbol, side) open counts → per-book open total, summed margin_pct, and the
      // most-concentrated leg.
      jdbc.query(
          """
          SELECT book, tradingsymbol, side, count(*) AS n,
                 sum(margin_pct) FILTER (WHERE margin_pct IS NOT NULL) AS heat
          FROM paper_positions
          WHERE status = 'OPEN'
          GROUP BY book, tradingsymbol, side
          """,
          rs -> {
            String book = rs.getString("book");
            int n = rs.getInt("n");
            BigDecimal heat = rs.getBigDecimal("heat");
            Agg agg = byBook.computeIfAbsent(book, Agg::new);
            agg.openPositions += n;
            if (heat != null) {
              agg.heatPct = agg.heatPct == null ? heat : agg.heatPct.add(heat);
            }
            if (n > agg.maxConcentration) {
              agg.maxConcentration = n;
              agg.concentrationLeg = rs.getString("tradingsymbol") + " " + rs.getString("side");
            }
          });
      if (byBook.isEmpty()) {
        return List.of();
      }
      Map<String, BigDecimal> caps = enabledCaps();
      List<BookHeat> out = new ArrayList<>(byBook.size());
      for (Agg a : byBook.values()) {
        out.add(
            new BookHeat(
                a.book, a.openPositions, a.heatPct, caps.get(a.book), a.maxConcentration, a.concentrationLeg));
      }
      return out;
    } catch (RuntimeException e) {
      log.warn("insight book-heat read FAILED (skipping risk sweep): {}", e.getMessage());
      return List.of();
    }
  }

  /** Enabled heat caps by book from {@code risk_settings} (key {@code heat_cap_pct}). */
  private Map<String, BigDecimal> enabledCaps() {
    Map<String, BigDecimal> caps = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT book, value ->> 'value' AS cap, (value ->> 'enabled')::boolean AS enabled
        FROM risk_settings
        WHERE key = 'heat_cap_pct'
        """,
        rs -> {
          boolean enabled = rs.getBoolean("enabled");
          String cap = rs.getString("cap");
          if (enabled && cap != null) {
            caps.put(rs.getString("book"), new BigDecimal(cap));
          }
        });
    return caps;
  }

  private static final class Agg {
    final String book;
    int openPositions;
    BigDecimal heatPct;
    int maxConcentration;
    String concentrationLeg;

    Agg(String book) {
      this.book = book;
    }
  }
}
