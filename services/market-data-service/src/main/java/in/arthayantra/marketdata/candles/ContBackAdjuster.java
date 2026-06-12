package in.arthayantra.marketdata.candles;

import in.arthayantra.common.web.time.Ist;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Read-time back-adjustment for CONT series (B-19): every bar dated on/before a roll is offset by
 * the SUM of {@code price_gap} of all rolls on/after its date — pure NUMERIC arithmetic; the
 * stored stitch stays raw, so the adjustment policy can change without rewriting data.
 */
@Component
public class ContBackAdjuster {

  private final RollEventsRepository rollEvents;

  /** Wires the roll log. */
  public ContBackAdjuster(RollEventsRepository rollEvents) {
    this.rollEvents = rollEvents;
  }

  /** Applies cumulative later-roll gaps to OHLC; volume/OI untouched. */
  public List<Candle> backAdjust(String underlying, List<Candle> bars) {
    List<RollEventsRepository.RollEvent> rolls = rollEvents.rollsFor(underlying);
    if (rolls.isEmpty()) {
      return bars;
    }
    List<Candle> adjusted = new ArrayList<>(bars.size());
    for (Candle bar : bars) {
      LocalDate barDate = bar.bucket().toInstant().atZone(Ist.ZONE).toLocalDate();
      BigDecimal offset = BigDecimal.ZERO;
      for (RollEventsRepository.RollEvent roll : rolls) {
        if (!roll.rollDate().isBefore(barDate)) {
          offset = offset.add(roll.priceGap());
        }
      }
      adjusted.add(
          offset.signum() == 0
              ? bar
              : new Candle(
                  bar.exchange(), bar.tradingsymbol(), bar.interval(), bar.bucket(),
                  bar.open().add(offset), bar.high().add(offset),
                  bar.low().add(offset), bar.close().add(offset),
                  bar.volume(), bar.oi(), bar.source()));
    }
    return adjusted;
  }
}
