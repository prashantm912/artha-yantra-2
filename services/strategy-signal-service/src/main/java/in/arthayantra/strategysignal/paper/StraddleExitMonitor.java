package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.StraddleLegRow;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient.StraddleSl;
import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * E11 part 2 — the LIVE combined-premium auto-exit for #11 long straddles. The faithful straddle stop is
 * the combined (CE+PE) premium falling to/below its session-VWAP-minus-buffer level (§3.11) — a LIVE
 * market-data construct the deterministic engine cannot compute, so it rides this scheduled monitor
 * rather than the YAML exit_rules. Parity-safe: it only runs live (the scheduler is never in a replay)
 * and only ACTS on a paper straddle position — engine goldens / backtests are untouched.
 *
 * <p>Each poll (market-hours only) re-forms every open straddle pair (the two legs share their parent
 * NEUTRAL signal), reads the {@code slLevel} + the latest combined premium from market-data's
 * straddle-chart, and when the premium has hit the stop it closes BOTH legs at their current premium. A
 * missing read / strike never triggers an exit (fail-safe — it leaves the position for the operator).
 */
@Component
public class StraddleExitMonitor {

  private static final Logger log = LoggerFactory.getLogger(StraddleExitMonitor.class);
  private static final LocalTime OPEN = LocalTime.of(9, 15);
  private static final LocalTime CLOSE = LocalTime.of(15, 30);

  private final PaperPositionRepository positions;
  private final MarketDataCandlesClient marketData;
  private final PaperService paper;
  private final ObjectMapper mapper;
  private final Clock clock;

  public StraddleExitMonitor(
      PaperPositionRepository positions,
      MarketDataCandlesClient marketData,
      PaperService paper,
      ObjectMapper mapper,
      Clock clock) {
    this.positions = positions;
    this.marketData = marketData;
    this.paper = paper;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Polls during market hours; the scheduler never runs in a deterministic replay (parity-safe). */
  @Scheduled(fixedDelayString = "${artha.straddle.exit-poll-ms:60000}")
  public void poll() {
    if (withinMarketHours()) {
      evaluate();
    }
  }

  /** The core (testable): close both legs of any open straddle whose combined premium has hit the stop. */
  void evaluate() {
    List<StraddleLegRow> legs = positions.openStraddleLegs();
    if (legs.isEmpty()) {
      return;
    }
    Map<Long, List<StraddleLegRow>> bySignal =
        legs.stream().collect(Collectors.groupingBy(StraddleLegRow::signalId));
    for (Map.Entry<Long, List<StraddleLegRow>> entry : bySignal.entrySet()) {
      List<StraddleLegRow> pair = entry.getValue();
      Optional<StraddleLegs.Ref> ref = ref(pair.get(0).scalperDetail());
      if (ref.isEmpty()) {
        continue;
      }
      Optional<StraddleSl> sl =
          marketData.straddleSl(ref.get().name(), ref.get().expiry(), ref.get().strike());
      if (sl.isEmpty() || !StraddleLegs.shouldExit(sl.get().combinedPremium(), sl.get().slLevel())) {
        continue;
      }
      for (StraddleLegRow leg : pair) {
        try {
          paper.closePosition(leg.positionId(), null); // null → close at the leg's current premium
        } catch (RuntimeException e) {
          log.warn("straddle exit: leg {} close failed: {}", leg.positionId(), e.getMessage());
        }
      }
      log.info(
          "straddle combined-prem SL hit: signal {} → closed {} leg(s) (combined {} <= slLevel {})",
          entry.getKey(), pair.size(), sl.get().combinedPremium(), sl.get().slLevel());
    }
  }

  private Optional<StraddleLegs.Ref> ref(String scalperDetailJson) {
    if (scalperDetailJson == null) {
      return Optional.empty();
    }
    try {
      JsonNode root = mapper.readTree(scalperDetailJson);
      return StraddleLegs.ref(root);
    } catch (Exception malformed) {
      return Optional.empty();
    }
  }

  private boolean withinMarketHours() {
    LocalTime now = LocalTime.now(clock.withZone(Ist.ZONE));
    return !now.isBefore(OPEN) && !now.isAfter(CLOSE);
  }
}
