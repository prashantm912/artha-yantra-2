package in.arthayantra.marketdata.nse.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.freshness.DataFreshness;
import in.arthayantra.marketdata.nse.analytics.NseEodReader.ParticipantOiRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * E3 {@code fii-dii-bias} (§3.3): the FII participant change-in-OI classifier. Compares the two most
 * recent FII participant-OI rows on/before a date and derives a single directional bias from (a) the
 * FII index-futures net-position change classified into the LB/SC/LU/SB grid (price-labelled when the
 * index 1d close is available, net-change-labelled otherwise) and (b) the FII index-option leg seller
 * read (net-seller of calls = bearish, net-seller of puts = bullish). The futures change is primary;
 * the leg read is the tiebreak. The combined {@code biasSign} (+1 bull / -1 bear / 0 neutral) is the
 * single value the scalper {@code fii-dii-gate} consumes — a neutral/conflicting read degrades to pass.
 *
 * <p>Unlike the OI-confluence gates, this is real EOD NSE participant data (not OI-derived), so it
 * carries signal on a PAST eod date and is usable in backtest (the seam already passes the prior
 * completed session's {@code eodDate}).
 */
@Service
public class ParticipantBiasService {

  private static final String FII = "FII";
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private final NseEodReader reader;
  private final CandleRepository candles;

  public ParticipantBiasService(NseEodReader reader, CandleRepository candles) {
    this.reader = reader;
    this.candles = candles;
  }

  /** The combined FII EOD bias for a date (sign drives the gate; the rest is surfaced for the UI). */
  public record Bias(
      LocalDate tradeDate,
      String fiiClassification,
      String bias,
      BigDecimal fiiLongPct,
      long callNet,
      long putNet,
      String legBias,
      int biasSign,
      @JsonInclude(JsonInclude.Include.NON_NULL) DataFreshness freshness) {

    public Bias(
        LocalDate tradeDate,
        String fiiClassification,
        String bias,
        BigDecimal fiiLongPct,
        long callNet,
        long putNet,
        String legBias,
        int biasSign) {
      this(tradeDate, fiiClassification, bias, fiiLongPct, callNet, putNet, legBias, biasSign, null);
    }

    static Bias neutral(LocalDate date) {
      return new Bias(date, "INSUFFICIENT", "NEUTRAL", null, 0, 0, "NEUTRAL", 0);
    }

    /** Returns a copy carrying the freshness envelope (populated at the controller boundary). */
    public Bias withFreshness(DataFreshness f) {
      return new Bias(
          tradeDate, fiiClassification, bias, fiiLongPct, callNet, putNet, legBias, biasSign, f);
    }
  }

  /** Reads the two most-recent FII rows on/before {@code date} + the index 1d close delta, then classifies. */
  public Bias bias(LocalDate date) {
    List<ParticipantOiRow> fii =
        reader.participantOi(date.minusDays(12), date).stream()
            .filter(r -> FII.equalsIgnoreCase(r.clientType()))
            .toList();
    if (fii.size() < 2) {
      return Bias.neutral(date);
    }
    ParticipantOiRow latest = fii.get(fii.size() - 1);
    ParticipantOiRow prev = fii.get(fii.size() - 2);
    BigDecimal closeNow = indexClose(latest.tradeDate());
    BigDecimal closePrev = indexClose(prev.tradeDate());
    BigDecimal deltaPrice =
        closeNow == null || closePrev == null ? null : closeNow.subtract(closePrev);
    return classify(prev, latest, deltaPrice);
  }

  /**
   * Pure classifier (DB-free, unit-testable). {@code deltaPrice} may be null (no index close on file) —
   * the LB/SC/LU/SB label then degrades to a net-change label, but the bias SIGN is unaffected (it comes
   * from the futures net-position change + the leg read, neither of which needs price).
   */
  static Bias classify(ParticipantOiRow prev, ParticipantOiRow latest, BigDecimal deltaPrice) {
    long deltaLong = latest.futureIndexLong() - prev.futureIndexLong();
    long deltaShort = latest.futureIndexShort() - prev.futureIndexShort();
    long deltaNet = deltaLong - deltaShort; // change in FII net-long futures positioning
    int futSign = Long.signum(deltaNet);

    // Leg read: a participant NET-SHORT calls (wrote calls) is bearish; NET-LONG puts is bearish; mirror
    // bullish. callNet/putNet are (long - short) on the FII index-option book.
    long callNet = latest.optionIndexCallLong() - latest.optionIndexCallShort();
    long putNet = latest.optionIndexPutLong() - latest.optionIndexPutShort();
    int legScore = Long.signum(callNet) - Long.signum(putNet); // +call-long & put-short = bullish
    int legSign = Integer.signum(legScore);

    int biasSign = futSign != 0 ? futSign : legSign; // futures change primary; leg is the tiebreak

    String classification;
    if (deltaPrice != null && deltaPrice.signum() != 0) {
      boolean priceUp = deltaPrice.signum() > 0;
      if (priceUp && deltaLong > 0) {
        classification = "LONG_BUILDUP";
      } else if (priceUp && deltaShort < 0) {
        classification = "SHORT_COVERING";
      } else if (!priceUp && deltaShort > 0) {
        classification = "SHORT_BUILDUP";
      } else if (!priceUp && deltaLong < 0) {
        classification = "LONG_UNWINDING";
      } else {
        classification = futSign > 0 ? "NET_LONG_INCREASE" : futSign < 0 ? "NET_SHORT_INCREASE" : "FLAT";
      }
    } else {
      classification = futSign > 0 ? "NET_LONG_INCREASE" : futSign < 0 ? "NET_SHORT_INCREASE" : "FLAT";
    }

    long totalFut = latest.futureIndexLong() + latest.futureIndexShort();
    BigDecimal fiiLongPct =
        totalFut == 0
            ? null
            : BigDecimal.valueOf(latest.futureIndexLong())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalFut), 2, RoundingMode.HALF_UP);
    String legBias = legSign > 0 ? "BULL" : legSign < 0 ? "BEAR" : "NEUTRAL";
    String bias = biasSign > 0 ? "BULL" : biasSign < 0 ? "BEAR" : "NEUTRAL";
    return new Bias(
        latest.tradeDate(), classification, bias, fiiLongPct, callNet, putNet, legBias, biasSign);
  }

  private BigDecimal indexClose(LocalDate date) {
    OffsetDateTime endOfDay = OffsetDateTime.of(date, LocalTime.of(23, 59), IST);
    return candles.closeAt("NSE", "NIFTY 50", "1d", endOfDay);
  }
}
