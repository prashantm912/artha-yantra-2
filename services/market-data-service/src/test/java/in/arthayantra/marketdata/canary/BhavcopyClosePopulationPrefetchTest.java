package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.constituents.StaticIndexConstituents;
import in.arthayantra.marketdata.kite.GapBackfiller;
import in.arthayantra.marketdata.kite.InstrumentKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * The close canary now fetches its OWN comparison population instead of reading whatever another
 * job left in {@code candles}.
 *
 * <p>⚠️ Why this exists. #1367 gave the canary a coverage floor so a collapsed population could not
 * read GREEN; the floor is the ALARM. This is the CURE, and it has one property worth pinning above
 * all others: <b>the population pass must actually issue a 1d fetch per constituent</b>. A pass that
 * enumerates zero symbols and returns cleanly is catalogue trap #14 all over again — the guard that
 * checks nothing — and it is exactly what an empty or misnamed reference index produces.
 *
 * <p>A unit test on purpose. The seam under test is enumeration + dispatch + the guards, all of
 * which are pure decisions over injected collaborators; the fetch itself is
 * {@code CandleQueryService.ensureCoverage}, already covered by the cache-first suite, and it is
 * reached here through the same {@link GapBackfiller} port {@code EodBackfillJob} uses.
 */
class BhavcopyClosePopulationPrefetchTest {

  /** 2026-08-13T12:10:00Z is 17:40 IST on 2026-08-13, a Thursday. */
  private static final Instant NOW = Instant.parse("2026-08-13T12:10:00Z");

  /** 2026-08-13T03:30:00Z is 09:00 IST — a normal morning boot, hours before the 16:05 pass. */
  private static final Instant MORNING = Instant.parse("2026-08-13T03:30:00Z");

  private static final LocalDate DAY = LocalDate.of(2026, 8, 13);

  /** The canary's own {@code @Scheduled} default, so the catch-up's "was it due?" matches it. */
  private static final String PREFETCH_CRON = "0 5 16 * * MON-FRI";

