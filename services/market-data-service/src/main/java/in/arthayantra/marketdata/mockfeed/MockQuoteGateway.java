package in.arthayantra.marketdata.mockfeed;

import in.arthayantra.black76.Black76;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.feed.LastTickStore;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.options.ExpiryClock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The B-11 deterministic mock chain: option quotes synthesize from a Black-76-CONSISTENT IV
 * surface around a ladder-anchored spot (the strike-ladder midpoint + a fixed offset, so the ATM
 * region always exists regardless of the tick generator's random-walk level); far wings quote
 * zero-bid/zero-ask (the dead-quote edge corpus); OUTSIDE market hours all bid/asks zero and OI
 * freezes to its EOD value — the documented degradation behind {@code stale: true}. Non-option
 * instruments quote from the last-tick map with a base-price fallback.
 */
public class MockQuoteGateway implements QuoteGateway {

  private static final BigDecimal RISK_FREE = new BigDecimal("0.065");
  private static final double LIQUID_MONEYNESS = 0.12; // beyond this the wings are dead

  private final LastTickStore lastTickStore;
  private final InstrumentRepository instruments;
  private final MarketCalendar calendar;
  private final Clock clock;

  /** Wires the synthesis inputs. */
  public MockQuoteGateway(
      LastTickStore lastTickStore,
      InstrumentRepository instruments,
      MarketCalendar calendar,
      Clock clock) {
    this.lastTickStore = lastTickStore;
    this.instruments = instruments;
    this.calendar = calendar;
    this.clock = clock;
  }

  @Override
  public Map<InstrumentKey, Quote> quotes(Collection<InstrumentKey> keys) {
    Map<InstrumentKey, Quote> out = new LinkedHashMap<>();
    Instant now = clock.instant();
    boolean open = isOpenSafe(now);
    for (InstrumentKey key : keys) {
      Optional<Instrument> instrument =
          instruments.findByKey(key.exchange(), key.tradingsymbol());
      if (instrument.isPresent() && isOption(instrument.get())) {
        optionQuote(key, instrument.get(), now, open).ifPresent(q -> out.put(key, q));
      } else if (instrument.isPresent() && "FUT".equals(instrument.get().instrumentType())) {
        futQuote(key, instrument.get(), now, open).ifPresent(q -> out.put(key, q));
      } else {
        spotQuote(key, instrument.orElse(null), now).ifPresent(q -> out.put(key, q));
      }
    }
    return out;
  }

  /** The chain-consistent underlying spot: ladder midpoint + a deterministic offset. */
  public BigDecimal ladderSpot(String underlying, java.time.LocalDate expiry) {
    List<BigDecimal> strikes = instruments.strikes(underlying, expiry);
    if (strikes.isEmpty()) {
      return null;
    }
    BigDecimal mid =
        strikes.get(0).add(strikes.get(strikes.size() - 1)).divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
    return mid.add(new BigDecimal("137.55")); // off-strike so the ATM is never exactly on a strike
  }

  private Optional<Quote> optionQuote(
      InstrumentKey key, Instrument instrument, Instant now, boolean open) {
    BigDecimal spot = ladderSpot(instrument.underlyingTradingsymbol(), instrument.expiry());
    if (spot == null || instrument.strike() == null || instrument.expiry() == null) {
      return Optional.empty();
    }
    OptionalDouble years = ExpiryClock.yearsToExpiry(now, instrument.expiry());
    double t = years.orElse(Black76.T_MIN);
    double forward = spot.doubleValue() * Math.exp(RISK_FREE.doubleValue() * t);
    double k = instrument.strike().doubleValue();
    double logMoneyness = Math.log(k / forward);
    // a deterministic smile: base vol + curvature + a touch of term structure
    double iv = 0.18 + 0.40 * logMoneyness * logMoneyness + 0.03 * Math.sqrt(t);
    Black76.OptionType type =
        "CE".equals(instrument.instrumentType()) ? Black76.OptionType.CE : Black76.OptionType.PE;
    double fair = Black76.price(type, forward, k, t, RISK_FREE.doubleValue(), iv);
    BigDecimal ltp = tick(BigDecimal.valueOf(fair));

    boolean liquid = Math.abs(k / forward - 1.0) <= LIQUID_MONEYNESS;
    BigDecimal bid = BigDecimal.ZERO;
    BigDecimal ask = BigDecimal.ZERO;
    if (open && liquid) {
      BigDecimal half = ltp.multiply(new BigDecimal("0.005")).max(new BigDecimal("0.05"));
      bid = tick(ltp.subtract(half)).max(new BigDecimal("0.05"));
      ask = tick(ltp.add(half));
    }
    long token = instrument.instrumentToken() == null ? 0 : instrument.instrumentToken();
    // B-11: OI/volume EVOLVE deterministically through the session and freeze to EOD off-hours
    long minuteOfDay = open ? (now.atZone(in.arthayantra.common.web.time.Ist.ZONE).getHour() * 60L
        + now.atZone(in.arthayantra.common.web.time.Ist.ZONE).getMinute()) : 930; // 15:30 EOD
    long oi = 10_000 + Math.floorMod(token * 7_919L + minuteOfDay * 37L, 90_000L);
    long volume = open ? Math.floorMod(token * 104_729L, 5_000L) * Math.max(1, minuteOfDay - 554) : 0;
    return Optional.of(
        new Quote(key, ltp, bid, ask, volume, oi, OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
  }

  /** FUT quotes carry the cost-of-carry off the chain-consistent index spot — clean contango. */
  private Optional<Quote> futQuote(
      InstrumentKey key, Instrument instrument, Instant now, boolean open) {
    String underlying = instrument.underlyingTradingsymbol();
    List<java.time.LocalDate> expiries =
        underlying == null ? List.of() : instruments.expiries(underlying);
    BigDecimal spot = expiries.isEmpty() ? null : ladderSpot(underlying, expiries.get(0));
    if (spot == null) {
      long token = instrument.instrumentToken() == null ? 0 : instrument.instrumentToken();
      spot = MockTickGenerator.basePrice(token);
    }
    double t =
        instrument.expiry() == null
            ? Black76.T_MIN
            : ExpiryClock.yearsToExpiry(now, instrument.expiry()).orElse(Black76.T_MIN);
    BigDecimal ltp =
        tick(spot.multiply(BigDecimal.valueOf(Math.exp(RISK_FREE.doubleValue() * t))));
    BigDecimal bid = open ? tick(ltp.subtract(new BigDecimal("0.45"))) : BigDecimal.ZERO;
    BigDecimal ask = open ? tick(ltp.add(new BigDecimal("0.45"))) : BigDecimal.ZERO;
    long token = instrument.instrumentToken() == null ? 0 : instrument.instrumentToken();
    long oi = 50_000 + Math.floorMod(token * 6_271L, 150_000L);
    long volume = open ? Math.floorMod(token * 99_991L, 800_000L) : 0;
    // Stage-G: synthesize a deterministic day range around the carry-priced ltp (close ~ prev ltp).
    Quote.Ohlc ohlc =
        new Quote.Ohlc(
            ltp,
            tick(ltp.multiply(new BigDecimal("1.01"))),
            tick(ltp.multiply(new BigDecimal("0.99"))),
            ltp);
    return Optional.of(
        new Quote(
            key, ltp, bid, ask, volume, oi, ohlc, OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
  }

  private Optional<Quote> spotQuote(InstrumentKey key, Instrument instrument, Instant now) {
    // chain underlyings answer the ladder-anchored spot so the chain is self-consistent
    if (instrument != null && "INDICES".equals(instrument.segment())) {
      List<java.time.LocalDate> expiries = instruments.expiries(key.tradingsymbol());
      if (!expiries.isEmpty()) {
        BigDecimal spot = ladderSpot(key.tradingsymbol(), expiries.get(0));
        if (spot != null) {
          return Optional.of(
              new Quote(
                  key, spot, null, null, null, null, OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        }
      }
    }
    return lastTickStore
        .latest(key)
        .map(tick -> new Quote(key, tick.lastPrice(), tick.timestamp()))
        .or(
            () ->
                Optional.ofNullable(instrument)
                    .map(Instrument::instrumentToken)
                    .map(
                        token ->
                            new Quote(
                                key,
                                MockTickGenerator.basePrice(token),
                                OffsetDateTime.ofInstant(now, ZoneOffset.UTC))));
  }

  private static boolean isOption(Instrument instrument) {
    return "CE".equals(instrument.instrumentType()) || "PE".equals(instrument.instrumentType());
  }

  private boolean isOpenSafe(Instant now) {
    try {
      return calendar.isOpen(now);
    } catch (IllegalArgumentException uncoveredYear) {
      return true;
    }
  }

  private static BigDecimal tick(BigDecimal price) {
    return price
        .divide(new BigDecimal("0.05"), 0, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("0.05"))
        .setScale(2, RoundingMode.HALF_UP)
        .max(new BigDecimal("0.05"));
  }
}
