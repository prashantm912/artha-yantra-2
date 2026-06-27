package in.arthayantra.strategysignal.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * The Phase 21 acceptance IT: the FULL lifecycle create→edit→publish→edit→publish→rollback with
 * audit rows asserted in order; identical-content save is a 409 no-op; a PUT on a published
 * strategy mints a NEW draft; diff is server-side; the index_constituents publish guard fires;
 * the audit log is append-only BY GRANT; the schema endpoint serves byte-identically; the seed
 * draft exists from the repeatable migration.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegistryLifecycleIntegrationTest extends StrategySignalIntegrationTestBase {

  /** Context instruments resolve unless the symbol is GHOST (publish-check cases). */
  @TestConfiguration
  static class StubInstrumentClient {
    @Bean
    @Primary
    MarketDataInstrumentClient stubClient() {
      return (exchange, tradingsymbol) -> !"GHOST".equals(tradingsymbol);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private RegistryService service;
  @Autowired private StrategyRepository repository;

  private static UUID strategyId;

  private static final String BASE_YAML =
      """
      schema: strategy-schema/v1
      id: lifecycle-walk
      name: "Lifecycle Walk"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: RELIANCE }
      timeframes: { primary: 1m }
      indicators:
        - { name: EMA, alias: ema_fast, timeframe: 1m, params: { period: 9 }, weight: 1.0 }
        - { name: EMA, alias: ema_slow, timeframe: 1m, params: { period: 21 }, weight: 1.0 }
        - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 14 }, weight: 1.0,
            normalize: { type: rsi_momentum } }
      entry_rules:
        direction: long
        gate:
          all:
            - crossover: { fast: ema_fast, slow: ema_slow }
        scoring: { threshold: 0.5 }
      exit_rules:
        - { type: signal_exit, params: { rule: "crossunder(ema_fast, ema_slow)" } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: intraday }
      """;

  private static String withPeriod(int fastPeriod) {
    return BASE_YAML.replace("period: 9", "period: " + fastPeriod);
  }

  @Test
  @Order(20)
  void rowTagResyncIsMetadataOnlyAndRoundTripsTheTextArray() {
    String yaml =
        BASE_YAML.replace("lifecycle-walk", "tags-resync-walk").replace("Lifecycle Walk", "Tags Resync");
    UUID id =
        (UUID) service.create("Tags Resync", null, List.of("scalper", "two-candle-pattern"), yaml).get("id");

    // ARM: the real text[] UPDATE round-trips back through the JDBC driver (validates createArrayOf).
    List<String> armed = List.of("scalper", "two-candle-pattern", "rsi-s24-bands", "overbought-defer");
    assertThat(service.updateTags(id, armed)).isTrue();
    assertThat(repository.findById(id).orElseThrow().tags()).isEqualTo(armed);

    // idempotent: a second identical resync (by slug) is a no-op.
    assertThat(service.resyncTags("tags-resync-walk", armed)).isFalse();

    // metadata only: NO new version was minted (still the single draft 1.0.0).
    assertThat(repository.versions(id, 10, 0)).hasSize(1);
  }

  @Test
  @Order(1)
  void fullLifecycleWithAuditTrail() {
    // CREATE -> draft 1.0.0
    Map<String, Object> created =
        service.create("Lifecycle Walk", "IT walk", List.of("test"), BASE_YAML);
    strategyId = (UUID) created.get("id");
    assertThat(created.get("version")).isEqualTo("1.0.0");
    assertThat(created.get("status")).isEqualTo("draft");

    // EDIT (draft -> draft, patch bump)
    Map<String, Object> edited = service.update(strategyId, withPeriod(11), null, "tune fast ema");
    assertThat(edited.get("version")).isEqualTo("1.0.1");

    // PUBLISH 1.0.1
    Map<String, Object> published = service.publish(strategyId, null, null);
    assertThat(published.get("version")).isEqualTo("1.0.1");
    assertThat(published.get("status")).isEqualTo("published");

    // EDIT on published -> NEW draft 1.0.2 (published row untouched)
    Map<String, Object> redrafted = service.update(strategyId, withPeriod(13), null, null);
    assertThat(redrafted.get("version")).isEqualTo("1.0.2");
    assertThat(repository.findVersion(strategyId, "1.0.1").orElseThrow().status())
        .isEqualTo("published");

    // PUBLISH 1.0.2 -> 1.0.1 archives
    service.publish(strategyId, "1.0.2", null);
    assertThat(repository.findVersion(strategyId, "1.0.1").orElseThrow().status())
        .isEqualTo("archived");
    assertThat(repository.findVersion(strategyId, "1.0.2").orElseThrow().status())
        .isEqualTo("published");

    // ROLLBACK to 1.0.1 content -> new draft 1.0.3, byte-identical copy
    Map<String, Object> rolledBack = service.rollback(strategyId, "1.0.1", false);
    assertThat(rolledBack.get("newVersion")).isEqualTo("1.0.3");
    StrategyRepository.VersionRow v101 = repository.findVersion(strategyId, "1.0.1").orElseThrow();
    StrategyRepository.VersionRow v103 = repository.findVersion(strategyId, "1.0.3").orElseThrow();
    assertThat(v103.checksum()).isEqualTo(v101.checksum());
    assertThat(v103.configYaml()).isEqualTo(v101.configYaml());
    assertThat(v103.status()).isEqualTo("draft");

    // the audit trail records every mutating call IN ORDER
    assertThat(repository.auditActions(strategyId))
        .containsExactly("CREATE", "UPDATE_DRAFT", "PUBLISH", "UPDATE_DRAFT", "PUBLISH", "ROLLBACK");
  }

  @Test
  @Order(2)
  void identicalContentSaveIsANoOp() {
    StrategyRepository.VersionRow latest = repository.latestVersion(strategyId).orElseThrow();

    assertThatThrownBy(() -> service.update(strategyId, latest.configYaml(), null, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCodes.CONFLICT_NO_CONTENT_CHANGE));
  }

  @Test
  @Order(3)
  void diffIsComputedServerSide() {
    Map<String, Object> diff = service.diff(strategyId, "1.0.0", "1.0.1");

    @SuppressWarnings("unchecked")
    List<ConfigDiff.Op> ops = (List<ConfigDiff.Op>) diff.get("structured");
    assertThat(ops)
        .anySatisfy(
            op -> {
              assertThat(op.path()).isEqualTo("indicators[alias=ema_fast].params.period");
              assertThat(op.op()).isEqualTo("change");
              assertThat(op.before()).isEqualTo("9");
              assertThat(op.after()).isEqualTo("11");
            });
    assertThat(diff.get("yamlFrom").toString()).contains("period: 9");
    assertThat(diff.get("yamlTo").toString()).contains("period: 11");

    assertThatThrownBy(() -> service.diff(strategyId, "1.0.1", "1.0.1"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
  }

  @Test
  @Order(4)
  void indexConstituentsUniversePublishesAfterTheGuardLift() {
    String constituents =
        BASE_YAML
            .replace("id: lifecycle-walk", "id: constituents-guard")
            .replace("name: \"Lifecycle Walk\"", "name: \"Constituents Guard\"")
            .replace(
                """
                universe:
                  mode: explicit
                  instruments:
                    - { exchange: NSE, tradingsymbol: RELIANCE }
                """,
                """
                universe:
                  mode: index_constituents
                  index: "NIFTY 100"
                """);
    Map<String, Object> created =
        service.create("Constituents Guard", null, null, constituents);
    UUID id = (UUID) created.get("id");

    // Phase 44: the publish guard is LIFTED — index_constituents now publishes (the resolver pins
    // membership by copy at submission). Previously this threw 422 STRATEGY_UNIVERSE_UNSUPPORTED.
    service.publish(id, null, null);
    assertThat(service.detail(id, null).get("status")).isEqualTo("published");
  }

  @Test
  @Order(5)
  void unresolvedContextInstrumentRefusesPublish() {
    String vixOverride =
        """
        - { name: VIX_LEVEL, alias: vix, timeframe: 1m, weight: 0.0,
              instrument: { exchange: NSE, tradingsymbol: GHOST } }
          - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 14 }, weight: 1.0,
              normalize: { type: rsi_momentum } }\
        """;
    String rsiOnly =
        """
        - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 14 }, weight: 1.0,
              normalize: { type: rsi_momentum } }\
        """;
    String ghost =
        BASE_YAML
            .replace("id: lifecycle-walk", "id: ghost-context")
            .replace("name: \"Lifecycle Walk\"", "name: \"Ghost Context\"")
            .replace(rsiOnly, vixOverride);
    UUID id = (UUID) service.create("Ghost Context", null, null, ghost).get("id");

    assertThatThrownBy(() -> service.publish(id, null, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(422);
              assertThat(e.code()).isEqualTo(ErrorCodes.STRATEGY_SCHEMA_INVALID);
            });
  }

  @Test
  @Order(6)
  void deleteIsDraftsOnlyOtherwiseArchives() {
    // drafts-only strategy hard-deletes
    UUID draftOnly =
        (UUID)
            service.create(
                    "Draft Only",
                    null,
                    null,
                    BASE_YAML
                        .replace("id: lifecycle-walk", "id: draft-only")
                        .replace("name: \"Lifecycle Walk\"", "name: \"Draft Only\""))
                .get("id");
    assertThat(service.delete(draftOnly)).isTrue();
    assertThat(repository.findById(draftOnly)).isEmpty();

    // published history archives + 409
    assertThatThrownBy(() -> service.delete(strategyId))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCodes.CONFLICT_HAS_PUBLISHED_HISTORY);
              assertThat(e.details()).containsEntry("archived", true);
            });
    assertThat(repository.findById(strategyId)).isPresent();
  }

  @Test
  @Order(7)
  void auditLogIsAppendOnlyByGrant() throws SQLException {
    try (Connection conn = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
        Statement st = conn.createStatement()) {
      st.execute("SET ROLE ay_strategy");
      st.execute("SET search_path TO strategy");
      // INSERT allowed
      st.execute(
          "INSERT INTO strategy_audit_log (strategy_id, action, actor) VALUES ('"
              + strategyId + "', 'ARCHIVE', 'grant-test')");
      // UPDATE/DELETE denied by grant
      assertThatThrownBy(() -> st.execute("UPDATE strategy_audit_log SET actor = 'tamper'"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(() -> st.execute("DELETE FROM strategy_audit_log"))
          .hasMessageContaining("permission denied");
    }
  }

  @Test
  @Order(8)
  void schemaDocumentServesByteIdentically() throws Exception {
    String fromDisk =
        Files.readString(
            locateSchemaResource(), StandardCharsets.UTF_8);

    mockMvc
        .perform(MockMvcRequestBuilders.get("/api/v1/strategies/schema/v1"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.content().string(fromDisk));
  }

  @Test
  @Order(9)
  void seedDraftExistsFromRepeatableMigration() throws Exception {
    StrategyRepository.StrategyRow seed =
        repository.findBySlug("minimal-ema-crossover").orElseThrow();
    StrategyRepository.VersionRow version =
        repository.latestVersion(seed.id()).orElseThrow();
    assertThat(version.status()).isEqualTo("draft");

    // the seed publishes cleanly through the REST surface (the MVP demo path)
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/strategies/" + seed.id() + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("published"));
  }

  @Test
  @Order(10)
  void validateEndpointFlagsWarningsStateless() throws Exception {
    String unknownIndicator = BASE_YAML.replace("name: RSI", "name: PE_RATIO");
    String body =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(Map.of("config", unknownIndicator));

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/strategies/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.valid").value(true))
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.warnings[*].message")
                .value(org.hamcrest.Matchers.hasItem(
                    org.hamcrest.Matchers.containsString("PE_RATIO"))));
  }

  @Test
  @Order(11)
  void filtersApplyBeforePaginationNotAfter() {
    // three strategies share a unique tag; three NEWER untagged ones are created afterwards, so a
    // naive LIMIT/OFFSET-then-filter (the bug) would page over the newest untagged rows and return
    // an empty filtered page. Correct behaviour filters first, then paginates the matched set.
    for (int i = 0; i < 3; i++) {
      service.create(
          "Pager Hit " + i, null, List.of("pgtest"),
          BASE_YAML
              .replace("id: lifecycle-walk", "id: pager-hit-" + i)
              .replace("name: \"Lifecycle Walk\"", "name: \"Pager Hit " + i + "\""));
    }
    for (int i = 0; i < 3; i++) {
      service.create(
          "Pager Noise " + i, null, List.of("noise"),
          BASE_YAML
              .replace("id: lifecycle-walk", "id: pager-noise-" + i)
              .replace("name: \"Lifecycle Walk\"", "name: \"Pager Noise " + i + "\""));
    }

    List<Map<String, Object>> page1 = service.list(null, "pgtest", null, 2, 0);
    List<Map<String, Object>> page2 = service.list(null, "pgtest", null, 2, 2);

    assertThat(page1).hasSize(2); // FILTERED page, not a pre-truncated unfiltered page (= 0 in the bug)
    assertThat(page2).hasSize(1); // exactly three matched, paginated over the filtered set
    assertThat(page1)
        .allSatisfy(m -> assertThat(((List<?>) m.get("tags")).contains("pgtest")).isTrue());
    java.util.Set<Object> ids = new java.util.HashSet<>();
    page1.forEach(m -> ids.add(m.get("id")));
    page2.forEach(m -> ids.add(m.get("id")));
    assertThat(ids).as("no overlap; union covers all three matches").hasSize(3);
  }

  @Test
  @Order(12)
  void reDeletingAnArchivedStrategyWritesNoPhantomAudit() {
    UUID id =
        (UUID)
            service
                .create(
                    "Archive Idem", null, null,
                    BASE_YAML
                        .replace("id: lifecycle-walk", "id: archive-idem")
                        .replace("name: \"Lifecycle Walk\"", "name: \"Archive Idem\""))
                .get("id");
    service.publish(id, null, null);

    // first delete demotes the published version (archived:true, 409)
    assertThatThrownBy(() -> service.delete(id))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.details()).containsEntry("archived", true));
    long archivesAfterFirst =
        repository.auditActions(id).stream().filter("ARCHIVE"::equals).count();

    // re-delete: already archived (no published version) — no re-archive, accurate message
    assertThatThrownBy(() -> service.delete(id))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCodes.CONFLICT_HAS_PUBLISHED_HISTORY);
              assertThat(e.details()).containsEntry("archived", false);
            });

    assertThat(repository.auditActions(id).stream().filter("ARCHIVE"::equals).count())
        .as("re-delete must NOT append a phantom ARCHIVE row to the append-only log")
        .isEqualTo(archivesAfterFirst);
  }

  private static java.nio.file.Path locateSchemaResource() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate =
          dir.resolve(
              "libs/strategy-schema/src/main/resources/strategy-schema/strategy-schema-v1.json");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("schema resource not found");
  }
}
