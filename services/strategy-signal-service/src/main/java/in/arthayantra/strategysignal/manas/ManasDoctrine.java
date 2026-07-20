package in.arthayantra.strategysignal.manas;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Manas Arora swing doctrine. Routes by setup (the breakout strategy fires only on a §3.2 breakout
 * base, the VCP strategy only on a §3.3 VCP base — expressed as {@code eligibleSetups} data), seeds
 * only the NON-NULL of its three pivot contexts (a 0-filled breakout/VCP context would make the
 * crossover gate pass spuriously), and carries the flag-gated §3.4 {@link ManasPyramidPolicy}.
 */
@Component
public class ManasDoctrine implements SwingDoctrine {

  private static final String PIVOT = "MANAS_PIVOT";
  private static final String BREAKOUT_PIVOT = "MANAS_BREAKOUT_PIVOT";
  private static final String VCP_PIVOT = "MANAS_VCP_PIVOT";
  private static final String SETUP_BREAKOUT = "breakout";
  private static final String SETUP_VCP = "vcp";

  private final ManasFunnelClient funnel;
  private final SignalRepository signals;
  private final ManasPyramidPolicy pyramid;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final int warmupDays;
  private final int minBars;
  private final long ttlMinutes;

  /** Wires the selection-funnel client, the signal repo, the pyramid policy, and the arming knobs. */
  public ManasDoctrine(
      ManasFunnelClient funnel,
      SignalRepository signals,
      ManasPyramidPolicy pyramid,
      ObjectMapper objectMapper,
      @Value("${artha.manas-arora.swing.enabled:false}") boolean enabled,
      @Value("${artha.manas-arora.swing.warmup-days:520}") int warmupDays,
      @Value("${artha.manas-arora.swing.min-bars:60}") int minBars,
      @Value("${artha.manas-arora.swing.signal-ttl-minutes:1440}") long ttlMinutes) {
    this.funnel = funnel;
    this.signals = signals;
    this.pyramid = pyramid;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.warmupDays = warmupDays;
    this.minBars = minBars;
    this.ttlMinutes = ttlMinutes;
  }

  @Override
  public String book() {
    return Books.MANAS_ARORA;
  }

  @Override
  public String universeMode() {
    return "manas_arora_funnel";
  }

  @Override
  public String batchName() {
    return "manas-arora";
  }

  @Override
  public String alertLabel() {
    return "Manas swing";
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

  /**
   * The setup a strategy trades ({@code "vcp"}/{@code "breakout"}). Prefers the tags' setup token (the
   * 4th tag {@code manas-arora, swing, equity, breakout|vcp}), falling back to the slug suffix; defaults
   * to {@code "breakout"} (the gate/exit math is identical, only the pivot the crossover reads differs).
   */
  @Override
  public String setupToken(JsonNode config, String slug) {
    for (JsonNode tag : config.path("tags")) {
      String t = tag.asText();
      if (SETUP_VCP.equals(t)) {
        return SETUP_VCP;
      }
      if (SETUP_BREAKOUT.equals(t)) {
        return SETUP_BREAKOUT;
      }
    }
    return slug != null && slug.endsWith("-" + SETUP_VCP) ? SETUP_VCP : SETUP_BREAKOUT;
  }

  @Override
  public List<SwingCandidate> candidates() {
    return funnel.buyableAndOnDeck().stream().map(this::toCandidate).toList();
  }

  @Override
  public java.util.Optional<java.time.LocalDate> inputsAsOf() {
    return funnel.screenDate();
  }

  private SwingCandidate toCandidate(ManasFunnelClient.Candidate c) {
    // Seed ONLY the non-null pivot contexts — a 0-filled breakout/VCP context would make its crossover
    // gate (px > pivot) pass on every symbol.
    Map<String, BigDecimal> seeds = new LinkedHashMap<>();
    if (c.pivot() != null) {
      seeds.put(PIVOT, c.pivot());
    }
    if (c.breakoutPivot() != null) {
      seeds.put(BREAKOUT_PIVOT, c.breakoutPivot());
    }
    if (c.vcpPivot() != null) {
      seeds.put(VCP_PIVOT, c.vcpPivot());
    }
    // Setup routing as data: a candidate is eligible for a setup iff it carries that setup's pivot.
    Set<String> eligible = new HashSet<>();
    if (c.breakoutPivot() != null) {
      eligible.add(SETUP_BREAKOUT);
    }
    if (c.vcpPivot() != null) {
      eligible.add(SETUP_VCP);
    }
    ObjectNode detail = objectMapper.createObjectNode();
    if (c.setupType() != null) {
      detail.put("setupType", c.setupType());
    }
    if (c.footprint() != null) {
      detail.put("footprint", c.footprint());
    }
    if (c.pivot() != null) {
      detail.put("pivot", c.pivot().toPlainString());
    }
    return new SwingCandidate(c.symbol(), seeds, eligible, detail, c.onDeck());
  }

  @Override
  public Map<String, BigDecimal> contextSeedsFromDetail(JsonNode detail) {
    // Re-run the entry gate on a held position: seed MANAS_PIVOT + the persisted setup's own pivot
    // context (a "-vcp" setup → the VCP pivot, else the breakout pivot), matching the old sell-decision.
    BigDecimal pivot = decimal(detail, "pivot");
    String setup = text(detail, "setup");
    boolean vcp = setup != null && setup.endsWith("-" + SETUP_VCP);
    Map<String, BigDecimal> seeds = new LinkedHashMap<>();
    if (pivot != null) {
      seeds.put(PIVOT, pivot);
      seeds.put(vcp ? VCP_PIVOT : BREAKOUT_PIVOT, pivot);
    }
    return seeds;
  }

  @Override
  public Map<String, BigDecimal> neutralContextSeeds() {
    Map<String, BigDecimal> seeds = new LinkedHashMap<>();
    seeds.put(PIVOT, BigDecimal.ZERO);
    seeds.put(BREAKOUT_PIVOT, BigDecimal.ZERO);
    seeds.put(VCP_PIVOT, BigDecimal.ZERO);
    return seeds;
  }

  @Override
  public void stampDetail(long signalId, String detailJson) {
    signals.stampManasAroraDetail(signalId, detailJson);
  }

  @Override
  public JsonNode detailOf(SignalRepository.SignalRow anchor) {
    return anchor.manasAroraDetail();
  }

  @Override
  public PyramidPolicy pyramid() {
    return pyramid;
  }

  @Override
  public BigDecimal trailLevel(
      StrategyDefinition definition,
      IndicatorBank bank,
      ExitEvaluator.Position position,
      int lastIndex,
      int entryIndex) {
    // The doc-faithful §3.5 2×ATR trailing stop (armed at +9%; audit M32/M33 — NOT sma20, which is the
    // entry gate). Meaningful only when the entry bar is still in the window (the trail scans the
    // high-water since entry); null until it arms.
    return entryIndex >= 0
        ? ExitEvaluator.trailStop(definition, bank, position, lastIndex).orElse(null)
        : null;
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? new BigDecimal(node.path(field).asText()) : null;
  }

  private static String text(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
  }
}
