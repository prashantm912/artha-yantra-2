package in.arthayantra.backtest.replay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/**
 * The reproducibility-triple leg 3 (§D.6): SHA-256 over the ordered tuple set (instrument keys,
 * interval, from/to, bar count, max fetched_at) of the candles actually read. Same triple ⇒
 * byte-identical trade list; differing {@code dataHash} flags leaderboard rows as not like-for-like.
 */
public final class DataHash {

  private DataHash() {}

  /** Computes the SHA-256 hex of the ordered read tuple. */
  public static String of(
      String exchange,
      String tradingsymbol,
      String interval,
      OffsetDateTime from,
      OffsetDateTime to,
      long barCount,
      OffsetDateTime maxFetchedAt) {
    String tuple =
        String.join(
            "|",
            exchange,
            tradingsymbol,
            interval,
            from.toInstant().toString(),
            to.toInstant().toString(),
            Long.toString(barCount),
            maxFetchedAt == null ? "none" : maxFetchedAt.toInstant().toString());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(tuple.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
