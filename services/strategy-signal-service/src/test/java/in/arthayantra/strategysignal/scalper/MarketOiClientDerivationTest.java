package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.scalper.MarketOiClient.IvPair;
import in.arthayantra.strategysignal.scalper.MarketOiClient.Sentiment;
import in.arthayantra.strategysignal.scalper.MarketOiClient.Spurt;
import in.arthayantra.strategysignal.scalper.MarketOiClient.Trending;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Pure unit tests for the Phase-3.5 temporal OI/IV derivations (§A3/A4/A5/A6) — fed hand-built JSON
 * directly into the package-private derivation helpers (no network). Locks the degrade discipline:
 * a short/absent/flat series yields null/false, never a value that falsely confirms a side.
 */
class MarketOiClientDerivationTest {

  private final ObjectMapper mapper = new ObjectMapper();
  // The derivation helpers are pure (JsonNode in → carrier out); the RestClient is never exercised.
  private final MarketOiClient client =
      new MarketOiClient(RestClient.builder(), mapper, MarketCalendar.nse(), "http://unused");

  private JsonNode json(String raw) {
    try {
      return mapper.readTree(raw);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ----------------------------------------------------------------------- §A3 trending full series

  @Test
  void trendingThreeBucketSeriesWithRealPeOverCeCross() {
    // first: CE 200 / PE 100 → gap −100 (CE over PE); last: CE 100 / PE 250 → gap +150 (PE over CE).
    // ceDelta = 100−200 = −100; peDelta = 250−100 = +150. Sign of (peOi−ceOi) flips − → + → crossed.
    // prior (2nd-last) gap = 120−110 = +10; latest gap = +150 → |150| > |10| → widening.
    Trending t =
        client.deriveTrending(
            json(
                "{\"items\":["
                    + "{\"ceOi\":200,\"peOi\":100},"
                    + "{\"ceOi\":110,\"peOi\":120},"
                    + "{\"ceOi\":100,\"peOi\":250}]}"));

    assertThat(t.ceOiDelta()).isEqualByComparingTo("-100");
    assertThat(t.peOiDelta()).isEqualByComparingTo("150");
    // |150 − (−100)| / max(100,150) × 100 = 250/150×100 = 166.6667
    assertThat(t.imbalancePct()).isEqualByComparingTo("166.6667");
    assertThat(t.crossed()).isTrue();
    assertThat(t.gapWidening()).isTrue();
    // level (last bucket): (250−100)×100 / 350 = 42.8571
    assertThat(t.peMinusCePct()).isEqualByComparingTo("42.8571");
  }

  @Test
  void trendingFlatSeriesYieldsNullImbalanceAndNoCrossOrWidening() {
    // Identical OI every bucket: a STATIC PE/CE gap with unchanged OI must NOT manufacture imbalance.
    Trending t =
        client.deriveTrending(
            json(
                "{\"items\":["
                    + "{\"ceOi\":100,\"peOi\":140},"
                    + "{\"ceOi\":100,\"peOi\":140},"
                    + "{\"ceOi\":100,\"peOi\":140}]}"));

    assertThat(t.ceOiDelta()).isEqualByComparingTo("0");
    assertThat(t.peOiDelta()).isEqualByComparingTo("0");
    assertThat(t.imbalancePct()).isNull(); // FLAT-OI caveat — both deltas ~0
    assertThat(t.crossed()).isFalse(); // gap permanently +40 → no sign transition
    assertThat(t.gapWidening()).isFalse(); // gap unchanged, not widening
    assertThat(t.peMinusCePct()).isNotNull(); // the LEVEL is still a real read
  }

  @Test
  void trendingSingleBucketSeriesGivesLevelButAllTemporalNull() {
    Trending t = client.deriveTrending(json("{\"items\":[{\"ceOi\":100,\"peOi\":200}]}"));

    assertThat(t.peMinusCePct()).isNotNull(); // single bucket → still a level
    assertThat(t.ceOiDelta()).isNull();
    assertThat(t.peOiDelta()).isNull();
    assertThat(t.imbalancePct()).isNull();
    assertThat(t.crossed()).isFalse();
    assertThat(t.gapWidening()).isFalse();
  }

  @Test
  void trendingEmptyOrAbsentSeriesIsAllNull() {
    Trending t = client.deriveTrending(json("{\"items\":[]}"));
    assertThat(t).isEqualTo(Trending.EMPTY);
    assertThat(client.deriveTrending(json("{}"))).isEqualTo(Trending.EMPTY);
  }

  @Test
  void trendingPermanentlyOneSidedDoesNotCross() {
    // gap stays positive across the window → no cross, deltas non-zero, imbalance present.
    Trending t =
        client.deriveTrending(
            json("{\"items\":[{\"ceOi\":100,\"peOi\":150},{\"ceOi\":120,\"peOi\":210}]}"));
    assertThat(t.crossed()).isFalse();
    assertThat(t.ceOiDelta()).isEqualByComparingTo("20");
    assertThat(t.peOiDelta()).isEqualByComparingTo("60");
  }

  // ------------------------------------------------------------------------------- §A4 6-strike IV

  @Test
  void ivPairAveragesThreeAboveAndThreeBelowAtm() {
    // 7 strikes, spot 20000 → ATM = 20000 (index 3). Below: 19700/19800/19900; above: 20100/20200/20300.
    // CE IVs below+above: .10 .12 .14 .20 .22 .24 → sum 1.02 / 6 = 0.17.  PE: .11 .13 .15 .21 .23 .25 → 0.18.
    String chain =
        "{\"spot\":\"20000\",\"rows\":["
            + row("19700", "0.10", "0.11")
            + "," + row("19800", "0.12", "0.13")
            + "," + row("19900", "0.14", "0.15")
            + "," + row("20000", "0.99", "0.99")  // ATM excluded from the average
            + "," + row("20100", "0.20", "0.21")
            + "," + row("20200", "0.22", "0.23")
            + "," + row("20300", "0.24", "0.25")
            + "]}";

    IvPair p = client.deriveIvPair(json(chain));

    assertThat(p.ceIvAvg6()).isEqualByComparingTo("0.1700");
    assertThat(p.peIvAvg6()).isEqualByComparingTo("0.1800");
  }

  @Test
  void ivPairBothHighStandAsideFixture() {
    // "40/40 both high" — both averages high and within a few points, so a later dot detects stand-aside.
    String chain =
        "{\"spot\":\"20000\",\"rows\":["
            + row("19700", "0.40", "0.41")
            + "," + row("19800", "0.41", "0.40")
            + "," + row("19900", "0.39", "0.40")
            + "," + row("20000", "0.50", "0.50")
            + "," + row("20100", "0.40", "0.41")
            + "," + row("20200", "0.41", "0.39")
            + "," + row("20300", "0.39", "0.41")
            + "]}";

    IvPair p = client.deriveIvPair(json(chain));

    assertThat(p.ceIvAvg6()).isGreaterThan(new java.math.BigDecimal("0.38"));
    assertThat(p.peIvAvg6()).isGreaterThan(new java.math.BigDecimal("0.38"));
    assertThat(p.ceIvAvg6().subtract(p.peIvAvg6()).abs())
        .isLessThan(new java.math.BigDecimal("0.02")); // within a few points
  }

  @Test
  void ivPairNullWhenFewerThanSixStrikes() {
    String chain =
        "{\"spot\":\"20000\",\"rows\":["
            + row("19900", "0.14", "0.15")
            + "," + row("20000", "0.13", "0.14")
            + "," + row("20100", "0.15", "0.16")
            + "]}";
    assertThat(client.deriveIvPair(json(chain))).isEqualTo(IvPair.EMPTY);
  }

  @Test
  void ivPairNullWhenAtmTooCloseToAnEdgeForThreeOnBothSides() {
    // 6 strikes, spot at the lowest → not 3 full strikes BELOW the ATM → null.
    String chain =
        "{\"spot\":\"19700\",\"rows\":["
            + row("19700", "0.10", "0.11")
            + "," + row("19800", "0.12", "0.13")
            + "," + row("19900", "0.14", "0.15")
            + "," + row("20000", "0.16", "0.17")
            + "," + row("20100", "0.18", "0.19")
            + "," + row("20200", "0.20", "0.21")
            + "]}";
    assertThat(client.deriveIvPair(json(chain))).isEqualTo(IvPair.EMPTY);
  }

  @Test
  void ivPairNullWhenNeededIvIsMissing() {
    // one of the 6 surrounding strikes has a null CE iv → cannot form the pair honestly → null.
    String chain =
        "{\"spot\":\"20000\",\"rows\":["
            + row("19700", "0.10", "0.11")
            + "," + "{\"strike\":\"19800\",\"ce\":{\"iv\":null},\"pe\":{\"iv\":\"0.13\"}}"
            + "," + row("19900", "0.14", "0.15")
            + "," + row("20000", "0.99", "0.99")
            + "," + row("20100", "0.20", "0.21")
            + "," + row("20200", "0.22", "0.23")
            + "," + row("20300", "0.24", "0.25")
            + "]}";
    assertThat(client.deriveIvPair(json(chain))).isEqualTo(IvPair.EMPTY);
  }

  private static String row(String strike, String ceIv, String peIv) {
    return "{\"strike\":\"" + strike + "\",\"ce\":{\"iv\":\"" + ceIv + "\"},\"pe\":{\"iv\":\"" + peIv + "\"}}";
  }

  // -------------------------------------------------------------------------- §A5 sentiment slope

  @Test
  void sentimentSlopeIsPositiveOnRisingSeries() {
    Sentiment s =
        client.deriveSentiment(
            json(
                "{\"sentimentPct\":\"40\",\"sentimentSeries\":["
                    + "{\"sentimentPct\":\"10\"},{\"sentimentPct\":\"25\"},{\"sentimentPct\":\"40\"}]}"));

    assertThat(s.level()).isEqualByComparingTo("40");
    assertThat(s.slope()).isEqualByComparingTo("30"); // 40 − 10
  }

  @Test
  void sentimentSlopeIsNegativeOnFallingSeries() {
    Sentiment s =
        client.deriveSentiment(
            json("{\"sentimentPct\":\"5\",\"sentimentSeries\":[{\"sentimentPct\":\"30\"},{\"sentimentPct\":\"5\"}]}"));
    assertThat(s.slope()).isEqualByComparingTo("-25");
  }

  @Test
  void sentimentSlopeNullWhenSeriesShortOrAbsentButLevelSurvives() {
    Sentiment one =
        client.deriveSentiment(json("{\"sentimentPct\":\"12.5\",\"sentimentSeries\":[{\"sentimentPct\":\"12.5\"}]}"));
    assertThat(one.level()).isEqualByComparingTo("12.5");
    assertThat(one.slope()).isNull(); // < 2 buckets

    Sentiment none = client.deriveSentiment(json("{\"sentimentPct\":\"12.5\"}"));
    assertThat(none.level()).isEqualByComparingTo("12.5");
    assertThat(none.slope()).isNull(); // absent series
  }

  // --------------------------------------------------------------------------------- §A6 spurt mags

  @Test
  void spurtMagnitudesPopulatedFromSummary() {
    Spurt s =
        client.deriveSpurt(
            json(
                "{\"summary\":{\"interpretation\":\"SHORT_COVERING\","
                    + "\"oiChangePct\":\"-8.5\",\"priceChangePct\":\"3.2\"}}"));

    assertThat(s.quadrant()).isEqualTo(OiQuadrant.SHORT_COVERING);
    assertThat(s.oiPct()).isEqualByComparingTo("-8.5");
    assertThat(s.pricePct()).isEqualByComparingTo("3.2");
  }

  @Test
  void spurtMagnitudesNullWhenAbsentQuadrantDegradesToNeutral() {
    Spurt s = client.deriveSpurt(json("{\"summary\":{}}"));
    assertThat(s.quadrant()).isEqualTo(OiQuadrant.NEUTRAL);
    assertThat(s.oiPct()).isNull();
    assertThat(s.pricePct()).isNull();
  }
}
