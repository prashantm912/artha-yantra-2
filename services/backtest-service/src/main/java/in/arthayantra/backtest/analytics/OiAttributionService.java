package in.arthayantra.backtest.analytics;

import in.arthayantra.backtest.client.MarketDataClient;
import in.arthayantra.backtest.client.MarketDataClient.CdRow;
import in.arthayantra.backtest.replay.TradeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Post-hoc OI-confluence attribution for a finished backtest run: did the scalper's high-confluence
 * entries actually win more than its low-confluence ones? This is the research question the whole
 * OI-gated platform rides on, answered with data instead of intuition.
 *
 * <p>It is deliberately <b>offline</b> — it never touches the replay engine, the {@code Trade}
 * record, or the golden/parity path (so determinism is untouched). For each persisted trade it floors
 * the entry timestamp to the OI interval bucket (IST) and looks up the historical "Connecting Dots"
 * composite {@code trend} (0..4) + net factor vote that was true at that bucket — computed by
 * market-data from the <i>already-captured/backfilled</i> {@code options_chain_snapshots}. Trades are
 * then bucketed by the 5-state trend and the win-rate / avg-P&amp;L per bucket is the attribution.
 *
 * <p>Coverage caveat: OI snapshots only exist where capture/backfill ran, and the Dow factor is
 * NEUTRAL for historical sessions (one of 11 factors degraded) — so the result reports how many
 * trades fell in covered vs uncovered sessions; an uncovered trade lands in the {@code NO_DATA}
 * bucket rather than being silently dropped.
 */
