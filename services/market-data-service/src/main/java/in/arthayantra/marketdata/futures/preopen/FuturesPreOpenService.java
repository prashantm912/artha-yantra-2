package in.arthayantra.marketdata.futures.preopen;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.futures.analytics.FuturesSnapshotReader;
import in.arthayantra.marketdata.futures.analytics.FuturesSnapshotReader.EodRow;
import in.arthayantra.marketdata.futures.preopen.FuturesPreOpenScan.FuturesPreOpen;
import in.arthayantra.marketdata.futures.preopen.FuturesPreOpenScan.PreOpenRow;
import in.arthayantra.marketdata.futures.screener.UpstoxFnoDailyReader;
import in.arthayantra.marketdata.upstox.PreOpenIndex;
import in.arthayantra.marketdata.upstox.UpstoxMarketStatusClient;
import in.arthayantra.marketdata.upstox.UpstoxMarketStatusClient.Quote;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Futures Pre-Open Market scan (oipulse §futures/pre-open-market) — the 09:00–09:08 pre-market session
 * read: which F&O stock futures and indices are advancing / declining at pre-open vs the previous close,
 * and whether they've broken the previous day's high/low. Live snapshot, no socket.
 *
 * <p>Two Upstox reads + the captured snapshots (v1 = the same captured NIFTY-Bank radar the §3.3
 * Market-Movers screener uses; widening to the full NIFTY-50 is the same v2 step — an on-demand Upstox
 * daily prev-H/L instead of the captured EOD):
 *
 * <ul>
 *   <li>{@link UpstoxMarketStatusClient#preOpen()} → the NSE session phase + the four index rows.
 *   <li>{@link UpstoxFnoDailyReader#frontFutureKey} resolves each radar stock's front-future Upstox key;
 *       ONE batched {@link UpstoxMarketStatusClient#quotes} call fetches their pre-open price + net
 *       change.
 *   <li>{@link FuturesSnapshotReader#eod} supplies the previous session's high/low for the Prev-Day-Break
 *       badge — already captured, so no extra Upstox call.
 * </ul>
 *
 * <p>The Upstox status client is an OPTIONAL bean (live profile + analytics on), so it rides an {@link
 * ObjectProvider}: when absent the scan is the empty/closed shape (phase {@code UNKNOWN}, no rows) rather
 * than a 503. Like the index pre-open, nothing here throws — a dead Upstox degrades to price-less rows.
 */
@Service
public class FuturesPreOpenService {

  /** How far back to look for the previous session's captured EOD high/low (calendar days). */
  private static final int PREV_EOD_LOOKBACK_DAYS = 10;

  private final ObjectProvider<UpstoxMarketStatusClient> statusClient;
  private final UpstoxFnoDailyReader fnoDailyReader;
  private final FuturesSnapshotReader snapshotReader;
  private final List<String> radar;
  private final Clock clock;

  public FuturesPreOpenService(
      ObjectProvider<UpstoxMarketStatusClient> statusClient,
      UpstoxFnoDailyReader fnoDailyReader,
      FuturesSnapshotReader snapshotReader,
      @Value(
              "${artha.futures.bank-stocks:HDFCBANK,ICICIBANK,SBIN,AXISBANK,KOTAKBANK,INDUSINDBK,"
                  + "BANKBARODA,PNB,AUBANK,FEDERALBNK,IDFCFIRSTB,CANBK,BANDHANBNK,BANKINDIA,"
                  + "RBLBANK,UNIONBANK,YESBANK}")
          List<String> radar,
      Clock clock) {
    this.statusClient = statusClient;
    this.fnoDailyReader = fnoDailyReader;
    this.snapshotReader = snapshotReader;
    this.radar = radar;
    this.clock = clock;
  }

  /** The whole scan; the empty/closed shape when Upstox analytics is not wired. NEVER throws. */
  public FuturesPreOpen scan() {
    OffsetDateTime asOf = OffsetDateTime.now(clock).withOffsetSameInstant(Ist.OFFSET);
    UpstoxMarketStatusClient client = statusClient.getIfAvailable();
    if (client == null) {
      return new FuturesPreOpen("UNKNOWN", false, asOf, 0, 0, 0, List.of(), List.of());
    }

    UpstoxMarketStatusClient.PreOpen index = client.preOpen();
    List<PreOpenRow> indices = new ArrayList<>(index.indices().size());
    for (PreOpenIndex ix : index.indices()) {
      // The index quote already derives prevClose/changePct; carry them through. No prev-day-break for
      // indices in v1 (no captured index EOD) -> null badge.
      indices.add(
          new PreOpenRow(ix.name(), ix.ltp(), ix.prevClose(), ix.netChange(), ix.changePct(), null));
    }

    List<PreOpenRow> stocks = stockRows(client);
    int[] c = FuturesPreOpenScan.counts(stocks);
    return new FuturesPreOpen(
        index.phase(), index.preOpen(), asOf, c[0], c[1], c[2], stocks, indices);
  }

  /** The radar's stock-future pre-open rows — one batched quote call + the captured prev-day high/low. */
  private List<PreOpenRow> stockRows(UpstoxMarketStatusClient client) {
    // Resolve each radar stock's front-future Upstox key (skip the unresolvable).
    Map<String, String> keyByStock = new LinkedHashMap<>();
    for (String stock : radar) {
      String key = fnoDailyReader.frontFutureKey(stock);
      if (key != null) {
        keyByStock.put(stock, key);
      }
    }
    Map<String, Quote> quotes = client.quotes(List.copyOf(keyByStock.values()));

    LocalDate today = LocalDate.now(clock);
    List<PreOpenRow> rows = new ArrayList<>(keyByStock.size());
    for (Map.Entry<String, String> e : keyByStock.entrySet()) {
      String stock = e.getKey();
      Quote quote = quotes.get(e.getValue());
      BigDecimal preOpenPrice = quote == null ? null : quote.lastPrice();
      BigDecimal netChange = quote == null ? null : quote.netChange();
      BigDecimal[] hl = prevDayHighLow(stock, today);
      rows.add(FuturesPreOpenScan.row(stock, preOpenPrice, netChange, hl[0], hl[1]));
    }
    return rows;
  }

  /**
   * The previous session's captured high/low for a stock's front future, as {@code [high, low]} (nulls
   * when no prior EOD is captured). Takes the most recent prior trading day's row, breaking a same-day
   * multi-contract tie toward the most-traded (front) contract.
   */
  private BigDecimal[] prevDayHighLow(String stock, LocalDate today) {
    List<EodRow> eod =
        snapshotReader.eod(stock, today.minusDays(PREV_EOD_LOOKBACK_DAYS), today.minusDays(1));
    EodRow latest =
        eod.stream()
            .max(
                Comparator.comparing(EodRow::tradeDate)
                    .thenComparingLong(EodRow::volume))
            .orElse(null);
    return latest == null
        ? new BigDecimal[] {null, null}
        : new BigDecimal[] {latest.high(), latest.low()};
  }
}
