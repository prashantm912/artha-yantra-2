package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OiHeatmapServiceTest {

  private static final OffsetDateTime B0 =
      OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

  private static OptionsSnapshotReader.StrikePoint pt(
      OffsetDateTime b, String strike, String type, Long oi) {
    return new OptionsSnapshotReader.StrikePoint(
        b, new BigDecimal(strike), type, null, oi, 0L, null, new BigDecimal("22500"), null);
  }

  @Test
  void emptySeriesYieldsEmptyGrid() {
    OiHeatmapService.Heatmap h = new OiHeatmapService().fold(List.of(), null, 5);
    assertThat(h.buckets()).isEmpty();
    assertThat(h.strikes()).isEmpty();
    assertThat(h.ce()).isEmpty();
    assertThat(h.pe()).isEmpty();
    assertThat(h.maxAbs()).isEqualTo(0L);
    assertThat(h.asOf()).isNull();
  }

  @Test
  void bucketLabelsAreFormattedInIstNotUtc() {
    // JDBC time_bucket returns UTC (+00); 03:45+00 is 09:15 IST — the x-axis label must read IST.
    OffsetDateTime u0 = OffsetDateTime.of(2026, 6, 20, 3, 45, 0, 0, ZoneOffset.UTC);
    OffsetDateTime u1 = u0.plusMinutes(5);
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(pt(u0, "22500", "CE", 1000L), pt(u1, "22500", "CE", 1200L));

    OiHeatmapService.Heatmap h = new OiHeatmapService().fold(series, new BigDecimal("22500"), 5);

    assertThat(h.buckets()).containsExactly("09:15", "09:20");
  }

  @Test
  void cellIsDeltaFromPriorBucketAndFirstColumnIsDropped() {
    OffsetDateTime b1 = B0.plusMinutes(5);
    // one strike, CE: oi 1000 -> 1200 (+200); PE: oi 800 -> 700 (-100)
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            pt(B0, "22500", "CE", 1000L),
            pt(b1, "22500", "CE", 1200L),
            pt(B0, "22500", "PE", 800L),
            pt(b1, "22500", "PE", 700L));

    OiHeatmapService.Heatmap h = new OiHeatmapService().fold(series, new BigDecimal("22500"), 5);

    assertThat(h.buckets()).containsExactly("09:15", "09:20");
    assertThat(h.strikes()).containsExactly("22500");
    // bucketCount=2 → exactly one column (x=1) per strike per leg; the x=0 column carries no delta.
    assertThat(h.ce()).containsExactly(new OiHeatmapService.Cell(1, 0, 200L));
    assertThat(h.pe()).containsExactly(new OiHeatmapService.Cell(1, 0, -100L));
    // maxAbs spans BOTH legs: max(|+200|, |-100|) = 200.
    assertThat(h.maxAbs()).isEqualTo(200L);
    assertThat(h.asOf()).isEqualTo(b1);
  }

  @Test
  void windowKeepsOnlyTheStrikesNearestAtm() {
    OffsetDateTime b1 = B0.plusMinutes(5);
    // five strikes around an ATM of 22500; window=1 keeps the 3 nearest (22400, 22500, 22600).
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            pt(B0, "22300", "CE", 100L), pt(b1, "22300", "CE", 150L),
            pt(B0, "22400", "CE", 100L), pt(b1, "22400", "CE", 150L),
            pt(B0, "22500", "CE", 100L), pt(b1, "22500", "CE", 150L),
            pt(B0, "22600", "CE", 100L), pt(b1, "22600", "CE", 150L),
            pt(B0, "22700", "CE", 100L), pt(b1, "22700", "CE", 150L));

    OiHeatmapService.Heatmap h = new OiHeatmapService().fold(series, new BigDecimal("22500"), 1);

    // rows read low→high among the kept (nearest) strikes.
    assertThat(h.strikes()).containsExactly("22400", "22500", "22600");
    assertThat(h.ce()).hasSize(3); // 3 strikes × 1 delta column
  }

  @Test
  void missingBucketLeavesANullCellButBridgesAOneBucketGap() {
    OffsetDateTime b1 = B0.plusMinutes(5);
    OffsetDateTime b2 = B0.plusMinutes(10);
    // CE present at b0 (1000) and b2 (1300) but MISSING at b1: x=1 cell null (no b1 sample),
    // x=2 cell = 1300 - 1000 = +300 (prior carried forward across the b1 gap).
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            pt(B0, "22500", "CE", 1000L),
            pt(b2, "22500", "CE", 1300L),
            // a PE row at b1 only ensures b1 is part of the bucket axis.
            pt(B0, "22500", "PE", 500L),
            pt(b1, "22500", "PE", 500L),
            pt(b2, "22500", "PE", 500L));

    OiHeatmapService.Heatmap h = new OiHeatmapService().fold(series, new BigDecimal("22500"), 5);

    assertThat(h.buckets()).containsExactly("09:15", "09:20", "09:25");
    assertThat(h.ce())
        .containsExactly(
            new OiHeatmapService.Cell(1, 0, null), new OiHeatmapService.Cell(2, 0, 300L));
    assertThat(h.maxAbs()).isEqualTo(300L);
  }

  @Test
  void nullAtmFallsBackToTheLowestStrikes() {
    OffsetDateTime b1 = B0.plusMinutes(5);
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            pt(B0, "22700", "CE", 100L), pt(b1, "22700", "CE", 150L),
            pt(B0, "22300", "CE", 100L), pt(b1, "22300", "CE", 150L),
            pt(B0, "22500", "CE", 100L), pt(b1, "22500", "CE", 150L));

    OiHeatmapService.Heatmap h = new OiHeatmapService().fold(series, null, 0);

    // window=0 → 1 strike; null ATM → lowest strike kept.
    assertThat(h.strikes()).containsExactly("22300");
  }
}
