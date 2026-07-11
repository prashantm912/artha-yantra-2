package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.signals.Books;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Audit M16 — Books-scoped STARTUP governor assertion. The paper {@code book} column DEFAULTs to
 * {@code 'manual'} and every governor read fails OPEN on a missing {@code (book, key)} row
 * ({@code boolFlag(...).orElse(false)} in {@link RiskService}): a future {@link Books} constant added
 * without a matching seed migration would trade UNGOVERNED and — worse — could never be killed (no
 * {@code kill_switch} row to flip). Runs once at boot, verifies every book {@link Books#all()} routes
 * to carries its two load-bearing governor rows ({@code kill_switch} + {@code auto_paper_trade}), and
 * auto-seeds conservative fail-CLOSED defaults for any that are missing.
 *
 * <p>Why auto-seed + WARN rather than fail-boot: this is the live trading engine's process — a benign
 * new book must never take the whole service (signals engine, exits, telegram) down. The seed makes
 * the book <em>governable</em> (a {@code kill_switch} row the owner can flip) and <em>fail-safe</em>
 * ({@code auto_paper_trade} disabled so an unseeded book never auto-opens); the loud WARN flags the
 * missing migration for follow-up.
 *
 * <p>Why STARTUP and not a runtime guard (audit §13): a runtime fail-closed check broke
 * {@code PaperAccountRiskIntegrationTest}, whose {@code @BeforeEach} deletes all {@code risk_settings}
 * and then asserts {@code entryAllowed} is still true. This never touches the runtime read path —
 * {@code boolFlag} keeps its {@code orElse(false)} semantics — and the seeded {@code {"enabled":false}}
 * value evaluates identically to an absent row, so it changes no governor decision. Idempotent: an
 * already-seeded book (the V021 baseline) is left byte-identical.
 */
@Component
public class PaperBookGovernorInitializer {

  private static final Logger log = LoggerFactory.getLogger(PaperBookGovernorInitializer.class);

  /** Kill switch present-but-disengaged: the owner CAN kill the book; it is not killed by default. */
  private static final String KILL_SWITCH_DEFAULT = "{\"enabled\": false}";

  /** Auto-paper OFF: an unseeded book never auto-opens positions (conservative / fail-safe). */
  private static final String AUTO_PAPER_DEFAULT = "{\"enabled\": false}";

  private final RiskSettingsRepository settings;

  /** Wires the risk-settings store. */
  public PaperBookGovernorInitializer(RiskSettingsRepository settings) {
    this.settings = settings;
  }

  /** After the context (and Flyway) is up, backfill any missing governor rows conservatively. */
  @EventListener(ApplicationReadyEvent.class)
  public void seedMissingGovernors() {
    for (String book : Books.all()) {
      try {
        seedIfMissing(book, RiskService.KILL_SWITCH, KILL_SWITCH_DEFAULT);
        seedIfMissing(book, RiskService.AUTO_PAPER_TRADE, AUTO_PAPER_DEFAULT);
      } catch (RuntimeException e) {
        // A ready-listener throwable fails the WHOLE boot — a seed hiccup must never take the
        // live engine (signals, exits, notifier) down with it (the class contract above).
        log.error("governor seed failed for book '{}' — continuing boot", book, e);
      }
    }
  }

  private void seedIfMissing(String book, String key, String defaultJson) {
    if (settings.get(book, key).isPresent()) {
      return;
    }
    log.warn(
        "paper book '{}' missing governor row '{}' at boot — auto-seeding conservative default {} "
            + "(add a seed migration: every book must be killable + explicitly auto-paper-gated) [audit M16]",
        book,
        key,
        defaultJson);
    settings.insertIfMissing(book, key, defaultJson);
  }
}
