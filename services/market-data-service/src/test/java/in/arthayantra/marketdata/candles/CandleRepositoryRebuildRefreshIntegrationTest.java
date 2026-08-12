package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.candles.CandleRepository.ChunkLoad;
import in.arthayantra.marketdata.candles.CandleRepository.DerivedAggregate;
import in.arthayantra.marketdata.candles.CandleRepository.Window;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The corporate-action rebuild's cagg refresh, against a REAL TimescaleDB — the per-DML
 * decompression cap that aborted it for 13 symbols (2026-07-21..07-31) is enforced by the database,
 * not by our code, and the catalog SQL the window planner reads is invisible to a mocked connection.
 *
 * <p>Two properties that only a real database can answer. The chunk-load query must actually return
 * the deployed layout — including {@code approximate_row_count} on a COMPRESSED materialization
 * chunk, where an estimate would make every planned window a guess. And a rebuild sliced into many
 * windows must materialize EXACTLY what one window would: the failure mode of a windowing bug is a
 * silently missing bucket, which is the same damage the rebuild exists to repair.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class CandleRepositoryRebuildRefreshIntegrationTest extends MarketDataIntegrationTestBase {

  /** Unique + far-past: ITs share the singleton database with no per-method cleanup. */
  private static final String SYMBOL = "CAGGPLAN";

  private static final OffsetDateTime FROM = OffsetDateTime.parse("2019-01-07T00:00:00+05:30");
  /** 210 days — past the 92-day span cap, so the rebuild MUST plan several windows. */
  private static final OffsetDateTime TO = FROM.plusDays(210);

  @Autowired private CandleRepository repository;
  @Autowired private JdbcTemplate jdbc;

  private static Candle bar(OffsetDateTime bucket) {
    return new Candle(
        "NSE", SYMBOL, "1m", bucket,
        new BigDecimal("100.00"), new BigDecimal("101.00"),
        new BigDecimal("99.00"), new BigDecimal("100.50"),
        500, null, "MOCK");
  }

  /** Three 1m bars a day, each in its own 5m bucket, across the whole range. */
  private int seedBars() {
    List<Candle> bars = new ArrayList<>();
    for (OffsetDateTime day = FROM; day.isBefore(TO); day = day.plusDays(1)) {
      bars.add(bar(day.plusHours(10)));
      bars.add(bar(day.plusHours(11)));
      bars.add(bar(day.plusHours(12)));
    }
    repository.upsertAuthoritativeAll(bars);
    return bars.size();
  }

  private long materialised5mBuckets() {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM candles_5m WHERE exchange = 'NSE' AND tradingsymbol = ?"
                + " AND bucket >= ? AND bucket < ?",
            Long.class,
            SYMBOL,
            FROM,
            TO);
    return count == null ? 0 : count;
  }

  private List<ChunkLoad> chunkLoad() {
    return jdbc.execute(
        (ConnectionCallback<List<ChunkLoad>>)
            connection -> {
              try (Statement st = connection.createStatement()) {
                return CandleRepository.chunkLoad(st, DerivedAggregate.CANDLES_5M, FROM, TO);
              }
            });
  }

  @Test
  void aWindowedRebuildMaterialisesEveryBucketAcrossEveryWindowCut() {
    int seeded = seedBars();

    // the owner-scoped repair subset — intraday planes only; 1d is served by native candles@1d
    repository.refreshDerivedAggregatesForRebuild(
        FROM, TO, List.of(DerivedAggregate.CANDLES_5M, DerivedAggregate.CANDLES_15M,
            DerivedAggregate.CANDLES_1H));

    assertThat(materialised5mBuckets())
        .as("every seeded bar has its own 5m bucket — a window cut that dropped a straddling "
            + "bucket, or a gap between windows, shows up here as a shortfall")
        .isEqualTo(seeded);
  }

  /** Compresses this range's candles_5m materialization chunks — the live 2026-07-30 state. */
  private void compressRange() {
    jdbc.query(
        "SELECT public.compress_chunk(c, if_not_compressed => true)"
            + " FROM public.show_chunks('candles_5m', newer_than => ?::timestamptz,"
            + " older_than => ?::timestamptz) c",
        rs -> {},
        FROM,
        TO);
  }

  @Test
  void chunkLoadCountsCompressedChunksAndReportsNothingToDecompressForTheRest() {
    seedBars();
    repository.refreshDerivedAggregatesForRebuild(FROM, TO, List.of(DerivedAggregate.CANDLES_5M));

    List<ChunkLoad> before = chunkLoad();
    assertThat(before)
        .as("the catalog join must actually resolve — a renamed column would return NOTHING here "
            + "and silently plan the wide windows this planner exists to replace")
        .isNotEmpty();
    assertThat(before).allSatisfy(c -> assertThat(c.from()).isBefore(c.to()));
    assertThat(before.stream().mapToLong(ChunkLoad::tuples).sum())
        .as("an UNCOMPRESSED chunk reports 0 (reltuples is unpopulated until ANALYZE) — which is "
            + "the correct answer for a DECOMPRESSION budget: it decompresses nothing. The 92-day "
            + "span cap, not the tuple budget, is what bounds these windows.")
        .isZero();

    compressRange();

    assertThat(chunkLoad().stream().mapToLong(ChunkLoad::tuples).sum())
        .as("once compressed — the state that aborted the live rebuild — the count is real, and "
            + "it is what every planned window is sized against")
        .isPositive();
  }

  @Test
  void aTinyBudgetSplitsTheRealCompressedLayoutIntoManyGapFreeWindows() {
    seedBars();
    repository.refreshDerivedAggregatesForRebuild(FROM, TO, List.of(DerivedAggregate.CANDLES_5M));
    compressRange();
    List<ChunkLoad> chunks = chunkLoad();
    assertThat(chunks).isNotEmpty();

    long spanCapOnly =
        CandleRepository.planRebuildWindows(List.of(), FROM, TO, 100, 92, 1).size();
    // the live candles_5m budget is 1.25M against chunks of ~5M; a 1-tuple budget against these
    // chunks reproduces that pressure at IT scale, on the REAL deployed chunk layout
    List<Window> windows = CandleRepository.planRebuildWindows(chunks, FROM, TO, 1, 92, 1);

    assertThat((long) windows.size())
        .as("a budget far below the measured chunk load must SPLIT, not shrug — and split further "
            + "than the day cap alone would")
        .isGreaterThan(spanCapOnly);
    assertThat(windows.get(0).from()).isEqualTo(FROM);
    assertThat(windows.get(windows.size() - 1).to()).isEqualTo(TO);
    for (int i = 1; i < windows.size(); i++) {
      assertThat(windows.get(i).from())
          .as("window %s must start inside its predecessor — no gap, no dropped bucket", i)
          .isBefore(windows.get(i - 1).to());
      assertThat(Duration.between(windows.get(i).from(), windows.get(i).to())).isPositive();
    }
  }
}
