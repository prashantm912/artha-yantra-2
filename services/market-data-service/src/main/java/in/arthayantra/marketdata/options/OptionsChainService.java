package in.arthayantra.marketdata.options;

import in.arthayantra.black76.Black76;
import in.arthayantra.black76.IvSolver;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The Phase-15 chain computation (B-10): batch quotes for one (underlying, expiry), spot from the
 * UNDERLYING quote (never a strike average), forward via the B-10 precedence, per-row IV/Greeks
 * through the S1-gated Phase-14 solver — null + reason rows are first-class, never skipped (raw
 * capture is unconditional). PCR = ΣPE OI / ΣCE OI.
 */
@Service
public class OptionsChainService {

  /**
   * One strike side. {@code prevOi} is non-null ONLY on the Upstox source (its {@code prev_oi}).
   * {@code vanna}/{@code charm}/{@code vomma} are the second-order greeks (§17.6) — additive,
   * live-only fields surfaced on the chain output; they are NOT persisted to the snapshot table.
   */
  public record Leg(
      String tradingsymbol,
      BigDecimal ltp,
      BigDecimal bid,
      BigDecimal ask,
      Long volume,
      Long oi,
      Long prevOi,
      BigDecimal iv,
      BigDecimal delta,
      BigDecimal gamma,
      BigDecimal theta,
      BigDecimal vega,
      BigDecimal rho,
      BigDecimal vanna,
      BigDecimal charm,
      BigDecimal vomma,
      BigDecimal speed,
      BigDecimal zomma,
      BigDecimal color,
      String ivReason,
      String priceSource) {}

  /** One chain row. */
  public record StrikeRow(BigDecimal strike, Leg ce, Leg pe) {}

  /** The computed chain. */
  public record Chain(
      String underlying,
      LocalDate expiry,
      BigDecimal spot,
      BigDecimal forward,
      String forwardSource,
      BigDecimal riskFreeRate,
      BigDecimal pcr,
      boolean stale,
      OffsetDateTime asOf,
      List<StrikeRow> rows) {}

  private final InstrumentRepository instruments;
  private final QuoteGateway quoteGateway;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final BigDecimal riskFreeRate;
  private final boolean ivEnabled;
  /**
   * The flag-selected per-strike LTP+OI+spot source (Wave U1). {@code null} ⇒ the DEFAULT Kite
   * {@code QuoteGateway} path (unchanged); non-null (when {@code
   * artha.marketdata.source.optionchain=upstox}) ⇒ source the raw quotes from the direct-Upstox
   * {@code /v2/option/chain} call instead, with the greeks/IV pipeline downstream untouched.
   */
  private final OptionChainQuoteSource optionChainSource;

