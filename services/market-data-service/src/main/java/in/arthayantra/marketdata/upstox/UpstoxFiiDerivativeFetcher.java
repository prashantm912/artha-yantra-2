package in.arthayantra.marketdata.upstox;

import in.arthayantra.marketdata.feeds.FiiDerivativeFetcher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upstox Market-Information FII-derivative source (ADR-0002 U6). FII net buy/sell across the four
 * F&amp;O segments (oipulse "FII Derivative Stats") — there is NO NSE EOD equivalent, so this is the
 * sole source (no fallback). Fetched in a single {@code /v2/market/fii} call requesting all four
 * {@code NSE_FO|*} segments; the amounts are ALREADY in ₹ crore (kept as-is) and the trade date is
 * pinned from the millisecond timestamp in IST. A fetch failure propagates to the scheduler, which
 * logs and retries next schedule.
 */
public final class UpstoxFiiDerivativeFetcher implements FiiDerivativeFetcher {

  private static final Logger log = LoggerFactory.getLogger(UpstoxFiiDerivativeFetcher.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final String DAILY = "1D";

  /** Upstox segment key → our canonical segment label (stable iteration order). */
  private static final String[][] SEGMENTS = {
    {"NSE_FO|INDEX_FUTURES", "INDEX_FUTURES"},
    {"NSE_FO|INDEX_OPTIONS", "INDEX_OPTIONS"},
    {"NSE_FO|STOCK_FUTURES", "STOCK_FUTURES"},
    {"NSE_FO|STOCK_OPTIONS", "STOCK_OPTIONS"}
  };

  private final UpstoxAnalyticsClient client;

  /**
   * @param client the Upstox Market-Information wire client
   */
  public UpstoxFiiDerivativeFetcher(UpstoxAnalyticsClient client) {
    this.client = client;
  }

  @Override
  public List<FiiDerivativeRow> fetchLatest() {
    List<String> dataTypes = new ArrayList<>();
    for (String[] seg : SEGMENTS) {
      dataTypes.add(seg[0]);
    }
    Map<String, List<UpstoxMarketActivity.Activity>> data =
        client.marketActivitySegments("fii", dataTypes, DAILY);

    List<FiiDerivativeRow> rows = new ArrayList<>();
    for (String[] seg : SEGMENTS) {
      for (UpstoxMarketActivity.Activity a : data.getOrDefault(seg[0], List.of())) {
        BigDecimal buy = toCrore(a.buyAmount());
        BigDecimal sell = toCrore(a.sellAmount());
        rows.add(
            new FiiDerivativeRow(
                Instant.ofEpochMilli(a.timeStamp()).atZone(IST).toLocalDate(),
                seg[1],
                buy,
                sell,
                buy.subtract(sell)));
      }
    }
    log.info("Upstox FII derivative-stats fetched {} rows", rows.size());
    return rows;
  }

  /**
   * Upstox reports the F&amp;O segment buy/sell amounts ALREADY in ₹ crore (the docs mislabel them
   * "INR"; verified against oipulse "FII Derivative Stats" — every net matches cell-for-cell, e.g.
   * 2026-06-22 INDEX_OPTIONS net 3340.90). The {@code buy_amount / buy_contracts} per-lot figure is
   * the contract NOTIONAL (≈ index × lot ≈ ₹15.8 lakh), not premium — confirming crore, not lakh.
   * So keep the value and only normalise scale; NO unit division.
   */
  private static BigDecimal toCrore(BigDecimal crore) {
    return crore == null
        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
        : crore.setScale(2, RoundingMode.HALF_UP);
  }
}
