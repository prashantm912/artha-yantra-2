package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.Trade;
import in.arthayantra.backtest.replay.options.OptionContractSelector.Catalog;
import in.arthayantra.backtest.replay.options.OptionContractSelector.Expiry;
import in.arthayantra.backtest.replay.options.OptionContractSelector.ExpiryMode;
import in.arthayantra.backtest.replay.options.OptionContractSelector.OptionContract;
import in.arthayantra.backtest.replay.options.OptionsPremiumReplay.PairedLeg;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The premium replay adapter and recorded Trade levels are driven from the shared fixture. */
class OptionsPremiumReplayEquivalenceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 16);
  private static final String SYMBOL = "NIFTY16JUN2625000CE";

  private static final Catalog CATALOG =
      new Catalog() {
        @Override
        public List<Expiry> expiriesOnOrAfter(String underlying, LocalDate date) {
          return List.of(new Expiry(EXPIRY, true));
        }

        @Override
        public Optional<OptionContract> nearestStrike(
            String underlying, LocalDate expiry, String optionType, BigDecimal spot) {
          return Optional.of(
              new OptionContract("NFO", SYMBOL, new BigDecimal("25000"), expiry, optionType, 65));
        }
      };

  private static OffsetDateTime timestamp(int index) {
    return OffsetDateTime.parse("2026-06-16T09:15:00+05:30").plusMinutes(index);
  }

  private static EngineCandle candle(OffsetDateTime timestamp, BigDecimal close) {
    return new EngineCandle(timestamp, close, close, close, close, 0L);
  }

  private static JsonNode fixture() throws Exception {
    Path p = Path.of("..", "..", "contracts", "fixtures", "exit-equivalence.json");
    return JSON.readTree(Files.readString(p));
  }

  @Test
  void replayLevelsAndBracketExitsMatchTheSharedEquivalenceFixture() throws Exception {
    JsonNode fx = fixture();
    assertThat(fx.path("scenarios").findValuesAsText("name"))
        .contains(
            "single-bar-entry-only",
            "only-stop",
            "only-target",
            "both-levels-round-to-same-paise-stop-first",
            "take-profit-subpaise-rounding");

    for (JsonNode sc : fx.path("scenarios")) {
      String name = sc.path("name").asText();
      BigDecimal entry =
          new BigDecimal(
              (sc.has("entryPremium") ? sc.path("entryPremium") : fx.path("entryPremium")).asText());
      JsonNode config = sc.has("config") ? sc.path("config") : fx.path("config");
      JsonNode levels = sc.has("expectedLevels") ? sc.path("expectedLevels") : fx.path("expectedLevels");
      List<BigDecimal> premiums =
          java.util.stream.StreamSupport.stream(sc.path("premiums").spliterator(), false)
              .map(node -> new BigDecimal(node.asText()))
              .toList();
      assertThat(premiums.get(0)).as(name + " entry bar").isEqualByComparingTo(entry);
      List<EngineCandle> underlying =
          java.util.stream.IntStream.range(0, premiums.size())
              .mapToObj(i -> candle(timestamp(i), new BigDecimal("25000")))
              .toList();
      List<EngineCandle> premiumBars =
          java.util.stream.IntStream.range(0, premiums.size())
              .mapToObj(i -> candle(timestamp(i), premiums.get(i)))
              .toList();

      CandleReader reader = mock(CandleReader.class);
      when(reader.read(eq("NFO"), eq(SYMBOL), eq("1m"), any(), any())).thenReturn(premiumBars);
      OptionsPremiumReplay replay =
          new OptionsPremiumReplay(new OptionContractSelector(CATALOG), new CandlePremiumReader(reader));
      Optional<Trade> result =
          replay.tradeForLeg(
              1,
              underlying,
              "NIFTY",
              new PairedLeg(false, 0, premiums.size() - 1),
              new OptionsPremiumReplay.UniverseSpec(ExpiryMode.NEAREST_WEEKLY, 0, Set.of("CE")),
              OptionsPremiumReplay.exitRules(config),
              15_000);

      assertThat(result).as(name).isPresent();
      Trade trade = result.orElseThrow();
      assertLevel(trade.stopLoss(), levels.path("stopLoss"), name + " stopLoss");
      assertLevel(trade.takeProfit(), levels.path("takeProfit"), name + " takeProfit");
      JsonNode expect = sc.path("expect");
      if (expect.path("reason").isNull()) {
        assertThat(trade.exitReason()).as(name).isIn("SIGNAL_EXIT", "END_OF_DATA");
      } else {
        assertThat(trade.exitReason()).as(name).isEqualTo(expect.path("reason").asText());
        assertThat(trade.barsHeld()).as(name).isEqualTo(expect.path("barOffset").asInt());
      }
    }
  }

  private static void assertLevel(BigDecimal actual, JsonNode expected, String name) {
    if (expected.isNull() || expected.isMissingNode()) {
      assertThat(actual).as(name).isNull();
    } else {
      assertThat(actual).as(name).isEqualByComparingTo(expected.asText());
    }
  }
}
