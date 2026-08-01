package in.arthayantra.strategysignal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import in.arthayantra.strategysignal.notifier.NotifierController;
import in.arthayantra.strategysignal.notifier.NotifierService;
import in.arthayantra.strategysignal.paper.RiskController;
import in.arthayantra.strategysignal.paper.RiskService;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository;
import in.arthayantra.strategysignal.paper.RiskViews;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.RegistryViews;
import in.arthayantra.strategysignal.registry.UniverseController;
import in.arthayantra.strategysignal.registry.UniverseResolver;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Wire guard for the D3 Map-return conversion (strategy-signal slice): the four handlers that
 * returned a {@code Map<String,Object>} now return records, and this pins the EXACT rendered key
 * set, key ORDER and null handling of each so the retyping cannot have changed the wire.
 *
 * <p>The expected key lists below were captured by serializing the PRE-conversion handlers, so they
 * are the observed old wire, not a restatement of the new records. Two deliberate specifics they
 * encode: the risk audit row's key is SNAKE_CASE {@code created_at} (it came from {@code
 * jdbc.queryForList}, whose keys are SQL column labels), and every nullable field is emitted as an
 * explicit {@code null} rather than omitted — the old {@code LinkedHashMap}s put them
 * unconditionally, so a record forcing them present is identical, not a new key.
 */
class MapReturnConversionWireTest {

