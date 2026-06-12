package in.arthayantra.marketdata.futures;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * The B-18 term-structure read: near/next/far + spot in ONE batched quote call (≤4 instruments
 * under the 1/1s quote limiter). Basis math in {@code BigDecimal}: absolute {@code F − S},
 * annualized {@code (F/S − 1) × 365 / daysToExpiry} with ACT/365 calendar days to 15:30 IST on
 * expiry (the B-10 day count). Off-hours (or on quote failure) the last computed structure serves
 * with {@code stale: true} — never assembled from per-contract candle closes while quotes flow.
 */
@Service
public class FuturesTermStructureService {

  /** One contract leg (B-18 shape: incl. volume). */
  public record ContractLeg(
      String tradingsymbol,
      LocalDate expiry,
      long daysToExpiry,
      BigDecimal ltp,
      Long oi,
      Long volume,
      BigDecimal basisAbsolute,
      BigDecimal basisAnnualized) {}

  /** The term structure. */
  public record TermStructure(
      String underlying,
      BigDecimal spot,
      String state,
      BigDecimal calendarSpread,
      boolean stale,
      OffsetDateTime asOf,
      List<ContractLeg> contracts) {}

  private final InstrumentRepository instruments;
  private final QuoteGateway quoteGateway;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final Map<String, TermStructure> lastGood = new ConcurrentHashMap<>();

  /** Wires the read path. */
  public FuturesTermStructureService(
      InstrumentRepository instruments,
      QuoteGateway quoteGateway,
      MarketCalendar calendar,
      Clock clock) {
    this.instruments = instruments;
    this.quoteGateway = quoteGateway;
    this.calendar = calendar;
    this.clock = clock;
  }

  /** Computes the structure with exactly one gateway invocation. */
  public TermStructure termStructure(String underlying) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    LocalDate today = now.toInstant().atZone(Ist.ZONE).toLocalDate();
    List<Instrument> ladder =
        instruments.futures(underlying).stream()
            .filter(i -> !i.expiry().isBefore(today))
            .limit(3)
            .toList();
    if (ladder.isEmpty()) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_INSTRUMENT, "no FUT contracts for " + underlying);
    }
    String underlyingExchange =
        ladder.get(0).underlyingExchange() == null ? "NSE" : ladder.get(0).underlyingExchange();
    InstrumentKey spotKey = new InstrumentKey(underlyingExchange, underlying);

    // ONE batched call: spot + up to three contracts (B-18)
    List<InstrumentKey> keys = new ArrayList<>();
    keys.add(spotKey);
    ladder.forEach(i -> keys.add(new InstrumentKey(i.exchange(), i.tradingsymbol())));
    Map<InstrumentKey, QuoteGateway.Quote> quotes;
    try {
      quotes = quoteGateway.quotes(keys);
    } catch (Exception quoteFailure) {
      return staleFallback(underlying, now, quoteFailure.getMessage());
    }
    QuoteGateway.Quote spotQuote = quotes.get(spotKey);
    if (spotQuote == null) {
      return staleFallback(underlying, now, "no spot quote");
    }
    BigDecimal spot = spotQuote.lastPrice();

    List<ContractLeg> legs = new ArrayList<>(ladder.size());
    for (Instrument contract : ladder) {
      QuoteGateway.Quote quote =
          quotes.get(new InstrumentKey(contract.exchange(), contract.tradingsymbol()));
      if (quote == null) {
        continue;
      }
      legs.add(leg(contract, quote.lastPrice(), quote.oi(), quote.volume(), spot, now));
    }
    if (legs.isEmpty()) {
      return staleFallback(underlying, now, "no contract quotes");
    }
    TermStructure structure =
        new TermStructure(
            underlying,
            spot,
            classify(legs),
            legs.size() >= 2 ? legs.get(1).ltp().subtract(legs.get(0).ltp()) : null,
            !isOpenSafe(now),
            now,
            legs);
    lastGood.put(underlying, structure);
    return structure;
  }

  /** Basis math (B-18): absolute F−S; annualized (F/S − 1) × 365/days, ACT/365 to 15:30 IST. */
  static ContractLeg leg(
      Instrument contract, BigDecimal ltp, Long oi, Long volume, BigDecimal spot, OffsetDateTime now) {
    OffsetDateTime cutoff =
        contract.expiry().atTime(15, 30).atOffset(Ist.OFFSET);
    long days =
        Math.max(1, java.time.Duration.between(now.toInstant(), cutoff.toInstant()).toSeconds() / 86_400);
    BigDecimal basisAbsolute = ltp.subtract(spot);
    BigDecimal basisAnnualized =
        ltp.divide(spot, MathContext.DECIMAL64)
            .subtract(BigDecimal.ONE)
            .multiply(BigDecimal.valueOf(365))
            .divide(BigDecimal.valueOf(days), 4, RoundingMode.HALF_UP);
    return new ContractLeg(
        contract.tradingsymbol(),
        contract.expiry(),
        days,
        ltp,
        oi,
        volume,
        basisAbsolute.setScale(4, RoundingMode.HALF_UP),
        basisAnnualized);
  }

  /** B-18 pins exactly two states, decided by the SIGN OF THE NEAR→NEXT SLOPE. */
  static String classify(List<ContractLeg> legs) {
    if (legs.size() < 2) {
      return legs.get(0).basisAbsolute().signum() >= 0 ? "CONTANGO" : "BACKWARDATION";
    }
    return legs.get(1).ltp().compareTo(legs.get(0).ltp()) >= 0 ? "CONTANGO" : "BACKWARDATION";
  }

  private TermStructure staleFallback(String underlying, OffsetDateTime now, String why) {
    TermStructure cached = lastGood.get(underlying);
    if (cached == null) {
      throw new ApiException(503, ErrorCodes.DATA_STALE, "no term structure available: " + why);
    }
    return new TermStructure(
        cached.underlying(), cached.spot(), cached.state(), cached.calendarSpread(),
        true, now, cached.contracts());
  }

  private boolean isOpenSafe(OffsetDateTime now) {
    try {
      return calendar.isOpen(now.toInstant());
    } catch (IllegalArgumentException uncoveredYear) {
      return true;
    }
  }
}
