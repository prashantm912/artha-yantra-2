package in.arthayantra.backtest.replay.folds;

import java.time.OffsetDateTime;

/**
 * One walk-forward fold: a contiguous {@code [trainFrom, trainTo)} in-sample window paired with the
 * immediately-following {@code [testFrom, testTo)} out-of-sample window. Windows are half-open on
 * {@code bucketStart} (the same convention as the candle reader and the data-hash tuple) and {@code
 * testFrom} always equals {@code trainTo} — the OOS window picks up exactly where training stopped,
 * so no bar is shared between the two and there is no gap. Bounds are computed in TRADING days via
 * {@link in.arthayantra.marketcalendar.MarketCalendar}, never wall-clock days (guard 2, §D.4).
 *
 * @param index zero-based fold index in chronological order
 * @param trainFrom inclusive train window start
 * @param trainTo exclusive train window end (also the inclusive test window start)
 * @param testFrom inclusive test window start (equals {@code trainTo})
 * @param testTo exclusive test window end
 */
public record Fold(
    int index,
    OffsetDateTime trainFrom,
    OffsetDateTime trainTo,
    OffsetDateTime testFrom,
    OffsetDateTime testTo) {}
