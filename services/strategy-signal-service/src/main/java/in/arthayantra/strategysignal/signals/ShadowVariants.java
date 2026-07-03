package in.arthayantra.strategysignal.signals;

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
 *  {"name":"composite-070","compositeThreshold":0.70}]
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

  /** One rail override: {@code disable} wins; otherwise operand-vs-threshold per {@code passWhen}. */
  public record RailOverride(String rail, boolean disable, BigDecimal threshold, String passWhen) {}

  /** One challenger book definition. */
  public record Variant(
      String name, Map<String, RailOverride> rails, BigDecimal compositeThreshold) {}

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
    return new Variant(name, rails, raw.compositeThreshold);
  }

  /** The active challenger variants (possibly empty). */
  public List<Variant> all() {
    return variants;
  }

  /**
   * True when this variant's config would have ACCEPTED the rejected entry: every evaluated rail
   * passes after overrides (a disabled rail always passes; an overridden threshold re-scores the
   * recorded operand; a null operand keeps the original verdict — an override cannot conjure data)
   * and the composite clears the variant floor (default: the champion threshold). The composite
   * rail itself is floor-ruled, not pass/fail-ruled, mirroring the champion book's
   * {@code min-composite} precedent.
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
      int cmp = c.operand().compareTo(o.threshold());
      boolean pass = "GTE".equals(o.passWhen()) ? cmp >= 0 : cmp <= 0;
      if (!pass) {
        return false;
      }
    }
    BigDecimal floor =
        v.compositeThreshold() != null ? v.compositeThreshold() : d.compositeThreshold();
    return d.compositeScore() != null && floor != null && d.compositeScore().compareTo(floor) >= 0;
  }

  /** Jackson shape of one configured variant. */
  static final class RawVariant {
    public String name;
    public List<RawRail> rails;
    public BigDecimal compositeThreshold;
  }

  /** Jackson shape of one rail override. */
  static final class RawRail {
    public String rail;
    public boolean disable;
    public BigDecimal threshold;
    public String passWhen;
  }
}
