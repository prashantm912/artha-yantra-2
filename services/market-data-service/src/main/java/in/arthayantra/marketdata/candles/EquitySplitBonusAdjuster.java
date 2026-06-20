package in.arthayantra.marketdata.candles;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.candles.EodCorporateActionRepository.Ratio;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Read-time split/bonus back-adjustment for equity 1d series — the multiplicative analogue of the
 * additive {@link ContBackAdjuster} (futures rolls), living alongside it in the candles read path.
 * Bars dated before a corporate action's ex-date are multiplied by the cumulative product of every
 * later ratio, so a daily series spanning a split is continuous (no false price cliff). The stored
 * bars stay RAW; the policy can change with no rewrite.
 *
 * <p>SOURCE-AWARE: only {@code source='BHAVCOPY'} bars are adjusted. Kite-sourced bars arrive
 * already split-adjusted from the broker feed (and {@code CorporateActionJob} re-fetches them after
 * an action), so adjusting them again would double-count — they pass through untouched, which keeps
 * a mixed (part-Kite, part-bhavcopy) series correct bar by bar.
 */
@Component
public class EquitySplitBonusAdjuster {

  private final EodCorporateActionRepository repo;

  public EquitySplitBonusAdjuster(EodCorporateActionRepository repo) {
    this.repo = repo;
  }

  /** Returns the bars with BHAVCOPY pre-ex-date OHLC scaled; non-BHAVCOPY bars are unchanged. */
  public List<Candle> adjust(String exchange, String tradingsymbol, List<Candle> bars) {
    List<Ratio> events = repo.ratiosFor(exchange, tradingsymbol);
    if (events.isEmpty()) {
      return bars;
    }
    List<Candle> out = new ArrayList<>(bars.size());
    for (Candle bar : bars) {
      if (!"BHAVCOPY".equals(bar.source())) {
        out.add(bar);
        continue;
      }
      LocalDate barDate = bar.bucket().toInstant().atZone(Ist.ZONE).toLocalDate();
      BigDecimal factor = BigDecimal.ONE;
      for (Ratio e : events) {
        if (barDate.isBefore(e.exDate())) {
          factor = factor.multiply(e.ratio());
        }
      }
      out.add(factor.compareTo(BigDecimal.ONE) == 0 ? bar : scale(bar, factor));
    }
    return out;
  }

  private static Candle scale(Candle b, BigDecimal f) {
    return new Candle(
        b.exchange(),
        b.tradingsymbol(),
        b.interval(),
        b.bucket(),
        px(b.open(), f),
        px(b.high(), f),
        px(b.low(), f),
        px(b.close(), f),
        b.volume(), // volume left raw (matches ContBackAdjuster) — price continuity is the goal
        b.oi(),
        b.source());
  }

  private static BigDecimal px(BigDecimal v, BigDecimal f) {
    return v.multiply(f).setScale(4, RoundingMode.HALF_UP);
  }
}
