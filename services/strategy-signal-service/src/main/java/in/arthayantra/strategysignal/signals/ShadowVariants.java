package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Challenger variant definitions for the shadow book (roadmap F1): each variant is a config diff —
 * per-rail threshold overrides / rail disables plus an optional composite floor — re-scored against
 * the SAME live {@link ScalperConfluenceGate.RejectionDiagnostic} (the gate is all-eval, so
 * {@code checks[]} carries every rail's operand). A variant "accepts" a rejection when every rail
 * passes under its overrides and the composite clears its floor — those entries open as virtual
 * positions tagged with the variant name, so a proposed knob change earns a real PnL label before
 * anyone touches the champion config.
 *
 * <p>Configured as one JSON array (env {@code ARTHA_SCALPER_SHADOW_BOOK_VARIANTS_JSON}):
 *
 * <pre>{@code
 * [{"name":"vol-off","rails":[{"rail":"volume-floor","disable":true}]},
 *  {"name":"vol-12k5","rails":[{"rail":"volume-floor","threshold":12500,"passWhen":"GTE"}]},
 *  {"name":"composite-070","compositeThreshold":0.70},
 *  {"name":"dot-null-withheld","nullPolicy":"withheld"}]
 * }</pre>
 *
 * <p>Empty (the code default) disables challengers entirely. A parse error logs and yields NO
 * variants — a broken experiment config must never break the live signal path.
 */
@Component
public class ShadowVariants {

  private static final Logger log = LoggerFactory.getLogger(ShadowVariants.class);
  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
  static final String CHAMPION = "champion";
  static final String COMPOSITE_RAIL = "confluence-composite";
  /** The one recognised {@code nullPolicy} value — F5 U4b's unified "input-missing ⇒ withheld" rule. */
  static final String NULL_POLICY_WITHHELD = "withheld";

  /** One rail override: {@code disable} wins; otherwise operand-vs-threshold per {@code passWhen}. */
  public record RailOverride(String rail, boolean disable, BigDecimal threshold, String passWhen) {}

  /**
   * One challenger book definition. {@code nullWithheld} (F5 U4b, spec key {@code "nullPolicy":
   * "withheld"}) scores against the confluence's {@code withheldAggregate} instead of the champion's
   * composite — the evidence lane for the DEFAULT-OFF {@code dot-null-withheld} dot-null unification.
   */
  public record Variant(
      String name, Map<String, RailOverride> rails, BigDecimal compositeThreshold,
      boolean nullWithheld) {

    /** Pre-U4b 3-arg form: {@code nullWithheld} defaults to false (the champion composite rules). */
    public Variant(String name, Map<String, RailOverride> rails, BigDecimal compositeThreshold) {
      this(name, rails, compositeThreshold, false);
    }
  }

  private final List<Variant> variants;

  /** Parses the JSON config once at boot; invalid config degrades to no challengers. */
  public ShadowVariants(
      ObjectMapper objectMapper,
      @Value("${artha.scalper.shadow-book.variants-json:[]}") String json) {
    this.variants = parse(objectMapper, json);
    if (!variants.isEmpty()) {
      log.info(
          "shadow challenger variants active: {}",
          variants.stream().map(Variant::name).collect(Collectors.joining(", ")));
    }
  }

  private static List<Variant> parse(ObjectMapper mapper, String json) {
    try {
      List<RawVariant> raw =
          mapper.readValue(
              json,
              mapper.getTypeFactory().constructCollectionType(List.class, RawVariant.class));
      return raw.stream().map(ShadowVariants::validated).toList();
    } catch (Exception e) {
      log.error("shadow variants config unparsable — challengers DISABLED: {}", e.toString());
      return List.of();
    }
  }

  private static Variant validated(RawVariant raw) {
    String name = raw.name == null ? "" : raw.name.toLowerCase(Locale.ROOT);
    if (!NAME.matcher(name).matches() || CHAMPION.equals(name)) {
      throw new IllegalArgumentException("bad variant name: " + raw.name);
    }
    Map<String, RailOverride> rails =
        raw.rails == null
            ? Map.of()
            : raw.rails.stream()
                .map(
                    r -> {
                      if (r.rail == null || (!r.disable && r.threshold == null)) {
                        throw new IllegalArgumentException(
                            name + ": each rail override needs rail + (disable | threshold)");
                      }
                      String passWhen = r.passWhen == null ? "GTE" : r.passWhen;
                      if (!"GTE".equals(passWhen) && !"LTE".equals(passWhen)) {
                        throw new IllegalArgumentException(name + ": passWhen must be GTE|LTE");
                      }
                      return new RailOverride(r.rail, r.disable, r.threshold, passWhen);
                    })
                .collect(Collectors.toMap(RailOverride::rail, Function.identity()));
    boolean nullWithheld = false;
    if (raw.nullPolicy != null) {
      // ONE recognised value: the vocabulary names the policy it opts into rather than a boolean, so
      // a future third policy is an added constant, not a second flag that can contradict this one.
      if (!NULL_POLICY_WITHHELD.equals(raw.nullPolicy.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException(
            name + ": nullPolicy must be \"" + NULL_POLICY_WITHHELD + "\"");
      }
      nullWithheld = true;
    }
    return new Variant(name, rails, raw.compositeThreshold, nullWithheld);
  }

  /**
   * The boot-time env-JSON fallback variants (possibly empty). The LIVE active challenger set is the
   * MERGE of these with the runtime-registered {@link ShadowVariantRegistry} rows — read from there
   * on the live path, not here.
   */
  public List<Variant> all() {
    return variants;
  }

  /**
   * Validates + builds ONE variant from a runtime registration (EVO E3 §11). {@code specNode} is the
   * vocabulary BODY ({@code {rails, compositeThreshold, nullPolicy}}, the {@code name} is separate); it is
   * STRICT-parsed so an unknown knob kind (e.g. an ENV-plane relative-vol field, §2.3) is rejected,
   * then run through the SAME name-regex + rail-shape rules as the env JSON. Throws {@link
   * IllegalArgumentException} on any violation — the registry surfaces it as a 422. The strictness
   * rides a per-call {@code ObjectReader}, never the shared mapper (the live Kite mapper must stay
   * lenient). Pure — never touches the live active set.
   */
  public static Variant validatedFromSpec(ObjectMapper mapper, String name, JsonNode specNode) {
    RawSpec spec;
    try {
      String json = specNode == null ? "{}" : mapper.writeValueAsString(specNode);
      spec =
          mapper
              .readerFor(RawSpec.class)
              .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .readValue(json);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "unrecognized shadow-variant spec (unknown knob kind — the vocabulary is rail"
              + " disable / rail threshold+passWhen / compositeThreshold / nullPolicy): "
              + rootMessage(e));
    }
    RawVariant raw = new RawVariant();
    raw.name = name;
    raw.rails = spec == null ? null : spec.rails;
    raw.compositeThreshold = spec == null ? null : spec.compositeThreshold;
    raw.nullPolicy = spec == null ? null : spec.nullPolicy;
    return validated(raw);
  }

  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    String m = t.getMessage();
    if (m == null) {
      return t.getClass().getSimpleName();
    }
    int nl = m.indexOf('\n');
    return nl < 0 ? m : m.substring(0, nl);
  }

  /**
   * True when this variant's config would have ACCEPTED the rejected entry: every evaluated rail
   * passes after overrides (a disabled rail always passes; an overridden threshold re-scores the
   * recorded operand; a null operand keeps the original verdict — an override cannot conjure data)
   * and the composite clears the variant floor (default: the champion threshold). The composite
   * rail itself is floor-ruled, not pass/fail-ruled, mirroring the champion book's
   * {@code min-composite} precedent.
   *
   * <p><b>§3.3.3 relaxing-or-neutral clamp.</b> A threshold override is clamped per bar against the
   * champion's OWN recorded threshold for that rail ({@code RailCheck.threshold()}, populated for
   * every evaluated rail by the all-eval gate): {@code GTE} (floor) rails re-score against
   * {@code min(override, champion)}, {@code LTE} (cap) rails against {@code max(override,
   * champion)} — so an override can only RELAX, never tighten. The shadow writer fires on the
   * REJECTION path only, so a tightening override would cherry-pick the rejected stream (the extra
   * entries it blocks are champion-ACCEPTED — invisible here) and bias the book upward; the clamp
   * realizes the §3.3.3 rule exactly, per strategy and per bar — a mixed relax+tighten spec
   * degrades to "the tightened rail behaves as champion". A rail whose champion threshold was not
   * recorded (null) uses the override as-is (nothing to clamp against). Consequence: a variant can
   * never flip a champion-passing rail to fail on the rails it overrides.
   */
  public static boolean accepts(ScalperConfluenceGate.RejectionDiagnostic d, Variant v) {
    if (d.checks() == null) {
      return false;
    }
    for (ScalperConfluenceGate.RailCheck c : d.checks()) {
      if (COMPOSITE_RAIL.equals(c.rail())) {
        continue;
      }
      RailOverride o = v.rails().get(c.rail());
      if (o == null) {
        if (!c.pass()) {
          return false;
        }
        continue;
      }
      if (o.disable()) {
        continue;
      }
      if (c.operand() == null) {
        if (!c.pass()) {
          return false;
        }
        continue;
      }
      int cmp = c.operand().compareTo(clampedThreshold(o, c.threshold()));
      boolean pass = "GTE".equals(o.passWhen()) ? cmp >= 0 : cmp <= 0;
      if (!pass) {
        return false;
      }
    }
    BigDecimal floor =
        v.compositeThreshold() != null ? v.compositeThreshold() : d.compositeThreshold();
    if (v.nullWithheld() && !armedPolicyCouldHaveFired(d)) {
      return false;
    }
    BigDecimal composite = compositeFor(d, v);
    return composite != null && floor != null && composite.compareTo(floor) >= 0;
  }

  /**
   * Whether a {@code nullPolicy} variant may consider this bar at all (F5 U4b, review Critical).
   *
   * <p>The recalculated scalar is NOT the armed verdict. {@code ConnectTheDotsScorer} makes a side
   * valid only when the scalar clears the threshold AND all three DECISIVE legs hold — hard-VWAP
   * alignment, 60m bias agreement, and no IV stand-aside — none of which the null policy changes.
   * Accepting on the scalar alone would open challenger positions on bars the ARMED policy still
   * rejects, so the book's PnL would not be noisy but WRONG. That divergence is observed, not
   * theoretical: {@code ScalperConfluenceGate.compositeMargin} records 4 live rows where the
   * aggregate cleared the threshold while a decisive leg blocked.
   *
   * <p>Fail-closed on a missing counterfactual: no confluence (the direction-neutral straddle
   * stand-in), no recorded {@code withheldAggregate} (a pre-U4b row), or legs that did not hold all
   * DECLINE. Declining is deliberate rather than degrading to champion behaviour — a bar with no
   * counterfactual has nothing for this experiment to measure, and booking it under the variant's
   * name would attribute a champion-duplicate row to the proposal.
   *
   * <p>Scope note: this guard is deliberately confined to {@code nullPolicy} variants. The champion
   * book and the rail-override variants keep their existing floor-ruled semantics ({@code
   * COMPOSITE_RAIL} is skipped above); widening it would silently shrink live books this change has
   * no mandate to touch.
   */
  private static boolean armedPolicyCouldHaveFired(ScalperConfluenceGate.RejectionDiagnostic d) {
    return d.confluence() != null
        && d.confluence().withheldAggregate() != null
        && d.confluence().decisiveLegsHeld();
  }

  /**
   * The composite this variant scores against: the champion's recorded score, or — for a
   * {@code nullPolicy: withheld} variant (F5 U4b) — the confluence's {@code withheldAggregate}
   * UNCLAMPED, the number the composite WOULD have been had every input-missing dot been withheld
   * rather than scored.
   *
   * <p><b>The {@code max(champion, withheld)} clamp this used to apply was removed (2026-08-03).</b>
   * Unifying the null rule moves the composite BOTH ways — withholding an opposes-in-denominator dot
   * RAISES it, withholding one that currently reads a null as SUPPORT ({@code vix} / {@code basis} /
   * and, when enabled, {@code premium_skew} / {@code dow}) LOWERS it — and on live data the dominant
   * missing input is {@code vix} (38 of the 45 clean rows with any gap in the post-P3 window), which
   * LOWERS it. A clamp that floors the variant at the champion is therefore blind in exactly the
   * direction the data moves: a ratchet that can only ever confirm its own hypothesis.
   *
   * <p><b>Removing it cannot change a decision this book was entitled to make</b>, and it closes one
   * it was not. Two cases, both provable from the code above rather than asserted:
   *
   * <ul>
   *   <li><b>Pure {@code nullPolicy} variant (the live {@code dot-null-withheld} shape): the clamp
   *       was arithmetically INERT.</b> {@link #accepts} returns false unless every non-composite
   *       rail passed, and {@link #armedPolicyCouldHaveFired} returns false unless the decisive legs
   *       held — so on any row that reaches here the block WAS the composite rail with legs held,
   *       i.e. {@code champion < championThreshold}, and the floor IS that threshold. Then
   *       {@code max(champion, withheld) >= floor} exactly when {@code withheld >= floor}. Same
   *       answer, every row. {@code clampRemovalIsInertForAPureNullPolicyVariant} pins it.
   *   <li><b>{@code nullPolicy} + a lower {@code compositeThreshold} (the vocabulary permits it):
   *       the clamp produced FALSE ACCEPTS.</b> With floor 0.55, champion 0.60 and withheld 0.50 the
   *       clamp scored 0.60 and opened a challenger position on a bar the armed policy scores 0.50
   *       and REJECTS — a trade booked against a proposal that would not have taken it.
   *       {@code clampWouldFalselyAcceptWhenWithholdingLowersBelowALowerFloor} pins that the two
   *       implementations disagree here, and which one is right.
   * </ul>
   *
   * <p>The §3.3.3 relaxing-or-neutral guarantee the clamp was reasoned from is NOT weakened: the
   * unclamped accept set is a SUBSET of the clamped one ({@code withheld <= max(champion, withheld)}
   * always), so this can only remove accepts, never add them, and the rejection-only writer still
   * cannot be biased upward.
   *
   * <p><b>⚠️ This plane still measures the PROMOTION half only and CANNOT authorize arming.</b> That
   * limit was never the clamp — it is the WRITER: the shadow book opens positions off the REJECTION
   * path, so a champion-FIRED trade that WITHHELD would have removed has no row here to appear in,
   * clamp or no clamp. This book answers "which extra entries would the unified rule admit, and did
   * they pay?" — never "is the unified rule net-positive?". The instrument for the tightening half is
   * the fired-side counterfactual ({@code withheldAggregate} + {@code decisiveLegsHeld} on
   * {@code signals.fired_diagnostic}, F5 U4b Part 1); a full verdict needs counterfactual replay or a
   * paper A/B, not this plane. A further caveat for whoever reads the book: the shadow exit monitor
   * deliberately omits confluence/indicator exits, while twelve live strategies carry
   * {@code oi-confluence-exit} and re-evaluate this same scorer on the EXIT path — for those, arming
   * would change exits too, which this book does not model. Nor does it model the §5.3 coverage
   * floor that {@code dot-null-withheld} now arms alongside the policy.
   */
  private static BigDecimal compositeFor(ScalperConfluenceGate.RejectionDiagnostic d, Variant v) {
    // A nullWithheld variant only reaches here past armedPolicyCouldHaveFired, so the confluence and
    // its shadow are both present; the guards stay so this stays correct if it is ever called first.
    if (!v.nullWithheld() || d.confluence() == null || d.confluence().withheldAggregate() == null) {
      return d.compositeScore();
    }
    return d.confluence().withheldAggregate();
  }

  /** The §3.3.3 clamp: floor overrides may only lower, cap overrides may only raise. */
  private static BigDecimal clampedThreshold(RailOverride o, BigDecimal champion) {
    if (champion == null) {
      return o.threshold();
    }
    return "GTE".equals(o.passWhen())
        ? o.threshold().min(champion)
        : o.threshold().max(champion);
  }

  /** Jackson shape of one configured variant. */
  static final class RawVariant {
    public String name;
    public List<RawRail> rails;
    public BigDecimal compositeThreshold;
    public String nullPolicy;
  }

  /** Jackson shape of a runtime-registration spec BODY (the variant minus its name). */
  static final class RawSpec {
    public List<RawRail> rails;
    public BigDecimal compositeThreshold;
    public String nullPolicy;
  }

  /** Jackson shape of one rail override. */
  static final class RawRail {
    public String rail;
    public boolean disable;
    public BigDecimal threshold;
    public String passWhen;
  }
}
