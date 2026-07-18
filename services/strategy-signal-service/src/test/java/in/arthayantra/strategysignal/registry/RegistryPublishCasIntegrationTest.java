package in.arthayantra.strategysignal.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * PF-01 round-5 #1 — the LIVE publish compare-and-set. Two concurrent promoters both read champion
 * V0; exactly one moves the live pointer, the other is rejected with a
 * {@code CONFLICT_PUBLISHED_VERSION_CHANGED} 409 BEFORE it can overwrite live state. Simulated
 * deterministically: a CAS publish against a now-stale expected version 409s and does NOT move the
 * pointer, while a CAS against the current version wins; the unconditional (legacy) publish is
 * unchanged.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock"})
class RegistryPublishCasIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubInstrumentClient {
    @Bean
    @Primary
    MarketDataInstrumentClient stubClient() {
      return (exchange, tradingsymbol) -> !"GHOST".equals(tradingsymbol);
    }
  }

  @Autowired private RegistryService service;
  @Autowired private StrategyRepository repository;

  private static final String YAML =
      """
      schema: strategy-schema/v1
      id: pf01-cas-walk
      name: "PF01 CAS Walk"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: RELIANCE }
      timeframes: { primary: 1m }
      indicators:
        - { name: EMA, alias: ema_fast, timeframe: 1m, params: { period: 9 }, weight: 1.0 }
        - { name: EMA, alias: ema_slow, timeframe: 1m, params: { period: 21 }, weight: 1.0 }
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
    return YAML.replace("period: 9", "period: " + fastPeriod);
  }

  private UUID versionId(UUID id, String semver) {
    return repository.findVersion(id, semver).orElseThrow().id();
  }

  @Test
  void casPublishGuardsTheLivePointer() {
    UUID id = (UUID) service.create("PF01 CAS Walk", "cas IT", List.of("test"), YAML).get("id");
    // publish 1.0.0 (unconditional, legacy behaviour) — the champion V0.
    service.publish(id, null, null);
    UUID v0 = versionId(id, "1.0.0");
    assertThat(repository.findVersionById(v0).orElseThrow().status()).isEqualTo("published");
    // round-7 #1: detail exposes the LIVE published pointer (the optimizer captures it as the
    // immutable champion its promote CAS-publishes against).
    assertThat(service.detail(id, null).get("publishedVersionId")).isEqualTo(v0);

    // a CAS publish of 1.0.1 against the CURRENT champion V0 WINS — the pointer moves to V1.
    service.update(id, withPeriod(11), null, "tune");
    Map<String, Object> won = service.publish(id, "1.0.1", null, true, v0.toString());
    assertThat(won.get("status")).isEqualTo("published");
    UUID v1 = versionId(id, "1.0.1");
    // round-6 #1: the publish response carries the EXACT published version UUID (not latestVersion).
    assertThat(won.get("versionId")).isEqualTo(v1);
    assertThat(repository.findVersionById(v1).orElseThrow().status()).isEqualTo("published");
    assertThat(repository.findVersionById(v0).orElseThrow().status()).isEqualTo("archived");

    // a CAS publish of 1.0.2 against the now-STALE expected V0 is REJECTED (a concurrent promoter
    // already moved the pointer to V1) — 409, and the pointer + the 1.0.2 draft are UNCHANGED.
    service.update(id, withPeriod(13), null, "sibling");
    assertThatThrownBy(() -> service.publish(id, "1.0.2", null, true, v0.toString()))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex ->
                assertThat(((ApiException) ex).code())
                    .isEqualTo(ErrorCodes.CONFLICT_PUBLISHED_VERSION_CHANGED));
    // the loser mutated nothing — 1.0.1 is still the live published version, 1.0.2 stays a draft.
    assertThat(repository.findVersionById(v1).orElseThrow().status()).isEqualTo("published");
    assertThat(repository.findVersion(id, "1.0.2").orElseThrow().status()).isEqualTo("draft");

    // a CAS publish against the CURRENT champion V1 wins — the pointer moves to 1.0.2.
    Map<String, Object> won2 = service.publish(id, "1.0.2", null, true, v1.toString());
    assertThat(won2.get("status")).isEqualTo("published");
    assertThat(repository.findVersion(id, "1.0.2").orElseThrow().status()).isEqualTo("published");
  }

  @Test
  void casPublishTargetsTheNamedVersionNotTheNewestDraft() {
    // round-6 #1: two concurrent promoters each mint a DISTINCT draft (update always inserts a new
    // version). A CAS-publishes ITS OWN version by name — the registry publishes THAT version, NOT
    // the newest draft — so A can never publish B's draft nor record B's UUID as its champion.
    // a distinct slug (the shared-DB IT harness needs a unique slug+name); use it CONSISTENTLY
    // across create + both updates (the config id must stay the strategy's slug).
    String base =
        YAML.replace("id: pf01-cas-walk", "id: pf01-cas-named").replace("PF01 CAS Walk", "PF01 CAS Named");
    UUID id = (UUID) service.create("PF01 CAS Named", "named IT", List.of("test"), base).get("id");
    service.publish(id, null, null); // champion V0 = 1.0.0
    UUID v0 = versionId(id, "1.0.0");
    service.update(id, base.replace("period: 9", "period: 21"), null, "A"); // A → draft 1.0.1
    service.update(id, base.replace("period: 9", "period: 22"), null, "B"); // B → draft 1.0.2 (newest)

    // A CAS-publishes ITS version 1.0.1 (NOT the newest 1.0.2).
    Map<String, Object> won = service.publish(id, "1.0.1", null, true, v0.toString());
    assertThat(won.get("version")).isEqualTo("1.0.1");
    assertThat(won.get("versionId")).isEqualTo(versionId(id, "1.0.1")); // A's exact version, not B's
    assertThat(repository.findVersion(id, "1.0.1").orElseThrow().status()).isEqualTo("published");
    assertThat(repository.findVersion(id, "1.0.2").orElseThrow().status()).isEqualTo("draft"); // B untouched
  }

  @Test
  void firstPublishCasAgainstNullExpectedWins() {
    // FIRST-CHAMPION: a CAS publish onto a never-published strategy (expected = null) wins.
    UUID id =
        (UUID) service.create("PF01 CAS First", "cas first IT", List.of("test"),
                withPeriod(15).replace("id: pf01-cas-walk", "id: pf01-cas-first")
                    .replace("PF01 CAS Walk", "PF01 CAS First"))
            .get("id");
    Map<String, Object> won = service.publish(id, "1.0.0", null, true, null);
    assertThat(won.get("status")).isEqualTo("published");
    assertThat(repository.findVersion(id, "1.0.0").orElseThrow().status()).isEqualTo("published");
  }
}
