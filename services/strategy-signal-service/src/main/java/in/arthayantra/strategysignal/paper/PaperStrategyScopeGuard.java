package in.arthayantra.strategysignal.paper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * V058 boot gate: refuses to start when a book's OPEN positions disagree with whether that book is
 * STRATEGY-SCOPED (cross-vendor review Critical 3).
 *
 * <p><b>Why this has to exist: default-OFF is not a rollback.</b> Shipping disarmed makes the
 * feature pre-V058-identical only while every OPEN row is unattributed. It does NOT make the flag
 * safe to flip back and forth once scoped rows exist, and the two mixed modes fail in opposite
 * directions:
 *
 * <ul>
 *   <li><b>ARMING with OPEN unattributed rows.</b> Every scoped predicate reads {@code
 *       (p.strategy_id IS NULL OR p.strategy_id = sv.strategy_id)} — NULL is a WILDCARD, which is
 *       exactly what keeps unscoped books behaving as before. A NULL row on a now-scoped book is
 *       therefore matched by EVERY strategy's exit, so any strategy can close a lot it never opened.
 *   <li><b>DISARMING with OPEN attributed rows.</b> Entries stop stamping a strategy and
 *       {@code findOpen} goes strategy-blind again, so a new fill averages into whichever sibling is
 *       oldest and one exit settles lots that were deliberately split.
 * </ul>
 *
 * <p>Neither is fixable in SQL — the information needed to disambiguate is simply not on the row.
 * The only safe transition is through a FLAT book, and boot is the one moment where refusing is
 * both possible and actionable, so this converts a silent mixed mode into a loud startup failure
 * that names the book, the count and the remedy.
 *
 * <p><b>Deliberately a hard failure, not a warning.</b> The failure mode it prevents is a wrong
 * CLOSE at a real price on the money path; a service that will not start is strictly safer than one
 * that starts and mis-settles. It can only trigger when someone changes the flag while positions are
 * open — precisely the moment a human is present to act. A clean boot on an unchanged flag can never
 * trip it: rows are written by the same flag that is read here.
 *
 * <p>Scope note: this checks the CURRENT state at boot, so it cannot catch a flag flipped while the
 * service is running (there is no such path — the property is read once at construction). It also
 * says nothing about CLOSED rows, which are inert.
 */
@Component
public class PaperStrategyScopeGuard implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(PaperStrategyScopeGuard.class);

  private final PaperPositionRepository positions;
  private final Set<String> strategyScopedBooks;

  /** Wires the position store + the same comma list {@code PaperService} parses. */
  public PaperStrategyScopeGuard(
      PaperPositionRepository positions,
      @Value("${artha.paper.strategy-scoped-books:}") String strategyScopedBooks) {
    this.positions = positions;
    this.strategyScopedBooks =
        strategyScopedBooks == null || strategyScopedBooks.isBlank()
            ? Set.of()
            : Arrays.stream(strategyScopedBooks.trim().split("\\s*,\\s*"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public void afterPropertiesSet() {
    List<String> problems = new ArrayList<>();

    // Arming direction: a scoped book must hold no unattributed (NULL) OPEN row.
    for (String book : strategyScopedBooks) {
      int n = positions.countOpenScopeMismatch(book, true);
      if (n > 0) {
        problems.add(
            "book '" + book + "' is listed in artha.paper.strategy-scoped-books but holds " + n
                + " OPEN position(s) with no strategy_id — those rows are closable by ANY"
                + " strategy's exit. Flatten the book (or remove it from the property) before"
                + " arming.");
      }
    }

    // Disarming direction: an unscoped book must hold no attributed OPEN row.
    for (String book : positions.booksWithOpenPositions()) {
      if (strategyScopedBooks.contains(book)) {
        continue;
      }
      int n = positions.countOpenScopeMismatch(book, false);
      if (n > 0) {
        problems.add(
            "book '" + book + "' holds " + n + " OPEN strategy-scoped position(s) but is NOT listed"
                + " in artha.paper.strategy-scoped-books — a new fill would average into a sibling"
                + " and one exit would settle lots that were deliberately split. Restore the"
                + " property (or flatten the book) before disarming.");
      }
    }

    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "V058 strategy-scoped paper book is in a MIXED state and cannot be served safely: "
              + String.join(" | ", problems)
              + " — see PaperStrategyScopeGuard for why this refuses rather than warns.");
    }
    log.info(
        "V058 strategy-scoped paper books: {}",
        strategyScopedBooks.isEmpty() ? "(none — disarmed)" : strategyScopedBooks);
  }
}
