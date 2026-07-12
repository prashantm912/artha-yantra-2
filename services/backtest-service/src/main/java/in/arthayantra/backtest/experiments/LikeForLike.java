package in.arthayantra.backtest.experiments;

/**
 * The comparability verdict across the compared runs (audit §13 #11): a flag is {@code true} iff every
 * run shares the same value on that axis, so a consumer (or Prompt 2's ranking) can refuse to rank a
 * non-like-for-like set. {@code dataHashMatch} / {@code universeMatch} / {@code engineShaMatch} are the
 * three the audit names, each backed by a real run-row column (universe treats a NULL checksum as its
 * own value, so a mix of pinned + unpinned runs reads as a mismatch, matching the FE banner logic).
 *
 * <p>{@code premiumSourceMatch} stands in for the audit's fourth-named axis, "cost-model match": the
 * per-run cost-model provenance block (§11.1 {@code costModel{class,source}}) is NOT persisted yet
 * (audit B4 / roadmap #22a), so a genuine cost-class flag can't be computed. {@code premium_source}
 * (SNAPSHOT / SYNTHETIC_B76 / NA) is the nearest persisted execution-basis axis — it separates the
 * candle/equity cost path (NA) from the options-premium path and distinguishes real captured premiums
 * from a synthetic reconstruction. When cost-model provenance lands, a {@code costModelMatch} field is
 * an additive extension of this shape.
 *
 * <p><b>Dataset comparability (roadmap #22a/#30).</b> {@code contentHashMatch} is the content-stable
 * data-equality axis (P1-1 / R2) — it does NOT flip on a value-identical tail re-fetch the way {@code
 * dataHashMatch} can, so it is the axis a ranking client should require. {@code datasetEpochMatch} is the
 * cheap epoch-scope axis: runs sharing an epoch head executed against the same ledger state. The audit's
 * re-run policy is "same engine SHA + same content hash + same epoch, else re-run the stale side before
 * ranking".
 */
public record LikeForLike(
    boolean dataHashMatch,
    boolean universeMatch,
    boolean engineShaMatch,
    boolean premiumSourceMatch,
    boolean contentHashMatch,
    boolean datasetEpochMatch) {}
