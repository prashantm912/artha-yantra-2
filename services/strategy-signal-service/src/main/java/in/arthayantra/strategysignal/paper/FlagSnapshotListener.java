package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.paper.RiskSettingsRepository.Setting;
import in.arthayantra.strategysignal.signals.FlagSnapshotService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * P1-7 flag-snapshot at paper-order open. When a position opens (or averages), snapshot the live paper
 * risk-governor regime + env knobs that govern the fill — the flags NOT captured by the version-pinned
 * strategy YAML — keyed to the position id, so forward-paper evidence can later be stratified by flag
 * regime. Fires AFTER_COMMIT (the row exists only for a fill that committed) and off the trade transaction;
 * {@link FlagSnapshotService#capture} is fail-soft, so an observability write can never break the trade.
 *
 * <p>Depends only on {@link RiskService} + {@link FlagSnapshotService} (no {@code SignalEngine}), so it is
 * safe in the engine-disabled paper integration tests (#634). The env placeholders MUST match the ones
 * {@code PaperService}/{@code RiskService} read (the #653 name-mismatch trap).
 */
@Component
public class FlagSnapshotListener {

  private final FlagSnapshotService snapshots;
  private final RiskService risk;
  private final boolean riskEnforcementEnabled;
  private final BigDecimal perTradeRiskPct;
  private final long tickMaxAgeSeconds;

  /** Wires the ledger + the governor read + the same env knobs the fill path uses. */
  public FlagSnapshotListener(
      FlagSnapshotService snapshots,
      RiskService risk,
      @Value("${artha.paper.risk.enabled:false}") boolean riskEnforcementEnabled,
      @Value("${artha.paper.risk.per-trade-risk-pct:1.0}") BigDecimal perTradeRiskPct,
      @Value("${artha.paper.tick-max-age-seconds:15}") long tickMaxAgeSeconds) {
    this.snapshots = snapshots;
    this.risk = risk;
    this.riskEnforcementEnabled = riskEnforcementEnabled;
    this.perTradeRiskPct = perTradeRiskPct;
    this.tickMaxAgeSeconds = tickMaxAgeSeconds;
  }

  /** Snapshots the book's live flag regime, keyed to the opened position. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onOpened(PaperPositionOpened e) {
    Map<String, Object> flags = new TreeMap<>();
    flags.put("paper.risk.enabled", riskEnforcementEnabled);
    flags.put("paper.risk.perTradeRiskPct", perTradeRiskPct.toPlainString());
    flags.put("paper.tickMaxAgeSeconds", tickMaxAgeSeconds);
    // Per-book DB governor rows (kill_switch / max_open / daily_loss / profit / deployment / heat_cap /
    // auto_paper): the raw JSON value per key — these ARE behaviour-affecting and mutable.
    for (Setting s : risk.all(e.book())) {
      flags.put("governor." + s.key(), s.value().toString());
    }
    snapshots.capture(
        FlagSnapshotService.PAPER_ORDER, String.valueOf(e.positionId()), e.book(), flags);
  }
}
