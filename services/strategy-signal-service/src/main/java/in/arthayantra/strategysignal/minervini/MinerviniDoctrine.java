package in.arthayantra.strategysignal.minervini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.swing.PyramidPolicy;
import in.arthayantra.strategysignal.swing.SwingCandidate;
import in.arthayantra.strategysignal.swing.SwingDoctrine;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Minervini SEPA swing doctrine: the family-specific inputs the shared {@code SwingBatchEngine}
 * reads. Single-lot ({@link PyramidPolicy#NONE}), no setup routing (all setups score every candidate),
 * and three unconditionally-seeded VCP-geometry contexts (pivot / cheat / thrust — 0-filled when the
 * candidate carries none, matching the frozen golden-runner context mechanism).
 */
@Component
public class MinerviniDoctrine implements SwingDoctrine {

  private static final String PIVOT = "MINERVINI_PIVOT";
  private static final String CHEAT = "MINERVINI_CHEAT";
  private static final String THRUST = "MINERVINI_THRUST";

  private final MinerviniFunnelClient funnel;
  private final SignalRepository signals;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final int warmupDays;
  private final int minBars;
  private final long ttlMinutes;

  /** Wires the SEPA funnel client, the signal repo (detail side-channel), and the arming knobs. */
  public MinerviniDoctrine(
      MinerviniFunnelClient funnel,
      SignalRepository signals,
      ObjectMapper objectMapper,
      @Value("${artha.minervini.swing.enabled:false}") boolean enabled,
      @Value("${artha.minervini.swing.warmup-days:520}") int warmupDays,
      @Value("${artha.minervini.swing.min-bars:60}") int minBars,
      @Value("${artha.minervini.swing.signal-ttl-minutes:1440}") long ttlMinutes) {
    this.funnel = funnel;
    this.signals = signals;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.warmupDays = warmupDays;
    this.minBars = minBars;
    this.ttlMinutes = ttlMinutes;
  }

  @Override
  public String book() {
    return Books.MINERVINI;
  }

  @Override
  public String universeMode() {
    return "minervini_funnel";
  }

  @Override
  public String batchName() {
    return "minervini";
  }

  @Override
  public String alertLabel() {
    return "Minervini swing";
  }

  @Override
  public boolean enabled() {
    return enabled;
  }

  @Override
  public int warmupDays() {
    return warmupDays;
  }

  @Override
  public int minBars() {
    return minBars;
  }

  @Override
  public long ttlMinutes() {
    return ttlMinutes;
  }

  @Override
  public String setupToken(JsonNode config, String slug) {
    return null; // no setup routing — every Minervini strategy scores every candidate
  }

  @Override
  public List<SwingCandidate> candidates() {
    return funnel.buyableAndOnDeck().stream().map(this::toCandidate).toList();
  }

  private SwingCandidate toCandidate(MinerviniFunnelClient.Candidate c) {
    Map<String, BigDecimal> seeds = new LinkedHashMap<>();
    seeds.put(PIVOT, c.pivot() == null ? BigDecimal.ZERO : c.pivot());
    seeds.put(CHEAT, c.cheatPivot() == null ? BigDecimal.ZERO : c.cheatPivot());
    seeds.put(THRUST, c.thrust() ? BigDecimal.ONE : BigDecimal.ZERO);
    ObjectNode detail = objectMapper.createObjectNode();
    if (c.stage() != null) {
      detail.put("stage", c.stage());
    }
    if (c.footprint() != null) {
      detail.put("footprint", c.footprint());
    }
    if (c.pivot() != null) {
      detail.put("pivot", c.pivot().toPlainString());
    }
    if (c.cheatPivot() != null) {
      detail.put("cheatPivot", c.cheatPivot().toPlainString());
    }
    detail.put("thrust", c.thrust());
    return new SwingCandidate(c.symbol(), seeds, null, detail);
  }

  @Override
  public Map<String, BigDecimal> contextSeedsFromDetail(JsonNode detail) {
    BigDecimal pivot = decimal(detail, "pivot");
    BigDecimal cheat = decimal(detail, "cheatPivot");
    boolean thrust = detail != null && detail.path("thrust").asBoolean(false);
    Map<String, BigDecimal> seeds = new LinkedHashMap<>();
    seeds.put(PIVOT, pivot == null ? BigDecimal.ZERO : pivot);
    seeds.put(CHEAT, cheat == null ? BigDecimal.ZERO : cheat);
    seeds.put(THRUST, thrust ? BigDecimal.ONE : BigDecimal.ZERO);
    return seeds;
  }

  @Override
  public Map<String, BigDecimal> neutralContextSeeds() {
    Map<String, BigDecimal> seeds = new LinkedHashMap<>();
    seeds.put(PIVOT, BigDecimal.ZERO);
    seeds.put(CHEAT, BigDecimal.ZERO);
    seeds.put(THRUST, BigDecimal.ZERO);
    return seeds;
  }

  @Override
  public void stampDetail(long signalId, String detailJson) {
    signals.stampMinerviniDetail(signalId, detailJson);
  }

  @Override
  public JsonNode detailOf(SignalRepository.SignalRow anchor) {
    return anchor.minerviniDetail();
  }

  @Override
  public PyramidPolicy pyramid() {
    return PyramidPolicy.NONE;
  }

  @Override
  public BigDecimal trailLevel(
      StrategyDefinition definition,
      IndicatorBank bank,
      ExitEvaluator.Position position,
      int lastIndex,
      int entryIndex) {
    // The 50-day-MA trail level (MV-7.4) — advisory display, independent of the entry index.
    return bank.has("sma50") ? bank.valueAt("sma50", lastIndex) : null;
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? new BigDecimal(node.path(field).asText()) : null;
  }
}