  /** Wires the chain inputs; {@code artha.options.iv-enabled} is the S1-SEQ gate switch. */
  public OptionsChainService(
      InstrumentRepository instruments,
      QuoteGateway quoteGateway,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.options.risk-free-rate:0.065}") BigDecimal riskFreeRate,
      @Value("${artha.options.iv-enabled:true}") boolean ivEnabled,
      Optional<OptionChainQuoteSource> optionChainSource) {
    this.instruments = instruments;
    this.quoteGateway = quoteGateway;
    this.calendar = calendar;
    this.clock = clock;
    this.riskFreeRate = riskFreeRate;
    this.ivEnabled = ivEnabled;
    this.optionChainSource = optionChainSource.orElse(null);
  }

  /** Default-expiry resolution: the nearest expiry on/after today. */
  public LocalDate resolveExpiry(String underlying, LocalDate requested) {
    if (requested != null) {
      return requested;
    }
    LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(ZoneOffset.ofHoursMinutes(5, 30)).toLocalDate();
    List<LocalDate> expiries = instruments.expiries(underlying);
    return expiries.stream()
        .filter(e -> !e.isBefore(today))
        .findFirst()
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCodes.NOT_FOUND_INSTRUMENT, "no option expiries for " + underlying));
  }

  /** Expiries on/after today and within {@code horizonDays} (ascending) — the snapshot horizon. */
  public List<LocalDate> expiriesWithin(String underlying, int horizonDays) {
    LocalDate today =
        OffsetDateTime.now(clock).atZoneSameInstant(ZoneOffset.ofHoursMinutes(5, 30)).toLocalDate();
    LocalDate limit = today.plusDays(horizonDays);
    return instruments.expiries(underlying).stream()
        .filter(e -> !e.isBefore(today) && !e.isAfter(limit))
        .toList();
  }

  /** Computes the full chain for (underlying, expiry). */
  public Chain chain(String underlying, LocalDate requestedExpiry) {
    LocalDate expiry = resolveExpiry(underlying, requestedExpiry);
    List<Instrument> chainInstruments = instruments.optionChain(underlying, expiry);
    if (chainInstruments.isEmpty()) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_INSTRUMENT, "no chain for " + underlying + " " + expiry);
    }
    OffsetDateTime now = OffsetDateTime.now(clock);
    boolean open = isOpenSafe(now);

    // Wave U1: source per-strike LTP+OI+spot from the direct-Upstox chain when the flag selects it
    // (login-free analytics token), else the DEFAULT Kite QuoteGateway path. prevOi is carried per
    // (strike,type) so the snapshot's oi_change can use Upstox's prev_oi directly.
    Optional<OptionChainQuoteSource.ChainQuotes> upstox =
        optionChainSource == null ? Optional.empty() : optionChainSource.fetch(underlying, expiry);

    BigDecimal spot;
    Map<InstrumentKey, QuoteGateway.Quote> quotes;
    Map<String, Long> prevOiByStrikeType = new java.util.HashMap<>();
    if (upstox.isPresent()) {
      OptionChainQuoteSource.ChainQuotes cq = upstox.get();
      spot =
          Optional.ofNullable(cq.spot())
              .orElseThrow(
                  () ->
                      new ApiException(
                          503, ErrorCodes.DATA_STALE, "no Upstox spot for " + underlying));
      quotes = upstoxQuotes(chainInstruments, cq, now, prevOiByStrikeType);
    } else {
      // spot from the UNDERLYING quote — never a strike average (the v1 defect)
      InstrumentKey underlyingKey =
          new InstrumentKey(
              chainInstruments.get(0).underlyingExchange() == null
                  ? "NSE"
                  : chainInstruments.get(0).underlyingExchange(),
              underlying);
      spot =
          Optional.ofNullable(quoteGateway.quotes(List.of(underlyingKey)).get(underlyingKey))
              .map(QuoteGateway.Quote::lastPrice)
              .orElseThrow(
                  () ->
                      new ApiException(
                          503, ErrorCodes.DATA_STALE, "no spot quote for " + underlying));
      quotes =
          quoteGateway.quotes(
              chainInstruments.stream()
                  .map(i -> new InstrumentKey(i.exchange(), i.tradingsymbol()))
                  .toList());
    }

    OptionalDouble yearsOpt = ExpiryClock.yearsToExpiry(now.toInstant(), expiry);
    double t = yearsOpt.orElse(Black76.T_MIN);
    boolean expired = yearsOpt.isEmpty();

    ForwardCalculator.ForwardResult forward = resolveForward(chainInstruments, quotes, spot, t);

    Map<BigDecimal, Leg[]> byStrike = new LinkedHashMap<>();
    long ceOi = 0;
    long peOi = 0;
    for (Instrument instrument : chainInstruments) {
      QuoteGateway.Quote quote =
          quotes.get(new InstrumentKey(instrument.exchange(), instrument.tradingsymbol()));
      Long prevOi =
          prevOiByStrikeType.get(instrument.strike() + "|" + instrument.instrumentType());
      Leg leg = computeLeg(instrument, quote, prevOi, forward.forward(), t, expired);
      Leg[] pair = byStrike.computeIfAbsent(instrument.strike(), s -> new Leg[2]);
      if ("CE".equals(instrument.instrumentType())) {
        pair[0] = leg;
        ceOi += leg != null && leg.oi() != null ? leg.oi() : 0;
      } else {
        pair[1] = leg;
        peOi += leg != null && leg.oi() != null ? leg.oi() : 0;
      }
    }
    List<StrikeRow> rows = new ArrayList<>(byStrike.size());
    byStrike.forEach((strike, pair) -> rows.add(new StrikeRow(strike, pair[0], pair[1])));

    BigDecimal pcr = putCallRatio(ceOi, peOi);
    return new Chain(
        underlying,
        expiry,
        spot,
        forward.forward().setScale(4, RoundingMode.HALF_UP),
        forward.source().name(),
        riskFreeRate,
        pcr,
        !open,
        now,
        rows);
  }

  /**
   * Maps the direct-Upstox chain to the {@code QuoteGateway.Quote} shape the chain pipeline already
   * consumes, keyed by each instrument's {@code (exchange, tradingsymbol)} so {@code computeLeg} and
   * {@code resolveForward} run UNCHANGED. The match is by strike + CE/PE (Upstox is keyed by strike,
   * the instrument master by tradingsymbol). The quote timestamp is {@code now} — the chain call is a
   * live fetch, so no staleness penalty. {@code prev_oi} is sidelined into {@code prevOiByStrikeType}
   * for the snapshot's {@code oi_change}; an instrument with no Upstox strike is simply absent (the
   * downstream {@code computeLeg(null)} drops it, exactly like a Kite quote miss).
   */
  private Map<InstrumentKey, QuoteGateway.Quote> upstoxQuotes(
      List<Instrument> chainInstruments,
      OptionChainQuoteSource.ChainQuotes cq,
      OffsetDateTime now,
      Map<String, Long> prevOiByStrikeType) {
    Map<InstrumentKey, QuoteGateway.Quote> quotes = new LinkedHashMap<>();
    for (Instrument instrument : chainInstruments) {
      boolean ce = "CE".equals(instrument.instrumentType());
      // scale-insensitive strike match: the Upstox map is keyed by the scale-2 strike (the
      // instrument master's NUMERIC scale need not equal Upstox's wire scale).
      OptionChainQuoteSource.Leg leg =
          (ce ? cq.ce() : cq.pe()).get(instrument.strike().setScale(2, RoundingMode.HALF_UP));
      if (leg == null) {
        continue;
      }
      quotes.put(
          new InstrumentKey(instrument.exchange(), instrument.tradingsymbol()),
          new QuoteGateway.Quote(
              new InstrumentKey(instrument.exchange(), instrument.tradingsymbol()),
              leg.ltp(),
              leg.bid(),
              leg.ask(),
              leg.volume(),
              leg.oi(),
              now));
      if (leg.prevOi() != null) {
        prevOiByStrikeType.put(instrument.strike() + "|" + instrument.instrumentType(), leg.prevOi());
      }
    }
    return quotes;
  }

  private ForwardCalculator.ForwardResult resolveForward(
      List<Instrument> chainInstruments,
      Map<InstrumentKey, QuoteGateway.Quote> quotes,
      BigDecimal spot,
      double t) {
    // nearest-ATM strike by |strike − spot|
    BigDecimal atmStrike = null;
    BigDecimal bestDistance = null;
    for (Instrument instrument : chainInstruments) {
      BigDecimal distance = instrument.strike().subtract(spot).abs();
      if (bestDistance == null || distance.compareTo(bestDistance) < 0) {
        bestDistance = distance;
        atmStrike = instrument.strike();
      }
    }
    QuoteGateway.Quote atmCall = null;
    QuoteGateway.Quote atmPut = null;
    for (Instrument instrument : chainInstruments) {
      if (instrument.strike().compareTo(atmStrike) == 0) {
        QuoteGateway.Quote quote =
            quotes.get(new InstrumentKey(instrument.exchange(), instrument.tradingsymbol()));
        if ("CE".equals(instrument.instrumentType())) {
          atmCall = quote;
        } else {
          atmPut = quote;
        }
      }
    }
    Optional<ForwardCalculator.PcpLegs> pcp =
        atmCall != null && atmPut != null
            ? Optional.of(
                new ForwardCalculator.PcpLegs(
                    atmStrike, atmCall.bid(), atmCall.ask(), atmPut.bid(), atmPut.ask()))
            : Optional.empty();
    // the monthly-futures leg arrives with Phase 15A's FUT pins; weeklies fall through anyway
    return ForwardCalculator.resolve(spot, t, riskFreeRate, pcp, Optional.empty());
  }

  private Leg computeLeg(
      Instrument instrument,
      QuoteGateway.Quote quote,
      Long prevOi,
      BigDecimal forward,
      double t,
      boolean expired) {
    if (quote == null) {
      return null;
    }
    BigDecimal iv = null;
    BigDecimal delta = null;
    BigDecimal gamma = null;
    BigDecimal theta = null;
    BigDecimal vega = null;
    BigDecimal rho = null;
    BigDecimal vanna = null;
    BigDecimal charm = null;
    BigDecimal vomma = null;
    BigDecimal speed = null;
    BigDecimal zomma = null;
    BigDecimal color = null;
    String reason;
    String priceSource = null;

    boolean zeroQuoted =
        (quote.bid() == null || quote.bid().signum() == 0)
            && (quote.ask() == null || quote.ask().signum() == 0);
    if (expired) {
      reason = "EXPIRED"; // post-15:30 on expiry day: null by definition
    } else if (!ivEnabled) {
      reason = "IV_DISABLED"; // the S1-SEQ switch — raw capture continues regardless
    } else if (zeroQuoted) {
      reason = IvSolver.Reason.ZERO_QUOTE.name(); // dead book: the LTP is stale by definition
    } else {
      // the staleness guard needs the REAL quote age — a dead LTP must yield a null-IV row
      Duration ltpAge =
          quote.timestamp() == null
              ? Duration.ofDays(1)
              : Duration.between(quote.timestamp().toInstant(), OffsetDateTime.now(clock).toInstant());
      Optional<QuotePriceRule.PriceInput> input =
          QuotePriceRule.choose(quote.bid(), quote.ask(), quote.lastPrice(), ltpAge);
      if (input.isEmpty()) {
        reason = IvSolver.Reason.ZERO_QUOTE.name();
      } else {
        priceSource = input.get().source().name();
        Black76.OptionType type =
            "CE".equals(instrument.instrumentType()) ? Black76.OptionType.CE : Black76.OptionType.PE;
        IvSolver.IvResult solved =
            IvSolver.solve(
                type,
                forward.doubleValue(),
                instrument.strike().doubleValue(),
                t,
                riskFreeRate.doubleValue(),
                input.get().price().doubleValue());
        reason = solved.reason().name();
        if (solved.iv() != null) {
          iv = solved.iv();
          Black76.Greeks greeks =
              Black76.greeks(
                  type,
                  forward.doubleValue(),
                  instrument.strike().doubleValue(),
                  t,
                  riskFreeRate.doubleValue(),
                  solved.iv().doubleValue());
          delta = greeks.delta();
          gamma = greeks.gamma();
          theta = greeks.theta();
          vega = greeks.vega();
          rho = greeks.rho();
          vanna = greeks.vanna();
          charm = greeks.charm();
          vomma = greeks.vomma();
          speed = greeks.speed();
          zomma = greeks.zomma();
          color = greeks.color();
        }
      }
    }
    return new Leg(
        instrument.tradingsymbol(),
        quote.lastPrice(),
        quote.bid(),
        quote.ask(),
        quote.volume(),
        quote.oi(),
        prevOi,
        scale6(iv),
        scale6(delta),
        scale6(gamma),
        scale6(theta),
        scale6(vega),
        scale6(rho),
        scale6(vanna),
        scale6(charm),
        scale6(vomma),
        scale6(speed),
        scale6(zomma),
        scale6(color),
        reason,
        priceSource);
  }

  /** PCR = ΣPE OI / ΣCE OI at 4 dp; null when no CE OI exists (unit-tested per Phase 15). */
  static BigDecimal putCallRatio(long ceOi, long peOi) {
    return ceOi == 0
        ? null
        : BigDecimal.valueOf(peOi).divide(BigDecimal.valueOf(ceOi), 4, RoundingMode.HALF_UP);
  }

  /** Public, reusable PCR for analytics over stored snapshots (delegates to {@link #putCallRatio}). */
  public static BigDecimal pcr(long ceOi, long peOi) {
    return putCallRatio(ceOi, peOi);
  }

  /** The configured pinned rate (provenance). */
  public BigDecimal riskFreeRate() {
    return riskFreeRate;
  }

  private boolean isOpenSafe(OffsetDateTime now) {
    try {
      return calendar.isOpen(now.toInstant());
    } catch (IllegalArgumentException uncoveredYear) {
      return true;
    }
  }

  private static BigDecimal scale6(BigDecimal value) {
    return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
  }

  static BigDecimal mid(BigDecimal bid, BigDecimal ask) {
    return bid.add(ask).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
  }
}
