package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Book equity must see a CASH EQUITY position's unrealized P&amp;L.
 *
 * <p>The defect these pin (measured live 2026-08-13): {@code unrealizedTotal} marked every position
 * through the Redis {@code ticks:last} hash and fell back to {@code avgEntryPrice} on a miss. That
 * hash is written from the live WS ticker, whose subscription is the futures/options universe —
 * {@code HLEN ticks:last} = 307, every entry an NFO/BFO contract, not one an NSE cash equity. So all
 * 18 open swing positions marked at their own entry price, contributed EXACTLY ZERO unrealized, and
 * book equity was blind to +₹27,213.97 — the denominator every equity-percentage risk gate divides
 * by.
 *
 * <p>Figures below are the real manas-arora book as of session 2026-08-12.
 */
class PaperAccountServiceEquityMarkTest {

  private static final String BOOK = "manas-arora";
  private static final BigDecimal STARTING_CAPITAL = new BigDecimal("150000.00");
  private static final BigDecimal REALIZED = new BigDecimal("-7053.62");
  private static final LocalDate SESSION = LocalDate.of(2026, 8, 12);

  /** The six live manas positions: symbol, qty, avgEntry, close on 2026-08-12. */
  private static final Object[][] MANAS_BOOK = {
    {"AVALON", 8L, "1760.3800", "1973.7000"},
    {"KANORICHEM", 100L, "152.2900", "157.2900"},
    {"PRECOT", 18L, "810.3500", "799.7500"},
    {"SANSERA", 6L, "3336.8700", "3925.0000"},
    {"SCPL", 23L, "615.3100", "650.0000"},
    {"SKYGOLD", 24L, "723.3600", "838.0000"},
  };

  private record Fixture(PaperAccountService service, EquityMarkCache marks) {}

  private static PositionRow row(long id, String symbol, long qty, String avgEntry) {
    return new PositionRow(
        id, "NSE", symbol, "BUY", qty, new BigDecimal(avgEntry), BigDecimal.ZERO, "OPEN",
        OffsetDateTime.parse("2026-07-20T14:35:00Z"), null, null, new BigDecimal("1.00"), null, BOOK);
  }

