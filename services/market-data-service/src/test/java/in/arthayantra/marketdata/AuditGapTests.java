package in.arthayantra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.GapBackfiller;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import in.arthayantra.marketdata.kite.ticker.EodBackfillJob;
import in.arthayantra.marketdata.kite.ticker.FuturesPinner;
import in.arthayantra.marketdata.kite.ticker.SubscriptionRegistry;
import in.arthayantra.marketdata.options.OptionsChainService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Spec-demanded unit coverage the Stage-B audit found missing. */
class AuditGapTests {

  // ---- Phase 15: 'Unit: PCR computation' ----

  @Test
  void putCallRatioMath() throws Exception {
    var method =
        OptionsChainService.class.getDeclaredMethod("putCallRatio", long.class, long.class);
    method.setAccessible(true);
    assertThat((BigDecimal) method.invoke(null, 100L, 150L)).isEqualByComparingTo("1.5000");
    assertThat((BigDecimal) method.invoke(null, 3L, 1L)).isEqualByComparingTo("0.3333");
    assertThat((BigDecimal) method.invoke(null, 0L, 500L)).isNull(); // no CE OI → undefined
  }

  // ---- Phase 15A: 'front/next/far resolution incl. expiry-week rollover' ----

  private static final InstrumentTokenResolver RESOLVER =
      key ->
          Optional.of(
              new InstrumentTokenResolver.TokenInfo(
                  Math.floorMod(key.tradingsymbol().hashCode(), 100_000), "FUT", "NFO-FUT"));

  @Test
  void futuresPinnerRollsOverAtExpiry() {
    List<FuturesContractSource.FutContract> ladder =
        new ArrayList<>(
            List.of(
                fut("JUNFUT", "2026-06-25"),
                fut("JULFUT", "2026-07-28"),
                fut("AUGFUT", "2026-08-25"),
                fut("SEPFUT", "2026-09-29")));
    FuturesContractSource source =
        (underlying, onOrAfter) ->
            ladder.stream().filter(c -> !c.expiry().isBefore(onOrAfter)).toList();
    var clockRef = new java.util.concurrent.atomic.AtomicReference<>(instantOf("2026-06-15"));
    Clock clock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return clockRef.get();
          }
        };
    SubscriptionRegistry registry =
        new SubscriptionRegistry(RESOLVER, 3_000, new SimpleMeterRegistry());
    FuturesPinner pinner =
        new FuturesPinner(registry, source, clock, List.of("NIFTY 50"), new SimpleMeterRegistry());

    pinner.repin(); // before expiry: JUN/JUL/AUG pinned
    assertThat(pinner.pinnedContracts())
        .extracting(InstrumentKey::tradingsymbol)
        .containsExactlyInAnyOrder("JUNFUT", "JULFUT", "AUGFUT");

    clockRef.set(instantOf("2026-06-26")); // the day after the JUN expiry
    pinner.repin();
    assertThat(pinner.pinnedContracts())
        .as("rollover re-pins the new far month and drops the dead contract")
        .extracting(InstrumentKey::tradingsymbol)
        .containsExactlyInAnyOrder("JULFUT", "AUGFUT", "SEPFUT");
  }

  private static FuturesContractSource.FutContract fut(String symbol, String expiry) {
    return new FuturesContractSource.FutContract(
        new InstrumentKey("NFO", symbol), LocalDate.parse(expiry));
  }

  private static Instant instantOf(String date) {
    return OffsetDateTime.parse(date + "T11:00:00+05:30").toInstant();
  }

  // ---- Phase 16: 'each schedule fires only inside its calendar window' ----

  @Test
  void eodBackfillScheduleIsCalendarGated() {
    List<String> backfills = new ArrayList<>();
    GapBackfiller recorder =
        new GapBackfiller() {
          @Override
          public void backfill(InstrumentKey key, Instant from, Instant to) {}

          @Override
          public void prefetch(InstrumentKey key, String baseInterval, Instant from, Instant to) {
            backfills.add(key.canonical() + ":" + baseInterval);
          }
        };
    SubscriptionRegistry registry =
        new SubscriptionRegistry(RESOLVER, 3_000, new SimpleMeterRegistry());
    registry.subscribe(
        "ui",
        new InstrumentKey("NSE", "RELIANCE"),
        in.arthayantra.marketdata.kite.ticker.SubscriptionMode.QUOTE,
        in.arthayantra.marketdata.kite.ticker.SubscriptionPriority.UI);

    // Sunday 2026-06-14: the cron gate must short-circuit before any backfill work
    Clock sunday = Clock.fixed(instantOf("2026-06-14"), ZoneOffset.UTC);
    FuturesPinner pinner =
        new FuturesPinner(
            registry, (u, d) -> List.of(), sunday, List.of(), new SimpleMeterRegistry());
    new EodBackfillJob(
            registry, pinner, recorder, in.arthayantra.marketcalendar.MarketCalendar.nse(), sunday)
        .scheduledEodBackfill();
    assertThat(backfills).isEmpty();

    // Monday 2026-06-15 (trading day): the same schedule runs the pass
    Clock monday = Clock.fixed(instantOf("2026-06-15"), ZoneOffset.UTC);
    new EodBackfillJob(
            registry, pinner, recorder, in.arthayantra.marketcalendar.MarketCalendar.nse(), monday)
        .scheduledEodBackfill();
    assertThat(backfills).isNotEmpty();
  }
}
