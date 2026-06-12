package in.arthayantra.marketdata.futures;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.candles.RollEventsRepository;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The 16:15 IST continuous-futures roll scheduler (B-19 / B-12): rolls
 * {@code roll_days_before_expiry} trading days before each contract's expiry, appends the
 * {@code roll_events} row (gap = incoming close − outgoing close on roll date, from LOCAL
 * per-contract 1d candles — never Kite's roll-unaware {@code continuous=true}), and extends the
 * UNADJUSTED CONT stitch under the synthetic {@code {root}-FUT-CONT} symbol. Deterministic and
 * idempotent — re-runs append nothing new.
 *
 * <p><b>Documented caveat (mandatory, B-19):</b> backtests of {@code futures_of_underlying}
 * replay the CONT series while live trades the actual front contract — on roll days the CONT bar
 * ≠ the front-contract bar (the basis gap). The divergence is inherent to continuous series and
 * is documented here and in the stage file rather than hidden.
 */
@Service
public class ContinuousFuturesRoller {

  private static final Logger log = LoggerFactory.getLogger(ContinuousFuturesRoller.class);
  private static final LocalDate STITCH_EPOCH = LocalDate.of(2000, 1, 1);

  private final InstrumentRepository instruments;
  private final CandleRepository candles;
  private final RollEventsRepository rollEvents;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final List<String> underlyings;
  private final int rollDaysBeforeExpiry;

  /** Wires the roller (A7 knob {@code roll_days_before_expiry}, default 1). */
  public ContinuousFuturesRoller(
      InstrumentRepository instruments,
      CandleRepository candles,
      RollEventsRepository rollEvents,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.futures.underlyings:NIFTY 50,NIFTY BANK}") List<String> underlyings,
      @Value("${artha.futures.roll-days-before-expiry:1}") int rollDaysBeforeExpiry) {
    this.instruments = instruments;
    this.candles = candles;
    this.rollEvents = rollEvents;
    this.calendar = calendar;
    this.clock = clock;
    this.underlyings = underlyings;
    this.rollDaysBeforeExpiry = rollDaysBeforeExpiry;
  }

  /** 16:15 IST daily — six-field cron, IST zone (B-12). */
  @Scheduled(cron = "0 15 16 * * MON-FRI", zone = "Asia/Kolkata")
  public void scheduledRoll() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    if (!isTradingDaySafe(today)) {
      return;
    }
    rollNow();
  }

  /** One deterministic pass: roll detection + event append + stitch extension. Idempotent. */
  public synchronized void rollNow() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    for (String configured : underlyings) {
      String underlying = configured.trim();
      try {
        rollOne(underlying, today);
      } catch (Exception e) {
        log.warn("continuous-futures roll failed for {}: {}", underlying, e.getMessage());
      }
    }
  }

  private void rollOne(String underlying, LocalDate today) {
    List<Instrument> ladder =
        instruments.futures(underlying).stream()
            .filter(i -> !"SYN-CONT".equals(i.segment()))
            .toList();
    if (ladder.isEmpty()) {
      return;
    }
    String root = ladder.get(0).name(); // dump root, e.g. NIFTY
    String contSymbol = root + "-FUT-CONT";
    String exchange = ladder.get(0).exchange();
    instruments.upsertSyntheticCont(
        exchange, contSymbol, root, ladder.get(0).underlyingExchange(), underlying);

    LocalDate previousRoll = null;
    for (int i = 0; i < ladder.size(); i++) {
      Instrument contract = ladder.get(i);
      LocalDate roll = rollDate(contract.expiry());

      // roll event: on/after the roll date, with the next contract listed and both closes known
      if (!today.isBefore(roll) && i + 1 < ladder.size()) {
        Instrument next = ladder.get(i + 1);
        OffsetDateTime rollBucket = roll.atStartOfDay().atOffset(Ist.OFFSET);
        BigDecimal outgoing = candles.closeAt(exchange, contract.tradingsymbol(), "1d", rollBucket);
        BigDecimal incoming = candles.closeAt(exchange, next.tradingsymbol(), "1d", rollBucket);
        if (outgoing != null && incoming != null) {
          boolean appended =
              rollEvents.append(
                  new RollEventsRepository.RollEvent(
                      underlying, roll, contract.tradingsymbol(), next.tradingsymbol(),
                      incoming.subtract(outgoing)));
          if (appended) {
            log.info(
                "roll {}: {} -> {} gap {}",
                roll, contract.tradingsymbol(), next.tradingsymbol(), incoming.subtract(outgoing));
          }
        }
      }

      // stitch this contract's active segment (… previousRoll] -> [segmentStart … min(roll, today)]
      LocalDate segmentStart = previousRoll == null ? STITCH_EPOCH : previousRoll.plusDays(1);
      LocalDate segmentEnd = roll.isBefore(today) ? roll : today;
      if (!segmentStart.isAfter(segmentEnd)) {
        candles.stitchInto(
            contSymbol,
            exchange,
            contract.tradingsymbol(),
            segmentStart.atStartOfDay().atOffset(Ist.OFFSET),
            segmentEnd.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET));
      }
      previousRoll = roll;
      if (!roll.isBefore(today)) {
        break; // later contracts are not active yet
      }
    }
  }

  /** Roll date = {@code rollDaysBeforeExpiry} TRADING days before expiry (holiday-aware). */
  LocalDate rollDate(LocalDate expiry) {
    LocalDate date = expiry;
    for (int i = 0; i < rollDaysBeforeExpiry; i++) {
      date = previousTradingDay(date);
    }
    return date;
  }

  private LocalDate previousTradingDay(LocalDate date) {
    LocalDate candidate = date.minusDays(1);
    while (!isTradingDaySafe(candidate)) {
      candidate = candidate.minusDays(1);
    }
    return candidate;
  }

  private boolean isTradingDaySafe(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException uncoveredYear) {
      DayOfWeek dow = day.getDayOfWeek();
      return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }
  }
}
