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

  /** Runs the three I1 generators over their fixture contexts and concatenates the candidates. */
  private List<InsightCandidate> generate() {
    InsightProperties props = new InsightProperties(null, null, null); // all defaults
    SignalPriorityGenerator priority = new SignalPriorityGenerator(props);
    DataTrustGenerator trust = new DataTrustGenerator();
    RiskHeatGenerator risk = new RiskHeatGenerator(props);

    List<InsightCandidate> out = new ArrayList<>();
    out.addAll(priority.generate(GenerationContext.forSignal(fixtureSignal(), NOW)));
    out.addAll(trust.generate(GenerationContext.forTrust(fixtureTrust(), NOW)));
    out.addAll(risk.generate(GenerationContext.forRisk(fixtureHeats(), NOW)));
    return out;
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
