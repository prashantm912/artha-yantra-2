package in.arthayantra.backtest.analytics;

import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.TradeRepository;
import in.arthayantra.strategyengine.series.EngineCandle;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
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
 *   <li><b>S24d-PROXY per-strike %-change:</b> the bought leg's own PREMIUM %-change over the
 *       {@value #CONFIRM_WINDOW_MIN}-minute window leading into entry; {@code confirmed} = ≥ a flat
 *       {@value #CONFIRM_THRESHOLD_PCT}%. This is a <i>premium proxy</i> — the disposition's S24d is
 *       on the underlying strike's PRICE+OI with CE/PE-asymmetric thresholds (CE ≥50% / PE ~70-78%),
 *       whose OI half is FU2's ΔOI gate, not here. Named/read as a value-verify lens, not that gate.</li>
 *   <li><b>S21b / Bearish-7 skew (raw data):</b> the mirror (same strike, opposite type) leg's entry
 *       premium + the signed per-side skew + a factual "the traded side is the richer leg" flag — the
 *       side-by-premium (S21b) and calls-at-a-discount (Bearish-7) reads SURFACED for the owner to
 *       judge, NOT automated verdicts. Degrades to null when {@code expired_contracts} has no pair.</li>
 * </ul>
 *
 * <p><b>Caveat:</b> on derived history the OI/Dow factors are muted, so judge a hero-zero leg on real
 * captured premium, not a weak historical backtest — this surface is a value-verify lens, not a verdict.
 */
@Service
public class HeroZeroPremiumService {

  private static final int PAGE = 100_000; // one run's full trade set; runs are far smaller
  private static final int LOOKBACK_MIN = 30; // premium history pulled before entry (for the confirm read)
  private static final int CONFIRM_WINDOW_MIN = 15; // the %-change window leading into entry
  private static final int CONFIRM_GRACE_MIN = 5; // a sparse baseline staler than window+grace is rejected
  private static final BigDecimal CONFIRM_THRESHOLD_PCT = new BigDecimal("50"); // a flat 50% premium-move proxy
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
  public HeroZeroPremium diagnostics(UUID runId) {
    List<Map<String, Object>> rows = trades.findByRun(runId, PAGE, 0, null, null, null);
    if (rows.isEmpty()) {
      return empty(runId, "run has no trades");
    }

    List<HeroZeroTrade> perTrade = new ArrayList<>();
    int priced = 0;
    int heroes = 0;
    int zeros = 0;
    int confirmed = 0;
    int slTouched = 0;
    int richerSideCount = 0;
    int changePctCount = 0; // priced trades that got a valid (in-window) confirm baseline
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

      // The seven fields every row carries, priced or not. Held as locals now rather than being
      // accumulated into a map, because the record is constructed at whichever exit is reached.
      int seq = ((Number) t.get("seq")).intValue();
      String entryTsText = (String) t.get("entryTs");
      String exitTsText = (String) t.get("exitTs");
      BigDecimal pnlValue = (BigDecimal) t.get("pnl");

      if (exchange == null || tradingsymbol == null || entryTs == null || exitTs == null) {
        perTrade.add(
            HeroZeroTrade.unpriced(
                seq, tradingsymbol, entryTsText, exitTsText, entryPrice, exitPrice, pnlValue));
        continue;
      }

      NavigableMap<OffsetDateTime, BigDecimal> series =
          premiumSeries(exchange, tradingsymbol, entryTs.minusMinutes(LOOKBACK_MIN), exitTs.plusMinutes(1));
      BigDecimal entryPrem = entryPrice != null ? entryPrice : premiumAt(series, entryTs);
      // The diagnostic IS the intra-hold premium path — with no backfilled bars there is nothing to
      // diagnose (the trade row's entry/exit alone are already on the run), so mark it unpriced.
      if (series.isEmpty() || entryPrem == null || entryPrem.signum() <= 0) {
        perTrade.add(
            HeroZeroTrade.unpriced(
                seq, tradingsymbol, entryTsText, exitTsText, entryPrice, exitPrice, pnlValue));
        continue;
      }
      priced++;

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
      BigDecimal peakPremium = peak.setScale(4, RoundingMode.HALF_UP);
      String peakTsText = peakTs.toString();
      BigDecimal troughPremium = trough.setScale(4, RoundingMode.HALF_UP);

      // --- S24d-PROXY per-strike premium %-change leading into entry ---
      // The chosen leg's own premium %-change over the ~CONFIRM_WINDOW before entry. NOTE this is a
      // PREMIUM proxy for the disposition's S24d strong-move confirmation, which is actually on the
      // underlying strike's PRICE+OI with CE/PE-asymmetric thresholds (CE ≥50% / PE ~70-78%) — the OI
      // half lives in FU2's ΔOI gate, not here. On a sparse series the floor lookup could land on a bar
      // far older than the window, so reject a baseline staler than window+grace (→ null, not confirmed).
      var pre = series.floorEntry(entryTs.minusMinutes(CONFIRM_WINDOW_MIN));
      BigDecimal prePrem =
          pre == null
                  || pre.getValue().signum() <= 0
                  || pre.getKey().isBefore(entryTs.minusMinutes(CONFIRM_WINDOW_MIN + CONFIRM_GRACE_MIN))
              ? null
              : pre.getValue();
      BigDecimal changePct =
          prePrem == null
              ? null
              : entryPrem.subtract(prePrem).divide(prePrem, 6, RoundingMode.HALF_UP).multiply(HUNDRED);
      boolean conf = changePct != null && changePct.compareTo(CONFIRM_THRESHOLD_PCT) >= 0;
      if (conf) {
        confirmed++;
      }
      if (changePct != null) {
        changePctSum = changePctSum.add(changePct);
        changePctCount++;
      }
      BigDecimal changePctScaled = changePct == null ? null : changePct.setScale(2, RoundingMode.HALF_UP);

      // --- S21b / Bearish-7 mirror-side premium (raw DATA for value-verify, NOT a derived verdict) ---
      // The disposition wants the side-by-premium (S21b) + "calls at a discount" (Bearish-7) reads
      // SURFACED on the chosen leg for the owner to judge — not automated. We report the mirror (same
      // strike, opposite type) entry premium + the signed skew; the owner reads the S21b/Bearish-7
      // caution off them. (No derived caution boolean: "calls at a discount to FAIR premium" is not the
      // same as "CE cheaper than PE at this strike" — put-call parity makes the same-strike skew
      // strike-position-dependent, so a boolean here would mislead.) Degrades to null without a pair.
      ContractMeta meta = meta(exchange, tradingsymbol);
      BigDecimal mirrorPrem = null;
      String optionType = null;
      BigDecimal strike = null;
      BigDecimal skewPct = null;
      Boolean tradedSideRicher = null;
      if (meta != null) {
        optionType = meta.type();
        strike = meta.strike();
        String mirror = mirrorSymbol(meta, exchange);
        if (mirror != null) {
          mirrorPrem =
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
            tradedSideRicher = skewPct.signum() > 0; // factual: the traded leg is the more expensive one
            if (Boolean.TRUE.equals(tradedSideRicher)) {
              richerSideCount++;
            }
          }
        }
      }
      BigDecimal mirrorPremiumScaled = mirrorPrem == null ? null : mirrorPrem.setScale(4, RoundingMode.HALF_UP);

      perTrade.add(
          new HeroZeroTrade(
              seq,
              tradingsymbol,
              entryTsText,
              exitTsText,
              entryPrice,
              exitPrice,
              pnlValue,
              true,
              peakPremium,
              peakTsText,
              troughPremium,
              peakMult,
              exitMult,
              klass,
              sl,
              changePctScaled,
              conf,
              optionType,
              strike,
              mirrorPremiumScaled,
              skewPct,
              tradedSideRicher));
    }

    return new HeroZeroPremium(
        runId.toString(),
        rows.size(),
        priced,
        rows.size() - priced,
        heroes,
        zeros,
        priced - heroes - zeros,
        confirmed,
        priced == 0 ? null : rate(confirmed, priced),
        changePctCount, // the avg's denominator (priced trades may lack a baseline)
        slTouched,
        richerSideCount,
        priced == 0 ? null : peakMultSum.divide(new BigDecimal(priced), 4, RoundingMode.HALF_UP),
        changePctCount == 0
            ? null
            : changePctSum.divide(new BigDecimal(changePctCount), 2, RoundingMode.HALF_UP),
        "Read-only per-strike premium diagnostics on each trade's chosen leg (S21b/S24d/Bearish-7); "
            + "the live side decision stays the VWAP+extreme proxy, NOT these reads. 'confirmed' is a "
            + "flat-50% PREMIUM proxy for S24d (the disposition's price+OI confirmation lives elsewhere); "
            + "'class' picks HERO (peak ≥ 2x) before ZERO, so a spike-then-collapse round-trip reads HERO "
            + "(see peakMultiple vs exitMultiple). On derived history OI/Dow factors are muted — judge a "
            + "hero-zero leg on real captured premium, not a weak historical backtest.",
        perTrade);
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

  /**
   * One trade's per-strike premium diagnostics. D3 — converted 2026-08-29 on an owner shape
   * decision.
   *
   * <p>⚠️ <b>THE ITEM IS WHERE THE REAL WIRE CHANGE IS, not the envelope.</b> The map this
   * replaces was built across THREE conditional tiers: an unpriced trade emitted 8 keys, a priced
   * one ~22, and the mirror-leg block only ran when the contract resolved. A record emits all 22
   * always, so an UNPRICED trade row now carries ~14 explicit nulls it did not before. That is a
   * bigger change than the 5-vs-16 envelope the ratchet documented, and it is why this handler sat
   * frozen as a "deliberate stop". Judged acceptable because the endpoint has ZERO frontend
   * consumers (`grep -rl "hero-zero" frontend-react/src` is empty).
   *
   * <p>⚠️ <b>{@code klass} carries the JSON key {@code "class"} via {@link JsonProperty}</b>,
   * because {@code class} is a Java reserved word and a record component cannot be named it. The
   * annotation is the ONLY thing keeping that key on the wire — remove it and the key silently
   * becomes {@code klass}. {@code HeroZeroWireShapeTest} pins it for exactly that reason.
   *
   * <p>⚠️ Decimals are STRINGS on our wire ({@code ToStringSerializer} is registered
   * platform-wide) while bare springdoc infers {@code number}, and {@code types} UNIONS with the
   * inferred type instead of replacing it — so each carries BOTH {@code type} and {@code types}.
   */
  public record HeroZeroTrade(
      int seq,
      @Schema(types = {"string", "null"}) String tradingsymbol,
      @Schema(types = {"string", "null"}) String entryTs,
      @Schema(types = {"string", "null"}) String exitTs,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal entryPremium,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal exitPremium,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal pnl,
      boolean priced,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal peakPremium,
      @Schema(types = {"string", "null"}) String peakTs,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal troughPremium,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal peakMultiple,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal exitMultiple,
      @JsonProperty("class") @Schema(types = {"string", "null"}) String klass,
      @Schema(types = {"boolean", "null"}) Boolean slTouched,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal premiumChangePct,
      @Schema(types = {"boolean", "null"}) Boolean confirmed,
      @Schema(types = {"string", "null"}) String optionType,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal strike,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal mirrorPremium,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal mirrorSkewPct,
      @Schema(types = {"boolean", "null"}) Boolean tradedSideRicher) {

    /** The unpriced exit: the first seven fields are known, everything downstream is not. */
    static HeroZeroTrade unpriced(
        int seq, String tradingsymbol, String entryTs, String exitTs,
        BigDecimal entryPremium, BigDecimal exitPremium, BigDecimal pnl) {
      return new HeroZeroTrade(
          seq, tradingsymbol, entryTs, exitTs, entryPremium, exitPremium, pnl, false,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
  }

  /**
   * The hero-zero premium response. Empty path emitted 5 keys, populated 16; a record emits all
   * sixteen always, so an empty response now carries eleven explicit nulls.
   */
  public record HeroZeroPremium(
      @Schema(types = {"string", "null"}) String runId,
      int tradeCount,
      int tradesPriced,
      @Schema(types = {"integer", "null"}) Integer tradesUnpriced,
      @Schema(types = {"integer", "null"}) Integer heroes,
      @Schema(types = {"integer", "null"}) Integer zeros,
      @Schema(types = {"integer", "null"}) Integer flats,
      @Schema(types = {"integer", "null"}) Integer confirmed,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal confirmedRate,
      @Schema(types = {"integer", "null"}) Integer premiumChangePctCount,
      @Schema(types = {"integer", "null"}) Integer slTouched,
      @Schema(types = {"integer", "null"}) Integer tradedSideRicherCount,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal avgPeakMultiple,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal avgPremiumChangePct,
      String caveat,
      List<HeroZeroTrade> trades) {}

  /** Every aggregate is NULL here: the empty path computed none of them. */
  private static HeroZeroPremium empty(UUID runId, String note) {
    return new HeroZeroPremium(
        runId.toString(), 0, 0, null, null, null, null, null, null, null, null, null, null,
        null, note, List.of());
  }

  /** A traded contract's registry coordinates, for mirror-leg resolution. */
  record ContractMeta(String underlying, LocalDate expiry, BigDecimal strike, String type) {}
}
