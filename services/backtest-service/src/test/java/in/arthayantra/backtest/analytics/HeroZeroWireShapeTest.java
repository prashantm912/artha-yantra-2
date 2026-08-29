package in.arthayantra.backtest.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.analytics.HeroZeroPremiumService.HeroZeroPremium;
import in.arthayantra.backtest.analytics.HeroZeroPremiumService.HeroZeroTrade;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the SERIALISED shape of the hero-zero response after the D3 Map→record conversion.
 *
 * <p>⚠️ <b>The reserved-word key is the reason this test exists.</b> The JSON key is {@code "class"},
 * which is a Java reserved word — a record component cannot be named it, so the component is
 * {@code klass} and the key survives ONLY because of {@code @JsonProperty("class")}. Delete that
 * annotation and the wire silently renames the field to {@code klass}: no compile error, no test
 * failure anywhere else, and a consumer reading {@code class} just starts seeing nothing. This test
 * is the only thing standing between that annotation and a silent wire break.
 *
 * <p><b>Both trade tiers are asserted, because the item is where the real wire change is.</b> The
 * map this replaced was built across three conditional tiers: an unpriced trade emitted 8 keys, a
 * priced one ~22. The record emits all 22 always, so an unpriced row now carries ~14 explicit nulls
 * it did not before. That is a bigger change than the 5-vs-16 envelope the ratchet documented, and
 * asserting only the priced tier would miss it entirely.
 */
class HeroZeroWireShapeTest {

  /**
   * ⚠️ THE WIRE MAPPER, not a bare one — and the difference is the whole point of the decimal
   * assertion below. A plain {@code new ObjectMapper()} serialises {@code BigDecimal} as a JSON
   * NUMBER, so the first cut of this test failed on its own fixture rather than on the code: it was
   * measuring a mapper the application never uses.
   *
   * <p>{@code ArthaJacksonAutoConfiguration} registers
   * {@code serializerByType(BigDecimal.class, ToStringSerializer.instance)} platform-wide, which is
   * why every decimal is a STRING on our wire while bare springdoc infers {@code number}. This
   * mirrors that ONE registration deliberately — if the platform ever drops it, this test keeps
   * passing and the spec silently starts lying again, so the pin that really matters is the captured
   * spec. This asserts the shape we believe we emit.
   */
  private static final ObjectMapper OM =
      new ObjectMapper()
          .registerModule(
              new com.fasterxml.jackson.databind.module.SimpleModule()
                  .addSerializer(
                      java.math.BigDecimal.class,
                      com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance));

  private static HeroZeroTrade priced() {
    return new HeroZeroTrade(
        1, "NIFTY26SEP24000CE", "2026-09-01T09:20:00+05:30", "2026-09-01T09:50:00+05:30",
        new BigDecimal("100.00"), new BigDecimal("180.00"), new BigDecimal("4000.00"), true,
        new BigDecimal("220.0000"), "2026-09-01T09:40:00+05:30", new BigDecimal("95.0000"),
        new BigDecimal("2.2000"), new BigDecimal("1.8000"), "HERO", false,
        new BigDecimal("12.00"), true, "CE", new BigDecimal("24000"),
        new BigDecimal("90.0000"), new BigDecimal("11.11"), true);
  }

  @Test
  void theReservedWordKeyIsClassNotKlass() throws Exception {
    JsonNode node = OM.valueToTree(priced());

    assertThat(node.has("class"))
        .as(
            "the JSON key MUST be `class`. It survives only via @JsonProperty(\"class\") because a"
                + " record component cannot be named a reserved word — remove that annotation and the"
                + " wire silently renames to `klass` with no compile error and no other failing test.")
        .isTrue();
    assertThat(node.get("class").asText()).isEqualTo("HERO");
    assertThat(node.has("klass"))
        .as("the Java component name must NOT leak onto the wire")
        .isFalse();
  }

  @Test
  void anUnpricedTradeCarriesEveryKeyWithNullsRatherThanOmittingThem() throws Exception {
    JsonNode unpriced =
        OM.valueToTree(
            HeroZeroTrade.unpriced(
                7, "NIFTY26SEP24000PE", "2026-09-01T10:00:00+05:30", "2026-09-01T10:30:00+05:30",
                new BigDecimal("50.00"), new BigDecimal("40.00"), new BigDecimal("-500.00")));

    // The deliberate wire change: the map omitted these on an unpriced row; the record emits nulls.
    assertThat(unpriced.has("peakPremium")).isTrue();
    assertThat(unpriced.get("peakPremium").isNull()).isTrue();
    assertThat(unpriced.has("class")).isTrue();
    assertThat(unpriced.get("class").isNull()).isTrue();
    assertThat(unpriced.get("priced").asBoolean()).isFalse();

    // Same key set on both tiers — that is the point of the conversion.
    assertThat(fieldNames(unpriced)).isEqualTo(fieldNames(OM.valueToTree(priced())));
  }

  /**
   * ⚠️ The scalar-type trap: {@code ToStringSerializer} is registered for {@code BigDecimal}
   * platform-wide, so every decimal is a JSON STRING on our wire while bare springdoc infers
   * {@code number}. A key-set test cannot catch a drift here, because {@code asText()} returns the
   * same value for a textual node and a numeric one — assert {@code isTextual()}.
   */
  @Test
  void decimalsAreStringsOnTheWire() {
    JsonNode node = OM.valueToTree(priced());

    for (String decimal : List.of("entryPremium", "peakPremium", "peakMultiple", "strike")) {
      assertThat(node.get(decimal).isTextual())
          .as("%s must serialise as a STRING — the captured spec claims string", decimal)
          .isTrue();
    }
  }

  @Test
  void theEmptyEnvelopeCarriesEveryAggregateAsNull() {
    JsonNode empty =
        OM.valueToTree(
            new HeroZeroPremium(
                "run-1", 0, 0, null, null, null, null, null, null, null, null, null, null, null,
                "no trades", List.of()));

    assertThat(empty.has("heroes")).isTrue();
    assertThat(empty.get("heroes").isNull()).isTrue();
    assertThat(empty.get("trades").isArray()).isTrue();
    assertThat(empty.get("trades")).isEmpty();
  }

  private static List<String> fieldNames(JsonNode node) {
    return java.util.stream.StreamSupport.stream(
            java.util.Spliterators.spliteratorUnknownSize(
                ((ObjectNode) node).fieldNames(), java.util.Spliterator.ORDERED),
            false)
        .toList();
  }
}
