package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.feed.LastTickStore;
import in.arthayantra.marketdata.feed.NormalizedTick;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import in.arthayantra.marketdata.kite.ticker.SubscriptionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Cross-vendor review Major: the RECURRING repin must not buy its correctness with REST quota.
 *
 * <p>Every five minutes, a full {@code chain()} per underlying costs a spot quote plus a batched
 * quote over every strike in the expiry — drawn from the same ~60/min Kite budget as futures-OI
 * capture. The band needs neither: it needs the strike ladder (instrument master) and the spot (the
 * underlying's own tick, already subscribed).
 *
 * <p>⚠️ The load-bearing assertion here is a {@code never()} on the chain service. A test that only
 * checked the resulting pin set would pass identically whether or not the fetch happened — the pins
 * are the same either way, which is exactly the point of the change and exactly what makes the
 * cheap assertion useless.
 */
class OptionAtmPinnerTickPathTest {

  private static final LocalDate NEAR = LocalDate.now().plusDays(2);
  private static final String UNDERLYING = "NIFTY 50";

  @Test
  void aPassServedFromTheLastTickIssuesNoChainFetchAtAll() {
    Harness h = harness(new BigDecimal("25000"));

    h.pinner().repin();

    verify(h.chains(), never()).chain(anyString(), any());
    assertThat(h.pinner().pinnedContracts())
        .as("and it is a real band, not an empty one dressed up as a saving")
        .isNotEmpty();
  }

  /** The band still centres on spot — the saving must not cost the behaviour it is saving for. */
  @Test
  void theBandCentresOnTheTickAndFollowsItWhenItMoves() {
    Harness h = harness(new BigDecimal("25000"));
    h.pinner().repin();
    List<String> atOpen = symbols(h);

    // Intraday drift, delivered the way it really arrives: a new tick, no new instrument master.
    h.ticks().update(tick(new BigDecimal("25600")));
    h.pinner().repin();
    List<String> later = symbols(h);

    assertThat(atOpen).isNotEqualTo(later);
    assertThat(atOpen).contains("NFO:NIFTY25000CE");
    assertThat(later)
        .as("the band re-centred on the NEW spot")
        .contains("NFO:NIFTY25600CE")
        .doesNotContain("NFO:NIFTY24000CE");
    verify(h.chains(), never()).chain(anyString(), any());
  }

  /**
   * The fallback is REACHABLE, and this is the case that needs it: at boot nothing has ticked yet,
   * so the only way to produce a band at all is the chain fetch. A fast path that silently returned
   * an empty band here would strand the whole morning.
   */
  @Test
  void withNoTickYetItFallsBackToTheChainFetch() {
    Harness h = harness(null);

    h.pinner().repin();

    verify(h.chains()).chain(UNDERLYING, NEAR);
  }

  /** Nor may an instrument master that has no rows for the expiry be read as "nothing wanted". */
  @Test
  void anEmptyLadderAlsoFallsBackRatherThanPinningNothing() {
    Harness h = harness(new BigDecimal("25000"));
    when(h.instruments().optionChain(anyString(), any())).thenReturn(List.of());

    h.pinner().repin();

    verify(h.chains()).chain(UNDERLYING, NEAR);
  }

  /**
   * Review round 2, Minor: the cron test only PARSED the annotation, so nothing pinned the gate
   * that actually decides. The expression cannot express a holiday at all — only
   * {@code MarketCalendar.isOpen} can — so a parse-only test leaves the entire reason the gate
   * moved into code unverified.
   *
   * <p>Invoking {@code repinDuringSession()} directly is the point: it is the production entry
   * point, and the annotation is not.
   */
  @Test
  void theSessionGateRefusesAHolidayAndAdmitsAnOpenSession() {
    // ⚠️ VERIFIED ON THE EXECUTOR'S WORK, NOT ON pinnedContracts(). repinDuringSession()
    // dispatches OFF-THREAD, so reading the pin set straight after the call races the pass:
    // the first version of this test asserted exactly that and its open-session control failed
    // for the RACE, not for the gate. Worse, the two refusal cases would have passed under the
    // same race — an unfinished pass and a refused one look identical from the pin set. A
    // Mockito timeout()/after() pair is what separates "refused" from "not finished yet".

    // 2026-01-26 is Republic Day, a Monday — a weekday the CRON happily admits and the CALENDAR
    // does not. That gap is the whole reason the gate moved into code.
    Harness holiday = harnessAt("2026-01-26T04:00:00Z"); // 09:30 IST
    holiday.pinner().repinDuringSession();
    verify(holiday.instruments(), after(300).never()).optionChain(anyString(), any());

    Harness preOpen = harnessAt("2026-08-31T03:00:00Z"); // 08:30 IST, a Monday
    preOpen.pinner().repinDuringSession();
    verify(preOpen.instruments(), after(300).never()).optionChain(anyString(), any());

    // The control, and the half that matters most: a gate that refuses EVERYTHING would satisfy
    // both cases above and reinstate the frozen band this change exists to fix.
    Harness open = harnessAt("2026-08-31T05:00:00Z"); // 10:30 IST, a Monday
    open.pinner().repinDuringSession();
    verify(open.instruments(), timeout(3_000)).optionChain(UNDERLYING, NEAR);
  }

  /**
   * ⚠️ A DELIBERATE DIVERGENCE from the chain path, found by re-deriving the equivalence claim
   * rather than accepting it.
   *
   * <p>{@code OptionsChainService.computeLeg} returns {@code null} when the strike has no quote,
   * and {@code addLeg} skips a null leg — so the chain path silently REFUSED TO PIN any contract
   * that was not already quoted. For an illiquid strike that is circular: an unpinned contract
   * never ticks, so it never gets quoted, so it never gets pinned. It is precisely the strikes
   * H44 is about.
   *
   * <p>The tick path pins from the instrument master and does not consult quotes at all, so it
   * pins strictly MORE. That is the intended direction and the cap pressure is nil (tens of
   * contracts against a 3000 cap) — but it is a behaviour change, not a pure optimisation, and it
   * is recorded here so it is not rediscovered as a surprise.
   */
  @Test
  void theTickPathPinsAnUnquotedStrikeThatTheChainPathWouldHaveSkipped() {
    Harness h = harness(new BigDecimal("25000"));

    h.pinner().repin();

    assertThat(symbols(h))
        .as("no quote exists for ANY strike here — the chain path would have pinned nothing")
        .contains("NFO:NIFTY25000CE", "NFO:NIFTY25000PE");
  }

  // ---------------------------------------------------------------- harness

  private record Harness(
      OptionAtmPinner pinner,
      OptionsChainService chains,
      InstrumentRepository instruments,
      LastTickStore ticks) {}

  private static List<String> symbols(Harness h) {
    return h.pinner().pinnedContracts().stream().map(InstrumentKey::canonical).sorted().toList();
  }

  private static NormalizedTick tick(BigDecimal price) {
    return new NormalizedTick(
        "NSE", UNDERLYING, price, 0L, null, OffsetDateTime.now(), 1L);
  }

  /** The tick-path harness with the clock pinned to an instant, for the session gate. */
  private static Harness harnessAt(String instant) {
    return harness(new BigDecimal("25000"), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
  }

  /** @param spot the underlying's last tick, or null for "nothing has ticked yet" */
  private static Harness harness(BigDecimal spot) {
    return harness(spot, Clock.systemUTC());
  }

  private static Harness harness(BigDecimal spot, Clock clock) {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin(UNDERLYING, 7)).thenReturn(List.of(NEAR));
    when(chains.chain(anyString(), any()))
        .thenReturn(
            new OptionsChainService.Chain(
                UNDERLYING, NEAR, new BigDecimal("25000"), null, null, BigDecimal.ZERO, null,
                false, false, OffsetDateTime.now(), List.of()));

    InstrumentRepository instruments = mock(InstrumentRepository.class);
    when(instruments.optionChain(UNDERLYING, NEAR)).thenReturn(ladder());

    LastTickStore ticks = new LastTickStore();
    if (spot != null) {
      ticks.update(tick(spot));
    }

    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    long token = 1;
    for (Instrument contract : ladder()) {
      master.put(
          contract.exchange() + ":" + contract.tradingsymbol(),
          new InstrumentTokenResolver.TokenInfo(token++, contract.instrumentType(), "NFO-OPT"));
    }
    SubscriptionRegistry registry =
        new SubscriptionRegistry(
            key -> Optional.ofNullable(master.get(key.canonical())), 3_000, new SimpleMeterRegistry());

    MeterRegistry meters = new SimpleMeterRegistry();
    OptionAtmPinner pinner =
        new OptionAtmPinner(
            registry,
            chains,
            List.of(UNDERLYING),
            2,
            7,
            instruments,
            ticks,
            meters,
            clock);
    return new Harness(pinner, chains, instruments, ticks);
  }

  /** A realistic ladder: 100-point strikes either side of 25000, CE and PE per strike. */
  private static List<Instrument> ladder() {
    List<Instrument> rows = new ArrayList<>();
    for (int strike = 24_000; strike <= 26_000; strike += 100) {
      for (String type : List.of("CE", "PE")) {
        rows.add(
            new Instrument(
                "NFO",
                "NIFTY" + strike + type,
                null,
                null,
                "NFO-OPT",
                type,
                "NSE",
                UNDERLYING,
                NEAR,
                new BigDecimal(strike),
                null,
                75,
                true,
                false));
      }
    }
    return rows;
  }
}
