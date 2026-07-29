package in.arthayantra.strategysignal.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.signals.SignalEngine;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Publish-time BTST exit parity gate and reload-safety proof. */
@SpringBootTest(properties = {"spring.profiles.active=mock"})
class BtstPublishParityIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubInstrumentClient {
    @Bean
    @Primary
    MarketDataInstrumentClient stubClient() {
      return (exchange, tradingsymbol) -> true;
    }
  }

  @Autowired private RegistryService service;
  @Autowired private StrategyRepository repository;
  @Autowired private SignalEngine signalEngine;

  private static final String BASE_YAML =
      """
      schema: strategy-schema/v1
      id: btst-publish-placeholder
      name: "BTST Publish Placeholder"
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

  @Test
  void rejectsBtstTrailingStopAtPublish() {
    assertPublishRejected(
        "trailing-stop",
        "- { type: trailing_stop, params: { basis: percent, value: 5 } }",
        "trailing_stop");
  }

  @Test
  void rejectsBtstAtrMultipleStopAtPublish() {
    assertPublishRejected(
        "atr-multiple",
        "- { type: stop_loss, params: { basis: atr_multiple, value: 2, atr_period: 14 } }",
        "atr_multiple");
  }

  @Test
  void publishesParitySafeBtstRules() {
    String suffix = uniqueSuffix();
    String yaml =
        config(
            "safe-" + suffix,
            "Safe " + suffix,
            "btst",
            """
            - { type: stop_loss, params: { basis: premium_pct, value: 50 } }
            - { type: time_stop, params: { max_holding_days: 1 } }
            """);

    UUID id = create("Safe " + suffix, yaml);

    var published = service.publish(id, null, null);

    assertThat(published.status()).isEqualTo("published");
  }

  /**
   * The exit-rule type {@code square_off} and the unrelated {@code risk.session.square_off} HH:MM
   * field share a name. All three seeded btst YAMLs carry the session field, so a predicate that
   * strayed off {@code exit_rules[].type} would refuse every one of them at publish — with no test
   * to catch it. Pins the collision.
   */
  @Test
  void publishesBtstCarryingTheSessionSquareOffField() {
    String suffix = uniqueSuffix();
    String yaml =
        config(
                "sessionsquareoff-" + suffix,
                "Session square-off " + suffix,
                "btst",
                "- { type: stop_loss, params: { basis: premium_pct, value: 50 } }")
            .replace(
                "session: { style: btst }",
                "session: { style: btst, square_off: \"15:20\", pre_close_at: \"15:20\" }");

    UUID id = create("Session square-off " + suffix, yaml);

    assertThat(service.publish(id, null, null).status()).isEqualTo("published");
  }

  /** {@code scaled_exit} is refused as a consequence of the ALLOWLIST, not an explicit denylist. */
  @Test
  void rejectsBtstScaledExitAtPublish() {
    assertPublishRejected(
        "scaled-exit",
        "- { type: scaled_exit, params: { tiers: [ { profit_pct: 20, qty_pct: 0.5 } ] } }",
        "scaled_exit");
  }

  /** {@code index_points} is a LEVEL BASIS, refused on an otherwise-allowed type. */
  @Test
  void rejectsBtstIndexPointsBasisAtPublish() {
    assertPublishRejected(
        "index-points",
        "- { type: take_profit, params: { basis: index_points, value: 40 } }",
        "index_points");
  }

  @Test
  void publishesTrailingStopWhenStrategyIsNotBtst() {
    String suffix = uniqueSuffix();
    String yaml =
        config(
            "intraday-" + suffix,
            "Intraday " + suffix,
            "intraday",
            "- { type: trailing_stop, params: { basis: percent, value: 5 } }");

    UUID id = create("Intraday " + suffix, yaml);

    var published = service.publish(id, null, null);

    assertThat(published.status()).isEqualTo("published");
  }

  /**
   * The drift the gate explains is anchored on the CONFIGURED pre-close, not a literal "15:20" — the
   * sim assembles its synthetic bar up to {@code risk.session.pre_close_at} ({@code PreCloseBarView},
   * {@code TickwiseGoldenRunner:159}; default "15:20" per {@code StrategyCompiler:200}). Without this
   * every rejection test uses the default, so the suite would pass identically against a hardcoded
   * literal — and the message would silently lie to an owner running a custom pre-close.
   */
  @Test
  void rejectionMessageReportsTheConfiguredPreCloseNotALiteral() {
    String suffix = uniqueSuffix();
    String yaml =
        config(
                "precustom-" + suffix,
                "Pre-close custom " + suffix,
                "btst",
                "- { type: trailing_stop, params: { basis: percent, value: 5 } }")
            .replace("session: { style: btst }", "session: { style: btst, pre_close_at: \"15:15\" }");

    UUID id = create("Pre-close custom " + suffix, yaml);

    assertThatThrownBy(() -> service.publish(id, null, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e ->
                assertThat(e.details().get("errors").toString())
                    .contains("15:15")
                    .doesNotContain("15:20"));
  }

  @Test
  void reloadLoadsAnAlreadyPublishedParityUnsafeBtstVersion() {
    String suffix = uniqueSuffix();
    String slug = "reload-" + suffix;
    UUID id = create(
        "Reload " + suffix,
        config(
            slug,
            "Reload " + suffix,
            "btst",
            "- { type: trailing_stop, params: { basis: percent, value: 5 } }"));
    StrategyRepository.VersionRow version =
        repository.findVersion(id, "1.0.0").orElseThrow();

    // Model a legacy/pre-existing published row without going through the new publish gate.
    repository.updateVersionStatus(version.id(), "published", true);
    repository.setPublishedVersion(id, version.id());

    // NOTE: this line is NEAR-VACUOUS on its own — reload() wraps each strategy in
    // catch (RuntimeException) (SignalEngine:423-425) and ApiException extends RuntimeException, so
    // even if reload DID route through the publish gate the 422 would be swallowed and this would
    // still pass.
    assertThatCode(signalEngine::reload).doesNotThrowAnyException();
    assertThat(repository.findVersionById(version.id()).orElseThrow().status())
        .isEqualTo("published");
    // *** THE LOAD-BEARING ASSERTION — do NOT delete as redundant. *** This is the ONLY line that
    // proves the safety property: an already-published parity-unsafe version still LOADS, i.e. the
    // publish gate cannot brick the live engine at boot.
    assertThat(signalEngine.loadedSlugs()).contains(slug);
  }

  private void assertPublishRejected(String label, String exitRule, String expected) {
    String suffix = uniqueSuffix();
    String yaml = config("unsafe-" + label + "-" + suffix, "Unsafe " + label + " " + suffix, "btst", exitRule);
    UUID id = create("Unsafe " + label + " " + suffix, yaml);

    assertThatThrownBy(() -> service.publish(id, null, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(422);
              assertThat(e.code()).isEqualTo(ErrorCodes.STRATEGY_SCHEMA_INVALID);
              assertThat(e.details()).containsKey("errors");
              assertThat(e.details().get("errors").toString())
                  .contains("/exit_rules", expected, "15:20", "15:30");
            });
  }

  private UUID create(String name, String yaml) {
    return service.create(name, null, null, yaml).id();
  }

  private static String config(String slug, String name, String style, String exitRules) {
    String formattedExitRules =
        exitRules.trim().replace("\n", "\n  ");
    return BASE_YAML
        .replace("btst-publish-placeholder", slug)
        .replace("BTST Publish Placeholder", name)
        .replace(
            "- { type: signal_exit, params: { rule: \"crossunder(ema_fast, ema_slow)\" } }",
            formattedExitRules)
        .replace("session: { style: intraday }", "session: { style: " + style + " }");
  }

  private static String uniqueSuffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }
}
