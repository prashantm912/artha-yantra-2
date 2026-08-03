package in.arthayantra.marketdata.screener.manas;

import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBarReader;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpFootprint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates per-candidate Manas base geometry: reads a symbol's recent daily series (via the
 * shared {@link DailyBarReader}) and runs BOTH the reused {@link VcpDetector} (the §3.3 vcp setup)
 * and {@link ConsolidationBreakout} (the §3.2 breakout setup). Used both by {@code ManasScheduler}
 * (batch, the day's passers) and the analyzer endpoint (single symbol, on demand). Pure fan-out over
 * the two detectors — no state. Mirrors the sibling {@code MinerviniGeometryService}.
 */
@Service
public class ManasGeometryService {

  private final DailyBarReader reader;
  private final VcpDetector vcpDetector;
  private final ConsolidationBreakout breakoutDetector;
  private final ManasSetupsRepository setupsRepo;
  private final int lookbackDays;
  private final boolean enabled;

  /** Wires the daily-bar reader + both detectors + the setups repo; {@code lookbackDays} bounds the window. */
  public ManasGeometryService(
      DailyBarReader reader,
      VcpDetector vcpDetector,
      ConsolidationBreakout breakoutDetector,
      ManasSetupsRepository setupsRepo,
      @Value("${artha.manas-arora.geometry.lookback-days:400}") int lookbackDays,
      @Value("${artha.manas-arora.geometry.enabled:true}") boolean enabled) {
    this.reader = reader;
    this.vcpDetector = vcpDetector;
    this.breakoutDetector = breakoutDetector;
    this.setupsRepo = setupsRepo;
    this.lookbackDays = lookbackDays;
    this.enabled = enabled;
  }

  /** Detects both setups for one symbol as of {@code asOf} (vcp first, then breakout). */
  public List<ManasSetup> detect(String symbol, LocalDate asOf) {
    List<DailyBar> bars = reader.read(symbol, asOf, lookbackDays);
    if (bars.isEmpty()) {
      return List.of(
          ManasSetup.vcp(VcpFootprint.rejected("no daily data")),
          ManasSetup.breakout(BreakoutFootprint.rejected("no daily data")));
    }
    List<ManasSetup> out = new ArrayList<>(2);
    out.add(ManasSetup.vcp(vcpDetector.detect(bars)));
    out.add(ManasSetup.breakout(breakoutDetector.detect(bars)));
    return out;
  }

  /** Detects geometry for a set of symbols; preserves input order (symbol → the 2 setups). */
  public Map<String, List<ManasSetup>> detectAll(List<String> symbols, LocalDate asOf) {
    Map<String, List<ManasSetup>> out = new LinkedHashMap<>();
    for (String s : symbols) {
      out.put(s, detect(s, asOf));
    }
    return out;
  }

  /**
   * Computes + persists geometry for the day's passers only (the per-symbol scan is expensive), the
   * single writer of {@code manas_arora_setups} shared by the scheduler (boot/cron) and the
   * controller (POST /run). Gated by {@code artha.manas-arora.geometry.enabled}. Returns rows written
   * (0 when disabled or no passers).
   */
  public int persistForPassers(LocalDate screenDate, List<ManasCandidate> candidates) {
    if (!enabled || screenDate == null) {
      return 0;
    }
    List<String> passers =
        candidates.stream().filter(ManasCandidate::passesAll).map(ManasCandidate::symbol).toList();
    // No early return on an empty passer list: replaceAll must still run so a setup row for a
    // symbol the trailing-bar guard has since dropped is cleared rather than stranded at this date.
    return setupsRepo.replaceAll(screenDate, detectAll(passers, screenDate));
  }
}
