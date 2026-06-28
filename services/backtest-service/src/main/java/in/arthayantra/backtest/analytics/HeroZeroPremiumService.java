package in.arthayantra.backtest.analytics;

import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.TradeRepository;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Post-hoc, read-only Hero-Zero per-strike PREMIUM diagnostics for a finished backtest run — the
 * {@code strike-premium-selection.md} §3.7 hero-zero rows (S21b side-by-premium, S24d per-strike
 * &gt;50% premium %-change confirmation, Bearish-7 "don't take a PE when calls trade at a discount"),
 * dispositioned as <b>backtest-only read-only diagnostics on the chosen leg</b> (Open Point #3) — NOT
 * live entry gates. The live side decision stays the VWAP+extreme proxy; this surface only lets the
 * owner <i>value-verify</i> that the chosen leg's premium actually behaved like a hero-zero break.
 *
 * <p>Like {@link OiAttributionService} it is deliberately <b>offline</b> — it never touches the replay
 * engine, the {@code Trade} record, or the golden/parity path. For each persisted trade it re-reads the
 * chosen leg's own backfilled 1-minute premium series ({@code marketdata.candles}, the same source the
 * premium replay fills against) over the hold window and computes:
 *
 * <ul>
 *   <li><b>Excursion (hero / zero):</b> entry → peak (MFE) → exit, the peak/exit premium multiples,
 *       and whether the 50%-premium stop level was touched. {@code HERO} = peak ≥ 2× entry,
 *       {@code ZERO} = exit ≤ ½ entry, else {@code FLAT}.</li>
 *   <li><b>S24d per-strike confirmation:</b> the bought leg's premium %-change over the
 *       {@value #CONFIRM_WINDOW_MIN}-minute window leading into entry — the "strong move ran through
 *       THIS strike" residual the index-spurt gate does not cover. {@code confirmed} = ≥
 *       {@value #CONFIRM_THRESHOLD_PCT}%.</li>
 *   <li><b>S21b / Bearish-7 skew:</b> the mirror (same strike, opposite type) leg's entry premium →
 *       the per-side premium skew, a "chasing the richer side" flag, and the PE-when-calls-at-a-discount
 *       caution. Mirror reads degrade to null when {@code expired_contracts} has no paired row.</li>
 * </ul>
 *
 * <p><b>Caveat:</b> on derived history the OI/Dow factors are muted, so judge a hero-zero leg on real
 * captured premium, not a weak historical backtest — this surface is a value-verify lens, not a verdict.
 */
@Service
public class HeroZeroPremiumService {

  private static final int PAGE = 100_000; // one run's full trade set; runs are far smaller
  private static final int LOOKBACK_MIN = 30; // premium history pulled before entry (for the confirm read)
  private static final int CONFIRM_WINDOW_MIN = 15; // the S24d %-change window leading into entry
  private static final BigDecimal CONFIRM_THRESHOLD_PCT = new BigDecimal("50"); // S24d ≥50% per-strike move
  private static final BigDecimal HERO_MULT = new BigDecimal("2.0"); // peak ≥ 2× entry ⇒ HERO
  private static final BigDecimal ZERO_MULT = new BigDecimal("0.5"); // exit ≤ ½ entry ⇒ ZERO
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private final TradeRepository trades;
  private final CandleReader candles;
  private final JdbcTemplate jdbc;

  /** Wires the trade repository, the premium-candle reader, and the expired-contract registry read. */
  public HeroZeroPremiumService(TradeRepository trades, CandleReader candles, JdbcTemplate jdbc) {
    this.trades = trades;
    this.candles = candles;
    this.jdbc = jdbc;
  }

  /** Builds the per-strike premium diagnostics for {@code runId}. */
  public Map<String, Object> diagnostics(UUID runId) {
    List<Map<String, Object>> rows = trades.findByRun(runId, PAGE, 0, null, null, null);
    if (rows.isEmpty()) {
      return empty(runId, "run has no trades");
    }

    List<Map<String, Object>> perTrade = new ArrayList<>();
    int priced = 0;
    int heroes = 0;
    int zeros = 0;
    int confirmed = 0;
    int slTouched = 0;
    int chaseWarnings = 0;
    int bearishCautions = 0;
    BigDecimal peakMultSum = BigDecimal.ZERO;
    BigDecimal changePctSum = BigDecimal.ZERO;

    for (Map<String, Object> t : rows) {
      String exchange = (String) t.get("exchange");
      String tradingsymbol = (String) t.get("tradingsymbol");
      OffsetDateTime entryTs = parseTs((String) t.get("entryTs"));
      OffsetDateTime exitTs = parseTs((String) t.get("exitTs"));
      BigDecimal entryPrice = (BigDecimal) t.get("entryPrice");
      BigDecimal exitPrice = (BigDecimal) t.get("exitPrice");
      BigDecimal stopLoss = (BigDecimal) t.get("stopLoss");

      Map<String, Object> pt = new LinkedHashMap<>();
      pt.put("seq", t.get("seq"));
      pt.put("tradingsymbol", tradingsymbol);
      pt.put("entryTs", t.get("entryTs"));
      pt.put("exitTs", t.get("exitTs"));
      pt.put("entryPremium", entryPrice);
      pt.put("exitPremium", exitPrice);
      pt.put("pnl", t.get("pnl"));

      if (exchange == null || tradingsymbol == null || entryTs == null || exitTs == null) {
        pt.put("priced", false);
        perTrade.add(pt);
        continue;
      }

      NavigableMap<OffsetDateTime, BigDecimal> series =
          premiumSeries(exchange, tradingsymbol, entryTs.minusMinutes(LOOKBACK_MIN), exitTs.plusMinutes(1));
      BigDecimal entryPrem = entryPrice != null ? entryPrice : premiumAt(series, entryTs);
      // The diagnostic IS the intra-hold premium path — with no backfilled bars there is nothing to
      // diagnose (the trade row's entry/exit alone are already on the run), so mark it unpriced.
      if (series.isEmpty() || entryPrem == null || entryPrem.signum() <= 0) {
        pt.put("priced", false);
        perTrade.add(pt);
        continue;
      }
      priced++;
      pt.put("priced", true);

      // --- excursion (hero / zero) over the hold ---
      BigDecimal peak = entryPrem;
      OffsetDateTime peakTs = entryTs;
      BigDecimal trough = entryPrem;
      for (var e : series.subMap(entryTs, true, exitTs, true).entrySet()) {
        if (e.getValue().compareTo(peak) > 0) {
          peak = e.getValue();
          peakTs = e.getKey();
        }
        if (e.getValue().compareTo(trough) < 0) {
          trough = e.getValue();
        }
      }
      BigDecimal exitPrem = exitPrice != null ? exitPrice : premiumAt(series, exitTs);
      BigDecimal peakMult = ratio(peak, entryPrem);
      BigDecimal exitMult = exitPrem == null ? null : ratio(exitPrem, entryPrem);
      String klass =
          peakMult.compareTo(HERO_MULT) >= 0
              ? "HERO"
              : (exitMult != null && exitMult.compareTo(ZERO_MULT) <= 0 ? "ZERO" : "FLAT");
      boolean sl = stopLoss != null && trough.compareTo(stopLoss) <= 0;
      if ("HERO".equals(klass)) {
        heroes++;
      } else if ("ZERO".equals(klass)) {
        zeros++;
      }
      if (sl) {
        slTouched++;
      }
      peakMultSum = peakMultSum.add(peakMult);
      pt.put("peakPremium", peak.setScale(4, RoundingMode.HALF_UP));
      pt.put("peakTs", peakTs.toString());
      pt.put("troughPremium", trough.setScale(4, RoundingMode.HALF_UP));
      pt.put("peakMultiple", peakMult);
      pt.put("exitMultiple", exitMult);
      pt.put("class", klass);
      pt.put("slTouched", sl);

      // --- S24d per-strike premium %-change confirmation leading into entry ---
      BigDecimal prePrem = premiumAt(series, entryTs.minusMinutes(CONFIRM_WINDOW_MIN));
      BigDecimal changePct =
          prePrem == null || prePrem.signum() <= 0
              ? null
              : entryPrem.subtract(prePrem).divide(prePrem, 6, RoundingMode.HALF_UP).multiply(HUNDRED);
      boolean conf = changePct != null && changePct.compareTo(CONFIRM_THRESHOLD_PCT) >= 0;
      if (conf) {
        confirmed++;
      }
      if (changePct != null) {
        changePctSum = changePctSum.add(changePct);
      }
      pt.put("premiumChangePct", changePct == null ? null : changePct.setScale(2, RoundingMode.HALF_UP));
      pt.put("confirmed", conf);

      // --- S21b / Bearish-7 mirror-side skew (degrades to null without a paired contract row) ---
      ContractMeta meta = meta(exchange, tradingsymbol);
      BigDecimal skewPct = null;
      Boolean richerSide = null;
      Boolean callsAtDiscountCaution = null;
      if (meta != null) {
        pt.put("optionType", meta.type());
        pt.put("strike", meta.strike());
        String mirror = mirrorSymbol(meta, exchange);
        if (mirror != null) {
          BigDecimal mirrorPrem =
              premiumAt(
                  premiumSeries(exchange, mirror, entryTs.minusMinutes(1), entryTs.plusMinutes(1)),
                  entryTs);
          if (mirrorPrem != null && mirrorPrem.signum() > 0) {
            skewPct =
                entryPrem
                    .subtract(mirrorPrem)
                    .divide(mirrorPrem, 6, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
                    .setScale(2, RoundingMode.HALF_UP);
            richerSide = skewPct.signum() > 0; // the traded side is the richer (more expensive) side
            // Bearish-7: on a PE trade, if the CE mirror trades cheaper than the PE (calls "at a
            // discount" → market leaning UP) → caution against the short-biased PE.
            callsAtDiscountCaution = "PE".equals(meta.type()) && skewPct.signum() > 0;
            if (Boolean.TRUE.equals(richerSide)) {
              chaseWarnings++;
            }
            if (Boolean.TRUE.equals(callsAtDiscountCaution)) {
              bearishCautions++;
            }
          }
        }
      }
      pt.put("mirrorSkewPct", skewPct);
      pt.put("chasingRicherSide", richerSide);
      pt.put("callsAtDiscountCaution", callsAtDiscountCaution);

      perTrade.add(pt);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("runId", runId.toString());
    out.put("tradeCount", rows.size());
    out.put("tradesPriced", priced);
    out.put("tradesUnpriced", rows.size() - priced);
    out.put("heroes", heroes);
    out.put("zeros", zeros);
    out.put("flats", priced - heroes - zeros);
    out.put("confirmed", confirmed);
    out.put("confirmedRate", priced == 0 ? null : rate(confirmed, priced));
    out.put("slTouched", slTouched);
    out.put("chaseWarnings", chaseWarnings);
    out.put("bearishCautions", bearishCautions);
    out.put(
        "avgPeakMultiple",
        priced == 0 ? null : peakMultSum.divide(new BigDecimal(priced), 4, RoundingMode.HALF_UP));
    out.put(
        "avgPremiumChangePct",
        confirmedDenom(perTrade) == 0
            ? null
            : changePctSum.divide(new BigDecimal(confirmedDenom(perTrade)), 2, RoundingMode.HALF_UP));
    out.put(
        "caveat",
        "Read-only per-strike premium diagnostics on each trade's chosen leg (S21b/S24d/Bearish-7); "
            + "the live side decision stays the VWAP+extreme proxy, NOT these reads. On derived history "
            + "OI/Dow factors are muted — judge a hero-zero leg on real captured premium, not a weak "
            + "historical backtest.");
    out.put("trades", perTrade);
    return out;
  }

  /** Count of trades that produced a (non-null) premiumChangePct, for the average's denominator. */
  private static int confirmedDenom(List<Map<String, Object>> perTrade) {
    int n = 0;
    for (Map<String, Object> pt : perTrade) {
      if (pt.get("premiumChangePct") != null) {
        n++;
      }
    }
    return n;
  }

  private NavigableMap<OffsetDateTime, BigDecimal> premiumSeries(
      String exchange, String tradingsymbol, OffsetDateTime from, OffsetDateTime to) {
    NavigableMap<OffsetDateTime, BigDecimal> series = new TreeMap<>();
    for (EngineCandle bar : candles.read(exchange, tradingsymbol, "1m", from, to)) {
      series.put(bar.bucketStart(), bar.close());
    }
    return series;
  }

  /** The premium at {@code ts} (the bar at/just-before it, carry-forward); null before the first bar. */
  static BigDecimal premiumAt(NavigableMap<OffsetDateTime, BigDecimal> series, OffsetDateTime ts) {
    var e = series.floorEntry(ts);
    return e == null ? null : e.getValue();
  }

  private static BigDecimal ratio(BigDecimal num, BigDecimal den) {
    return num.divide(den, 4, RoundingMode.HALF_UP);
  }

  private static BigDecimal rate(int n, int d) {
    return new BigDecimal(n).divide(new BigDecimal(d), 4, RoundingMode.HALF_UP);
  }

  /** (underlying_symbol, expiry, strike, instrument_type) for a traded contract; null when unlisted. */
  private ContractMeta meta(String exchange, String tradingsymbol) {
    List<ContractMeta> r =
        jdbc.query(
            "SELECT underlying_symbol, expiry, strike, instrument_type FROM marketdata.expired_contracts "
                + "WHERE exchange = ? AND tradingsymbol = ? LIMIT 1",
            (rs, n) ->
                new ContractMeta(
                    rs.getString("underlying_symbol"),
                    rs.getObject("expiry", LocalDate.class),
                    rs.getBigDecimal("strike"),
                    rs.getString("instrument_type")),
            exchange,
            tradingsymbol);
    return r.isEmpty() ? null : r.get(0);
  }

  /** The opposite-type contract at the same (underlying, expiry, strike, exchange); null when unlisted. */
  private String mirrorSymbol(ContractMeta m, String exchange) {
    if (m.underlying() == null || m.expiry() == null || m.strike() == null || m.type() == null) {
      return null;
    }
    String mirrorType = "CE".equals(m.type()) ? "PE" : "CE";
    List<String> r =
        jdbc.query(
            "SELECT tradingsymbol FROM marketdata.expired_contracts WHERE underlying_symbol = ? "
                + "AND expiry = ? AND strike = ? AND instrument_type = ? AND exchange = ? LIMIT 1",
            (rs, n) -> rs.getString("tradingsymbol"),
            m.underlying(),
            java.sql.Date.valueOf(m.expiry()),
            m.strike(),
            mirrorType,
            exchange);
    return r.isEmpty() ? null : r.get(0);
  }

  /** Parses an entry/exit timestamp tolerant of both ISO-8601 and Postgres timestamptz text. */
  static OffsetDateTime parseTs(String raw) {
    if (raw == null) {
      return null;
    }
    return OiAttributionService.parseEntry(raw);
  }

  private static Map<String, Object> empty(UUID runId, String note) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("runId", runId.toString());
    out.put("tradeCount", 0);
    out.put("tradesPriced", 0);
    out.put("caveat", note);
    out.put("trades", List.of());
    return out;
  }

  /** A traded contract's registry coordinates, for mirror-leg resolution. */
  record ContractMeta(String underlying, LocalDate expiry, BigDecimal strike, String type) {}
}
