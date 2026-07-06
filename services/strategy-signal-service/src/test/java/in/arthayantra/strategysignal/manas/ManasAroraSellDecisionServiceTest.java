package in.arthayantra.strategysignal.manas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalRepository;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the audit-H11 gap on the Manas sell-decision triad's orchestration (the per-symbol evaluator
 * math is exercised by {@link ManasAroraSwingEngineTest}; this asserts the {@code report()}
 * branches): family scoping (a foreign anchor is never even fetched), superseded-version adoption
 * (the H2 mirror — an orphaned holding still shows), and per-symbol exception isolation (one bad
 * symbol never sinks the report). Uses an empty candle series so {@code decide()} throws early after
 * the fetch — enough to assert which anchors are scoped IN (fetched) vs OUT (never fetched).
 */
class ManasAroraSellDecisionServiceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-07-07T14:30:00Z"), ZoneOffset.UTC);
  private static final OffsetDateTime AT = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, IST);

  @Test
  void aForeignFamilyAnchorIsScopedOutAndNeverFetched() throws IOException {
    StrategyRepository registry = mock(StrategyRepository.class);
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    SignalRepository signals = mock(SignalRepository.class);
    // No published manas swing strategy, and the anchor's version doesn't resolve to a manas swing
    // config (adoptDef → empty) → this anchor belongs to some other family.
    when(registry.listAll()).thenReturn(List.of());
    UUID foreign = UUID.randomUUID();
    when(registry.findVersionById(foreign)).thenReturn(Optional.empty());
    when(signals.activeEntries()).thenReturn(List.of(anchor(1L, foreign, "OTHERCO")));

    var report = service(registry, candles, signals).report();

    assertThat(report.items()).isEmpty();
    verify(candles, never()).fetch(any(), any(), any(), any(), any());
  }

  @Test
  void aScopedInAnchorIsFetchedAndAFetchFailureIsIsolatedNotFatal() throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    StrategyRepository registry = mock(StrategyRepository.class);
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(registry.listAll()).thenReturn(List.of(publishedManasStrategy(strategyId, publishedVersion)));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(manasVersion(publishedVersion, strategyId, "published")));
    // Two held anchors, both scoped in; both get an empty series → decide() throws "no daily series"
    // and is skipped per-symbol. The report is still produced (isolation), and BOTH were fetched.
    when(signals.activeEntries())
        .thenReturn(List.of(anchor(1L, publishedVersion, "AAA"), anchor(2L, publishedVersion, "BBB")));
    when(candles.fetch(any(), any(), any(), any(), any())).thenReturn(List.<EngineCandle>of());

    var report = service(registry, candles, signals).report();

    assertThat(report.items()).isEmpty(); // both skipped on the empty series
    verify(candles).fetch(eq("NSE"), eq("AAA"), eq("1d"), any(), any());
    verify(candles).fetch(eq("NSE"), eq("BBB"), eq("1d"), any(), any());
  }

  @Test
  void aSupersededVersionAnchorIsAdoptedSoTheHoldingStillShows() throws IOException {
    UUID supersededVersion = UUID.randomUUID();
    UUID strategyId = UUID.randomUUID();
    StrategyRepository registry = mock(StrategyRepository.class);
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    SignalRepository signals = mock(SignalRepository.class);
    // The anchor's version is NOT a currently-published swing (listAll empty), but findVersionById
    // resolves it to the manas swing config → adoptDef compiles it and the holding is still managed.
    when(registry.listAll()).thenReturn(List.of());
    when(registry.findVersionById(supersededVersion))
        .thenReturn(Optional.of(manasVersion(supersededVersion, strategyId, "draft")));
    when(signals.activeEntries()).thenReturn(List.of(anchor(9L, supersededVersion, "ORPHAN")));
    when(candles.fetch(any(), any(), any(), any(), any())).thenReturn(List.<EngineCandle>of());

    service(registry, candles, signals).report();

    verify(candles).fetch(eq("NSE"), eq("ORPHAN"), eq("1d"), any(), any());
  }

  // ---- fixtures -------------------------------------------------------------------------------

  private static ManasAroraSellDecisionService service(
      StrategyRepository registry, MarketDataCandlesClient candles, SignalRepository signals) {
    return new ManasAroraSellDecisionService(registry, candles, signals, FIXED, 520);
  }

  private static StrategyRepository.StrategyRow publishedManasStrategy(UUID id, UUID version) {
    return new StrategyRepository.StrategyRow(
        id, "manas-arora-breakout", "Manas Breakout", null, null,
        List.of("manas-arora"), true, version, null, null, false, null);
  }

  private static StrategyRepository.VersionRow manasVersion(UUID version, UUID strategyId, String status)
      throws IOException {
    return new StrategyRepository.VersionRow(
        version, strategyId, "1", null, breakoutConfig(), "1", "chk", status, null, null, null, null);
  }

  private static SignalRepository.SignalRow anchor(long id, UUID versionId, String symbol) {
    return new SignalRepository.SignalRow(
        id, versionId, "NSE", symbol, "1d", "ENTRY", "BUY",
        new BigDecimal("100"), null, null, BigDecimal.ONE, new ObjectMapper().createObjectNode(),
        "TAKEN", AT, AT.plusDays(1), null, null, null, null, null, null);
  }

  private static JsonNode breakoutConfig() throws IOException {
    try (InputStream in =
        ManasAroraSellDecisionServiceTest.class.getResourceAsStream(
            "/manas-arora-strategies/manas-arora-breakout.yaml")) {
      assertThat(in).isNotNull();
      String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return StrategyDocuments.parse(yaml).config();
    }
  }
}