  /** Mirrors Spring Boot's JacksonAutoConfiguration (it disables WRITE_DATES_AS_TIMESTAMPS). */
  private static final ObjectMapper MAPPER =
      Jackson2ObjectMapperBuilder.json()
          .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private static List<String> keysOf(JsonNode node) {
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  private static JsonNode render(Object value) {
    return MAPPER.valueToTree(value);
  }

  // ── POST /api/v1/strategies/{id}/notifications/test ──────────────────────────────────────────

  @Test
  void notifierTestSendKeepsItsSingleStatusKey() {
    NotifierController controller = new NotifierController(mock(NotifierService.class));
    JsonNode body = render(controller.test(UUID.randomUUID()));
    assertThat(keysOf(body)).containsExactly("status");
    assertThat(body.get("status").asText()).isEqualTo("SENT");
  }

  // ── GET /api/v1/strategies/{id}/universe ─────────────────────────────────────────────────────

  private static UniverseController universeController(UniverseResolver resolver) {
    RegistryService registry = mock(RegistryService.class);
    RegistryViews.StrategyDetail detail =
        new RegistryViews.StrategyDetail(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            "slug",
            "name",
            null,
            List.of(),
            true,
            "1.0.0",
            "draft",
            MAPPER.createObjectNode(),
            "",
            "chk",
            null,
            OffsetDateTime.parse("2026-07-31T10:00:00Z"),
            OffsetDateTime.parse("2026-07-31T10:00:00Z"),
            false,
            null);
    when(registry.detail(any(), any())).thenReturn(detail);
    return new UniverseController(registry, resolver);
  }

  @Test
  void universeKeepsItsSixKeysInOrderWhenFullyPopulated() {
    UniverseResolver resolver = mock(UniverseResolver.class);
    when(resolver.resolve(any()))
        .thenReturn(
            new UniverseResolver.ResolvedUniverse(
                "index_constituents",
                "2026-07-31",
                List.of(new UniverseResolver.Constituent("NSE", "RELIANCE")),
                "abc123",
                "caveat text"));
    JsonNode body = render(universeController(resolver).universe(UUID.randomUUID(), null));

    assertThat(keysOf(body))
        .containsExactly("mode", "asOf", "constituentCount", "checksum", "survivorshipCaveat", "items");
    assertThat(body.get("mode").asText()).isEqualTo("index_constituents");
    assertThat(body.get("asOf").asText()).isEqualTo("2026-07-31");
    assertThat(body.get("constituentCount").asInt()).isEqualTo(1);
    assertThat(body.get("checksum").asText()).isEqualTo("abc123");
    assertThat(body.get("survivorshipCaveat").asText()).isEqualTo("caveat text");
    assertThat(keysOf(body.get("items").get(0))).containsExactly("exchange", "tradingsymbol");
  }

  /**
   * The nullable branch — {@code explicit}/{@code futures_*} resolve with a null {@code asOf} AND a
   * null {@code survivorshipCaveat}. Both must still be PRESENT as explicit nulls: the old
   * {@code LinkedHashMap} put them unconditionally, so omitting either would be the wire change.
   */
  @Test
  void universeStillEmitsAsOfAndCaveatAsExplicitNulls() {
    UniverseResolver resolver = mock(UniverseResolver.class);
    when(resolver.resolve(any()))
        .thenReturn(new UniverseResolver.ResolvedUniverse("explicit", null, List.of(), "def456", null));
    JsonNode body = render(universeController(resolver).universe(UUID.randomUUID(), null));

    assertThat(keysOf(body))
        .containsExactly("mode", "asOf", "constituentCount", "checksum", "survivorshipCaveat", "items");
    assertThat(body.get("asOf").isNull()).isTrue();
    assertThat(body.get("survivorshipCaveat").isNull()).isTrue();
    assertThat(body.get("constituentCount").asInt()).isZero();
    assertThat(body.get("items")).isEmpty();
  }

  // ── GET / PUT /api/v1/risk/settings ──────────────────────────────────────────────────────────

  private static RiskService riskService() throws Exception {
    RiskService risk = mock(RiskService.class);
    RiskViews.RiskSettingRow row =
        new RiskViews.RiskSettingRow(
            "kill_switch",
            MAPPER.readTree("{\"enabled\":false}"),
            OffsetDateTime.parse("2026-07-31T10:00:00Z"));
    RiskSettingsRepository.AuditEntry entry =
        new RiskSettingsRepository.AuditEntry(
            "kill_switch", "UPDATE", null, OffsetDateTime.parse("2026-07-31T10:00:00Z"));
    when(risk.settingsView(any()))
        .thenAnswer(
            inv -> new RiskViews.RiskSettings(inv.getArgument(0), List.of(row), List.of(entry)));
    when(risk.audit(any(), anyInt())).thenReturn(List.of(entry));
    return risk;
  }

  @Test
  void riskSettingsKeepsItsEnvelopeItemAndAuditKeys() throws Exception {
    RiskController controller = new RiskController(riskService(), MAPPER);
    JsonNode body = render(controller.settings("scalper"));

    assertThat(keysOf(body)).containsExactly("book", "items", "audit");
    assertThat(body.get("book").asText()).isEqualTo("scalper");
    assertThat(keysOf(body.get("items").get(0))).containsExactly("key", "value", "updatedAt");

    // created_at is SNAKE_CASE on purpose — the pre-conversion value was a jdbc.queryForList map
    // keyed by SQL column labels. Renaming it to createdAt would be a silent wire break.
    JsonNode audit = body.get("audit").get(0);
    assertThat(keysOf(audit)).containsExactly("key", "action", "detail", "created_at");
    assertThat(audit.get("detail").isNull()).as("nullable V006 column stays an explicit null").isTrue();
  }

  /** An absent/blank {@code book} still defaults to the scalper book (unchanged by the retyping). */
  @Test
  void riskSettingsDefaultsTheBookWhenAbsent() throws Exception {
    RiskController controller = new RiskController(riskService(), MAPPER);
    assertThat(render(controller.settings(null)).get("book").asText()).isEqualTo("scalper");
    assertThat(render(controller.settings("  ")).get("book").asText()).isEqualTo("scalper");
  }

  /** {@code PUT} answers with the very same envelope shape the {@code GET} does. */
  @Test
  void riskUpdateAnswersWithTheSameEnvelopeAsGet() throws Exception {
    RiskService risk = riskService();
    RiskController controller = new RiskController(risk, MAPPER);
    JsonNode body =
        render(
            controller.update(
                new RiskController.UpdateBody(
                    "scalper", RiskService.KILL_SWITCH, MAPPER.readTree("{\"enabled\":true}"))));

    assertThat(keysOf(body)).containsExactly("book", "items", "audit");
    assertThat(keysOf(body.get("audit").get(0)))
        .containsExactly("key", "action", "detail", "created_at");
  }
}
