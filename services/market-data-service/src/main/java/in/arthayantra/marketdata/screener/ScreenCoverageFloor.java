package in.arthayantra.marketdata.screener;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.LocalDate;

/**
 * Refuses to publish an equity screen the trailing-bar guard has gutted.
 *
 * <p><b>Why.</b> The guard drops any symbol whose latest bar trails the universe's latest bar, which
 * is right for the handful of names that stopped printing but converts a PARTIAL bhavcopy ingest
 * from "wrong-but-full output" into "a silently near-empty screen". If only a fraction of the
 * universe prints for the day, every symbol still on yesterday's bar is dropped at once and the
 * screen publishes a tiny, plausible-looking result. Nothing downstream would notice: the schedulers
 * had no coverage check of any kind.
 *
 * <p><b>Denominator: the universe, not the prior run.</b> {@code eligible} is the count of symbols
 * that reached the row selection in THIS query — same window, same series filter, same session /
 * price / turnover gates — of which {@code surviving} carry the universe's latest bar. A prior-run
 * or rolling-median denominator was rejected on two counts: it drifts if two bad nights run
 * consecutively (the second night compares against the first night's already-depressed count), and,
 * decisively, it is meaningless on a historical {@code screen(asOf)} replay, where the persisted
 * "previous" run belongs to a different era and the floor would trip on every backfill. A
 * universe-relative ratio is computed from the same bounded window at any {@code asOf}, so it reads
 * identically on a replay and on today — the same property that made {@code max(bucket) FROM base}
 * the right comparison date rather than a global {@code max(trade_date)}.
 *
 * <p><b>What defeats it:</b> a TOTAL ingest failure, where no rows land for the day at all. Then the
 * universe's max bar is yesterday, every symbol matches it, the ratio is 100% and the floor passes.
 * The floor cannot see this case at all — it is closed OUTSIDE this class, by resolving the screen
 * date to the real bhavcopy watermark ({@code effectiveScreenDate}) so the result is labelled with
 * the date its data actually came from.
 *
 * <p>⚠️ That correction is load-bearing and was originally missing. This javadoc previously argued
 * the total-failure case was "already safe because the screen is labelled yesterday" — true only on
 * the scheduler path, which passes a null {@code asOf}. An EXPLICIT {@code asOf} (POST /run) used to
 * be retained verbatim as the label while the row selection compared against the {@code asOf}-bounded
 * maximum, so {@code POST /run?asOf=today} before the evening bhavcopy landed saw 100% coverage,
 * passed this floor, and persisted YESTERDAY's rows under TODAY's date — the very mislabelling the
 * trailing-bar guard exists to prevent, entering through the one door the floor cannot watch. The
 * argument was reasoned rather than executed; the test that now pins it drives the endpoint and
 * asserts the persisted {@code screen_date}.
 *
 * <p><b>Threshold.</b> Default 80%, config-tunable per screen. Measured over the 25 trade dates from
 * 2026-06-30 to 2026-08-03, the surviving fraction ranged 99.29%–100.00% (worst: 2026-07-20 and
 * 2026-07-23, both 99.29%). 80% therefore leaves ~19 points of headroom — the stale set would have
 * to grow roughly 28× in a single day to trip it — while still catching the loss of a fifth of the
 * universe, which is never a normal session.
 *
 * <p><b>"Refuse" means do not publish.</b> This throws, so the caller never reaches its
 * {@code upsertAll}, and the previous screen date remains the latest — labelled with ITS OWN date,
 * which is truthful. Deliberately NOT chosen: re-publishing the prior run under today's date (that
 * is precisely the stale-close-under-a-current-badge defect this whole change exists to fix), and
 * publishing-with-a-flag (needs a migration plus consumer cooperation, and any consumer that ignored
 * the flag would silently get the gutted screen). Every publish path — both schedulers, both
 * {@code POST /run} controllers, {@code MinerviniScheduler.runOnce} — routes through
 * {@code screen()}, so refusing here covers all of them by construction; the read paths serve the
 * repository and are unaffected. Both schedulers already catch, record {@code ledger.fail(...)} and
 * fire a high-priority {@code NtfyClient} alert carrying this message, so no new alert channel is
 * introduced.
 */
public final class ScreenCoverageFloor {

  private ScreenCoverageFloor() {}

  /**
   * Throws {@link ApiException} (422 / {@code DATA_GAP}, the same shape {@code EquityReturnsService}
   * uses for an under-accrued read of this table) when {@code surviving} is below
   * {@code minPct}% of {@code eligible}.
   *
   * <p>An {@code eligible} of 0 is NOT a floor breach — there is simply no data to screen yet, which
   * the callers already handle by publishing nothing.
   */
  public static void check(
      String screen, LocalDate asOf, int surviving, int eligible, int minPct) {
    if (eligible <= 0 || surviving * 100L >= (long) eligible * minPct) {
      return;
    }
    throw new ApiException(
        422,
        ErrorCodes.DATA_GAP,
        ("%s screen REFUSED for %s: only %d of %d eligible symbols (%.1f%%) carry the universe's "
                + "latest bar, below the %d%% coverage floor — the day's bhavcopy ingest is most "
                + "likely partial. Nothing was published; the previous screen date stands.")
            .formatted(screen, asOf, surviving, eligible, 100.0 * surviving / eligible, minPct));
  }
}
