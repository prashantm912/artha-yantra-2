package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategysignal.insights.TrustSnapshot.FamilyTrust;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * The trust oracle (INT design §7.1): composes P1's existing rails — the {@code ingest_runs} board
 * ({@code /market/health/ingest}, #699) and the live-capture canary ({@code /market/health/data}) —
 * into a per-family {@link TrustSnapshot}. Fail-soft everywhere: an unreachable rail degrades that
 * family to a CONSERVATIVE state (never OK, never a 5xx). A short in-memory TTL memo keeps a burst of
 * signals in one bar from re-fanning-out the two health reads.
 */
@Service
public class TrustService {

  /** The family whose trust caps a signal's priority (ticks live on the traded instrument). */
  public static final String LIVE_CAPTURE = "live-capture";

  private static final long MEMO_TTL_MS = 30_000;

  private final ContextClient client;
  private final Clock clock;
  private final AtomicReference<Memo> memo = new AtomicReference<>();

  private record Memo(TrustSnapshot snapshot, long expiresAtMs) {}

  public TrustService(ContextClient client, Clock clock) {
    this.client = client;
    this.clock = clock;
  }

  /** The current trust snapshot (memoized ~30s), composed from the two health rails. */
  public TrustSnapshot snapshot() {
    long now = clock.millis();
    Memo hit = memo.get();
    if (hit != null && now < hit.expiresAtMs()) {
      return hit.snapshot();
    }
    TrustSnapshot fresh = compose();
    memo.set(new Memo(fresh, now + MEMO_TTL_MS));
    return fresh;
  }

  /** Per-family trust for the {@code /trust-summary} strip (§7.3). */
  public List<FamilyTrust> summary() {
    return snapshot().families();
  }

  /**
   * The trust verdict that caps a signal's priority — the live-capture family (ticks live on the
   * traded instrument, §7.4). Absent → conservative DEGRADED.
   */
  public FamilyTrust signalTrust(TrustSnapshot snap) {
    return snap.family(LIVE_CAPTURE)
        .orElseGet(
            () -> new FamilyTrust(LIVE_CAPTURE, DataTrust.DEGRADED, List.of("live-capture health unavailable"), snap.asOf()));
  }

  private TrustSnapshot compose() {
    String asOf = clock.instant().toString();
    List<FamilyTrust> families = new ArrayList<>();
    families.add(liveCapture(asOf));
    families.addAll(ingestFamilies(asOf));
    return new TrustSnapshot(asOf, List.copyOf(families));
  }

  /** live-capture family from {@code /market/health/data} (GREEN/AMBER/RED → OK/DEGRADED/BLOCKED). */
  private FamilyTrust liveCapture(String asOf) {
    return client
        .dataHealth()
        .map(
            report -> {
              DataTrust state = fromGar(report.path("status").asText("RED"));
              List<String> reasons = new ArrayList<>();
              for (JsonNode p : report.path("problems")) {
                String detail = p.path("detail").asText(p.path("check").asText(null));
                if (detail != null && !detail.isBlank()) {
                  reasons.add(detail);
                }
              }
              if (!report.path("marketOpen").asBoolean(true)) {
                reasons.add("market closed");
              }
              return new FamilyTrust(LIVE_CAPTURE, state, List.copyOf(reasons), report.path("asOf").asText(asOf));
            })
        .orElseGet(() -> new FamilyTrust(LIVE_CAPTURE, DataTrust.DEGRADED, List.of("market-data health unreachable"), asOf));
  }

  /** One family per expected ingest source from {@code /market/health/ingest} (GREEN/YELLOW/RED). */
  private List<FamilyTrust> ingestFamilies(String asOf) {
    return client
        .ingestHealth()
        .map(
            board -> {
              List<FamilyTrust> out = new ArrayList<>();
              for (JsonNode s : board.path("sources")) {
                String source = s.path("source").asText(null);
                if (source == null) {
                  continue;
                }
                DataTrust state = fromGyr(s.path("status").asText("RED"));
                String detail = s.path("detail").asText("");
                int missing = s.path("missingDays").asInt(0);
                List<String> reasons = new ArrayList<>();
                if (!detail.isBlank()) {
                  reasons.add(detail);
                }
                if (missing > 0) {
                  reasons.add(missing + " missed day(s) in window");
                }
                out.add(new FamilyTrust(source, state, List.copyOf(reasons), asOf));
              }
              return out;
            })
        .orElseGet(() -> List.of(new FamilyTrust("ingest", DataTrust.DEGRADED, List.of("ingest health board unreachable"), asOf)));
  }

  private static DataTrust fromGar(String status) {
    return switch (status) {
      case "GREEN" -> DataTrust.OK;
      case "AMBER" -> DataTrust.DEGRADED;
      default -> DataTrust.BLOCKED; // RED / unknown
    };
  }

  private static DataTrust fromGyr(String status) {
    return switch (status) {
      case "GREEN" -> DataTrust.OK;
      case "YELLOW" -> DataTrust.DEGRADED;
      default -> DataTrust.BLOCKED; // RED / unknown
    };
  }
}
