package in.arthayantra.backtest.replay;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The CONTENT-STABLE dataset fingerprint (audit P1-1 / R2). Unlike {@link DataHash} — whose tuple leg
 * carries {@code max(fetched_at)} and therefore FLIPS on a harmless value-identical tail re-fetch
 * ({@code fetched_at=now()} is re-stamped even on a no-op conflict write) — this hash folds only the
 * <b>actual bar values</b> of the series a run consumed, so:
 *
 * <ul>
 *   <li>a value-identical re-fetch (or a run re-executed against unchanged data) hashes IDENTICALLY, and
 *   <li>any changed bar (a CA re-adjust, a corrected backfill) changes the hash.
 * </ul>
 *
 * <p><b>Recipe.</b> SHA-256 over an ordered list of consumed-series tuples, each rendered as {@code
 * exchange|tradingsymbol|interval|barCount|valueChecksum} joined by {@code ~}. The primary series is
 * first; the caller appends the strike-reference + each context series (all held in memory, so their
 * {@code valueChecksum} is real) and, for the options path, one leg per traded option contract (NOT held
 * in memory — those legs carry {@code barCount} with the literal {@code valueChecksum} {@code "count"},
 * a documented weaker leg whose content-drift is instead caught by the epoch ledger + the primary's own
 * checksum). {@code valueChecksum} for an in-memory series is itself a SHA-256 STREAMED over each bar's
 * {@code bucketEpochSeconds:open:high:low:close:volume:oi} (exact decimals via {@code toPlainString};
 * {@code oi} rendered {@code "none"} when null) in bucket order — bounded memory even for a year of 1m.
 */
public final class DatasetContentHash {

  private DatasetContentHash() {}

  /** One consumed series' content leg: identity + bar count + a value checksum (or {@code "count"}). */
  public record Series(
      String exchange, String tradingsymbol, String interval, long barCount, String valueChecksum) {

    /** A leg whose value checksum is a streamed SHA-256 over the in-memory bars (bucket order). */
    public static Series withCandles(
        String exchange, String tradingsymbol, String interval, List<EngineCandle> candles) {
      return new Series(exchange, tradingsymbol, interval, candles.size(), checksumOf(candles));
    }

    /** A leg the runner does NOT hold in memory (an option-contract premium series): count-only. */
    public static Series countOnly(
        String exchange, String tradingsymbol, String interval, long barCount) {
      return new Series(exchange, tradingsymbol, interval, barCount, "count");
    }
  }

  /** The content hash over the ordered series legs (primary first). */
  public static String of(List<Series> series) {
    StringBuilder tuple = new StringBuilder();
    for (Series s : series) {
      if (tuple.length() > 0) {
        tuple.append('~');
      }
      tuple
          .append(s.exchange())
          .append('|')
          .append(s.tradingsymbol())
          .append('|')
          .append(s.interval())
          .append('|')
          .append(s.barCount())
          .append('|')
          .append(s.valueChecksum());
    }
    return sha256Hex(tuple.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The value checksum of one in-memory series — a SHA-256 streamed over each bar's
   * {@code bucketEpochSeconds:open:high:low:close:volume:oi} in the list's order (already bucket-ordered
   * by {@link CandleReader}). Deterministic and {@code fetched_at}-independent; changes iff a bar's OHLCV
   * or OI changes. An empty series hashes to the digest of the empty stream (a stable, distinct value).
   */
  public static String checksumOf(List<EngineCandle> candles) {
    MessageDigest digest = sha256();
    for (EngineCandle c : candles) {
      StringBuilder bar =
          new StringBuilder()
              .append(c.bucketStart().toInstant().getEpochSecond())
              .append(':')
              .append(c.open().toPlainString())
              .append(':')
              .append(c.high().toPlainString())
              .append(':')
              .append(c.low().toPlainString())
              .append(':')
              .append(c.close().toPlainString())
              .append(':')
              .append(c.volume())
              .append(':')
              .append(c.oi() == null ? "none" : c.oi().toPlainString())
              .append(';');
      digest.update(bar.toString().getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String sha256Hex(byte[] bytes) {
    return HexFormat.of().formatHex(sha256().digest(bytes));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
