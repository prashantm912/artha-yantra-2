package in.arthayantra.marketdata.openalgo.live;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway.Candle;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway.Quote;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoCandle;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoLeg;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Wire -> domain mapping for OpenAlgo (kept out of the gateways so the canary path stays
 * domain-free). Mirrors the Kite {@code toDomain} idiom: a missing {@code ltp} defaults to zero,
 * {@code oi} (decimal on the wire) narrows to {@code Long}.
 */
final class OpenAlgoMappers {

  /**
   * OpenAlgo {@code /quotes} -> domain {@link Quote}. {@code prev_close} maps to the OHLC
   * {@code close} slot (Kite's convention: the PREVIOUS day's close). {@code oi} is present on every
   * quote ({@code 0} for cash/index, real for F&O) — narrowed to {@code Long}.
   */
  static Quote toQuote(InstrumentKey key, OpenAlgoQuote q) {
    Quote.Ohlc ohlc =
        (q.open() == null && q.high() == null && q.low() == null && q.prevClose() == null)
            ? null
            : new Quote.Ohlc(q.open(), q.high(), q.low(), q.prevClose());
    return new Quote(
        key,
        q.ltp() != null ? q.ltp() : BigDecimal.ZERO,
        q.bid(),
        q.ask(),
        q.volume(),
        q.oi() != null ? q.oi().longValue() : null,
        ohlc,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  /**
   * One {@code /optionchain} CE/PE leg -> domain {@link Quote} with per-strike {@code oi} (decimal
   * narrowed to {@code Long}). This is the OI-capture path the integration exists for (plan M0).
   */
  static Quote legToQuote(InstrumentKey key, OpenAlgoLeg leg) {
    return new Quote(
        key,
        leg.ltp() != null ? leg.ltp() : BigDecimal.ZERO,
        leg.bid(),
        leg.ask(),
        leg.volume(),
        leg.oi() != null ? leg.oi().longValue() : null,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  /**
   * OpenAlgo {@code /history} row -> domain {@link Candle}; {@code timestamp} is Unix epoch SECONDS,
   * normalized to an IST bucket start (matches the Kite candle's {@code +0530} convention).
   */
  static Candle toCandle(InstrumentKey key, String interval, OpenAlgoCandle c) {
    return new Candle(
        key,
        interval,
        OffsetDateTime.ofInstant(Instant.ofEpochSecond(c.timestamp()), Ist.ZONE),
        c.open(),
        c.high(),
        c.low(),
        c.close(),
        c.volume(),
        c.oi());
  }

  private OpenAlgoMappers() {}
}
