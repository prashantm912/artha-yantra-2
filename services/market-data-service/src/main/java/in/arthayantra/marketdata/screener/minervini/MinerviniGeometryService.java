package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBarReader;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpFootprint;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates per-candidate base geometry (MV-5.4/5.5): reads a symbol's recent daily series and
 * runs {@link VcpDetector}. Used both by {@code MinerviniScheduler} (batch, the day's passers) and
 * the analyzer endpoint (single symbol, on demand). Pure fan-out over the detector — no state.
 */
@Service
public class MinerviniGeometryService {

  private final DailyBarReader reader;
  private final VcpDetector detector;
  private final MinerviniSetupsRepository setupsRepo;
  private final int lookbackDays;
  private final boolean enabled;

  /** Wires the daily-bar reader + VCP detector + setups repo; {@code lookbackDays} bounds the window. */
  public MinerviniGeometryService(
      DailyBarReader reader,
      VcpDetector detector,
      MinerviniSetupsRepository setupsRepo,
      @Value("${artha.minervini.vcp.lookback-days:400}") int lookbackDays,
      @Value("${artha.minervini.vcp.enabled:true}") boolean enabled) {
    this.reader = reader;
    this.detector = detector;
    this.setupsRepo = setupsRepo;
    this.lookbackDays = lookbackDays;
    this.enabled = enabled;
  }

  /** Detects the most recent VCP base for one symbol as of {@code asOf}. */
  public VcpFootprint detect(String symbol, LocalDate asOf) {
    List<DailyBar> bars = reader.read(symbol, asOf, lookbackDays);
    if (bars.isEmpty()) {
      return VcpFootprint.rejected("no daily data");
    }
    return detector.detect(bars);
  }

  /** Detects geometry for a set of symbols; preserves input order (symbol → footprint). */
  public Map<String, VcpFootprint> detectAll(List<String> symbols, LocalDate asOf) {
    Map<String, VcpFootprint> out = new LinkedHashMap<>();
    for (String s : symbols) {
      out.put(s, detect(s, asOf));
    }
    return out;
  }

  /**
   * Computes + persists geometry for the day's passers only (the per-symbol scan is expensive), the
   * single writer of {@code minervini_setups} shared by the scheduler (boot/cron) and the controller
   * (POST /run). Gated by {@code artha.minervini.vcp.enabled}. Returns rows written (0 when disabled
   * or no passers).
   */
  public int persistForPassers(LocalDate screenDate, List<TrendCandidate> candidates) {
    if (!enabled || screenDate == null) {
      return 0;
    }
    List<String> passers =
        candidates.stream().filter(TrendCandidate::passesAll).map(TrendCandidate::symbol).toList();
    if (passers.isEmpty()) {
      return 0;
    }
    return setupsRepo.upsertAll(screenDate, detectAll(passers, screenDate));
  }
}
