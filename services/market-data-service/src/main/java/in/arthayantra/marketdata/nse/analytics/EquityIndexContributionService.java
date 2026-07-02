package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.constituents.StaticIndexWeights;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * oipulse "Index Contribution": how much each constituent pushes the index, split into advances and
 * declines. Contribution is computed weight × % change (free-float weights from {@link
 * StaticIndexWeights}). Two reads: the EOD fold (% change from the latest EQ bhavcopy — the v1
 * previous-day view) and the LIVE fold (audit §9.4, Wave 5): one batched {@link QuoteGateway} call
 * for the index + constituents, % change = (ltp − prev-close)/prev-close off the venue quote, and
 * the points scale off the LIVE index level — the intraday read the barometer serves. The live fold
 * falls back to EOD when quotes are unavailable (off-hours, feed down).
 */
@Service
public class EquityIndexContributionService {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final JdbcTemplate jdbc;
  private final StaticIndexWeights weights;
  private final QuoteGateway quoteGateway;

  public EquityIndexContributionService(
      JdbcTemplate jdbc, StaticIndexWeights weights, QuoteGateway quoteGateway) {
    this.jdbc = jdbc;
    this.weights = weights;
    this.quoteGateway = quoteGateway;
  }

  /** One constituent's contribution: weight × % change. {@code points} = contribution × index level /
   * 100 (oipulse's index-point form; null when the index daily close isn't in the candle archive). */
  public record ContribRow(
      int rank,
      String symbol,
      BigDecimal contribution,
      BigDecimal changePct,
      BigDecimal close,
      BigDecimal points) {}

  /** Advances + declines, each ranked by |contribution|, with their summed contributions + points.
   * {@code live} marks an intraday quote-based fold (vs the EOD bhavcopy read). */
  public record IndexContribution(
      String index,
      BigDecimal indexChangePct,
      BigDecimal advanceTotal,
      BigDecimal declineTotal,
      BigDecimal indexLevel,
      BigDecimal advancePoints,
      BigDecimal declinePoints,
      List<ContribRow> advances,
      List<ContribRow> declines,
      LocalDate asOf,
      boolean live) {}

  public IndexContribution contribution(String index) {
    Map<String, BigDecimal> w = requireWeights(index);
    LocalDate asOf = asOf();
    // indexClose null when the index 1d candle isn't archived
    return fold(index, w, latestChange(), asOf, indexClose(index, asOf), false);
  }

  /**
   * The intraday read (audit §9.4): ONE batched quote call for the index + constituents; % change
   * = (ltp − prev-close)/prev-close off each venue quote, points off the LIVE index level. Falls
   * back to the EOD fold when no constituent quote is available (off-hours, feed down).
   */
  public IndexContribution liveContribution(String index) {
    Map<String, BigDecimal> w = requireWeights(index);
    List<InstrumentKey> keys = new ArrayList<>(w.size() + 1);
    for (String symbol : w.keySet()) {
      keys.add(new InstrumentKey("NSE", symbol));
    }
    InstrumentKey indexKey = new InstrumentKey("NSE", index);
    keys.add(indexKey);
    Map<InstrumentKey, QuoteGateway.Quote> quotes = quoteGateway.quotes(keys);

    Map<String, Latest> latest = new HashMap<>();
    for (String symbol : w.keySet()) {
      QuoteGateway.Quote q = quotes.get(new InstrumentKey("NSE", symbol));
      if (q == null || q.lastPrice() == null || q.ohlc() == null || q.ohlc().close() == null
          || q.ohlc().close().signum() <= 0) {
        continue;
      }
      BigDecimal pct =
          q.lastPrice()
              .subtract(q.ohlc().close())
              .multiply(HUNDRED)
              .divide(q.ohlc().close(), 2, RoundingMode.HALF_UP);
      latest.put(symbol, new Latest(pct, q.lastPrice()));
    }
    if (latest.isEmpty()) {
      return contribution(index); // no live quotes — serve the EOD read
    }
    QuoteGateway.Quote iq = quotes.get(indexKey);
    LocalDate today = LocalDate.now(Ist.ZONE);
    BigDecimal indexLevel =
        iq != null && iq.lastPrice() != null ? iq.lastPrice() : indexClose(index, today);
    return fold(index, w, latest, today, indexLevel, true);
  }

