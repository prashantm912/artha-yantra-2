package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Visibility for the paper exit path's stale/absent-tick blind spots (audit V3 / research-fidelity
 * P1-6). Two silent failures used to hide here: the SL/TP bracket evaluator {@link
 * PaperBracketEvaluator} defers a stop indefinitely when its option tick goes dead ("a dead option
 * tick silently defers stops all session"), and {@link PaperService#settle} used to book a fictional
 * breakeven exit when no price was available. This collaborator instruments BOTH — a Micrometer
 * counter every occurrence plus a once-per-(position, IST day) ntfy push — without changing what
 * either path DECIDES (the bracket still holds; the settle takes the last REAL tick at any age,
 * visibly when stale, and refuses only when no tick has ever been seen).
 *
 * <p>Follows the {@code RiskService} governor-trip idiom exactly: fail-soft ntfy (skipped silently
 * when unconfigured), a {@link Clock}-driven IST day, and a per-key {@link ConcurrentHashMap} dedup.
 * Kept in the {@code paper} module so it uses the same in-package {@link NotifierClient} the risk
 * governor does (paper already depends on notifier; there is no module-cycle concern here).
 */
@Component
public class PaperStaleTickAlerter {

  private static final Logger log = LoggerFactory.getLogger(PaperStaleTickAlerter.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  /** NUL delimiter (never appears in a book slug or a numeric id) — same anti-collision trick as RiskService. */
  private static final char DEDUP_DELIM = 0;

  private final NotifierClient notifier;
  private final Clock clock;
  private final Duration bracketAlertThreshold;

  /** Every bracket-eval pass that could not freshly evaluate a position's stop/target. */
  private final Counter bracketStarvedTotal;
  /** Every settle attempt refused because no tick has EVER been seen (no explicit price either). */
  private final Counter settleRefusedTotal;
  /** Every settle that priced off a REAL-but-stale tick (older than the fill max-age). */
  private final Counter staleSettleTotal;

  /**
   * positionId -> the instant its (absent-tick) starvation window opened; cleared on a fresh tick or
   * a bracket close. A position closed via a NON-bracket path (manual close, engine exit, the 15:45
   * sweep) never reaches {@link #bracketRecovered}, so its entry lingers until restart — accepted:
   * bounded at one small entry per position id ever starved, and a JVM restart clears the map.
   */
  private final java.util.Map<Long, Instant> starvedSince = new ConcurrentHashMap<>();
  /** (kind + positionId) -> the IST day it last alerted, so each position pages at most once per day. */
  private final java.util.Map<String, LocalDate> alertedOn = new ConcurrentHashMap<>();

  /** Wires the notifier + clock + meters and the bracket-starvation alert threshold. */
  public PaperStaleTickAlerter(
      NotifierClient notifier,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${artha.paper.bracket-starvation-alert-minutes:2}") long bracketAlertMinutes) {
    this.notifier = notifier;
    this.clock = clock;
    this.bracketAlertThreshold = Duration.ofMinutes(bracketAlertMinutes);
    this.bracketStarvedTotal = meterRegistry.counter("ay_paper_bracket_starved_total");
    this.settleRefusedTotal = meterRegistry.counter("ay_paper_settle_refused_total");
    this.staleSettleTotal = meterRegistry.counter("ay_paper_stale_settle_total");
  }

  /**
   * Observe a bracket position that was NOT closed on this eval pass. A FRESH tick (present and no
   * older than the alert threshold) clears any starvation window and is a no-op. An absent or stale
   * tick counts the starved pass and, once starvation has persisted at least the alert threshold,
   * sends ONE ntfy per (position, IST day). Never touches the close decision — visibility only.
   */
  public void observeBracket(PositionRow pos, Optional<LastTickReader.TickView> tick) {
    Duration age = tick.map(LastTickReader.TickView::age).orElse(null);
    boolean fresh = age != null && age.compareTo(bracketAlertThreshold) <= 0;
    if (fresh) {
      starvedSince.remove(pos.id());
      return;
    }
    bracketStarvedTotal.increment();
    // A present-but-stale tick carries its own age = exactly how long it has been dead; an absent
    // tick has none, so anchor the starvation window in memory the first pass we see it.
    Duration starvedFor;
    if (age != null) {
      starvedFor = age;
      starvedSince.remove(pos.id());
    } else {
      Instant since = starvedSince.computeIfAbsent(pos.id(), k -> clock.instant());
      starvedFor = Duration.between(since, clock.instant());
    }
    if (starvedFor.compareTo(bracketAlertThreshold) >= 0) {
      String detail =
          "position " + pos.id() + " SL/TP un-evaluated for ~" + starvedFor.toSeconds() + "s (tick "
              + (age == null ? "absent" : age.toSeconds() + "s old") + ") — stop may not fire";
      log.warn("paper bracket starved: {}", detail);
      alertOncePerDay(
          "bracket-starve",
          pos.id(),
          "ArthaYantra Paper — bracket starved (" + pos.exchange() + ":" + pos.tradingsymbol() + ")",
          detail);
    }
  }

  /** A bracket position closed (or otherwise recovered) — drop its starvation anchor. */
  public void bracketRecovered(long positionId) {
    starvedSince.remove(positionId);
  }

  /**
   * A settle attempt had no price AT ALL — no explicit price and no {@code ticks:last} entry has ever
   * existed for the symbol — so the caller refused to fabricate a breakeven exit and left the position
   * OPEN. Counts it and alerts once per (position, IST day). log.error (not warn): a position that
   * cannot be marked is a real operational hole.
   */
  public void settleRefused(PositionRow pos, String closeReason) {
    settleRefusedTotal.increment();
    String detail =
        "position " + pos.id() + " (" + closeReason + ") " + pos.exchange() + ":"
            + pos.tradingsymbol() + " has no tick at all — left OPEN, NOT settled at breakeven";
    log.error("paper settle refused: {}", detail);
    alertOncePerDay(
        "settle-refuse",
        pos.id(),
        "ArthaYantra Paper — settle refused (" + pos.exchange() + ":" + pos.tradingsymbol() + ")",
        detail);
  }

  /**
   * A settle priced off a REAL tick older than the fill max-age. Deliberate and allowed — exits take
   * the best available truth (see {@code PaperService.doSettle}) — but never silent: counts every
   * occurrence and warns + ntfy-pushes once per (position, IST day) with the tick's age, so a book
   * that keeps flattening off dead prices is seen.
   */
  public void staleSettleUsed(PositionRow pos, String closeReason, Duration age) {
    staleSettleTotal.increment();
    String detail =
        "position " + pos.id() + " (" + closeReason + ") " + pos.exchange() + ":"
            + pos.tradingsymbol() + " settled off a tick " + age.toSeconds()
            + "s old — the last REAL price, not a fresh one";
    log.warn("paper stale settle: {}", detail);
    alertOncePerDay(
        "stale-settle",
        pos.id(),
        "ArthaYantra Paper — stale settle (" + pos.exchange() + ":" + pos.tradingsymbol() + ")",
        detail);
  }

  private void alertOncePerDay(String kind, long positionId, String title, String message) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    String key = kind + DEDUP_DELIM + positionId;
    if (today.equals(alertedOn.get(key))) {
      return;
    }
    alertedOn.put(key, today);
    pushAlert(title, message);
  }

  /** One fail-soft ntfy push (skipped silently when ntfy is unconfigured) — the RiskService idiom. */
  private void pushAlert(String title, String message) {
    try {
      if (notifier.configured("NTFY")) {
        notifier.send("NTFY", title, message);
      }
    } catch (RuntimeException e) {
      log.warn("paper stale-tick ntfy push failed: {}", e.getMessage());
    }
  }
}
