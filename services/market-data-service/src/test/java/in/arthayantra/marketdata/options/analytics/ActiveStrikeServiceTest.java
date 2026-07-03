package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import in.arthayantra.black76.Black76;
import in.arthayantra.marketdata.options.ExpiryClock;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveStrikeServiceTest {

  private static ActiveStrikeService.StrikeOiSnap snap(
      String strike, long ceOi, long ceChg, long peOi, long peChg) {
    return new ActiveStrikeService.StrikeOiSnap(new BigDecimal(strike), ceOi, ceChg, peOi, peChg);
  }

  @Test
  void selectsTopNByTotalOi() {
    List<ActiveStrikeService.StrikeOiSnap> chain =
        List.of(
            snap("22400", 100, 0, 100, 0),
            snap("22500", 900, 0, 900, 0),
            snap("22600", 50, 0, 50, 0));
    ActiveStrikeService svc = new ActiveStrikeService(1);
    assertThat(svc.activeStrikes(chain))
        .extracting(s -> s.strike().toPlainString())
        .containsExactly("22500");
  }

  @Test
  void sentimentBullishWhenPutsBuildAndCallsUnwind() {
    // active strike 22500: PE OI building (+300), CE OI unwinding (-200); base = 1000+1000
    List<ActiveStrikeService.StrikeOiSnap> chain = List.of(snap("22500", 1000, -200, 1000, 300));
    ActiveStrikeService svc = new ActiveStrikeService(1);
    // bullishFlow = 300 - (-200) = 500; base = 2000; sentiment = 100*500/2000 = 25.00
    assertThat(svc.sentimentPct(chain)).isEqualByComparingTo("25.00");
  }

  @Test
  void sentimentNullWhenNoBaseOi() {
    assertThat(new ActiveStrikeService(5).sentimentPct(List.of())).isNull();
  }

  @Test
  void levelSentimentUsesOiStockNotFlow() {
    // active strike 22500: PE OI 1200, CE OI 800 (the flow deltas are irrelevant to the LEVEL number)
    List<ActiveStrikeService.StrikeOiSnap> chain = List.of(snap("22500", 800, 999, 1200, -999));
    ActiveStrikeService svc = new ActiveStrikeService(1);
    // level = 100*(1200-800)/1200 = 33.33 (oipulse §18.6); flow number is a separate figure
    assertThat(svc.sentimentLevelPct(chain)).isEqualByComparingTo("33.33");
    assertThat(new ActiveStrikeService(5).sentimentLevelPct(List.of())).isNull();
  }

  private static OptionsSnapshotReader.StrikePoint pt(
      java.time.OffsetDateTime b, String strike, String type, long oi, long oiChange) {
    return new OptionsSnapshotReader.StrikePoint(
        b, new BigDecimal(strike), type, null, oi, oiChange, null, null, null);
  }

  @Test
  void sentimentSeriesComputesPerBucketNewestLast() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    java.time.OffsetDateTime b1 = b0.plusMinutes(5);
    // bucket b0, strike 22500: PE OI +300, CE OI -200; base 1000+1000=2000 => 100*500/2000 = 25.00
    // bucket b1, strike 22500: PE OI -100, CE OI +400; base 1100+900=2000 => 100*(-500)/2000 = -25.00
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            pt(b0, "22500", "CE", 1000, -200),
            pt(b0, "22500", "PE", 1000, 300),
            pt(b1, "22500", "CE", 900, 400),
            pt(b1, "22500", "PE", 1100, -100));
    ActiveStrikeService svc = new ActiveStrikeService(5);
    List<ActiveStrikeService.SentimentPoint> out = svc.sentimentSeries(series);
    assertThat(out).hasSize(2);
    assertThat(out.get(0).bucket()).isEqualTo(b0);
    assertThat(out.get(0).sentimentPct()).isEqualByComparingTo("25.00");
    assertThat(out.get(1).bucket()).isEqualTo(b1);
    assertThat(out.get(1).sentimentPct()).isEqualByComparingTo("-25.00");
  }

  @Test
  void sentimentSeriesEmptyWhenNoPoints() {
    assertThat(new ActiveStrikeService(5).sentimentSeries(List.of())).isEmpty();
  }

  @Test
  void activeStrikeOiSeriesReselectsActiveStrikePerBucket() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    java.time.OffsetDateTime b1 = b0.plusMinutes(5);
    // The active (peak-total-OI) strike FLIPS between buckets: 22500 dominates b0, 22600 dominates b1.
    // With topN=1 the OI series must follow the per-bucket peak — proving selection is per-bucket, not
    // chosen once globally (a global pick would report 22500's tiny OI in b1).
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            pt(b0, "22500", "CE", 1000, 0),
            pt(b0, "22500", "PE", 1000, 0),
            pt(b0, "22600", "CE", 100, 0),
            pt(b0, "22600", "PE", 100, 0),
            pt(b1, "22500", "CE", 100, 0),
            pt(b1, "22500", "PE", 100, 0),
            pt(b1, "22600", "CE", 1500, 0),
            pt(b1, "22600", "PE", 1200, 0));
    ActiveStrikeService svc = new ActiveStrikeService(1);
    List<ActiveStrikeService.ActiveStrikeOiPoint> out = svc.activeStrikeOiSeries(series);
    assertThat(out).hasSize(2);
    assertThat(out.get(0).bucket()).isEqualTo(b0); // newest-last ordering
    assertThat(out.get(0).ceOi()).isEqualTo(1000L); // 22500 is the b0 peak
    assertThat(out.get(0).peOi()).isEqualTo(1000L);
    assertThat(out.get(1).bucket()).isEqualTo(b1);
    assertThat(out.get(1).ceOi()).isEqualTo(1500L); // 22600 is the b1 peak (reselected)
    assertThat(out.get(1).peOi()).isEqualTo(1200L);
  }

  @Test
  void activeStrikeOiSeriesSumsTopNWhenMultipleActive() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    // topN=2 over two strikes → the OI line is the aggregate of both active strikes (documented divergence
    // from oipulse's single peak strike): ceOi = 1000+100, peOi = 1000+100.
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            pt(b0, "22500", "CE", 1000, 0),
            pt(b0, "22500", "PE", 1000, 0),
            pt(b0, "22600", "CE", 100, 0),
            pt(b0, "22600", "PE", 100, 0));
    List<ActiveStrikeService.ActiveStrikeOiPoint> out =
        new ActiveStrikeService(2).activeStrikeOiSeries(series);
    assertThat(out).hasSize(1);
    assertThat(out.get(0).ceOi()).isEqualTo(1100L);
    assertThat(out.get(0).peOi()).isEqualTo(1100L);
  }

  @Test
  void activeStrikeOiSeriesEmptyWhenNoPoints() {
    assertThat(new ActiveStrikeService(5).activeStrikeOiSeries(List.of())).isEmpty();
  }

  private static OptionsSnapshotReader.StrikePoint ptIv(
      java.time.OffsetDateTime b, String strike, String type, long oi, String iv, String spot) {
    return new OptionsSnapshotReader.StrikePoint(
        b,
        new BigDecimal(strike),
        type,
        null,
        oi,
        0L,
        iv == null ? null : new BigDecimal(iv),
        spot == null ? null : new BigDecimal(spot),
        null);
  }

  @Test
  void activeStrikeIvSeriesPicksThePeakStrikeIvPerBucket() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    java.time.OffsetDateTime b1 = b0.plusMinutes(5);
    // IV is per-strike, unsummable → the series carries the SINGLE peak-OI strike's IVs + price. The peak
    // flips 22500→22600 between buckets, so the emitted IVs must follow it (not stay on 22500).
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            ptIv(b0, "22500", "CE", 1000, "13.84", "57700"),
            ptIv(b0, "22500", "PE", 1000, "19.20", "57700"),
            ptIv(b0, "22600", "CE", 100, "14.50", "57700"),
            ptIv(b0, "22600", "PE", 100, "20.10", "57700"),
            ptIv(b1, "22500", "CE", 100, "12.00", "57750"),
            ptIv(b1, "22500", "PE", 100, "18.00", "57750"),
            ptIv(b1, "22600", "CE", 1500, "15.25", "57750"),
            ptIv(b1, "22600", "PE", 1200, "21.40", "57750"));
    List<ActiveStrikeService.ActiveStrikeIvPoint> out =
        new ActiveStrikeService(5).activeStrikeIvSeries(series);
    assertThat(out).hasSize(2);
    assertThat(out.get(0).bucket()).isEqualTo(b0); // newest-last
    assertThat(out.get(0).ceIv()).isEqualByComparingTo("13.84"); // 22500 peak in b0
    assertThat(out.get(0).peIv()).isEqualByComparingTo("19.20");
    assertThat(out.get(0).price()).isEqualByComparingTo("57700");
    assertThat(out.get(1).ceIv()).isEqualByComparingTo("15.25"); // 22600 peak in b1 (reselected)
    assertThat(out.get(1).peIv()).isEqualByComparingTo("21.40");
    assertThat(out.get(1).price()).isEqualByComparingTo("57750");
  }

  @Test
  void activeStrikeIvSeriesTieBreaksToTheLowerStrike() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    // Equal total OI on both strikes → the LOWER strike (22500) must win deterministically. Insert the
    // higher strike FIRST so a correct tie-break cannot rely on encounter order.
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            ptIv(b0, "22600", "CE", 500, "99.00", "57700"),
            ptIv(b0, "22600", "PE", 500, "99.00", "57700"),
            ptIv(b0, "22500", "CE", 500, "13.84", "57700"),
            ptIv(b0, "22500", "PE", 500, "19.20", "57700"));
    List<ActiveStrikeService.ActiveStrikeIvPoint> out =
        new ActiveStrikeService(5).activeStrikeIvSeries(series);
    assertThat(out).hasSize(1);
    assertThat(out.get(0).ceIv()).isEqualByComparingTo("13.84"); // 22500, the lower strike
  }

  @Test
  void activeStrikeIvSeriesPassesNullIvThrough() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            ptIv(b0, "22500", "CE", 1000, null, "57700"),
            ptIv(b0, "22500", "PE", 1000, "19.20", "57700"));
    List<ActiveStrikeService.ActiveStrikeIvPoint> out =
        new ActiveStrikeService(5).activeStrikeIvSeries(series);
    assertThat(out.get(0).ceIv()).isNull();
    assertThat(out.get(0).peIv()).isEqualByComparingTo("19.20");
  }

  @Test
  void activeStrikeIvSeriesEmptyWhenNoPoints() {
    assertThat(new ActiveStrikeService(5).activeStrikeIvSeries(List.of())).isEmpty();
  }

  private static OptionsSnapshotReader.StrikePoint ptLtp(
      java.time.OffsetDateTime b, String strike, String type, long oi, String ltp, String spot) {
    return new OptionsSnapshotReader.StrikePoint(
        b,
        new BigDecimal(strike),
        type,
        ltp == null ? null : new BigDecimal(ltp),
        oi,
        0L,
        null,
        spot == null ? null : new BigDecimal(spot),
        null);
  }

  @Test
  void activeStrikeSideIvSeriesSolvesEachLegUnconstrainedAgainstSpot() {
    // §10.2-1: the stored iv is PCP-forward-solved (CE IV == PE IV by construction). The side series
    // must recover DISTINCT per-side IVs when the two legs are priced at different vols off the spot.
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    java.time.LocalDate expiry = java.time.LocalDate.of(2026, 6, 25);
    double t = ExpiryClock.yearsToExpiry(b0.toInstant(), expiry).orElseThrow();
    double f = 24000;
    double r = 0.065;
    String ceLtp =
        BigDecimal.valueOf(Black76.price(Black76.OptionType.CE, f, 24000, t, r, 0.10))
            .toPlainString();
    String peLtp =
        BigDecimal.valueOf(Black76.price(Black76.OptionType.PE, f, 24000, t, r, 0.12))
            .toPlainString();
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            ptLtp(b0, "24000", "CE", 1000, ceLtp, "24000"),
            ptLtp(b0, "24000", "PE", 1000, peLtp, "24000"));

    List<ActiveStrikeService.ActiveStrikeIvPoint> out =
        new ActiveStrikeService(5)
            .activeStrikeSideIvSeries(series, expiry, new BigDecimal("0.065"));

    assertThat(out).hasSize(1);
    assertThat(out.get(0).ceIv().doubleValue()).isCloseTo(0.10, within(1e-4));
    assertThat(out.get(0).peIv().doubleValue()).isCloseTo(0.12, within(1e-4)); // skew visible
    assertThat(out.get(0).price()).isEqualByComparingTo("24000");
  }

  @Test
  void activeStrikeSideIvSeriesNullsSidesWithoutQuoteOrSpot() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(
            2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    java.time.LocalDate expiry = java.time.LocalDate.of(2026, 6, 25);
    // CE has no LTP -> null ceIv; PE ltp present but spot missing on b1 -> both null there.
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            ptLtp(b0, "24000", "CE", 1000, null, "24000"),
            ptLtp(b0, "24000", "PE", 1000, "150", "24000"),
            ptLtp(b0.plusMinutes(5), "24000", "PE", 1000, "150", null));

    List<ActiveStrikeService.ActiveStrikeIvPoint> out =
        new ActiveStrikeService(5)
            .activeStrikeSideIvSeries(series, expiry, new BigDecimal("0.065"));

    assertThat(out).hasSize(2);
    assertThat(out.get(0).ceIv()).isNull();
    assertThat(out.get(0).peIv()).isNotNull();
    assertThat(out.get(1).ceIv()).isNull();
    assertThat(out.get(1).peIv()).isNull();
  }
}