  private static Fixture fixture(LastTickReader ticks) {
    PaperAccountRepository accounts = mock(PaperAccountRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    EquityMarkCache marks =
        new EquityMarkCache(Clock.fixed(Instant.parse("2026-08-13T03:05:00Z"), ZoneOffset.UTC), 5);

    when(accounts.get(any()))
        .thenReturn(new PaperAccountRepository.Account(STARTING_CAPITAL, STARTING_CAPITAL, null));
    when(positions.realizedTotal(any())).thenReturn(REALIZED);

    List<PositionRow> open = new java.util.ArrayList<>();
    long id = 1;
    for (Object[] p : MANAS_BOOK) {
      open.add(row(id++, (String) p[0], (Long) p[1], (String) p[2]));
    }
    when(positions.listOpen(any(String.class))).thenReturn(open);
    when(positions.listOpen()).thenReturn(open);

    PaperAccountService service =
        new PaperAccountService(
            accounts, positions, ticks, marks, mock(InstrumentMetaClient.class),
            mock(MarginServiceClient.class),
            Clock.fixed(Instant.parse("2026-08-13T03:05:00Z"), ZoneOffset.UTC),
            new BigDecimal("0.15"), new BigDecimal("0.12"));
    return new Fixture(service, marks);
  }

  /** A tick reader that, like production for cash equities, knows nothing. */
  private static LastTickReader noTicks() {
    LastTickReader ticks = mock(LastTickReader.class);
    when(ticks.lastPrice(any(), any())).thenReturn(Optional.empty());
    return ticks;
  }

  private static void warmAllMarks(EquityMarkCache marks) {
    for (Object[] p : MANAS_BOOK) {
      marks.put("NSE", (String) p[0], new BigDecimal((String) p[3]), SESSION);
    }
  }

  // ---- the defect ------------------------------------------------------------------------------

  /**
   * The status-quo case, kept as the explicit baseline: with no tick AND no captured close, equity is
   * still computable and still marks at cost — the fallback is deliberately NOT removed (a null
   * equity would break the account API, the sizing path and every risk gate). What must NOT happen is
   * that this state is indistinguishable from a fully-marked book.
   */
  @Test
  void withNoTickAndNoCapturedCloseUnrealizedIsZeroAndEveryPositionReportsUnmarked() {
    Fixture f = fixture(noTicks());

    assertThat(f.service().unrealizedTotal(BOOK))
        .as("no mark of any kind — every position valued at its own entry")
        .isEqualByComparingTo("0.00");
    assertThat(f.service().unmarkedOpenCount(BOOK))
        .as("and the blindness is COUNTED, not silent")
        .isEqualTo(6);
  }

  /**
   * The fix. The same six positions, marked from the captured daily closes, produce the book's real
   * unrealized — the figure measured live on 2026-08-12 closes.
   */
  @Test
  void capturedDailyClosesGiveTheBookItsRealUnrealizedPnl() {
    Fixture f = fixture(noTicks());
    warmAllMarks(f.marks());

    assertThat(f.service().unrealizedTotal(BOOK))
        .as("manas-arora unrealized on 2026-08-12 closes")
        .isEqualByComparingTo("9093.77");
    assertThat(f.service().unmarkedOpenCount(BOOK)).as("all six marked").isZero();
  }

  /**
   * The consequence that actually matters: equity is the denominator of the Manas 6% open-risk cap,
   * {@code max_deployment_pct}, and every {@code mode: pct} daily limit. Blind it read ₹142,946.38.
   */
  @Test
  void equityGrowsByTheMarkedUnrealizedWhichIsTheRiskCapDenominator() {
    Fixture blind = fixture(noTicks());
    Fixture marked = fixture(noTicks());
    warmAllMarks(marked.marks());

    assertThat(blind.service().equity(BOOK))
        .as("the denominator the live cap divided by")
        .isEqualByComparingTo("142946.38");
    assertThat(marked.service().equity(BOOK))
        .as("+ the ₹9,093.77 it could not see")
        .isEqualByComparingTo("152040.15");
  }

  // ---- resolution order ------------------------------------------------------------------------

  /**
   * A live tick WINS over a captured close. This is what keeps the scalper book byte-identical: its
   * option positions always tick, so the cache is never consulted for them and nothing changes.
   */
  @Test
  void aLiveTickTakesPrecedenceOverTheCapturedClose() {
    LastTickReader ticks = mock(LastTickReader.class);
    when(ticks.lastPrice(any(), any())).thenReturn(Optional.empty());
    when(ticks.lastPrice("NSE", "SANSERA")).thenReturn(Optional.of(new BigDecimal("4000.0000")));

    Fixture f = fixture(ticks);
    warmAllMarks(f.marks()); // SANSERA's captured close is 3925.00

    assertThat(f.service().markFor("NSE", "SANSERA"))
        .as("the tick, not the stale-by-a-session close")
        .contains(new BigDecimal("4000.0000"));
    // SANSERA at 4000 instead of 3925 adds 6 × 75.00 = 450.00 to the book's unrealized.
    assertThat(f.service().unrealizedTotal(BOOK))
        .as("the ticked position marks at the tick; the other five at their captured closes")
        .isEqualByComparingTo("9543.77");
  }

  /**
   * Superseded characterization (owner decision, 2026-08-13). This used to assert PARTIAL summing —
   * the two marked positions contributing 6,280.14 while the other four scored zero. That is exactly
   * the shape that let a marked winner sit beside an unmarked loser and report equity above the
   * truth, so a partially-warmed book now withholds entirely.
   */
  @Test
  void aPartiallyWarmedCacheWithholdsTheWholeBook() {
    Fixture f = fixture(noTicks());
    f.marks().put("NSE", "SANSERA", new BigDecimal("3925.0000"), SESSION);
    f.marks().put("NSE", "SKYGOLD", new BigDecimal("838.0000"), SESSION);

    assertThat(f.service().unmarkedOpenCount(BOOK)).as("four of six still blind").isEqualTo(4);
    assertThat(f.service().unrealizedTotal(BOOK))
        .as("no partial sum — the whole book is withheld rather than mixing marked and cost-valued")
        .isEqualByComparingTo("0.00");
    assertThat(f.service().unrealizedWithheld(BOOK)).isTrue();
  }

  /** A stale mark is refused rather than served, and the position reverts to being counted blind. */
  @Test
  void aStaleCapturedCloseIsNotUsedAsAMark() {
    PaperAccountRepository accounts = mock(PaperAccountRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    when(accounts.get(any()))
        .thenReturn(new PaperAccountRepository.Account(STARTING_CAPITAL, STARTING_CAPITAL, null));
    when(positions.realizedTotal(any())).thenReturn(REALIZED);
    when(positions.listOpen(any(String.class))).thenReturn(List.of(row(1, "SANSERA", 6, "3336.8700")));

    Clock clock = Clock.fixed(Instant.parse("2026-08-13T03:05:00Z"), ZoneOffset.UTC);
    EquityMarkCache marks = new EquityMarkCache(clock, 5);
    // Captured RIGHT NOW, but off a month-old bar — the shape a pinned catch-up run produces.
    marks.put("NSE", "SANSERA", new BigDecimal("3925.0000"), LocalDate.of(2026, 7, 10));

    PaperAccountService service =
        new PaperAccountService(
            accounts, positions, noTicks(), marks, mock(InstrumentMetaClient.class),
            mock(MarginServiceClient.class), clock, new BigDecimal("0.15"), new BigDecimal("0.12"));

    assertThat(service.unrealizedTotal(BOOK))
        .as("a stale close is never served into a money figure")
        .isEqualByComparingTo("0.00");
    assertThat(service.unmarkedOpenCount(BOOK)).isEqualTo(1);
  }

  /**
   * A close from BEFORE the position existed is not a stale price, it is an unrelated one — valuing
   * against it manufactures P&L out of the gap. The session bound alone permits this: a mark may be
   * several sessions old, which is older than a position opened this morning (cross-vendor review).
   */
  @Test
  void aMarkFromBeforeThePositionOpenedIsNotUsed() {
    PaperAccountRepository accounts = mock(PaperAccountRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    when(accounts.get(any()))
        .thenReturn(new PaperAccountRepository.Account(STARTING_CAPITAL, STARTING_CAPITAL, null));
    when(positions.realizedTotal(any())).thenReturn(REALIZED);
    // Opened 2026-08-12 IST (2026-08-12T04:00Z = 09:30 IST).
    PositionRow openedYesterday =
        new PositionRow(
            1, "NSE", "SANSERA", "BUY", 6, new BigDecimal("3336.8700"), BigDecimal.ZERO, "OPEN",
            OffsetDateTime.parse("2026-08-12T04:00:00Z"), null, null, new BigDecimal("1.00"), null,
            BOOK);
    when(positions.listOpen(any(String.class))).thenReturn(List.of(openedYesterday));

    Clock clock = Clock.fixed(Instant.parse("2026-08-13T03:05:00Z"), ZoneOffset.UTC);
    EquityMarkCache marks = new EquityMarkCache(clock, 5);
    marks.put("NSE", "SANSERA", new BigDecimal("3100.0000"), LocalDate.of(2026, 8, 10)); // pre-entry

    PaperAccountService service =
        new PaperAccountService(
            accounts, positions, noTicks(), marks, mock(InstrumentMetaClient.class),
            mock(MarginServiceClient.class), clock, new BigDecimal("0.15"), new BigDecimal("0.12"));

    assertThat(service.unrealizedTotal(BOOK))
        .as("a pre-entry close must not become this position's mark")
        .isEqualByComparingTo("0.00");
    assertThat(service.unmarkedOpenCount(BOOK)).isEqualTo(1);
  }

  // ---- all-or-nothing marks, per BOOK (owner decision 2026-08-13) -------------------------------

  private static PaperAccountService svc(
      PaperPositionRepository positions, EquityMarkCache marks, LastTickReader ticks) {
    PaperAccountRepository accounts = mock(PaperAccountRepository.class);
    when(accounts.get(any()))
        .thenReturn(new PaperAccountRepository.Account(STARTING_CAPITAL, STARTING_CAPITAL, null));
    when(positions.realizedTotal(any())).thenReturn(BigDecimal.ZERO);
    Clock clock = Clock.fixed(Instant.parse("2026-08-13T03:05:00Z"), ZoneOffset.UTC);
    return new PaperAccountService(
        accounts, positions, ticks, marks, mock(InstrumentMetaClient.class),
        mock(MarginServiceClient.class), clock, new BigDecimal("0.15"), new BigDecimal("0.12"));
  }

  private static PositionRow row(long id, String book, String sym, long qty, String avgEntry) {
    return new PositionRow(
        id, "NSE", sym, "BUY", qty, new BigDecimal(avgEntry), BigDecimal.ZERO, "OPEN",
        OffsetDateTime.parse("2026-07-20T14:35:00Z"), null, null, new BigDecimal("1.00"), null, book);
  }

  /**
   * THE SIGN CASE — the whole reason all-or-nothing exists. A book whose MARKED position is a WINNER
   * and whose UNMARKED position is a LOSER reported equity ABOVE the truth under per-position
   * fallback (the loser's loss was erased), loosening every {@code mode: pct} rail. Withholding the
   * whole book must land BELOW the all-marked figure, never above it.
   */
  /**
   * The mirror of the test below, and the case the all-or-nothing version got WRONG (cross-vendor
   * review Critical, 2026-08-21).
   *
   * <p>When the MARKED subset is net-negative, a flat ZERO is ABOVE the truth — so the book's own
   * daily-loss rail sees a healthier number than reality, on the one book that has such a rail
   * enabled ({@code scalper}: 20 option slots, {@code daily_loss_limit} + {@code
   * daily_profit_target}, both evaluated against {@code dayPnl = realized + unrealizedTotal}). One
   * unmarkable option among twenty would have erased the whole book's open loss from its own stop.
   *
   * <p>⚠️ No existing test could see this: every other fixture here ends fully marked, or with a
   * net-POSITIVE marked subset where withholding to 0 is correct. The defect lives only in the
   * mixed-and-losing state.
   */
  @Test
  void aMeasuredLossIsNeverDiscardedByWithholding() {
    PositionRow loser = row(1, BOOK, "LOSER", 10, "100.00"); // marks at 40 -> -600
    PositionRow blind = row(2, BOOK, "BLIND", 10, "100.00"); // no mark of any kind
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    when(positions.listOpen(any(String.class))).thenReturn(List.of(loser, blind));
    when(positions.listOpen(null)).thenReturn(List.of(loser, blind));

    EquityMarkCache loserOnly = new EquityMarkCache(
        Clock.fixed(Instant.parse("2026-08-13T03:05:00Z"), ZoneOffset.UTC), 5);
    loserOnly.put("NSE", "LOSER", new BigDecimal("40.00"), SESSION);
    PaperAccountService degraded = svc(positions, loserOnly, noTicks());

    assertThat(degraded.unrealizedTotal(BOOK))
        .as("a flat ZERO here reports the book as FLAT while it is down 600 — fail-OPEN on the rail"
            + " that is supposed to stop the day. The measured loss must survive.")
        .isEqualByComparingTo("-600.00");
    assertThat(degraded.equity(BOOK))
        .as("and equity must come DOWN by it, not stay at the blind figure")
        .isEqualByComparingTo("149400.00");
    assertThat(degraded.unrealizedWithheld(BOOK))
        .as("still flagged degraded — reporting the loss does not mean the book is fully marked")
        .isTrue();
  }

  @Test
  void anUnmarkedLoserMustNotPushEquityAboveTheFullyMarkedFigure() {
    PositionRow winner = row(1, BOOK, "WINNER", 10, "100.00"); // marks at 150 -> +500
    PositionRow loser = row(2, BOOK, "LOSER", 10, "100.00"); //  marks at  40 -> -600
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    when(positions.listOpen(any(String.class))).thenReturn(List.of(winner, loser));
    when(positions.listOpen(null)).thenReturn(List.of(winner, loser));

    EquityMarkCache both = new EquityMarkCache(
        Clock.fixed(Instant.parse("2026-08-13T03:05:00Z"), ZoneOffset.UTC), 5);
    both.put("NSE", "WINNER", new BigDecimal("150.00"), SESSION);
    both.put("NSE", "LOSER", new BigDecimal("40.00"), SESSION);
    BigDecimal fullyMarked = svc(positions, both, noTicks()).equity(BOOK);

    EquityMarkCache winnerOnly = new EquityMarkCache(
        Clock.fixed(Instant.parse("2026-08-13T03:05:00Z"), ZoneOffset.UTC), 5);
    winnerOnly.put("NSE", "WINNER", new BigDecimal("150.00"), SESSION);
    PaperAccountService degraded = svc(positions, winnerOnly, noTicks());

    assertThat(fullyMarked)
        .as("truth: +500 winner, -600 loser = -100 unrealized")
        .isEqualByComparingTo("149900.00");
    assertThat(degraded.unrealizedTotal(BOOK))
        .as("the marked subset is net-POSITIVE (+500) — a positive partial is the cherry-pick"
                + " this refuses, since the unmarked row could be a loser, so it withholds to 0")
        .isEqualByComparingTo("0.00");
    assertThat(degraded.equity(BOOK))
        .as(
            "per-position fallback reported 150500.00 here — ABOVE the 149900.00 truth, because the"
                + " loser's -600 was erased while the winner's +500 was kept. That is the defect.")
        .isLessThan(new BigDecimal("150500.00"));
    assertThat(degraded.unrealizedWithheld(BOOK)).as("and it is not silent").isTrue();
  }

  /** One book's missing mark must not zero another book's unrealized. */
  @Test
  void withholdingIsPerBookNotGlobal() {
    PositionRow manasOk = row(1, "manas-arora", "AAA", 10, "100.00"); // marked -> +200
    PositionRow minerviniGap = row(2, "minervini", "BBB", 10, "100.00"); // unmarked
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    when(positions.listOpen("manas-arora")).thenReturn(List.of(manasOk));
    when(positions.listOpen("minervini")).thenReturn(List.of(minerviniGap));
    when(positions.listOpen(null)).thenReturn(List.of(manasOk, minerviniGap));

    EquityMarkCache marks = new EquityMarkCache(
        Clock.fixed(Instant.parse("2026-08-13T03:05:00Z"), ZoneOffset.UTC), 5);
    marks.put("NSE", "AAA", new BigDecimal("120.00"), SESSION);
    PaperAccountService s = svc(positions, marks, noTicks());

    assertThat(s.unrealizedTotal("manas-arora"))
        .as("a fully-marked book keeps its unrealized")
        .isEqualByComparingTo("200.00");
    assertThat(s.unrealizedTotal("minervini")).as("the gapped book withholds").isEqualByComparingTo("0.00");
    assertThat(s.unrealizedTotal(null))
        .as("the aggregate SUMS per-book results — one gap must not zero the other book")
        .isEqualByComparingTo("200.00");
    assertThat(s.unrealizedWithheld("manas-arora")).isFalse();
    assertThat(s.unrealizedWithheld("minervini")).isTrue();
    assertThat(s.withheldBookCount()).isEqualTo(1);
  }

  /** Fully marked = unchanged behaviour; withholding must not fire on a complete book. */
  @Test
  void aFullyMarkedBookIsUnaffectedByTheAllOrNothingRule() {
    Fixture f = fixture(noTicks());
    warmAllMarks(f.marks());

    assertThat(f.service().unrealizedTotal(BOOK)).isEqualByComparingTo("9093.77");
    assertThat(f.service().unrealizedWithheld(BOOK)).isFalse();
    assertThat(f.service().withheldBookCount()).isZero();
  }
}
