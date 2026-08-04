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
    // Live: refresh the CONT mid-interval caggs per segment — the daily increment only invalidates
    // today's narrow window, so the refresh is cheap and live charts need the fresh 5m/15m/… bars.
    stitch(ladder, underlying, today, true);
  }

  /**
   * Stitches a front-month {@code Instrument} ladder (expiry-ascending) into {@code {root}-FUT-CONT}
   * through {@code today}: upserts the synthetic instrument, appends a {@code roll_events} row at each
   * contract's roll date, and extends the UNADJUSTED CONT stitch one front segment at a time. The live
   * 16:15 roll feeds the currently-listed ladder; {@link ContinuousFuturesBackfill} feeds the full
   * expired+live roster to reconstruct history for backtests. Idempotent — re-runs append nothing new.
   *
   * <p>{@code refreshAggregates} drives the continuous-aggregate refresh after each stitched segment.
   * The live path passes {@code true} — a narrow daily invalidation → cheap, but only since
   * 2026-08-04: the refresh used to be handed this method's {@code [from, to)}, whose first segment
   * starts at {@link #STITCH_EPOCH}, so "narrow daily invalidation" described the INTENT while the
   * code asked for ~26 years. It now refreshes the range {@code stitchInto} reports having actually
   * inserted.
   *
   * <p>The historical backfill
   * passes {@code false}: stitching months of bars at once invalidates a WIDE cagg range, and because
   * the expired-OI backfill never materialised the mid-interval caggs over history, a refresh there
   * re-aggregates ~106k expired contracts' buckets in one call — minutes-long, lock-holding (it blocks
   * the live refresh policy), and an OOM risk on the 1 GB instance. Backtests read CONT 1m from the
   * base {@code candles} table (NOT the caggs), so the 1m stitch alone is what they need; CONT
   * mid-interval history stays unmaterialised by design, exactly like the rest of the historical data.
   */
  void stitch(List<Instrument> ladder, String underlying, LocalDate today, boolean refreshAggregates) {
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
        BigDecimal outgoing = closeForRoll(exchange, contract.tradingsymbol(), roll, rollBucket);
        BigDecimal incoming = closeForRoll(exchange, next.tradingsymbol(), roll, rollBucket);
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
        OffsetDateTime from = segmentStart.atStartOfDay().atOffset(Ist.OFFSET);
        OffsetDateTime to = segmentEnd.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);
        CandleRepository.StitchedRange stitched =
            candles.stitchInto(contSymbol, exchange, contract.tradingsymbol(), from, to);
        if (stitched.rows() > 0 && refreshAggregates) {
          // stitched 1m/1d rows behind a cagg watermark are invisible until refreshed —
          // CONT mid-interval reads (5m/15m/1h/1w) must see the new segment (live path only).
          // Refresh the range actually INSERTED, never [from, to): this segment's `from` is
          // STITCH_EPOCH, so passing the request window re-materialized ~26 years of caggs nightly
          // to publish one day of bars — and once V049 compressed them that aborted the whole roll
          // (2026-08-04, all six roots). The inserted range is a normal day on a normal day, which
          // is what CandleRepository's cap doctrine assumed the roller was already doing.
          candles.refreshDerivedAggregates(stitched.firstBucket(), stitched.lastBucket());
        }
      }
      previousRoll = roll;
      if (!roll.isBefore(today)) {
        break; // later contracts are not active yet
      }
    }
  }

  /**
   * The contract's close on the roll date for the gap math: native 1d when present (the live path),
   * else the last 1m close of the day — {@code expired_contracts} carry 1m-only bars (no native 1d).
   */
  private BigDecimal closeForRoll(
      String exchange, String tradingsymbol, LocalDate rollDate, OffsetDateTime rollBucket) {
    BigDecimal daily = candles.closeAt(exchange, tradingsymbol, "1d", rollBucket);
    if (daily != null) {
      return daily;
    }
    OffsetDateTime from = rollDate.atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = rollDate.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);
    return candles.lastIntradayClose(exchange, tradingsymbol, from, to);
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
