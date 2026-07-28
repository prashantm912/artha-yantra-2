package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.RailCheck;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.RejectionDiagnostic;
import in.arthayantra.strategysignal.scalper.StrikePicker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Challenger-variant IT (roadmap F1, real V017 lineage): a volume-floor-blocked rejection whose
 * composite passed opens BOTH the champion book and the vol-off challenger (each tagged), dedup is
 * per (strategy, side, variant), a rail the variant does not override still vetoes it, and the
 * league rollup groups by variant. Shared singleton DB — every slug/symbol unique per method.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.scalper.shadow-book.enabled=true",
      "artha.scalper.shadow-book.poll-ms=3600000",
      "artha.scalper.shadow-book.variants-json="
          + "[{\"name\":\"vol-off\",\"rails\":[{\"rail\":\"volume-floor\",\"disable\":true}]},"
          + "{\"name\":\"vol-12k5\",\"rails\":[{\"rail\":\"volume-floor\",\"threshold\":12500,\"passWhen\":\"GTE\"}]}]"
    })
class ShadowVariantsIntegrationTest extends in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase {

  @Autowired private ShadowBookService shadowBook;
  @Autowired private ShadowPositionRepository shadows;
  @Autowired private SignalRejectionRepository rejections;
  @Autowired private JdbcTemplate jdbc;

  private UUID versionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }

  private long rejection(UUID versionId, String slug) {
    return rejections.insert(
        versionId, slug, "NFO", "SHVFUT", "3m", "CE", "volume-floor", new BigDecimal("7865"),
        new BigDecimal("125000"), new BigDecimal("-117135"), "volume < 125000",
        new BigDecimal("0.70"), new BigDecimal("0.60"), "{}", OffsetDateTime.now());
  }

  private static RailCheck rail(String name, boolean pass, String operand, String threshold) {
    BigDecimal o = operand == null ? null : new BigDecimal(operand);
    BigDecimal t = threshold == null ? null : new BigDecimal(threshold);
    return new RailCheck(
        name, pass, o, t, o != null && t != null ? o.subtract(t) : null, "it", null);
  }

  private RejectionDiagnostic diag(String optSym, List<RailCheck> checks, BigDecimal composite) {
    return new RejectionDiagnostic(
        "volume-floor", OptionType.CE, new BigDecimal("7865"), new BigDecimal("125000"),
        new BigDecimal("-117135"), "volume < 125000", composite, new BigDecimal("0.60"),
        checks, null, null, "NIFTY 50", LocalDate.now().plusDays(5),
        new StrikePicker.Pick(
            new StrikePicker.Candidate(
                "NFO", optSym, new BigDecimal("24250"), OptionType.CE, new BigDecimal("100.00"),
                new BigDecimal("0.12")),
            new BigDecimal("0.55")),
        null);
  }

  @Test
  void volumeBlockedRejectionOpensChampionAndAcceptingChallengers() {
    UUID v = versionId();
    String slug = "shv-open-" + UUID.randomUUID();
    String optSym = "SHVOPT" + UUID.randomUUID().toString().substring(0, 8);
    // volume 14000: above the 12.5k challenger floor, below the champion 125k floor
    List<RailCheck> checks =
        List.of(
            rail("volume-floor", false, "14000", "125000"),
            rail("rsi-band", true, "62", "60"));
    long r = rejection(v, slug);

    Long championId =
        shadowBook.maybeOpen(r, v, slug, "NFO", "SHVFUT", OffsetDateTime.now(),
            diag(optSym, checks, new BigDecimal("0.70")));
    assertThat(championId).isNotNull();

    List<String> variants =
        jdbc.queryForList(
            "SELECT variant FROM shadow_positions WHERE strategy_slug = ? ORDER BY variant",
            String.class, slug);
    assertThat(variants).containsExactly("champion", "vol-12k5", "vol-off");

    // dedup is PER VARIANT: the same setup re-blocking next bar adds nothing to any book
    long r2 = rejection(v, slug);
    shadowBook.maybeOpen(r2, v, slug, "NFO", "SHVFUT", OffsetDateTime.now(),
        diag(optSym, checks, new BigDecimal("0.70")));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM shadow_positions WHERE strategy_slug = ?", Long.class, slug))
        .isEqualTo(3);
  }

  @Test
  void unrelatedFailingRailVetoesChallengersButNotChampion() {
    UUID v = versionId();
    String slug = "shv-veto-" + UUID.randomUUID();
    String optSym = "SHVVET" + UUID.randomUUID().toString().substring(0, 8);
    // volume fails AND rsi fails: no variant overrides rsi → challengers reject;
    // the champion book (composite-passed observational class) still opens
    List<RailCheck> checks =
        List.of(
            rail("volume-floor", false, "9000", "125000"),
            rail("rsi-band", false, "48", "60"));
    long r = rejection(v, slug);

    shadowBook.maybeOpen(r, v, slug, "NFO", "SHVFUT", OffsetDateTime.now(),
        diag(optSym, checks, new BigDecimal("0.70")));

    List<String> variants =
        jdbc.queryForList(
            "SELECT variant FROM shadow_positions WHERE strategy_slug = ?", String.class, slug);
    assertThat(variants).containsExactly("champion");
  }

  @Test
  void leagueSummaryGroupsByVariant() {
    UUID v = versionId();
    String slug = "shv-league-" + UUID.randomUUID();
    String optSym = "SHVLEA" + UUID.randomUUID().toString().substring(0, 8);
    long r = rejection(v, slug);
    long id =
        shadows.insert(
            r, v, slug, "CE", "NIFTY 50", "NFO", optSym, new BigDecimal("24250"),
            LocalDate.now().plusDays(5), new BigDecimal("100.00"), null, null, null,
            "NFO", "SHVFUT", "volume-floor", new BigDecimal("0.70"), OffsetDateTime.now(),
            "vol-off");
    shadows.close(id, "TAKE_PROFIT", new BigDecimal("140.00"), new BigDecimal("40.00"),
        new BigDecimal("40.00"), null, null);

    var league = shadows.variantSummary(null, null, null);
    var volOff = league.stream().filter(s -> s.variant().equals("vol-off")).findFirst().orElseThrow();
    assertThat(volOff.closed()).isGreaterThanOrEqualTo(1);
    assertThat(volOff.wins()).isGreaterThanOrEqualTo(1);
    // Closed here with a null pnl_net (no cost model on this path) — it must count as unpriced so the
    // book total can never silently drop it (the 2026-07-06 champion-book artifact this guards against).
    assertThat(volOff.unpriced()).isGreaterThanOrEqualTo(1);
  }
}
