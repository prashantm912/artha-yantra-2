package in.arthayantra.backtest.analytics;

import in.arthayantra.backtest.client.MarketDataClient;
import in.arthayantra.backtest.client.MarketDataClient;
import in.arthayantra.backtest.client.MarketDataClient.CdRow;
import in.arthayantra.backtest.replay.TradeRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
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

  // Postgres timestamptz text ("2026-06-15 04:56:00+00" — space separator, optional fraction, a
  // 2-digit offset like +00) is NOT ISO-8601, so OffsetDateTime.parse rejects it. The repository
  // hands entry_ts back as that raw driver string, so we parse it tolerantly (ISO first).
  private static final DateTimeFormatter PG_TS =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
          .optionalEnd()
          .appendPattern("X")
          .toFormatter();
  private static final int PAGE = 100_000; // one run's full trade set; runs are far smaller

  /**
   * Composite-trend ordinal → label, matching {@code ConnectingDotsService.composite}: 1 Ext.Bullish,
   * 2 Bullish, 3 Bearish, 4 Ext.Bearish — bullish-first, and there is NO Neutral composite state
   * (the net vote never resolves to a neutral bucket). {@code -1} = the entry had no OI to score.
   */
  static String labelFor(int trend) {
    return switch (trend) {
      case 1 -> "Ext.Bullish";
      case 2 -> "Bullish";
      case 3 -> "Bearish";
      case 4 -> "Ext.Bearish";
      default -> "NO_DATA";
    };
  }

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
  /**
   * One trade, bucketed onto the OI-confluence trend that was live at its entry.
   *
   * <p>⚠️ Decimals are STRINGS on our wire: {@code ArthaJacksonAutoConfiguration} registers
   * {@code ToStringSerializer} for {@code BigDecimal} platform-wide, while bare springdoc infers
   * {@code number}. And {@code types} UNIONS with the inferred type rather than replacing it, so
   * {@code types = {"string","null"}} ALONE would capture {@code ["number","string","null"]} —
   * still advertising an impossible type. Both attributes are required. This is the scalar-type
   * trap the earlier D3 slice was caught on in review; verify by reading the CAPTURED SPEC, never
   * the annotation.
   */
  public record TradeAttribution(
      int seq,
      @Schema(types = {"string", "null"}) String tradingsymbol,
      @Schema(types = {"string", "null"}) String entryTs,
      String bucket,
      int trend,
      String trendLabel,
      /** The confluence net score at entry; null when the session had no stored OI. */
      @Schema(types = {"integer", "null"}) Integer net,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal pnl,
      boolean win) {}

  /** One trend bucket of the ladder. {@code winRate}/{@code avgPnl} are null at count 0. */
  public record TrendBucket(
      int trend,
      String label,
      int count,
      int wins,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal winRate,
      @Schema(type = "string") BigDecimal totalPnl,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal avgPnl) {}

  /**
   * The OI-confluence attribution response. D3 — converted 2026-08-29 on an owner shape decision.
   *
   * <p><b>This ADDS keys to the EMPTY response, which is why it needed a decision rather than a
   * refactor.</b> The empty path emitted 10 keys and the populated path 12; a record emits all
   * twelve always, so an empty response now carries {@code runId: null} and {@code oiDerived:
   * null}. Verified before converting: the only consumer is
   * {@code BacktestResultsPage.tsx}, which reads neither.
   *
   * <p>Component order mirrors the {@code LinkedHashMap} this replaced on BOTH paths, so the wire
   * is unchanged apart from those two added keys.
   */
  public record OiAttribution(
      @Schema(types = {"string", "null"}) String runId,
      @Schema(types = {"string", "null"}) String underlying,
      @Schema(types = {"string", "null"}) String interval,
      int tradeCount,
      int tradesAttributed,
      int tradesNoData,
      int sessionsCovered,
      int sessionsUncovered,
      /** Null on the empty path; true when any joined session came from derived (not captured) OI. */
      @Schema(types = {"boolean", "null"}) Boolean oiDerived,
      String caveat,
      List<TrendBucket> buckets,
      List<TradeAttribution> trades) {}

  public OiAttribution attribution(UUID runId, String intervalToken, String underlyingOverride) {
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
    boolean[] anyDerived = {false}; // any covered session's OI was candle-derived (not captured)

    List<TradeAttribution> perTrade = new ArrayList<>();
    Map<Integer, int[]> tally = new LinkedHashMap<>(); // trend(0..4 or -1 NO_DATA) -> [count, wins]
    Map<Integer, BigDecimal> pnlByTrend = new LinkedHashMap<>();

    for (Map<String, Object> t : rows) {
      OffsetDateTime entry = parseEntry(String.valueOf(t.get("entryTs")));
      LocalDate session = entry.atZoneSameInstant(IST).toLocalDate();
      Map<String, CdRow> matrix =
          bySession.computeIfAbsent(
              session,
              s -> {
                MarketDataClient.CdResponse cd = marketData.connectingDots(underlying, s, interval);
                Map<String, CdRow> m = new java.util.HashMap<>();
                for (CdRow r : cd.rowsOrEmpty()) {
                  m.put(r.timeInterval(), r);
                }
                if (m.isEmpty()) {
                  uncovered.add(s);
                } else {
                  covered.add(s);
                  if (cd.derived()) {
                    anyDerived[0] = true;
                  }
                }
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

      perTrade.add(
          new TradeAttribution(
              ((Number) t.get("seq")).intValue(),
              (String) t.get("tradingsymbol"),
              (String) t.get("entryTs"),
              label,
              trend,
              labelFor(trend),
              net,
              pnl,
              win));
    }

    List<TrendBucket> buckets = new ArrayList<>();
    // bullish-first ladder (1 Ext.Bullish … 4 Ext.Bearish), then NO_DATA — a stable, sorted display.
    for (int trend = 1; trend <= 4; trend++) {
      buckets.add(bucket(trend, labelFor(trend), tally.get(trend), pnlByTrend.get(trend)));
    }
    if (tally.containsKey(-1)) {
      buckets.add(bucket(-1, "NO_DATA", tally.get(-1), pnlByTrend.get(-1)));
    }

    int attributed = perTrade.size() - tally.getOrDefault(-1, new int[2])[0];

    return new OiAttribution(
        runId.toString(),
        underlying,
        interval,
        perTrade.size(),
        attributed,
        tally.getOrDefault(-1, new int[2])[0],
        covered.size(),
        uncovered.size(),
        anyDerived[0],
        "Historical OI-confluence join over captured/backfilled snapshots; the Dow factor is "
            + "NEUTRAL for past sessions (1 of 11 factors degraded). NO_DATA = the entry's session "
            + "had no stored OI to score against.",
        buckets,
        perTrade);
  }

  private static TrendBucket bucket(int trend, String label, int[] agg, BigDecimal pnl) {
    int count = agg == null ? 0 : agg[0];
    int wins = agg == null ? 0 : agg[1];
    BigDecimal total = pnl == null ? BigDecimal.ZERO : pnl;
    return new TrendBucket(
        trend,
        label,
        count,
        wins,
        count == 0
            ? null
            : new BigDecimal(wins).divide(new BigDecimal(count), 4, RoundingMode.HALF_UP),
        total.setScale(2, RoundingMode.HALF_UP),
        count == 0 ? null : total.divide(new BigDecimal(count), 2, RoundingMode.HALF_UP));
  }

  /** runId and oiDerived are NULL here: the empty path never resolved either. */
  private static OiAttribution empty(String interval, String underlying, String note) {
    return new OiAttribution(
        null, underlying, interval, 0, 0, 0, 0, 0, null, note, List.of(), List.of());
  }

  /** Parses an entry timestamp tolerant of both ISO-8601 and Postgres timestamptz text. */
  static OffsetDateTime parseEntry(String raw) {
    try {
      return OffsetDateTime.parse(raw);
    } catch (java.time.format.DateTimeParseException e) {
      return OffsetDateTime.parse(raw, PG_TS);
    }
  }

  /** Floors an entry instant to the OI interval bucket and renders the {@code HH:mm-HH:mm} label. */
  public static String bucketLabel(OffsetDateTime entry, int intervalMin) {
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
