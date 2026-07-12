package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import in.arthayantra.strategysignal.insights.InsightGenerator.GenerationContext;
import in.arthayantra.strategysignal.insights.TrustSnapshot.FamilyTrust;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden insight test (INT design §10.1): a fixed fixture (frozen inputs + default config) → a
 * byte-identical insight-candidate set. Generators are pure and templates are code, so identical
 * inputs must reproduce identical candidates run-to-run and against the committed golden — any diff
 * is either an intentional template/threshold change (re-capture with {@code -Dinsights.golden.capture=true})
 * or a determinism bug. No Spring context; the candidate carries no id/timestamp/engine identity, so
 * it is the naturally-deterministic unit to pin.
 */
class InsightGoldenTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-12T09:47:12+05:30");

  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(SerializationFeature.INDENT_OUTPUT);

  @Test
  void insightSetIsDeterministicAndMatchesGolden() throws Exception {
    String first = render(generate());
    String second = render(generate());
    assertThat(second).as("re-running generation reproduces the byte-identical set").isEqualTo(first);

    if (Boolean.getBoolean("insights.golden.capture")) {
      Path golden =
          repoRoot()
              .resolve("services/strategy-signal-service/src/test/resources/insights/golden-insights.json");
      Files.createDirectories(golden.getParent());
      Files.writeString(golden, first);
      return;
    }

    String committed =
        new String(
            InsightGoldenTest.class.getResourceAsStream("/insights/golden-insights.json").readAllBytes(),
            StandardCharsets.UTF_8);
    // Normalize line endings so a CRLF checkout never false-fails (JSON is eol=lf in git).
    assertThat(first.replace("\r\n", "\n")).isEqualTo(committed.replace("\r\n", "\n"));
  }

  /** Runs every generator over its fixture context and concatenates the candidates in a fixed order. */
  private List<InsightCandidate> generate() {
    InsightProperties props =
        new InsightProperties(null, null, null, null, null, null, null, null, null, null); // all defaults
    SignalPriorityGenerator priority = new SignalPriorityGenerator(props);
    DataTrustGenerator trust = new DataTrustGenerator();
    RiskHeatGenerator risk = new RiskHeatGenerator(props);
    StaleTickGenerator staleTick = new StaleTickGenerator();
    ContextShiftGenerator contextShift = new ContextShiftGenerator(props);
    MarketStructureGenerator structure = new MarketStructureGenerator(props);
    RejectionNearMissGenerator nearMiss = new RejectionNearMissGenerator(props);
    RailTrendGenerator railTrend = new RailTrendGenerator(props);
    HygieneGenerator hygiene = new HygieneGenerator(props);
    ExpiryEventGenerator expiry = new ExpiryEventGenerator();
    QualityReportGenerator quality = new QualityReportGenerator(props);
    StrategyEvidenceGenerator strategyEvidence = new StrategyEvidenceGenerator();
    SellDecisionGenerator sellDecision = new SellDecisionGenerator(props);

    List<InsightCandidate> out = new ArrayList<>();
    // I1 generators.
    out.addAll(priority.generate(GenerationContext.forSignal(fixtureSignal(), NOW)));
    out.addAll(trust.generate(GenerationContext.forTrust(fixtureTrust(), NOW)));
    GenerationContext riskCtx = GenerationContext.forRisk(fixtureHeats(), fixtureStaleTick(), NOW);
    out.addAll(risk.generate(riskCtx));
    out.addAll(staleTick.generate(riskCtx));
    // I2 — 15-min context sweep (market digests + near-miss).
    GenerationContext ctxSweep =
        GenerationContext.forContext(fixtureMarket(), fixtureNearMiss(), NOW);
    out.addAll(contextShift.generate(ctxSweep));
    out.addAll(structure.generate(ctxSweep));
    out.addAll(nearMiss.generate(ctxSweep));
    // I2 — EOD sweep (rail trend + hygiene).
    GenerationContext eodCtx = GenerationContext.forEod(fixtureRailTrend(), fixtureHygiene(), NOW);
    out.addAll(railTrend.generate(eodCtx));
    out.addAll(hygiene.generate(eodCtx));
    // I2 — T-1 expiry + weekly quality report.
    out.addAll(expiry.generate(GenerationContext.forExpiry(fixtureExpiry(), NOW)));
    out.addAll(quality.generate(GenerationContext.forQuality(fixtureQuality(), NOW)));
    // I3 — strategy-evidence (snapshot + crossings) + sell-decision.
    out.addAll(
        strategyEvidence.generate(GenerationContext.forStrategyEvidence(fixtureStrategyEvidence(), NOW)));
    out.addAll(sellDecision.generate(GenerationContext.forSellDecision(fixtureSellDecision(), NOW)));
    return out;
  }

  /**
   * A strategy-evidence input: one strategy that crossed UP (PF passes + stage → TAKE_ELIGIBLE), one
   * that dropped DOWN (trades + PF fail, stage → PAPER), and one first-seen (no prior → snapshot only).
   */
  private StrategyEvidenceInputs fixtureStrategyEvidence() {
    java.util.UUID a = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    java.util.UUID b = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    java.util.UUID c = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    StrategyEvidenceInputs.BoardRow rowA =
        new StrategyEvidenceInputs.BoardRow(
            a, "manas-arora-breakout", "Manas Breakout", "TAKE_ELIGIBLE", 22,
            new BigDecimal("1.35"), new BigDecimal("0.50"), new BigDecimal("12"), criteria(true, true, true, true));
    StrategyEvidenceInputs.BoardRow rowB =
        new StrategyEvidenceInputs.BoardRow(
            b, "manas-arora-vcp", "Manas VCP", "PAPER", 18,
            new BigDecimal("1.10"), new BigDecimal("0.10"), new BigDecimal("20"), criteria(false, false, true, true));
    StrategyEvidenceInputs.BoardRow rowC =
        new StrategyEvidenceInputs.BoardRow(
            c, "siva-vwap-breakout", "Siva VWAP", "PAPER", 30,
            new BigDecimal("1.20"), new BigDecimal("0.05"), new BigDecimal("22"), criteria(true, false, true, true));
    java.util.Map<java.util.UUID, StrategyEvidenceInputs.PriorSnapshot> yesterday =
        new java.util.LinkedHashMap<>();
    yesterday.put(a, new StrategyEvidenceInputs.PriorSnapshot("PAPER", pass(true, false, true, true)));
    yesterday.put(b, new StrategyEvidenceInputs.PriorSnapshot("TAKE_ELIGIBLE", pass(true, true, true, true)));
    return new StrategyEvidenceInputs(
        java.time.LocalDate.parse("2026-07-12"), List.of(rowA, rowB, rowC), yesterday, java.util.Set.of(a));
  }

  /** A sell-decision input: a fresh SELL (NOTICE) + an un-acked SELL two sessions old (WARN escalation). */
  private SellDecisionInputs fixtureSellDecision() {
    return new SellDecisionInputs(
        java.time.LocalDate.parse("2026-07-12"),
        List.of(
            new SellDecisionInputs.SellRow(
                5001L, "manas-arora", java.time.LocalDate.parse("2026-07-12"), 7001L, "NSE", "SBCL",
                "manas-arora-breakout", "SELL (stop 2xATR breached)", "stop 2xATR breached",
                new BigDecimal("100"), new BigDecimal("104.2"), new BigDecimal("4.2"), 0L),
            new SellDecisionInputs.SellRow(
                5002L, "minervini", java.time.LocalDate.parse("2026-07-10"), 7002L, "NSE", "TCS",
                "minervini-vcp", "SELL (50-DMA break)", "50-DMA break",
                new BigDecimal("3000"), new BigDecimal("2850"), new BigDecimal("-5.0"), 2L)));
  }

  private static List<StrategyEvidenceInputs.Criterion> criteria(
      boolean trades, boolean pf, boolean expectancy, boolean maxDd) {
    return List.of(
        new StrategyEvidenceInputs.Criterion("trades", ">= 20", trades ? "22" : "18", trades),
        new StrategyEvidenceInputs.Criterion("profitFactor", ">= 1.3", pf ? "1.35" : "1.10", pf),
        new StrategyEvidenceInputs.Criterion("expectancy", "> 0", "0.5", expectancy),
        new StrategyEvidenceInputs.Criterion("maxDrawdownPct", "<= 25", maxDd ? "12" : "27", maxDd));
  }

  private static java.util.Map<String, Boolean> pass(
      boolean trades, boolean pf, boolean expectancy, boolean maxDd) {
    java.util.Map<String, Boolean> m = new java.util.LinkedHashMap<>();
    m.put("trades", trades);
    m.put("profitFactor", pf);
    m.put("expectancy", expectancy);
    m.put("maxDrawdownPct", maxDd);
    return m;
  }

  /** A market context: one options shift crossing all four thresholds + an ELEVATED/WIDE regime. */
  private MarketContext fixtureMarket() {
    MarketContext.OptionsShift os =
        new MarketContext.OptionsShift(
            "NSE", "NIFTY", "2026-07-15",
            new BigDecimal("1.14"), new BigDecimal("0.20"), new BigDecimal("75"),
            new BigDecimal("25"), 3, "SHORT_COVERING", "OK", List.of(),
            "2026-07-12T14:47:12+05:30");
    MarketContext.MarketStructureView s =
        new MarketContext.MarketStructureView(
            "NIFTY 50", new BigDecimal("18.5"), "ELEVATED", new BigDecimal("0.8"), "WIDE",
            new BigDecimal("1.5"), "UP", "OK", "2026-07-12T14:47:12+05:30");
    return new MarketContext(List.of(os), s);
  }

  /** A near-miss scan: two rejections within 5% of firing (closeness precomputed by the reader). */
  private RejectionScan fixtureNearMiss() {
    return new RejectionScan(
        List.of(
            new RejectionScan.NearMiss(1001L, "siva-vwap-breakout", "NIFTY25JUL25200CE", "CE",
                "volume_floor", new BigDecimal("120000"), new BigDecimal("125000"),
                new BigDecimal("-5000"), new BigDecimal("0.0400")),
            new RejectionScan.NearMiss(1002L, "siva-vwap-breakout", "NIFTY25JUL25100PE", "PE",
                "adx_floor", new BigDecimal("19"), new BigDecimal("20"), new BigDecimal("-1"),
                new BigDecimal("0.0500"))),
        List.of());
  }

  /** A rail-trend scan: one rail spiked to a 2.67× share vs its 5-day mean. */
  private RejectionScan fixtureRailTrend() {
    return new RejectionScan(
        List.of(),
        List.of(
            new RejectionScan.RailShare("volume_floor", 12, 30, new BigDecimal("0.40"),
                new BigDecimal("0.15"), new BigDecimal("2.67"), 5)));
  }

  /** A hygiene snapshot: unrated entries + un-journaled closes. */
  private HygieneInputs fixtureHygiene() {
    return new HygieneInputs(7, 4, 2);
  }

  /** An expiry snapshot: one leg expiring tomorrow, a holiday two days out. */
  private ExpirySnapshot fixtureExpiry() {
    return new ExpirySnapshot(
        java.time.LocalDate.parse("2026-07-12"),
        List.of(
            new ExpirySnapshot.ExpiringPosition(2001L, "scalper", "NFO", "NIFTY25JUL25200CE", "BUY",
                75, java.time.LocalDate.parse("2026-07-13"), 1)),
        java.time.LocalDate.parse("2026-07-14"), "Test Holiday");
  }

  /** A stale-tick snapshot: one bracketed position over a stalled instrument. */
  private StaleTickSnapshot fixtureStaleTick() {
    return new StaleTickSnapshot(
        List.of(
            new StaleTickSnapshot.StaleBracket(3001L, "scalper", "NFO", "NIFTY25JUL25200CE", "BUY",
                true, true, "NFO:NIFTY25JUL25200CE", "ticks flowing but no 1m bar closed for 240s")));
  }

  /** A quality-stats fixture: below-target act rate + a high-dismiss retirement candidate. */
  private QualityStats fixtureQuality() {
    return new QualityStats(
        7, new BigDecimal("0.5000"), 10, 4,
        List.of(new QualityStats.KeyCount("CONTEXT_SHIFT:underlying:NSE:NIFTY:PCR", 3)),
        List.of(
            new QualityStats.TypeStat("SIGNAL_PRIORITY", 40, 5, 1, new BigDecimal("0.1667")),
            new QualityStats.TypeStat("REJECTION_NEARMISS", 8, 1, 3, new BigDecimal("0.7500"))));
  }

  /** A full-input scalper signal (all five components live, trust OK). */
  private SignalPriorityInputs fixtureSignal() {
    return new SignalPriorityInputs(
        42L, "scalper", "NFO", "NIFTY25JUL25200CE", "CE", new BigDecimal("25200"),
        new BigDecimal("0.81"), new BigDecimal("0.72"),
        new BigDecimal("0.75"), null, null,
        new BigDecimal("1.9"), new BigDecimal("1.0"), 34, true,
        new BigDecimal("34"), null, false, 1,
        12L, null, DataTrust.OK, List.of(), "2026-07-12T09:47:12+05:30");
  }

  /** A trust snapshot: one OK family (no insight), one DEGRADED, one BLOCKED. */
  private TrustSnapshot fixtureTrust() {
    return new TrustSnapshot(
        "2026-07-12T04:17:12Z",
        List.of(
            new FamilyTrust("live-capture", DataTrust.OK, List.of(), "2026-07-12T09:47:00+05:30"),
            new FamilyTrust("participant-oi", DataTrust.DEGRADED, List.of("participant-OI missing for 2026-07-11"), "2026-07-12T09:47:12+05:30"),
            new FamilyTrust("screener", DataTrust.BLOCKED, List.of("screener ingest FAILED with no subsequent success"), "2026-07-12T09:47:12+05:30")));
  }

  /** One hot, concentrated book. */
  private List<BookHeat> fixtureHeats() {
    return List.of(
        new BookHeat("scalper", 3, new BigDecimal("78"), new BigDecimal("100"), 2, "NIFTY25JUL25200CE CE"));
  }

  private String render(List<InsightCandidate> candidates) throws Exception {
    return mapper.writeValueAsString(candidates) + "\n";
  }

  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !java.nio.file.Files.isDirectory(dir.resolve("deploy/flyway"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("repo root not found");
    }
    return dir;
  }
}
