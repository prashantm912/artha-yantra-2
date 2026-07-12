package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes a P1-7 flag-snapshot row: the live values of behaviour-affecting flags that live OUTSIDE the
 * version-pinned strategy YAML (the paper risk governor + env knobs at order open; the pyramid env flags at
 * a swing batch run), keyed to the order/run, so forward-paper evidence can be stratified by flag regime
 * after the fact. {@code flags_hash} is a canonical (alphabetical-key) sha-256 so identical regimes share a
 * hash — "did the flag regime change between run A and run B" is a hash comparison. Fail-soft: an
 * observability write must never break the trade/run it observes. Lives in the signals module (which both
 * the paper adapter and the swing engine already depend on), so it carries no paper/notifier import and the
 * module graph stays acyclic.
 */
@Service
public class FlagSnapshotService {

  /** Context kinds. */
  public static final String PAPER_ORDER = "PAPER_ORDER";

  public static final String SWING_BATCH = "SWING_BATCH";

  private static final Logger log = LoggerFactory.getLogger(FlagSnapshotService.class);

  private final FlagSnapshotRepository repo;
  // A dedicated canonical mapper: alphabetical keys → a stable hash regardless of the flag map's iteration
  // order (belt-and-suspenders with the TreeMap below).
  private final ObjectMapper canonical =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  /** Wires the append-only ledger. */
  public FlagSnapshotService(FlagSnapshotRepository repo) {
    this.repo = repo;
  }

  /**
   * Captures a flag regime keyed to a context ({@code contextRef} = the paper position id / a
   * {@code batch:runDate} key). Fail-soft — logs and swallows any failure.
   */
  public void capture(String contextKind, String contextRef, String book, Map<String, Object> flags) {
    try {
      String flagsJson = canonical.writeValueAsString(new TreeMap<>(flags));
      repo.insert(contextKind, contextRef, book, flagsJson, sha256(flagsJson));
    } catch (Exception e) {
      log.warn("flag_snapshots not written for {}:{}: {}", contextKind, contextRef, e.getMessage());
    }
  }

  private static String sha256(String s) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
