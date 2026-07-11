package in.arthayantra.backtest.experiments;

import java.util.List;

/**
 * The server-side compare matrix (audit §13 #11 / roadmap #20): the per-run metric cells the FE compare
 * page currently stitches from N separate {@code /results} fetches, plus the {@link LikeForLike}
 * comparability verdict — one call for Prompt 2 to consume, whose ranking output extends this shape
 * rather than inventing a new one. A TYPED record (never a {@code Map}).
 */
public record ExperimentCompareResponse(List<ExperimentCompareRun> runs, LikeForLike likeForLike) {}
