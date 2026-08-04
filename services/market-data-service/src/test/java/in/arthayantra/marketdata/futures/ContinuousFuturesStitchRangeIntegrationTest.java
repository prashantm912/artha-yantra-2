package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the DB-side half of the 2026-08-04 continuous-futures roll fix, which a mocked repository
 * cannot reach: {@link CandleRepository#stitchInto} must report the range it ACTUALLY inserted, and
 * that range must be decided by PostgreSQL's {@code RETURNING} semantics under {@code ON CONFLICT
 * DO NOTHING}, not by the window the caller asked for.
 *
 * <p>The roller asks to stitch from its {@code STITCH_EPOCH} of 2000-01-01 every evening, and the
 * refresh window used to be that request. Because a re-run inserts only the newest bars, the
 * requested window over-states the real cagg invalidation by decades — which is what made the
 * nightly refresh reach into chunks compressed by V049 and abort the roll for all six index roots
 * ({@code tuple decompression limit exceeded by operation … tuples decompressed: 341820}). The fix
 * is only sound if {@code RETURNING} really does skip conflicting rows; if it returned them the
 * reported range would silently widen back to the full stitch and the defect would return wearing a
 * green suite. That is a claim about the database, so it is tested against the real one.
 *
 * <p>Throwaway {@code TSTSR} symbols — the IT DB is a shared singleton with no per-method cleanup.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class ContinuousFuturesStitchRangeIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String CONT = "TSTSR-FUT-CONT";
  private static final String CONTRACT = "TSTSR26JANFUT";

  /** The roller's real ask: everything since the epoch. */
  private static final OffsetDateTime EPOCH = ist("2000-01-01T00:00:00");
  private static final OffsetDateTime FAR_FUTURE = ist("2027-01-01T00:00:00");

  private static final OffsetDateTime DAY_ONE = ist("2026-01-05T09:15:00");
  private static final OffsetDateTime DAY_ONE_LAST = ist("2026-01-05T15:29:00");
  private static final OffsetDateTime DAY_TWO = ist("2026-01-06T09:15:00");
  private static final OffsetDateTime DAY_TWO_LAST = ist("2026-01-06T15:29:00");

  @Autowired private CandleRepository candles;
  @Autowired private JdbcTemplate jdbc;

  private static OffsetDateTime ist(String text) {
    return OffsetDateTime.parse(text + "+05:30");
  }

  private void seed1m(OffsetDateTime bucket, String close) {
    BigDecimal c = new BigDecimal(close);
    candles.upsert(new Candle("NFO", CONTRACT, "1m", bucket, c, c, c, c, 100, 0L, "MOCK"));
  }

  @BeforeEach
  void cleanSlate() {
    jdbc.update("DELETE FROM candles WHERE tradingsymbol LIKE 'TSTSR%'");
  }

  @Test
  void reportsTheInsertedBucketRangeNotTheRequestedWindow() {
    seed1m(DAY_ONE, "100");
    seed1m(DAY_ONE_LAST, "101");

    CandleRepository.StitchedRange stitched =
        candles.stitchInto(CONT, "NFO", CONTRACT, EPOCH, FAR_FUTURE);

    assertThat(stitched.rows()).isEqualTo(2);
    assertThat(stitched.firstBucket())
        .as("the earliest bar that exists — NOT the requested EPOCH")
        .isEqualTo(DAY_ONE)
        .isNotEqualTo(EPOCH);
    assertThat(stitched.lastBucket()).isEqualTo(DAY_ONE_LAST);
  }

  @Test
  void aReRunReportsOnlyTheNEWLYInsertedDayNotTheWholeStitch() {
    // day one is already stitched — exactly the steady state of the nightly roll
    seed1m(DAY_ONE, "100");
    seed1m(DAY_ONE_LAST, "101");
    candles.stitchInto(CONT, "NFO", CONTRACT, EPOCH, FAR_FUTURE);

    // the next session arrives; the roller re-asks for the SAME epoch-wide window
    seed1m(DAY_TWO, "102");
    seed1m(DAY_TWO_LAST, "103");
    CandleRepository.StitchedRange second =
        candles.stitchInto(CONT, "NFO", CONTRACT, EPOCH, FAR_FUTURE);

    assertThat(second.rows())
        .as("ON CONFLICT DO NOTHING skipped day one, and RETURNING must skip it too")
        .isEqualTo(2);
    assertThat(second.firstBucket())
        .as(
            "the invalidated range is TODAY. Day one here means RETURNING yielded conflicting rows"
                + " and the refresh window silently widens back toward the 2026-08-04 failure")
        .isEqualTo(DAY_TWO)
        .isNotEqualTo(DAY_ONE);
    assertThat(second.lastBucket()).isEqualTo(DAY_TWO_LAST);
  }

  @Test
  void aFullyIdempotentReRunReportsNothingToRefresh() {
    seed1m(DAY_ONE, "100");
    candles.stitchInto(CONT, "NFO", CONTRACT, EPOCH, FAR_FUTURE);

    CandleRepository.StitchedRange again =
        candles.stitchInto(CONT, "NFO", CONTRACT, EPOCH, FAR_FUTURE);

    assertThat(again.rows()).isZero();
    assertThat(again.firstBucket())
        .as("no rows inserted → no invalidation → the roller must not refresh at all")
        .isNull();
    assertThat(again.lastBucket()).isNull();
  }

  @Test
  void stillCopiesTheBarsItReports() {
    // the range is a report ABOUT the insert, so prove the insert itself did not regress
    seed1m(DAY_ONE, "100");
    seed1m(DAY_TWO, "102");

    CandleRepository.StitchedRange stitched =
        candles.stitchInto(CONT, "NFO", CONTRACT, EPOCH, FAR_FUTURE);

    assertThat(stitched.rows()).isEqualTo(2);
    assertThat(candles.range("NFO", CONT, "1m", ist("2026-01-01T00:00:00"), FAR_FUTURE))
        .extracting(c -> c.close().stripTrailingZeros().toPlainString())
        .containsExactly("100", "102");
  }
}
