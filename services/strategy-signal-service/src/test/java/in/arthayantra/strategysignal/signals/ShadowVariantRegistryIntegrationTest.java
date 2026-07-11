package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.RailCheck;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.RejectionDiagnostic;
import in.arthayantra.strategysignal.scalper.StrikePicker;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * EVO E3 §11 runtime shadow-variant registration IT (mock profile, real V031 lineage,
 * engine-disabled so the paper-IT boot rule holds). Proves: register validates against the existing
 * vocabulary + durably persists + HOT-RELOADS the live active set (a DB registration opens a
 * challenger book with NO restart), the relaxing-or-neutral gate rejects a tightening composite floor
 * (422 EVIDENCE_PLANE_UNSUPPORTED), an unknown knob / duplicate name / full cap are rejected, and a
 * retire soft-disables (history stays) + drops the variant from the live set. Shared singleton DB —
 * the registry table is wiped per method so this class stays isolated from the env-only shadow ITs.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.scalper.shadow-book.enabled=true",
      "artha.scalper.shadow-book.poll-ms=3600000",
      "artha.scalper.shadow-book.max-variants=4",
      "artha.scalper.shadow-book.champion-composite-threshold=0.60"
    })
class ShadowVariantRegistryIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private ShadowVariantRegistry registry;
  @Autowired private ShadowVariantRegistryRepository repo;
  @Autowired private ShadowVariantsController controller;
  @Autowired private ShadowBookService shadowBook;
  @Autowired private SignalRejectionRepository rejections;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;

  @BeforeEach
  @AfterEach
  void resetRegistry() {
    jdbc.update("DELETE FROM shadow_variant_registry");
    registry.reload(); // clear the live active snapshot so methods don't leak into one another
  }

  private String uniqueName(String stem) {
    return stem + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private JsonNode spec(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID versionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }

  private long rejection(UUID versionId, String slug) {
    return rejections.insert(
        versionId, slug, "NFO", "SVRFUT", "3m", "CE", "volume-floor", new BigDecimal("14000"),
        new BigDecimal("125000"), new BigDecimal("-111000"), "volume < 125000",
        new BigDecimal("0.70"), new BigDecimal("0.60"), "{}", OffsetDateTime.now());
  }

  private static RailCheck rail(String name, boolean pass, String operand, String threshold) {
    BigDecimal o = operand == null ? null : new BigDecimal(operand);
    BigDecimal t = threshold == null ? null : new BigDecimal(threshold);
    return new RailCheck(name, pass, o, t, o != null && t != null ? o.subtract(t) : null, "it", null);
  }

  private RejectionDiagnostic volumeBlockedDiag(String optSym) {
    // volume 14000 fails the champion 125k floor; rsi passes → only a variant that relaxes
    // volume-floor accepts the entry
    return new RejectionDiagnostic(
        "volume-floor", OptionType.CE, new BigDecimal("14000"), new BigDecimal("125000"),
        new BigDecimal("-111000"), "volume < 125000", new BigDecimal("0.70"), new BigDecimal("0.60"),
        List.of(rail("volume-floor", false, "14000", "125000"), rail("rsi-band", true, "62", "60")),
        null, null, "NIFTY 50", LocalDate.now().plusDays(5),
        new StrikePicker.Pick(
            new StrikePicker.Candidate(
                optSym, new BigDecimal("24250"), OptionType.CE, new BigDecimal("100.00"),
                new BigDecimal("0.12")),
            new BigDecimal("0.55")),
        null);
  }

  @Test
  void registerPersistsAndHotReloadsTheLiveActiveSet() {
    String name = uniqueName("vreg-disable");
    UUID campaign = UUID.randomUUID();
    var row =
        registry.register(
            name, campaign, spec("{\"rails\":[{\"rail\":\"volume-floor\",\"disable\":true}]}"), "evo:test");
    assertThat(row.enabled()).isTrue();
    assertThat(row.id()).isNotNull();
    assertThat(row.createdBy()).isEqualTo("evo:test");
    assertThat(row.campaignId()).isEqualTo(campaign); // the soft campaign ref round-trips

    // hot-reloaded: the live active set now carries the DB variant WITHOUT a restart
    assertThat(registry.active()).extracting(ShadowVariants.Variant::name).contains(name);

    // and the live rejection path opens that challenger's book from the DB registration
    UUID v = versionId();
    String slug = "svr-open-" + UUID.randomUUID();
    String optSym = "SVROPT" + UUID.randomUUID().toString().substring(0, 8);
    long r = rejection(v, slug);
    shadowBook.maybeOpen(r, v, slug, "NFO", "SVRFUT", OffsetDateTime.now(), volumeBlockedDiag(optSym));

    List<String> variants =
        jdbc.queryForList(
            "SELECT variant FROM shadow_positions WHERE strategy_slug = ? ORDER BY variant",
            String.class, slug);
    assertThat(variants).containsExactly("champion", name);
  }

  @Test
  void listReturnsEnabledAndRetiredNewestFirst() {
    String keep = uniqueName("vreg-keep");
    String gone = uniqueName("vreg-gone");
    registry.register(keep, null, spec("{\"compositeThreshold\":0.55}"), null);
    registry.register(gone, null, spec("{\"rails\":[{\"rail\":\"volume-floor\",\"disable\":true}]}"), null);
    registry.disable(gone);

    var items = controller.list().items();
    assertThat(items).extracting(ShadowVariantsController.ShadowVariantView::name).contains(keep, gone);
    var goneView =
        items.stream().filter(i -> i.name().equals(gone)).findFirst().orElseThrow();
    assertThat(goneView.enabled()).isFalse();
    assertThat(goneView.disabledAt()).isNotNull();
    // the typed spec projection round-trips the vocabulary body
    var keepView = items.stream().filter(i -> i.name().equals(keep)).findFirst().orElseThrow();
    assertThat(keepView.spec().compositeThreshold()).isEqualByComparingTo("0.55");
  }

  @Test
  void retireSoftDisablesAndDropsFromTheLiveSet() {
    String name = uniqueName("vreg-retire");
    registry.register(name, null, spec("{\"rails\":[{\"rail\":\"volume-floor\",\"disable\":true}]}"), null);
    assertThat(registry.active()).extracting(ShadowVariants.Variant::name).contains(name);

    int updated = registry.disable(name);
    assertThat(updated).isEqualTo(1);
    assertThat(registry.active()).extracting(ShadowVariants.Variant::name).doesNotContain(name);
    // the row survives (history stays), just flipped
    assertThat(repo.findByName(name)).isPresent();
    assertThat(repo.findByName(name).orElseThrow().enabled()).isFalse();
    // idempotent retire — no throw, no further change
    assertThat(registry.disable(name)).isZero();
  }

  @Test
  void retireOfUnknownNameIs404() {
    assertThatThrownBy(() -> controller.retire("no-such-variant-xyz"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
  }

  @Test
  void tighteningCompositeFloorIsRejected() {
    // 0.75 > the 0.60 champion reference → a tightening variant has no paired plane (§3.3.3)
    assertThatThrownBy(
            () -> registry.register(uniqueName("vreg-tight"), null, spec("{\"compositeThreshold\":0.75}"), null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(422);
              assertThat(e.code()).isEqualTo("EVIDENCE_PLANE_UNSUPPORTED");
            });
    // a relaxing floor at/below the reference is accepted
    var ok = registry.register(uniqueName("vreg-loose"), null, spec("{\"compositeThreshold\":0.55}"), null);
    assertThat(ok.enabled()).isTrue();
  }

  @Test
  void unknownKnobKindIsRejected() {
    // the ENV-plane relative-vol k/N (§2.3) is outside the vocabulary → strict-parse 422
    assertThatThrownBy(
            () ->
                registry.register(
                    uniqueName("vreg-bad"), null,
                    spec("{\"rails\":[{\"rail\":\"volume-floor\",\"disable\":true}],\"relativeVolK\":1.5}"),
                    null))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(422));
    // a reserved/invalid name is likewise rejected against the existing regex
    assertThatThrownBy(
            () -> registry.register("champion", null, spec("{\"rails\":[]}"), null))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(422));
  }

  @Test
  void duplicateNameIsConflict() {
    String name = uniqueName("vreg-dup");
    registry.register(name, null, spec("{\"rails\":[{\"rail\":\"volume-floor\",\"disable\":true}]}"), null);
    assertThatThrownBy(
            () -> registry.register(name, null, spec("{\"compositeThreshold\":0.55}"), null))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(409));
  }

  @Test
  void capIsEnforcedOnTheTotalActiveSet() {
    // max-variants=4: register four, the fifth trips the cap
    for (int i = 0; i < 4; i++) {
      registry.register(uniqueName("vreg-cap" + i), null, spec("{\"compositeThreshold\":0.55}"), null);
    }
    assertThatThrownBy(
            () -> registry.register(uniqueName("vreg-cap-over"), null, spec("{\"compositeThreshold\":0.55}"), null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(422);
              assertThat(e.code()).isEqualTo("CONFLICT_SHADOW_VARIANT_CAP");
            });
  }
}
