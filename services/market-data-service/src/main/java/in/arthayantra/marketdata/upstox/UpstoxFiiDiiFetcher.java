package in.arthayantra.marketdata.upstox;

import in.arthayantra.marketdata.nse.FiiDiiFetcher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upstox Market-Information FII/DII cash source (ADR-0002, U2). When {@code
 * artha.marketdata.source.fiidii=upstox} this {@link FiiDiiFetcher} is the {@code @Primary} bean, so
 * the existing {@code NseEodScheduler} persists Upstox-sourced cash flows into the same {@code
 * nse_eod_fii_dii} table — the read side ({@code FiiDiiController} / {@code NseEodReader}) and the FE
 * are untouched.
 *
 * <p>Upstox is PRIMARY, NSE is the SWAP-OUT FALLBACK: any Upstox failure (transport / HTTP / empty
 * response) transparently delegates to the NSE {@code LiveFiiDiiFetcher}, so an Upstox outage can
 * never starve the page. Cash buy/sell amounts arrive in INR and are converted to the NSE
 * convention of ₹ crore (÷10,000,000, 2 dp); the per-record millisecond timestamp is interpreted in
 * IST to pin the trade date.
 */
public final class UpstoxFiiDiiFetcher implements FiiDiiFetcher {

  private static final Logger log = LoggerFactory.getLogger(UpstoxFiiDiiFetcher.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final String CASH = "NSE_EQ|CASH";
  private static final String DAILY = "1D";
  /** 1 crore = 10,000,000 INR — Upstox reports INR, NSE/the FE expect ₹ crore. */
  private static final BigDecimal CRORE = BigDecimal.valueOf(10_000_000L);

  private final UpstoxAnalyticsClient client;
  private final FiiDiiFetcher fallback;

  /**
   * @param client the Upstox Market-Information wire client
   * @param fallback the NSE {@code LiveFiiDiiFetcher} used when Upstox is unavailable
   */
  public UpstoxFiiDiiFetcher(UpstoxAnalyticsClient client, FiiDiiFetcher fallback) {
    this.client = client;
    this.fallback = fallback;
  }

  @Override
  public List<FiiDiiRow> fetchLatest() {
    try {
      List<FiiDiiRow> rows = new ArrayList<>();
      rows.addAll(map("fii", "FII/FPI"));
      rows.addAll(map("dii", "DII"));
      if (rows.isEmpty()) {
        log.warn("Upstox FII/DII returned no rows — falling back to NSE");
        return fallback.fetchLatest();
      }
      log.info("Upstox FII/DII fetched {} cash rows (source=upstox)", rows.size());
      return rows;
    } catch (RuntimeException upstoxFailed) {
      log.warn("Upstox FII/DII fetch failed — falling back to NSE: {}", upstoxFailed.getMessage());
      return fallback.fetchLatest();
    }
  }

  private List<FiiDiiRow> map(String endpoint, String category) {
    List<FiiDiiRow> rows = new ArrayList<>();
    for (UpstoxMarketActivity.Activity a : client.marketActivity(endpoint, CASH, DAILY)) {
      BigDecimal buy = toCrore(a.buyAmount());
      BigDecimal sell = toCrore(a.sellAmount());
      rows.add(
          new FiiDiiRow(
              Instant.ofEpochMilli(a.timeStamp()).atZone(IST).toLocalDate(),
              category,
              buy,
              sell,
              buy.subtract(sell)));
    }
    return rows;
  }

  private static BigDecimal toCrore(BigDecimal inr) {
    return inr == null
        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
        : inr.divide(CRORE, 2, RoundingMode.HALF_UP);
  }
}