@Service
public class OiAttributionService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
  private static final int PAGE = 100_000; // one run's full trade set; runs are far smaller

  /** 5-state composite trend labels (market-data's {@code ConnectingDotsService} 0..4 ordinals). */
  private static final String[] TREND_LABELS = {
    "Ext.Bearish", "Bearish", "Neutral", "Bullish", "Ext.Bullish"
  };

  private final TradeRepository trades;
  private final MarketDataClient marketData;

  /** Wires the trade repository + the market-data Connecting-Dots client. */
  public OiAttributionService(TradeRepository trades, MarketDataClient marketData) {
    this.trades = trades;
    this.marketData = marketData;
  }

  /**
   * Builds the attribution for {@code runId} at the given OI {@code intervalToken} ({@code 5m}). The
   * index is derived from the traded option symbols unless {@code underlyingOverride} is given.
   */
  public Map<String, Object> attribution(UUID runId, String intervalToken, String underlyingOverride) {
    String interval = intervalToken == null || intervalToken.isBlank() ? "5m" : intervalToken;
    int intervalMin = parseMinutes(interval);

    List<Map<String, Object>> rows = trades.findByRun(runId, PAGE, 0, null, null, null);
    if (rows.isEmpty()) {
      return empty(interval, null, "run has no trades");
    }

    String underlying =
        underlyingOverride != null && !underlyingOverride.isBlank()
            ? underlyingOverride
            : indexOf(String.valueOf(rows.get(0).get("tradingsymbol")));
    if (underlying == null) {
      return empty(interval, null, "could not derive an index from the traded symbols");
    }

    // one Connecting-Dots fetch per distinct session, label-keyed; sessions with no OI stay empty.
    Map<LocalDate, Map<String, CdRow>> bySession = new LinkedHashMap<>();
    java.util.Set<LocalDate> covered = new java.util.LinkedHashSet<>();
    java.util.Set<LocalDate> uncovered = new java.util.LinkedHashSet<>();

    List<Map<String, Object>> perTrade = new ArrayList<>();
    Map<Integer, int[]> tally = new LinkedHashMap<>(); // trend(0..4 or -1 NO_DATA) -> [count, wins]
    Map<Integer, BigDecimal> pnlByTrend = new LinkedHashMap<>();

    for (Map<String, Object> t : rows) {
      OffsetDateTime entry = OffsetDateTime.parse(String.valueOf(t.get("entryTs")));
      LocalDate session = entry.atZoneSameInstant(IST).toLocalDate();
      Map<String, CdRow> matrix =
          bySession.computeIfAbsent(
              session,
              s -> {
                Map<String, CdRow> m = new java.util.HashMap<>();
                for (CdRow r : marketData.connectingDots(underlying, s, interval)) {
                  m.put(r.timeInterval(), r);
                }
                (m.isEmpty() ? uncovered : covered).add(s);
                return m;
              });

      String label = bucketLabel(entry, intervalMin);
      CdRow row = matrix.get(label);
      int trend = row == null ? -1 : row.trend();
      Integer net = row == null ? null : row.net();
      BigDecimal pnl = (BigDecimal) t.get("pnl");
      boolean win = pnl != null && pnl.signum() > 0;

      int[] agg = tally.computeIfAbsent(trend, k -> new int[2]);
      agg[0]++;
      if (win) {
        agg[1]++;
      }
      pnlByTrend.merge(trend, pnl == null ? BigDecimal.ZERO : pnl, BigDecimal::add);

      Map<String, Object> pt = new LinkedHashMap<>();
      pt.put("seq", t.get("seq"));
      pt.put("tradingsymbol", t.get("tradingsymbol"));
      pt.put("entryTs", t.get("entryTs"));
      pt.put("bucket", label);
      pt.put("trend", trend);
      pt.put("trendLabel", trend < 0 ? "NO_DATA" : TREND_LABELS[trend]);
      pt.put("net", net);
      pt.put("pnl", pnl);
      pt.put("win", win);
      perTrade.add(pt);
    }

    List<Map<String, Object>> buckets = new ArrayList<>();
    // present every trend 0..4 (then NO_DATA) so the UI gets a stable, sorted ladder.
    for (int trend = 0; trend <= 4; trend++) {
      buckets.add(bucket(trend, TREND_LABELS[trend], tally.get(trend), pnlByTrend.get(trend)));
    }
    if (tally.containsKey(-1)) {
      buckets.add(bucket(-1, "NO_DATA", tally.get(-1), pnlByTrend.get(-1)));
    }

    int attributed = perTrade.size() - tally.getOrDefault(-1, new int[2])[0];

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("runId", runId.toString());
    out.put("underlying", underlying);
    out.put("interval", interval);
    out.put("tradeCount", perTrade.size());
    out.put("tradesAttributed", attributed);
    out.put("tradesNoData", tally.getOrDefault(-1, new int[2])[0]);
    out.put("sessionsCovered", covered.size());
    out.put("sessionsUncovered", uncovered.size());
    out.put(
        "caveat",
        "Historical OI-confluence join over captured/backfilled snapshots; the Dow factor is "
            + "NEUTRAL for past sessions (1 of 11 factors degraded). NO_DATA = the entry's session "
            + "had no stored OI to score against.");
    out.put("buckets", buckets);
    out.put("trades", perTrade);
    return out;
  }

  private static Map<String, Object> bucket(int trend, String label, int[] agg, BigDecimal pnl) {
    int count = agg == null ? 0 : agg[0];
    int wins = agg == null ? 0 : agg[1];
    BigDecimal total = pnl == null ? BigDecimal.ZERO : pnl;
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("trend", trend);
    b.put("label", label);
    b.put("count", count);
    b.put("wins", wins);
    b.put(
        "winRate",
        count == 0
            ? null
            : new BigDecimal(wins).divide(new BigDecimal(count), 4, RoundingMode.HALF_UP));
    b.put("totalPnl", total.setScale(2, RoundingMode.HALF_UP));
    b.put(
        "avgPnl",
        count == 0 ? null : total.divide(new BigDecimal(count), 2, RoundingMode.HALF_UP));
    return b;
  }

  private static Map<String, Object> empty(String interval, String underlying, String note) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("underlying", underlying);
    out.put("interval", interval);
    out.put("tradeCount", 0);
    out.put("tradesAttributed", 0);
    out.put("tradesNoData", 0);
    out.put("sessionsCovered", 0);
    out.put("sessionsUncovered", 0);
    out.put("caveat", note);
    out.put("buckets", List.of());
    out.put("trades", List.of());
    return out;
  }

  /** Floors an entry instant to the OI interval bucket and renders the {@code HH:mm-HH:mm} label. */
  static String bucketLabel(OffsetDateTime entry, int intervalMin) {
    LocalTime ist = entry.atZoneSameInstant(IST).toLocalTime();
    int minutesOfDay = ist.getHour() * 60 + ist.getMinute();
    int start = (minutesOfDay / intervalMin) * intervalMin;
    int end = start + intervalMin;
    return LocalTime.of(start / 60, start % 60).format(HHMM)
        + "-"
        + LocalTime.of((end / 60) % 24, end % 60).format(HHMM);
  }

  /** Maps a traded option symbol root to the index name market-data keys snapshots/Connecting-Dots by. */
  static String indexOf(String tradingsymbol) {
    if (tradingsymbol == null) {
      return null;
    }
    String s = tradingsymbol.toUpperCase(java.util.Locale.ROOT);
    if (s.startsWith("BANKNIFTY")) {
      return "NIFTY BANK";
    }
    if (s.startsWith("FINNIFTY")) {
      return "NIFTY FIN SERVICE";
    }
    if (s.startsWith("MIDCPNIFTY")) {
      return "NIFTY MID SELECT";
    }
    if (s.startsWith("NIFTY")) {
      return "NIFTY 50";
    }
    if (s.startsWith("SENSEX")) {
      return "SENSEX";
    }
    if (s.startsWith("BANKEX")) {
      return "BANKEX";
    }
    return null;
  }

  private static int parseMinutes(String token) {
    String digits = token.replaceAll("[^0-9]", "");
    return digits.isEmpty() ? 5 : Integer.parseInt(digits);
  }
}
