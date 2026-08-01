package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository.Setting;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Global paper-risk limits (A12 / FP-42). Tripping the daily loss pauses ENTRY emission for the IST
 * day (exit/stop evaluation continues — the gate is consulted only by {@code emitEntry}); the kill
 * switch is one-click pause-all; {@code max_open_paper_positions} caps concurrency. Every trip / flip
 * writes a {@code risk_audit} row (trips deduped per IST day). Limits live on DB rows, never YAML.
 */
@Service
public class RiskService {

  /** The limit keys. */
  public static final String KILL_SWITCH = "kill_switch";
  public static final String MAX_OPEN = "max_open_paper_positions";
  public static final String DAILY_LOSS = "daily_loss_limit";
  public static final String DAILY_PROFIT_TARGET = "daily_profit_target";
  public static final String MAX_DEPLOYMENT_PCT = "max_deployment_pct";
  /** F9 portfolio heat cap: max total SPAN margin-at-risk as % of book equity (options books). */
  public static final String HEAT_CAP_PCT = "heat_cap_pct";
  /** When ON, an emitted ENTRY auto-opens a paper position at the suggested qty (no manual take). */
  public static final String AUTO_PAPER_TRADE = "auto_paper_trade";

  private static final Logger log = LoggerFactory.getLogger(RiskService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  /** Upstox caps a margin basket at 20 legs; the paper book is small, but cap defensively. */
  private static final int MAX_LEGS = 20;

  private final RiskSettingsRepository settings;
  private final PaperPositionRepository positions;
  private final PaperAccountService account;
  private final PaperMarginClient marginClient;
  private final NotifierClient notifier;
  private final Clock clock;

  /**
   * F9 master enforcement gate (default OFF → advisory: the heat cap audits + ntfy-alerts on a breach
   * but does not BLOCK a new entry until the owner flips it after a clean advisory week). The
   * daily-loss/kill/deployment governors are unaffected — they enforce on their own DB-setting flags.
   */
  private final boolean enforcementEnabled;

  /** Per-day, per-cap trip dedup (key -> IST day it last tripped); re-armed on an {@code update}. */
  private final java.util.Map<String, LocalDate> trippedOn = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Composite dedup-key delimiter (audit M34). A space would let {@code ("a b", "c")} and
   * {@code ("a", "b c")} collide; a NUL char never appears in a book slug or a limit-key constant.
   * Written as the integer literal {@code 0} so the source stays plain ASCII (no NUL byte / no
   * {@code \\u0000} escape that a JSON edit pipeline can re-inject as a real NUL).
   */
  private static final char DEDUP_DELIM = 0;

  private static String tripKey(String book, String key) {
    return book + DEDUP_DELIM + key;
  }

  /** Wires the risk inputs. */
  public RiskService(
      RiskSettingsRepository settings,
      PaperPositionRepository positions,
      PaperAccountService account,
      PaperMarginClient marginClient,
      NotifierClient notifier,
      Clock clock,
      @Value("${artha.paper.risk.enabled:false}") boolean enforcementEnabled) {
    this.settings = settings;
    this.positions = positions;
    this.account = account;
    this.marginClient = marginClient;
    this.notifier = notifier;
    this.clock = clock;
    this.enforcementEnabled = enforcementEnabled;
  }

  /** Whether a book's emitted ENTRY signals should auto-open a paper position (the auto-paper toggle). */
  public boolean autoPaperTradeEnabled(String book) {
    return boolFlag(book, AUTO_PAPER_TRADE);
  }

  /** Whether a new ENTRY may be emitted right now for a book (the per-book {@code emitEntry} gate). */
  public boolean entryAllowed(String book) {
    return entryVeto(book).isEmpty();
  }

  /**
   * The DEPLOYMENT rail alone, projected against a candidate order's own notional — {@code true} when
   * filling it would push the book PAST its cap.
   *
   * <p>Deliberately separate from {@link #entryVeto}, and deliberately PURE: no {@code risk_audit} row,
   * no ntfy, no per-day dedup, no reads that depend on call order. That purity is what makes it safe to
   * call from the fill path. {@code entryVeto} is not: its rails carry once-per-entry side-effects, which
   * is precisely why {@code openOrder} must never re-run it ({@code
   * PaperManualOrderGovernorIntegrationTest.takenPathOpenOrderIsUngatedByDesign} — "or a taken entry
   * would be double-charged"). That test forbids re-running the GOVERNOR; it does not forbid the writer
   * from validating. This method charges nothing, so calling it twice costs nothing.
   *
   * <p>Why the deployment rail specifically cannot be decided at emission: it is the ONE rail whose
   * answer depends on the candidate's SIZE, and at emission neither the tradeable leg nor the sized
   * quantity nor the slippage-adjusted fill price exists yet. The old form could therefore only refuse
   * an entry once the book was ALREADY at or over the cap, so the order that CROSSED the cap was always
   * admitted in full.
   *
   * <p>{@code >} not {@code >=}: landing exactly ON the cap is inside it. The already-at-cap case stays
   * {@link #entryVeto}'s job; this exists solely to catch the crossing order.
   */
  public boolean deploymentWouldCross(String book, BigDecimal candidateDeployment) {
    if (candidateDeployment == null || candidateDeployment.signum() <= 0) {
      return false;
    }
    Optional<Setting> deployment = settings.get(book, MAX_DEPLOYMENT_PCT);
    if (!enabled(deployment)) {
      return false;
    }
    BigDecimal value = deployment.get().value().path("value").decimalValue();
    BigDecimal cap = account.equity(book).multiply(value).divide(BigDecimal.valueOf(100));
    return account.capitalUsed(book).add(candidateDeployment).compareTo(cap) > 0;
  }

  /** The deployment cap in rupees for a book, or empty when the rail is disabled (for refusal detail). */
  public Optional<BigDecimal> deploymentCap(String book) {
    Optional<Setting> deployment = settings.get(book, MAX_DEPLOYMENT_PCT);
    if (!enabled(deployment)) {
      return Optional.empty();
    }
    BigDecimal value = deployment.get().value().path("value").decimalValue();
    return Optional.of(account.equity(book).multiply(value).divide(BigDecimal.valueOf(100)));
  }

  /**
   * The governor rail (a limit-key constant, e.g. {@link #KILL_SWITCH}) blocking a new ENTRY on a book
   * right now, or {@link Optional#empty()} when entry is allowed. This is the single source of truth for
   * the per-book gate: {@link #entryAllowed(String)} is a thin {@code isEmpty()} view, so both callers
   * apply IDENTICAL semantics AND identical audit side-effects (a daily-loss/profit/deployment/heat trip
   * writes its {@code risk_audit} row + ntfy here regardless of which caller triggered it; the kill-switch
   * and max-open rails write no audit, matching the emission path). Used by the manual paper-order path
   * ({@code PaperService.openManualOrder}) to surface the blocking rail in a 422 body.
   */
  public Optional<String> entryVeto(String book) {
    if (boolFlag(book, KILL_SWITCH)) {
      return Optional.of(KILL_SWITCH);
    }
    Optional<Setting> maxOpen = settings.get(book, MAX_OPEN);
    if (enabled(maxOpen)
        && positions.openCount(book) >= maxOpen.get().value().path("value").asInt(Integer.MAX_VALUE)) {
      return Optional.of(MAX_OPEN);
    }
    Optional<Setting> dailyLoss = settings.get(book, DAILY_LOSS);
    if (enabled(dailyLoss)) {
      BigDecimal limit = limitInr(book, dailyLoss.get().value());
      BigDecimal dayPnl = account.dayPnl(book);
      if (dayPnl.compareTo(limit.negate()) <= 0) {
        recordTrip(book, DAILY_LOSS, dayPnl, limit);
        return Optional.of(DAILY_LOSS);
      }
    }
    Optional<Setting> profitTarget = settings.get(book, DAILY_PROFIT_TARGET);
    if (enabled(profitTarget)) {
      BigDecimal target = limitInr(book, profitTarget.get().value());
      BigDecimal dayPnl = account.dayPnl(book);
      if (dayPnl.compareTo(target) >= 0) {
        recordTrip(book, DAILY_PROFIT_TARGET, dayPnl, target);
        return Optional.of(DAILY_PROFIT_TARGET);
      }
    }
    Optional<Setting> deployment = settings.get(book, MAX_DEPLOYMENT_PCT);
    if (enabled(deployment)) {
      // deployment is ALWAYS a % of equity (no mode field) — a live capital-state check, not a
      // one-shot day event, so it is audited directly each time it blocks (no per-day trip dedup).
      BigDecimal value = deployment.get().value().path("value").decimalValue();
      BigDecimal cap = account.equity(book).multiply(value).divide(BigDecimal.valueOf(100));
      BigDecimal used = account.capitalUsed(book);
      if (used.compareTo(cap) >= 0) {
        settings.audit(
            book,
            MAX_DEPLOYMENT_PCT,
            "TRIP",
            "open deployment " + used.toPlainString() + " ≥ cap " + cap.toPlainString());
        return Optional.of(MAX_DEPLOYMENT_PCT);
      }
    }
    // F9 portfolio heat cap — the open book's total SPAN margin as % of equity, blocks a new entry
    // when at/over the cap. Priced ONLY when enforcement is on: the margin call is a synchronous HTTP
    // round-trip to market-data and must NOT sit on the emission hot path in the default advisory-off
    // state. During the advisory week the owner watches heat via GET /api/v1/paper/margin-heat (+ the
    // per-position margin_snapshot annotation), then flips artha.paper.risk.enabled to enforce. Then
    // fail-soft: an unpriced book (analytics off / market-data down) cannot be assessed → never blocks.
    if (enforcementEnabled) {
      Optional<Setting> heatCap = settings.get(book, HEAT_CAP_PCT);
      if (enabled(heatCap)) {
        BigDecimal capPct = heatCap.get().value().path("value").decimalValue();
        BigDecimal heatPct = currentHeatPct(book);
        if (heatPct == null) {
          // Enforcement is ON but heat is unassessable (unpriced basket / >20 legs / analytics
          // down). Fail-soft still holds (never block on blindness) — but make the blind gate
          // VISIBLE, else the owner cannot tell "under cap" from "cannot see" (M1 review).
          log.warn("book '{}' heat-cap enforcement ON but heat unassessable — gate inert this entry", book);
          settings.audit(book, HEAT_CAP_PCT, "UNPRICED", "enforcement on, heat unassessable — entry allowed");
        } else if (heatPct.compareTo(capPct) >= 0) {
          recordHeatTrip(book, heatPct, capPct);
          return Optional.of(HEAT_CAP_PCT);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * The open book's SPAN margin as a % of its equity — priced through market-data's Upstox margin
   * endpoint ({@link PaperMarginClient}). {@code 0} for an empty book; {@code null} when unpriced (a
   * cash-equity book Upstox does not margin / analytics token off / market-data unreachable) — the
   * caller treats {@code null} as "cannot assess" and never blocks on it.
   */
  private BigDecimal currentHeatPct(String book) {
    List<PositionRow> open = positions.listOpen(book);
    if (open.isEmpty()) {
      return BigDecimal.ZERO;
    }
    if (open.size() > MAX_LEGS) {
      // Over the 20-leg Upstox basket limit: a truncated basket under-counts SPAN margin, and an
      // under-counted heat could FAIL to block when it should. Treat it as "cannot assess" (null →
      // never blocks — the fail-soft contract of this method) rather than silently pricing a partial
      // basket (audit M1: the same silent-truncation the margin-heat endpoint refuses loud).
      return null;
    }
    List<PaperMarginClient.Leg> legs =
        open.stream()
            .map(
                p ->
                    new PaperMarginClient.Leg(
                        p.exchange(), p.tradingsymbol(), (int) p.qty(), p.side(), "D"))
            .toList();
    PaperMarginClient.Quote q = marginClient.margin(legs);
    if (!q.priced() || q.spanMargin() == null) {
      return null;
    }
    BigDecimal equity = account.equity(book);
    if (equity.signum() <= 0) {
      return null;
    }
    return q.spanMargin().multiply(BigDecimal.valueOf(100)).divide(equity, 2, RoundingMode.HALF_UP);
  }

  /** Audits + ntfy-alerts a heat-cap breach (deduped per IST day, like {@link #recordTrip}). */
  private void recordHeatTrip(String book, BigDecimal heatPct, BigDecimal capPct) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    String dedupKey = tripKey(book, HEAT_CAP_PCT);
    if (today.equals(trippedOn.get(dedupKey))) {
      return;
    }
    trippedOn.put(dedupKey, today);
    String detail =
        "heat " + heatPct.toPlainString() + "% ≥ cap " + capPct.toPlainString()
            + "% — new entries paused";
    settings.audit(book, HEAT_CAP_PCT, "TRIP", detail);
    log.warn("risk heat cap {} tripped ({})", book, detail);
    pushAlert("ArthaYantra Risk — heat cap (" + book + ")", detail);
  }

  /** One fail-soft ntfy push for a governor trip (skipped silently when ntfy is unconfigured). */
  private void pushAlert(String title, String message) {
    try {
      if (notifier.configured("NTFY")) {
        notifier.send("NTFY", title, message);
      }
    } catch (RuntimeException e) {
      log.warn("risk governor ntfy push failed: {}", e.getMessage());
    }
  }

  /** Resolves a {@code {value, mode}} limit to INR: {@code pct} → book equity × value/100, else raw INR. */
  private BigDecimal limitInr(String book, JsonNode node) {
    BigDecimal value = node.path("value").decimalValue();
    if ("pct".equalsIgnoreCase(node.path("mode").asText("inr"))) {
      return account.equity(book).multiply(value).divide(BigDecimal.valueOf(100));
    }
    return value;
  }

  private void recordTrip(String book, String key, BigDecimal dayPnl, BigDecimal limit) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    String dedupKey = tripKey(book, key);
    if (!today.equals(trippedOn.get(dedupKey))) {
      trippedOn.put(dedupKey, today);
      String detail = "day P&L " + dayPnl.toPlainString() + " breached limit " + limit.toPlainString();
      settings.audit(book, key, "TRIP", detail);
      log.warn("risk cap {}/{} tripped — ENTRY emission paused for {}", book, key, today);
      pushAlert("ArthaYantra Risk — " + key + " (" + book + ")", detail);
    }
  }

  /** All limit rows for a book's settings panel. */
  public List<Setting> all(String book) {
    return settings.all(book);
  }

  /** Recent trip/flip audit rows for a book. */
  public List<RiskSettingsRepository.AuditEntry> audit(String book, int limit) {
    return settings.auditTail(book, limit);
  }

  /**
   * A book's settings panel: its limit rows plus the recent trip/flip audit tail. Assembled HERE
   * rather than in the controller so the records are the single source of truth for the wire shape
   * (D3), not a controller-side re-mapping.
   */
  public RiskViews.RiskSettings settingsView(String book) {
    List<RiskViews.RiskSettingRow> items =
        all(book).stream()
            .map(s -> new RiskViews.RiskSettingRow(s.key(), s.value(), s.updatedAt()))
            .toList();
    return new RiskViews.RiskSettings(book, items, audit(book, 20));
  }

  /** Upserts a book's limit (audited as a flip). */
  public void update(String book, String key, String valueJson) {
    settings.upsert(book, key, valueJson);
    settings.audit(book, key, "UPDATE", valueJson);
    trippedOn.remove(tripKey(book, key)); // re-arm this cap's per-day trip dedup on a limit change
  }

  private boolean boolFlag(String book, String key) {
    return settings.get(book, key).map(s -> s.value().path("enabled").asBoolean(false)).orElse(false);
  }

  private static boolean enabled(Optional<Setting> setting) {
    return setting.map(s -> s.value().path("enabled").asBoolean(false)).orElse(false);
  }
}