  private Map<String, BigDecimal> requireWeights(String index) {
    Map<String, BigDecimal> w = weights.weights(index);
    if (w.isEmpty()) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_RESOURCE, "no seeded weights for index '" + index + "'");
    }
    return w;
  }

  private IndexContribution fold(
      String index,
      Map<String, BigDecimal> w,
      Map<String, Latest> latest,
      LocalDate asOf,
      BigDecimal indexLevel,
      boolean live) {
    List<ContribRow> advances = new ArrayList<>();
    List<ContribRow> declines = new ArrayList<>();
    BigDecimal advTotal = BigDecimal.ZERO;
    BigDecimal decTotal = BigDecimal.ZERO;
    BigDecimal advPts = BigDecimal.ZERO;
    BigDecimal decPts = BigDecimal.ZERO;
    for (Map.Entry<String, BigDecimal> e : w.entrySet()) {
      Latest l = latest.get(e.getKey());
      if (l == null || l.changePct == null) {
        continue;
      }
      BigDecimal contribution =
          e.getValue().multiply(l.changePct).divide(HUNDRED, 4, RoundingMode.HALF_UP);
      BigDecimal points =
          indexLevel == null
              ? null
              : contribution.multiply(indexLevel).divide(HUNDRED, 2, RoundingMode.HALF_UP);
      ContribRow row = new ContribRow(0, e.getKey(), contribution, l.changePct, l.close, points);
      if (contribution.signum() >= 0) {
        advances.add(row);
        advTotal = advTotal.add(contribution);
        if (points != null) {
          advPts = advPts.add(points);
        }
      } else {
        declines.add(row);
        decTotal = decTotal.add(contribution);
        if (points != null) {
          decPts = decPts.add(points);
        }
      }
    }
    if (advances.isEmpty() && declines.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no bhavcopy for index '" + index + "'");
    }
    advances.sort(Comparator.comparing(ContribRow::contribution).reversed()); // biggest pusher first
    declines.sort(Comparator.comparing(ContribRow::contribution)); // biggest drag first
    List<ContribRow> rankedAdv = rank(advances);
    List<ContribRow> rankedDec = rank(declines);
    BigDecimal indexChangePct = advTotal.add(decTotal).setScale(2, RoundingMode.HALF_UP);
    return new IndexContribution(
        index,
        indexChangePct,
        advTotal.setScale(2, RoundingMode.HALF_UP),
        decTotal.setScale(2, RoundingMode.HALF_UP),
        indexLevel,
        indexLevel == null ? null : advPts.setScale(2, RoundingMode.HALF_UP),
        indexLevel == null ? null : decPts.setScale(2, RoundingMode.HALF_UP),
        rankedAdv,
        rankedDec,
        asOf,
        live);
  }

  private static List<ContribRow> rank(List<ContribRow> rows) {
    List<ContribRow> out = new ArrayList<>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      ContribRow r = rows.get(i);
      out.add(new ContribRow(i + 1, r.symbol(), r.contribution(), r.changePct(), r.close(), r.points()));
    }
    return out;
  }

  /**
   * The index's latest 1d close on/before {@code asOf} from the candle archive, or {@code null}.
   *
   * <p>Daily buckets are IST-day-aligned instants (00:00+05:30 = 18:30Z of the PREVIOUS day), and
   * the container session is UTC, so a bare {@code bucket::date} lands one day early and today's
   * accruing bar would slip under an {@code asOf} of yesterday (audit P2 — the lone bare-::date
   * violator; CLAUDE.md bans it). Convert to IST before the date cast, matching every other
   * date-compare in the codebase.
   */
  private BigDecimal indexClose(String index, LocalDate asOf) {
    return jdbc.query(
        "SELECT close FROM candles WHERE interval = '1d' AND tradingsymbol = ? "
            + "AND (bucket AT TIME ZONE 'Asia/Kolkata')::date <= ? ORDER BY bucket DESC LIMIT 1",
        rs -> rs.next() ? rs.getBigDecimal("close") : null,
        index,
        java.sql.Date.valueOf(asOf));
  }

  private record Latest(BigDecimal changePct, BigDecimal close) {}

  /** Latest-session % change + close for every EQ symbol (keyed by symbol). */
  private Map<String, Latest> latestChange() {
    Map<String, Latest> map = new java.util.HashMap<>();
    jdbc.query(
        "WITH ranked AS ("
            + "  SELECT symbol, close_price, prev_close, "
            + "    ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn "
            + "  FROM nse_eod_bhavcopy WHERE series = 'EQ') "
            + "SELECT symbol, close_price, prev_close FROM ranked WHERE rn = 1",
        rs -> {
          String sym = rs.getString("symbol");
          BigDecimal close = rs.getBigDecimal("close_price");
          BigDecimal prev = rs.getBigDecimal("prev_close");
          BigDecimal pct =
              (prev != null && prev.signum() > 0 && close != null)
                  ? close.subtract(prev).multiply(HUNDRED).divide(prev, 2, RoundingMode.HALF_UP)
                  : null;
          map.put(sym, new Latest(pct, close));
        });
    return map;
  }

  private LocalDate asOf() {
    return jdbc.queryForObject(
        "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE series = 'EQ'", LocalDate.class);
  }
}
