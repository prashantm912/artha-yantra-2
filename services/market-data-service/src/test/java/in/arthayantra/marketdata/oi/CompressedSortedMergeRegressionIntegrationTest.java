package in.arthayantra.marketdata.oi;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.context.FuturesDigestService;
import in.arthayantra.marketdata.futures.analytics.FuturesSnapshotReader;
import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.options.analytics.OptionsSnapshotReader;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression guard for the TimescaleDB 2.18.2 "non-Var pathkey" planner defect.
 *
 * <p><b>The defect.</b> A top-level {@code DISTINCT}/{@code ORDER BY}/{@code GROUP BY} key that is
 * {@code time_bucket(iv, <expression>, tz)} — our end-of-window {@code ts - INTERVAL '1 second'}
 * shift — combined with a {@code LIMIT}, over a hypertable that has ANY compressed chunk in the scan
 * set, aborts query PLANNING with {@code "non-Var pathkey not expected for compressed batch sorted
 * merge"} when {@code timescaledb.enable_decompression_sorted_merge = on} (the shipped default). The
 * three OI-confluence dot feeders ({@link FuturesDigestService}, {@link FuturesSnapshotReader}, {@link
 * OptionsSnapshotReader}) all used exactly that shape ({@code SELECT DISTINCT time_bucket(ts - '1
 * second' …) … ORDER BY b DESC LIMIT 2}) to find their two most-recent snapshot buckets, so on
 * 2026-07-20 every futures/underlying/spurt OI read threw and all three dots read the data-absent
 * sentinel for the whole session (docs/signal-analysis/2026-07-20-session-findings.md §6.2). A
 * DB-level GUC flip mitigated it; the readers were rewritten to a pathkey-free two-{@code max(ts)}
 * form so the mitigation can be reverted and the optimisation stays enabled.
 *
 * <p><b>What this test proves.</b> With the GUC on (asserted) and a COMPRESSED chunk in the scan set
 * (asserted), each reader's {@code latestPair}/digest path returns the correct two-newest-bucket
 * result WITHOUT throwing. If any of them regresses to the {@code DISTINCT … ORDER BY expr … LIMIT}
 * shape, planning aborts here and the test fails — which is the point.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.futures.oi-snapshot-underlyings=SMERGEDIGEST",
      "artha.futures.bank-stocks=SMERGEDIGEST"
    })
class CompressedSortedMergeRegressionIntegrationTest extends MarketDataIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  // A distinctive, isolated day set that no other IT in this module seeds into these two
  // hypertables — the "old" day is compressed, the "recent" day carries the pair. 5 days apart, so
  // (1-day chunks) they always land in separate chunks.
  private static final LocalDate OLD_DAY = LocalDate.of(2026, 2, 15);
  private static final LocalDate RECENT_DAY = LocalDate.of(2026, 2, 20);
  private static final LocalDate EXPIRY = LocalDate.of(2026, 2, 26);

  @Autowired JdbcTemplate jdbc;
  @Autowired FuturesSnapshotReader futuresReader;
  @Autowired OptionsSnapshotReader optionsReader;
  @Autowired FuturesDigestService digestService;

  @BeforeEach
  void assertGucOnPrecondition() {
    // The defect only triggers with the sorted-merge optimisation ENABLED. A fresh Testcontainers
    // instance ships this on by default (boot_val=on); assert it so a future harness change that
    // disabled it can never silently neuter this guard.
    String guc =
        jdbc.queryForObject("SHOW timescaledb.enable_decompression_sorted_merge", String.class);
    assertThat(guc).as("regression guard requires the sorted-merge optimisation ON").isEqualTo("on");
  }

  @Test
  void futuresLatestPairSurvivesACompressedChunk() {
    String u = "SMERGEFUT";
    // one row in the OLD chunk (compressed → forces a compressed chunk into the whole-table scan)
    insertFut(u, OLD_DAY.atTime(10, 0).atOffset(IST), 500L);
    // three 15m buckets in the RECENT chunk; latestPair must return the two newest (b1,b2)
    OffsetDateTime b0 = RECENT_DAY.atTime(10, 0).atOffset(IST);
    insertFut(u, b0, 1000L);
    insertFut(u, b0.plusMinutes(15), 1200L);
    insertFut(u, b0.plusMinutes(30), 1500L);
    compress("futures_oi_snapshots", OLD_DAY);
    assertChunkCompressed("futures_oi_snapshots", OLD_DAY);

    // single-underlying path (FuturesSnapshotReader:latestPair) — must not throw, oldest-first pair
    assertThat(futuresReader.latestPair(u, OiInterval.parse("15m")))
        .extracting(FuturesSnapshotReader.FutPoint::oi)
        .containsExactly(1200L, 1500L);

    // multi-underlying path (FuturesSnapshotReader:latestPairAll)
    assertThat(futuresReader.latestPairAll(List.of(u), OiInterval.parse("15m"), null))
        .extracting(FuturesSnapshotReader.FutPoint::oi)
        .containsExactly(1200L, 1500L);
  }

  @Test
  void optionsLatestPairSurvivesACompressedChunk() {
    String u = "SMERGEOPT";
    insertOpt(u, OLD_DAY.atTime(10, 0).atOffset(IST), 500L);
    OffsetDateTime b0 = RECENT_DAY.atTime(10, 0).atOffset(IST);
    insertOpt(u, b0, 1000L);
    insertOpt(u, b0.plusMinutes(15), 1200L);
    insertOpt(u, b0.plusMinutes(30), 1500L);
    compress("options_chain_snapshots", OLD_DAY);
    assertChunkCompressed("options_chain_snapshots", OLD_DAY);

    assertThat(optionsReader.latestPair(u, EXPIRY, OiInterval.parse("15m")))
        .extracting(OptionsSnapshotReader.StrikePoint::oi)
        .containsExactly(1200L, 1500L);
  }

  @Test
  void futuresDigestSurvivesACompressedChunk() {
    // The live-signal feeder (FuturesDigestService:latestTwoBuckets). Config binds the captured
    // underlying to SMERGEDIGEST; two 15m buckets give a valid OI diff (+200 on a +10 move).
    String u = "SMERGEDIGEST";
    insertFut(u, OLD_DAY.atTime(10, 0).atOffset(IST), 500L);
    OffsetDateTime prior = RECENT_DAY.atTime(10, 2).atOffset(IST);
    OffsetDateTime now = RECENT_DAY.atTime(10, 17).atOffset(IST);
    insertFut(u, prior, "100", 1000L);
    insertFut(u, now, "110", 1200L);
    compress("futures_oi_snapshots", OLD_DAY);
    assertChunkCompressed("futures_oi_snapshots", OLD_DAY);

    // history-mode read pinned to RECENT_DAY (deterministic on the shared singleton DB); the whole
    // -day predicate still spans the compressed OLD chunk on the underlying, so the planner must
    // consider it. Must return a real quadrant, not the NEUTRAL/BLOCKED data-absent shell.
    FuturesDigestService.FuturesDigest digest = digestService.digest(RECENT_DAY);

    assertThat(digest.underlyings()).hasSize(1);
    FuturesDigestService.UnderlyingQuadrant q = digest.underlyings().get(0);
    assertThat(q.underlying()).isEqualTo(u);
    assertThat(q.oiChange()).isEqualTo(200L);
    assertThat(q.interpretation()).isEqualTo("LONG_BUILDUP");
  }

  // -- helpers -----------------------------------------------------------------------------------

  private void insertFut(String u, OffsetDateTime ts, long oi) {
    insertFut(u, ts, "100", oi);
  }

  private void insertFut(String u, OffsetDateTime ts, String ltp, long oi) {
    jdbc.update(
        "INSERT INTO futures_oi_snapshots "
            + "(ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change, prev_close) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        u,
        u + "26FEBFUT",
        java.sql.Date.valueOf(EXPIRY),
        ltp,
        10L,
        oi,
        0L,
        "100");
  }

  private void insertOpt(String u, OffsetDateTime ts, long oi) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots "
            + "(ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, oi, oi_change, volume, spot_price) "
            + "VALUES (?,?,?,?::numeric,?,?,?::numeric,?,?,?,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        u,
        java.sql.Date.valueOf(EXPIRY),
        "22500",
        "CE",
        u + "22500CE",
        "100.00",
        oi,
        0L,
        10L,
        "22480.00");
  }

  /** Compress the single chunk covering {@code day} (idempotent — skips an already-compressed one). */
  private void compress(String table, LocalDate day) {
    String noon = day.atTime(12, 0).atOffset(IST).toInstant().toString();
    jdbc.queryForList(
        "SELECT public.compress_chunk(format('%I.%I', chunk_schema, chunk_name)::regclass, true)::text "
            + "FROM timescaledb_information.chunks "
            + "WHERE hypertable_name = ? AND NOT is_compressed "
            + "  AND range_start <= ?::timestamptz AND range_end > ?::timestamptz",
        String.class,
        table,
        noon,
        noon);
  }

  private void assertChunkCompressed(String table, LocalDate day) {
    Boolean compressed =
        jdbc.queryForObject(
            "SELECT bool_or(is_compressed) FROM timescaledb_information.chunks "
                + "WHERE hypertable_name = ? AND range_start <= ?::timestamptz AND range_end > ?::timestamptz",
            Boolean.class,
            table,
            day.atTime(12, 0).atOffset(IST).toInstant().toString(),
            day.atTime(12, 0).atOffset(IST).toInstant().toString());
    assertThat(compressed).as("%s chunk for %s must be compressed", table, day).isTrue();
  }
}