  private final StaticIndexConstituents constituents = mock(StaticIndexConstituents.class);
  private final GapBackfiller backfiller = mock(GapBackfiller.class);
  private final MarketCalendar calendar = mock(MarketCalendar.class);
  private final NtfyClient ntfy = mock(NtfyClient.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @Test
  @DisplayName("every constituent gets a 1d fetch bounded to the trade date's IST session")
  void fetchesOneDailyBarPerConstituent() {
    when(constituents.symbols("NIFTY 200")).thenReturn(List.of("RELIANCE", "TCS", "INFY"));

    int attempted = canary().prefetchNow(DAY);

    assertThat(attempted).isEqualTo(3);
    ArgumentCaptor<InstrumentKey> keys = ArgumentCaptor.forClass(InstrumentKey.class);
    ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
    verify(backfiller, times(3)).prefetch(keys.capture(), eq("1d"), from.capture(), to.capture());

    assertThat(keys.getAllValues())
        .as("the canary compares NSE cash closes, so the population must resolve on NSE")
        .containsExactly(
            new InstrumentKey("NSE", "RELIANCE"),
            new InstrumentKey("NSE", "TCS"),
            new InstrumentKey("NSE", "INFY"));
    // IST midnight, not JVM midnight. The JVM here is UTC, so a zone-dropping implementation would
    // pass 2026-08-13T00:00Z — 05:30 IST on the same date, which still LOOKS like the right day and
    // still fetches the right bar. It is pinned anyway because the same slip in the sweep's date
    // arithmetic is what puts a canary a whole session off.
    assertThat(from.getAllValues())
        .containsOnly(DAY.atStartOfDay(Ist.ZONE).toInstant())
        .doesNotContain(DAY.atStartOfDay(ZoneOffset.UTC).toInstant());
    assertThat(to.getAllValues()).containsOnly(NOW);
  }

  /**
   * ⚠️ This test asserts the guard's OWN SIGNAL, and it has to, because everything else about the
   * empty case is indistinguishable from having no guard at all.
   *
   * <p>Delete the {@code symbols.isEmpty()} branch and the loop below it runs zero times, returns
   * the same {@code 0}, and touches the backfiller not at all — so the obvious assertions
   * ({@code isZero()}, {@code verifyNoInteractions}) pass IDENTICALLY with and without it. That is
   * catalogue trap #14 sitting inside the fix for trap #14: a guard that enumerates zero items and
   * reports success. The counter and the alert are the only things that exist ONLY when the guard
   * does, so they are what this pins.
   */
  @Test
  @DisplayName("an empty reference index pages and counts, not just returns zero")
  void anEmptyPopulationIndexFetchesNothingAndSaysSo() {
    when(constituents.symbols("NIFTY 200")).thenReturn(List.of());

    assertThat(canary().prefetchNow(DAY)).isZero();

    verifyNoInteractions(backfiller);
    assertThat(meters.counter("ay_bhavcopy_close_population_empty_total").count())
        .as(
            "an empty reference index must increment a counter of its own — without one, deleting"
                + " the guard entirely leaves every other assertion here green")
        .isEqualTo(1.0);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(ntfy)
        .send(
            eq("ArthaYantra bhavcopy-close population empty"), eq("urgent"), body.capture());
    assertThat(body.getValue())
        .as("the page must name the misconfigured index, not just report a small population")
        .contains("NIFTY 200");
  }

  @Test
  @DisplayName("one symbol's failure does not abort the rest of the population")
  void aPerSymbolFailureDoesNotStopThePass() {
    when(constituents.symbols("NIFTY 200")).thenReturn(List.of("RELIANCE", "TCS", "INFY"));
    // GapBackfiller swallows fetch failures itself; this pins that the canary does not RE-raise
    // around the loop. A Kite outage must shrink the population — which the coverage floor then
    // reports — never truncate it at the first bad symbol and leave the rest unattempted.
    doThrow(new IllegalStateException("kite circuit open"))
        .when(backfiller)
        .prefetch(eq(new InstrumentKey("NSE", "TCS")), anyString(), any(), any());
    BhavcopyCloseCanary canary = canary();

    // Asserted as "does not throw" rather than by reading the return value: without the guard the
    // exception escapes BEFORE any assertion runs, and the test would then fail carrying Kite's
    // message instead of this one — a red that does not name the property it is proving.
    assertThatCode(() -> canary.prefetchNow(DAY))
        .as(
            "a single symbol's fetch failure must not abort the population pass — unhandled, one"
                + " delisted name would leave every later symbol unattempted")
        .doesNotThrowAnyException();

    verify(backfiller).prefetch(eq(new InstrumentKey("NSE", "INFY")), eq("1d"), any(), any());
  }

  @Test
  @DisplayName("the scheduled entry point skips a non-trading day")
  void theScheduledPassSkipsAHoliday() {
    when(calendar.isTradingDay(DAY)).thenReturn(false);

    canary().prefetchPopulation();

    verifyNoInteractions(backfiller);
    verify(constituents, never()).symbols(anyString());
  }

  @Test
  @DisplayName("the scheduled entry point runs on a trading day")
  void theScheduledPassRunsOnATradingDay() {
    when(calendar.isTradingDay(DAY)).thenReturn(true);
    when(constituents.symbols("NIFTY 200")).thenReturn(List.of("RELIANCE"));

    canary().prefetchPopulation();

    verify(backfiller).prefetch(eq(new InstrumentKey("NSE", "RELIANCE")), eq("1d"), any(), any());
  }

  @Test
  @DisplayName("a year outside the bundled calendar is treated as a non-trading day, not a crash")
  void anUncoveredCalendarYearSkipsRatherThanThrows() {
    when(calendar.isTradingDay(DAY))
        .thenThrow(new IllegalArgumentException("NSE holiday calendar covers years [2024, 2026]"));

    canary().prefetchPopulation();

    verifyNoInteractions(backfiller);
  }

  @Test
  @DisplayName("the pass is inert outside the live profile")
  void theScheduledPassIsLiveOnly() {
    BhavcopyCloseCanary mock = canary(new MockEnvironment().withProperty("spring.profiles.active", "mock"), true);

    mock.prefetchPopulation();

    verifyNoInteractions(backfiller, calendar);
  }

  @Test
  @DisplayName("the enabled=false off-switch stops the fetch as well as the sweep")
  void theDisabledFlagAlsoStopsTheFetch() {
    // Otherwise disabling the canary would leave a daily 202-symbol Kite pass running to feed a
    // report nobody produces — an off-switch that turns off the output and not the cost.
    BhavcopyCloseCanary disabled =
        canary(new MockEnvironment().withProperty("spring.profiles.active", "live"), false);

    disabled.prefetchPopulation();

    verifyNoInteractions(backfiller, calendar);
  }

  /**
   * ⚠️ The 16:05 cron only fires while the service is UP at 16:05. Nothing else replays it.
   *
   * <p>This machine is off overnight and the live stack has already spent a whole afternoon down
   * (2026-08-10: no batch 08:29→18:47 IST). On a boot after 16:05 the scheduled pass is simply
   * skipped for that session — and {@code BhavcopyStartupCatchup} fires immediately, so the
   * bhavcopy projection claims today's 1d buckets first and the perfectly-agreeing bars keep
   * {@code source='BHAVCOPY'}. That is the exact ordering inversion the 16:05 hour exists to
   * prevent, arriving through the one door the cron cannot cover.
   */
  @Test
  @DisplayName("a boot after the scheduled slot replays the missed population pass")
  void aLateBootReplaysTheMissedPass() {
    // NOW is 17:40 IST: past the 16:05 pass, and still ahead of NSE's earliest measured 17:52
    // publish — the window where a replay can still win the race against tonight's projection.
    when(calendar.isTradingDay(DAY)).thenReturn(true);
    when(constituents.symbols("NIFTY 200")).thenReturn(List.of("RELIANCE", "TCS"));

    assertThat(canary().catchUpPopulation())
        .as(
            "a boot at 17:40 IST missed the 16:05 pass; without a replay the evening's population"
                + " is whatever other jobs left behind — measured 14 against a floor of 100")
        .isEqualTo(2);

    verify(backfiller).prefetch(eq(new InstrumentKey("NSE", "RELIANCE")), eq("1d"), any(), any());
    verify(backfiller).prefetch(eq(new InstrumentKey("NSE", "TCS")), eq("1d"), any(), any());
  }

  @Test
  @DisplayName("a boot before the scheduled slot leaves the pass to its own cron")
  void anEarlyBootDoesNotReplayAnything() {
    // 09:00 IST. Replaying here fetches 202 IN-PROGRESS 1d bars — 71 s of kite-historical budget
    // for a partial bar that the 16:05 pass then re-fetches anyway (B-4 always refreshes the
    // in-progress bucket). "Catch up whenever we boot" would pay that on every restart.
    when(calendar.isTradingDay(DAY)).thenReturn(true);

    assertThat(canary(live(), true, MORNING).catchUpPopulation()).isZero();

    verifyNoInteractions(backfiller);
    verify(constituents, never()).symbols(anyString());
  }

  @Test
  @DisplayName("the catch-up honours the same holiday / profile / off-switch guards as the cron")
  void theCatchUpHonoursTheSameGuardsAsTheScheduledPass() {
    when(calendar.isTradingDay(DAY)).thenReturn(false);
    assertThat(canary().catchUpPopulation()).isZero();

    assertThat(canary(new MockEnvironment().withProperty("spring.profiles.active", "mock"), true)
            .catchUpPopulation())
        .isZero();
    assertThat(canary(live(), false).catchUpPopulation()).isZero();

    verifyNoInteractions(backfiller);
  }

  /**
   * ⚠️ The two defaults must agree, and nothing else checks that they do. {@code population-index}
   * and {@code min-compared} are independent {@code @Value} knobs, so a typo in the index name, or a
   * reference list that erodes below the floor as NSE rebalances the index out from under a STATIC
   * JSON file, produces a canary that is YELLOW every single night by construction — with a fetch
   * pass that dutifully runs and a report that never certifies anything. That failure is silent in
   * every other test here, because they all inject their own symbol list.
   */
  @Test
  @DisplayName("the default reference index really can satisfy the default coverage floor")
  void theDefaultPopulationClearsTheDefaultFloor() throws java.io.IOException {
    // Read the @Value default out of the source rather than repeating the literal. Every other test
    // here injects "NIFTY 200" explicitly, so a typo in the production default would be invisible to
    // all of them — the guard would pass while the shipped canary enumerated nothing.
    String declaredIndex = declaredPopulationIndexDefault();
    List<String> population =
        new StaticIndexConstituents(new com.fasterxml.jackson.databind.ObjectMapper())
            .symbols(declaredIndex);

    assertThat(population)
        .as(
            "artha.bhavcopy-close.population-index defaults to '%s'; if that key is absent from"
                + " reference/index-constituents.json the pass fetches nothing at all",
            declaredIndex)
        .isNotEmpty();
    assertThat(population.size())
        .as(
            "the default population must clear the default min-compared of 100 with room to spare,"
                + " or the canary is YELLOW every night no matter how healthy the feeds are")
        .isGreaterThanOrEqualTo(150);
  }

  /**
   * ⚠️ The population pass must land its Kite bars BEFORE any bhavcopy projection claims the same
   * 1d buckets, and nothing except this test says so.
   *
   * <p>{@code source} is not in the {@code candles} PK, and {@code upsertAuthoritativeAll} keeps the
   * EXISTING source when every OHLCV field matches. So if bhavcopy writes first, the bars that agree
   * PERFECTLY are precisely the ones that stay {@code source='BHAVCOPY'} and fall out of the
   * canary's population — a sample biased against agreement, judging agreement. Measured
   * 2026-08-05..08-12: bhavcopy close equals Kite close exactly in 22 of 682 dual-sourced rows.
   *
   * <p>Asserted against compose because compose is what production runs ({@code
   * CronPassthroughParityTest} separately pins compose to the {@code @Scheduled} defaults). Nothing
   * here can see a {@code .env} override, so this catches a committed inversion, not an operator's.
   */
  @Test
  @DisplayName("the population fetch is scheduled ahead of the bhavcopy ingest")
  void thePopulationPassRunsBeforeTheBhavcopyIngest() throws java.io.IOException {
    String compose =
        java.nio.file.Files.readString(repoRoot().resolve("deploy/docker-compose.yml"));

    int prefetch = minuteOfDay(compose, "ARTHA_BHAVCOPY_CLOSE_PREFETCH_CRON");
    int ingest = minuteOfDay(compose, "ARTHA_BHAVCOPY_EOD_CRON");

    assertThat(prefetch)
        .as(
            "the close canary's population fetch (%02d:%02d) must run before the bhavcopy ingest"
                + " (%02d:%02d), or the bars that agree perfectly keep source='BHAVCOPY' and drop"
                + " out of the very population used to judge agreement",
            prefetch / 60, prefetch % 60, ingest / 60, ingest % 60)
        .isLessThan(ingest);
  }

  /** The {@code artha.bhavcopy-close.population-index} default as the canary actually declares it. */
  private static String declaredPopulationIndexDefault() throws java.io.IOException {
    String source =
        java.nio.file.Files.readString(
            repoRoot()
                .resolve(
                    "services/market-data-service/src/main/java/in/arthayantra/marketdata/canary/"
                        + "BhavcopyCloseCanary.java"));
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "\\$\\{artha\\.bhavcopy-close\\.population-index:([^}]*)}")
            .matcher(source);
    assertThat(m.find())
        .as("BhavcopyCloseCanary no longer declares an artha.bhavcopy-close.population-index default")
        .isTrue();
    return m.group(1);
  }

