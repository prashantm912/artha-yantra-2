package in.arthayantra.marketdata.bhavcopy;

import in.arthayantra.marketdata.candles.Candle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Builds a 1d {@code source='BHAVCOPY'} candle from a daily EOD bhavcopy row. The bucket is the
 * trade date at IST midnight (00:00+05:30), matching how Kite 1d bars are bucketed, so a BHAVCOPY
 * bar and a Kite bar for the same symbol+day share the candles PK (and the projection inserts
 * DO-NOTHING so Kite always wins). OI is null (cash equities). Volume is the total traded quantity.
 */
public final class BhavcopyCandles {

  static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private BhavcopyCandles() {}

  /**
   * A 1d BHAVCOPY candle, or {@code null} when any of OHLC is missing (a listed-but-untraded
   * security) — such rows are not chartable bars and are skipped by the projection.
   */
  public static Candle of(
      String exchange,
      String tradingsymbol,
      LocalDate tradeDate,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      Long volume) {
    if (open == null || high == null || low == null || close == null) {
      return null;
    }
    return new Candle(
        exchange,
        tradingsymbol,
        "1d",
        tradeDate.atStartOfDay().atOffset(IST),
        open,
        high,
        low,
        close,
        volume == null ? 0L : volume,
        null,
        "BHAVCOPY");
  }
}
