package in.arthayantra.marketdata.openalgo.live;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.kite.HistoricalCandleGateway.Candle;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway.Quote;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoCandle;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoLeg;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoQuote;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Wire -> domain mapping: the OI-into-domain-record proof (M0) + epoch-seconds timestamp. */
class OpenAlgoMappersTest {

  private static final InstrumentKey NIFTY = new InstrumentKey("NSE", "NIFTY 50");
  private static final InstrumentKey CE = new InstrumentKey("NFO", "NIFTY19JUN2624000CE");

  @Test
  void quoteMapsPricesPrevCloseAndOi() {
    OpenAlgoQuote wire =
        new OpenAlgoQuote(
            new BigDecimal("24137.55"),
            new BigDecimal("24137.5"),
            new BigDecimal("24138.0"),
            new BigDecimal("24100.0"),
            new BigDecimal("24200.0"),
            new BigDecimal("24050.0"),
            new BigDecimal("24095.0"),
            987654L,
            new BigDecimal("106860000"));

    Quote q = OpenAlgoMappers.toQuote(NIFTY, wire);

    assertThat(q.lastPrice()).isEqualByComparingTo("24137.55");
    assertThat(q.bid()).isEqualByComparingTo("24137.5");
    assertThat(q.ask()).isEqualByComparingTo("24138.0");
    assertThat(q.volume()).isEqualTo(987654L);
    assertThat(q.oi()).isEqualTo(106860000L); // /quotes carries oi (decimal -> Long)
    assertThat(q.ohlc().close()).isEqualByComparingTo("24095.0"); // prev_close -> Kite close slot
  }

  @Test
  void legMapsPerStrikeOiNarrowedToLong() {
    OpenAlgoLeg leg =
        new OpenAlgoLeg(
            "NIFTY19JUN2624000CE",
            "24000 CE",
            new BigDecimal("312.55"),
            new BigDecimal("312.05"),
            new BigDecimal("313.00"),
            500L,
            450L,
            null,
            null,
            null,
            null,
            123456L,
            new BigDecimal("987654"),
            75,
            new BigDecimal("0.05"));

    Quote q = OpenAlgoMappers.legToQuote(CE, leg);

    assertThat(q.lastPrice()).isEqualByComparingTo("312.55");
    assertThat(q.volume()).isEqualTo(123456L);
    assertThat(q.oi()).isEqualTo(987654L); // decimal -> Long
  }

  @Test
  void candleMapsEpochSecondsToIstAndOhlcvOi() {
    OpenAlgoCandle wire =
        new OpenAlgoCandle(
            1750909500L, // epoch seconds
            new BigDecimal("100.50"),
            new BigDecimal("101.00"),
            new BigDecimal("100.00"),
            new BigDecimal("100.75"),
            1500L,
            12345L);

    Candle c = OpenAlgoMappers.toCandle(NIFTY, "1m", wire);

    assertThat(c.interval()).isEqualTo("1m");
    assertThat(c.bucketStart()).isNotNull();
    assertThat(c.bucketStart().getOffset().getTotalSeconds()).isEqualTo(5 * 3600 + 30 * 60); // IST
    assertThat(c.close()).isEqualByComparingTo("100.75");
    assertThat(c.volume()).isEqualTo(1500L);
    assertThat(c.oi()).isEqualTo(12345L);
  }
}