  /** Minute-of-day from a compose {@code NAME: "${NAME:-0 m H * * MON-FRI}"} line. */
  private static int minuteOfDay(String compose, String envName) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "^\\s*" + envName + ":\\s*\"?\\$\\{" + envName + ":-0 (\\d+) (\\d+) ",
                java.util.regex.Pattern.MULTILINE)
            .matcher(compose);
    assertThat(m.find()).as("no compose passthrough for %s", envName).isTrue();
    return Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(1));
  }

  private static java.nio.file.Path repoRoot() {
    java.nio.file.Path path = java.nio.file.Paths.get("").toAbsolutePath();
    while (path != null && !java.nio.file.Files.exists(path.resolve("deploy/docker-compose.yml"))) {
      path = path.getParent();
    }
    assertThat(path).as("could not locate the repo root from the test working directory").isNotNull();
    return path;
  }

  private BhavcopyCloseCanary canary() {
    return canary(live(), true);
  }

  private BhavcopyCloseCanary canary(MockEnvironment environment, boolean enabled) {
    return canary(environment, enabled, NOW);
  }

  private BhavcopyCloseCanary canary(MockEnvironment environment, boolean enabled, Instant now) {
    return new BhavcopyCloseCanary(
        mock(JdbcTemplate.class),
        ntfy,
        Clock.fixed(now, ZoneOffset.UTC),
        constituents,
        backfiller,
        calendar,
        meters,
        environment,
        enabled,
        true,
        new BigDecimal("0.01"),
        20,
        25,
        100,
        "NIFTY 200",
        PREFETCH_CRON);
  }

  private static MockEnvironment live() {
    return new MockEnvironment().withProperty("spring.profiles.active", "live");
  }
}
