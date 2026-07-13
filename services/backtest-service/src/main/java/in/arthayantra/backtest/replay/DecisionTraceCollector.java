package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.eval.ScoreBreakdown;
import in.arthayantra.strategyengine.golden.TickwiseGoldenRunner.DecisionListener;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded per-session/reason aggregation of the engine's optional decision side-channel. */
public final class DecisionTraceCollector implements DecisionListener {

  private final ObjectMapper objectMapper;
  private final Map<Key, Accumulator> values =
      new TreeMap<>(Comparator.comparing(Key::sessionDate).thenComparing(Key::reason));

  /** Wires the mapper used for the compact sample-breakdown JSON. */
  public DecisionTraceCollector(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void onDecision(
      LocalDate sessionDate,
      OffsetDateTime bucketStart,
      String reason,
      ScoreBreakdown breakdown) {
    Key key = new Key(sessionDate, reason);
    Accumulator accumulator = values.computeIfAbsent(key, ignored -> new Accumulator(bucketStart));
    accumulator.bars++;
    BigDecimal composite = breakdown == null ? null : breakdown.composite();
    if (composite != null
        && (accumulator.maxComposite == null
            || composite.compareTo(accumulator.maxComposite) > 0)) {
      accumulator.maxComposite = composite;
      accumulator.sampleBucket = bucketStart;
      accumulator.sampleBreakdown = sample(breakdown);
    }
  }

  /** Immutable, session/reason-ordered rows ready for one repository batch insert. */
  public List<Trace> rows() {
    List<Trace> rows = new ArrayList<>(values.size());
    values.forEach(
        (key, value) ->
            rows.add(
                new Trace(
                    key.sessionDate(),
                    key.reason(),
                    value.bars,
                    value.maxComposite,
                    value.sampleBucket,
                    value.sampleBreakdown)));
    return List.copyOf(rows);
  }

  private JsonNode sample(ScoreBreakdown breakdown) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("composite", breakdown.composite());
    ObjectNode indicators = root.putObject("indicators");
    breakdown.indicators().forEach(
        indicator -> {
          ObjectNode item = indicators.putObject(indicator.alias());
          item.put("score", indicator.score());
          item.put("weight", indicator.weight());
          item.put("activated", indicator.activated());
        });
    return root;
  }

  /** One persisted/API decision-day row. */
  public record Trace(
      LocalDate sessionDate,
      String reason,
      int bars,
      BigDecimal maxComposite,
      OffsetDateTime sampleBucket,
      JsonNode sampleBreakdown) {}

  private record Key(LocalDate sessionDate, String reason) {}

  private static final class Accumulator {
    private int bars;
    private BigDecimal maxComposite;
    private OffsetDateTime sampleBucket;
    private JsonNode sampleBreakdown;

    private Accumulator(OffsetDateTime sampleBucket) {
      this.sampleBucket = sampleBucket;
    }
  }
}
