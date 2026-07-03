package in.arthayantra.backtest.replay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * The reproducibility-triple leg 3 (§D.6): SHA-256 over the ordered tuple set (instrument keys,
 * interval, from/to, bar count, max fetched_at) of the candles actually read. The PRIMARY series
 * tuple comes first (byte-identical to the original algorithm); each ADDITIONAL consumed series
 * (audit row 6: the decoupled strike-reference, each context series, each traded option contract's
 * premium series) appends a {@link SeriesLeg} as {@code
 * |exchange|tradingsymbol|interval|barCount|maxFetchedAt} — so a run that consumed ONLY the primary
 * series hashes exactly as before the extension. Same tuple set ⇒ byte-identical trade list;
 * differing {@code dataHash} flags leaderboard rows as not like-for-like.
 */
public final class DataHash {

  private DataHash() {}

  /** One additional consumed series' tuple leg — mirrors the primary's identity/count/freshness. */
  public record SeriesLeg(
      String exchange,
      String tradingsymbol,
      String interval,
      long barCount,
      OffsetDateTime maxFetchedAt) {}

  /** Computes the SHA-256 hex of the ordered read tuple set (primary first, then each extra leg). */
  public static String of(
      String exchange,
      String tradingsymbol,
      String interval,
      OffsetDateTime from,
      OffsetDateTime to,
      long barCount,
      OffsetDateTime maxFetchedAt,
      List<SeriesLeg> extraSeries) {
    StringBuilder tuple =
        new StringBuilder(
            String.join(
                "|",
                exchange,
                tradingsymbol,
                interval,
                from.toInstant().toString(),
                to.toInstant().toString(),
                Long.toString(barCount),
                instantOrNone(maxFetchedAt)));
    for (SeriesLeg leg : extraSeries) {
      tuple
          .append('|')
          .append(leg.exchange())
          .append('|')
          .append(leg.tradingsymbol())
          .append('|')
          .append(leg.interval())
          .append('|')
          .append(leg.barCount())
          .append('|')
          .append(instantOrNone(leg.maxFetchedAt()));
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(tuple.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String instantOrNone(OffsetDateTime ts) {
    return ts == null ? "none" : ts.toInstant().toString();
  }
}
