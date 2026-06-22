package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketdata.constituents.StaticIndexConstituents;
import in.arthayantra.marketdata.constituents.StockSectorMap;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * oipulse Equity sector views from the latest EQ bhavcopy session + the static {@link StockSectorMap}:
 *
 * <ul>
 *   <li><b>Sector Heatmap</b> — an index's constituents grouped by sector, each stock's % change (close
 *       vs prev close). Sized by |% change| (the feed carries no market cap), coloured by % change.
 *   <li><b>Sector Stats</b> — per-sector aggregates (avg % change + advancer/decliner counts, computed
 *       FROM the constituents) plus the per-stock factor table. The live sector-INDEX level (NIFTY
 *       METAL, …) is not captured, so the cards are derived from constituent performance, not the index.
 * </ul>
 */
@Service
public class EquitySectorService {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final JdbcTemplate jdbc;
  private final StockSectorMap sectors;
  private final StaticIndexConstituents constituents;

  public EquitySectorService(
      JdbcTemplate jdbc, StockSectorMap sectors, StaticIndexConstituents constituents) {
    this.jdbc = jdbc;
    this.sectors = sectors;
    this.constituents = constituents;
  }

  /** One stock's latest-session change + its sector. */
  public record StockChange(
      String symbol, String sector, BigDecimal changePct, BigDecimal close, BigDecimal prevClose) {}

  /** An index's constituent tiles grouped (client-side) by sector. */
  public record SectorHeatmap(String index, List<StockChange> tiles, LocalDate asOf) {}

  /** Per-sector roll-up of the constituents (avg change + advancer/decliner split). */
  public record SectorAgg(
      String sector, BigDecimal avgChangePct, int positive, int negative, int total) {}

  /** Sector overview: per-sector cards + the flat per-stock factor table. */
  public record SectorStats(LocalDate asOf, List<SectorAgg> sectors, List<StockChange> stocks) {}

  public SectorHeatmap sectorHeatmap(String index) {
    List<String> members = constituents.symbols(index);
    if (members.isEmpty()) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_RESOURCE, "no constituent reference for index '" + index + "'");
    }
    Set<String> wanted = Set.copyOf(members);
    List<StockChange> tiles = new ArrayList<>();
    for (StockChange s : latestMapped()) {
      if (wanted.contains(s.symbol())) {
        tiles.add(s);
      }
    }
    if (tiles.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no bhavcopy for index '" + index + "'");
    }
    return new SectorHeatmap(index, tiles, asOf());
  }

  public SectorStats sectorStats() {
    List<StockChange> stocks = latestMapped();
    if (stocks.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no EQ bhavcopy accrued yet");
    }
    Map<String, List<StockChange>> bySector = new LinkedHashMap<>();
    for (StockChange s : stocks) {
      bySector.computeIfAbsent(s.sector(), k -> new ArrayList<>()).add(s);
    }
    List<SectorAgg> aggs = new ArrayList<>();
    for (Map.Entry<String, List<StockChange>> e : bySector.entrySet()) {
      List<StockChange> group = e.getValue();
      int pos = 0;
      int neg = 0;
      BigDecimal sum = BigDecimal.ZERO;
      int counted = 0;
      for (StockChange s : group) {
        if (s.changePct() == null) {
          continue;
        }
        counted++;
        sum = sum.add(s.changePct());
        if (s.changePct().signum() > 0) {
          pos++;
        } else if (s.changePct().signum() < 0) {
          neg++;
        }
      }
      BigDecimal avg =
          counted == 0 ? null : sum.divide(BigDecimal.valueOf(counted), 2, RoundingMode.HALF_UP);
      aggs.add(new SectorAgg(e.getKey(), avg, pos, neg, group.size()));
    }
    aggs.sort((a, b) -> a.sector().compareTo(b.sector()));
    stocks.sort((a, b) -> a.symbol().compareTo(b.symbol()));
    return new SectorStats(asOf(), aggs, stocks);
  }

  /** Latest-session change for every sector-mapped EQ stock (unmapped stocks are dropped). */
  private List<StockChange> latestMapped() {
    record Raw(String symbol, BigDecimal close, BigDecimal prevClose) {}
    List<Raw> rows =
        jdbc.query(
            "WITH ranked AS ("
                + "  SELECT symbol, close_price, prev_close, "
                + "    ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn "
                + "  FROM nse_eod_bhavcopy WHERE series = 'EQ') "
                + "SELECT symbol, close_price, prev_close FROM ranked WHERE rn = 1",
            (rs, n) ->
                new Raw(rs.getString("symbol"), rs.getBigDecimal("close_price"), rs.getBigDecimal("prev_close")));
    List<StockChange> out = new ArrayList<>();
    for (Raw r : rows) {
      String sector = sectors.sector(r.symbol());
      if (sector == null) {
        continue; // curated (sector-mapped) universe only
      }
      BigDecimal pct =
          (r.prevClose() != null && r.prevClose().signum() > 0 && r.close() != null)
              ? r.close().subtract(r.prevClose()).multiply(HUNDRED).divide(r.prevClose(), 2, RoundingMode.HALF_UP)
              : null;
      out.add(new StockChange(r.symbol(), sector, pct, r.close(), r.prevClose()));
    }
    return out;
  }

  private LocalDate asOf() {
    return jdbc.queryForObject(
        "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE series = 'EQ'", LocalDate.class);
  }
}
