package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Position-attribution ratchet: freezes the SET of locations that resolve a {@code paper_positions}
 * row — or a per-position value — from the NATURAL KEY {@code (book, exchange, tradingsymbol, side)}
 * or a subset of it, rather than from {@code id}.
 *
 * <p><b>⚠️ THIS TEST IS NOT COMPLETE, AND ITS GAP IS THE SAME KIND IT EXISTS TO PUNISH.</b> The whole
 * thesis here is that four manual sweeps produced four different answers because each one's SEARCH
 * SPACE excluded the defect by construction. This detector has a search space too, and it is a REGEX
 * over source text. Measured misses: SQL assembled by fragment concatenation, a descriptor that never
 * spells {@code tradingsymbol}, and any resolution in a language or module this test does not walk.
 * Do not describe this as bounding the set — it bounds the set <i>expressible in the shapes below</i>.
 * The Python half is {@code services/optimizer-service/tests/test_position_attribution_ratchet.py};
 * neither is complete, and a new shape must be added to BOTH.
 *
 * <p><b>Why this exists.</b> The count of these locations moved four times across four rounds —
 * six, seven, eight, nine, twelve — and every revision came from someone choosing a different SEARCH
 * UNIT, not from looking harder. The sweep that finally bounded it
 * ({@code docs/signal-analysis/2026-08-04-scoped-position-key-attribution-sites.md}) named the cause:
 * <i>nothing enforces the invariant — every round was a fresh act of diligence, and diligence does
 * not compose.</i> Same pure-file, no-container pattern as edge-gateway's
 * {@code MapReturnRatchetTest}.
 *
 * <p><b>It has already earned its keep.</b> Written against the merge base, where the set was 17
 * sites / 19 statements. PR #1275 merged at {@code 060bbaf1} hours later, adding
 * {@code PaperPositionRepository.findOpenForStrategy}; the detector saw it immediately as an
 * unregistered 18th site and failed the build. That is the point — a new natural-key resolver now
 * costs one deliberate registration line instead of a fifth sweep.
 *
 * <h2>What a member IS, and what now guarantees it</h2>
 *
 * <p>The natural key was a CARDINALITY GUARANTEE: at most one OPEN row per
 * {@code (book, exchange, tradingsymbol, side)}. Every site below silently depends on it.
 *
 * <p><b>⚠️ That guarantee CHANGED KIND, which is the most load-bearing sentence in this file.</b>
 * V058 (#1275, merged AND deployed) dropped and recreated {@code uq_paper_positions_open} as
 * {@code (book, exchange, tradingsymbol, side, strategy_id) NULLS NOT DISTINCT WHERE status='OPEN'}
 * — verified against the live index definition, not inferred from the migration. The four-tuple is
 * still effectively unique ONLY because the feature ships disarmed:
 * {@code ARTHA_PAPER_STRATEGY_SCOPED_BOOKS} is empty, so every row stamps {@code strategy_id = NULL}
 * and {@code NULLS NOT DISTINCT} collapses them to one key. <b>Uniqueness is therefore a CONFIG
 * guarantee now, not a SCHEMA one.</b> Every reason below that reads "unique today" is resting on a
 * flag that one {@code .env} line flips. The old {@code V021__paper_books.sql} citation is
 * superseded.
 *
 * <h2>How to register a new site</h2>
 *
 * <p>Add ONE line to {@link #REGISTERED} with a real reason:
 *
 * <p>{@code site("PaperPositionRepository.findOpen", 1, "why this natural-key resolve is correct")}
 *
 * <p>Registration lives HERE, not as a marker comment at the call site, and that is deliberate: a
 * marker travels with a copy-paste, so duplicating a registered method into a new one would carry
 * its exemption silently — precisely "applied by accident". Forcing the author into this file makes
 * them read the invariant above first. The reason is asserted non-trivial so a drive-by {@code "ok"}
 * does not pass.
 *
 * <p><b>Deliberately-unscoped sites are ACCEPTED MEMBERS, not failures.</b> Four sites below are
 * correct precisely because they do NOT discriminate — {@code openSubAccountIdx} must return the
 * key's existing sub-account, since scoping it would route a sibling to a second account and halve
 * the capital each pair charges. An UNLISTED site fails; not every listed site is a bug.
 *
 * <h2>Identity, not count — and why</h2>
 *
 * {@code MapReturnRatchetTest} freezes a per-service COUNT. This freezes the SET, keyed by
 * {@code ClassName.methodName}, with a per-method statement count. A count catches additions but not
 * substitutions and — more to the point — <b>a count is exactly what was already wrong four
 * times</b>: the number moved while the set was never pinned, so freezing the number would reproduce
 * the original defect in test form. Set equality is asserted in BOTH directions.
 *
 * <p>Method NAME rather than line number, because line numbers are worthless here: every line number
 * in both manual sweeps was already stale against the tree it was written for.
 *
 * <h2>What this does NOT catch</h2>
 *
 * <ol>
 *   <li><b>Substitution INSIDE a registered method.</b> Swapping one natural-key statement for
 *       another keeps the count at 1. Closing it needs a normalised SQL hash, which makes every
 *       whitespace edit a failure — and a ratchet that is annoying to update gets weakened.
 *   <li><b>⚠️ REWRITING a registered site into a shape the detector cannot see.</b> The nastiest,
 *       because the failure INVERTS: the site stops being detected, the {@code stale} branch fires,
 *       and a naive reading of it deletes the registration for a site that is still live. That
 *       message is worded to say so — do not follow it without confirming the method is really gone.
 *   <li><b>Fragment-concatenated SQL.</b> A key predicate appended from pieces evades the regex. The
 *       sweep measured no Spring Data, no {@code @Query}, no reflection and no classpath
 *       {@code .sql} here, so every statement is a literal — but two hand-rolled concatenation sites
 *       do exist.
 *   <li><b>Anything outside the repository</b> — a hand-run {@code psql}, a saved snippet.
 * </ol>
 *
 * <p><b>Deliberately broader than "lacks a strategy predicate".</b> Freezing only sites MISSING a
 * {@code strategy_id} predicate was rejected: it would let anyone add a new natural-key resolver
 * unreviewed so long as they pasted a predicate on it, and the sweep's clause-(c) analysis shows a
 * predicate is not automatically the right fix — four sites here are correct BECAUSE they stay
 * unscoped. Registering every natural-key resolution and recording its scoping status is strictly
 * more informative.
 */
class PaperNaturalKeyRatchetTest {

  /**
   * The natural key as it appears in SQL. {@code tradingsymbol} is the anchor rather than the full
   * four-tuple because every dangerous descriptor in the sweep contains it — including the Python
   * one, whose key {@code (tradingsymbol, IST-date)} drops {@code book}, {@code exchange} and
   * {@code side} entirely. Requiring all four columns would miss every subset key, which is the
   * shape that kept escaping.
   *
   * <p><b>Widened after a cross-vendor review probed ten evasion shapes and found the original
   * missed nine.</b> It anchored on {@code tradingsymbol =} followed by a bind marker or an alias,
   * so it could not see a row-constructor comparison
   * {@code WHERE (book, exchange, tradingsymbol, side) = (?,?,?,?)} — <i>the most literal expression
   * of the very key this test guards</i>. Now covered, each pinned by
   * {@link #theDetectorSeesEveryKnownEvasionShape}: row constructors, {@code JOIN … USING (…)},
   * {@code IN (?)}, {@code CAST(? AS text)}, named parameters {@code = :sym}, and bare literals.
   *
   * <p><b>The bare-word alternative was measured and rejected, with the number.</b> Anchoring on a
   * bare {@code \btradingsymbol\b} inside any paper statement takes the set from 18 sites / 20
   * statements to <b>39 / 64</b> — 21 new members that are almost entirely SELECT column lists, DTO
   * projections and INSERT targets ({@code toPositionDto}, {@code insertOpen}, {@code insertFilled},
   * {@code findDetail}). A registry where most entries mean "this is a column list" trains the
   * reader to rubber-stamp registrations, which is the "annoying to update gets weakened" failure
   * this design avoids. The targeted alternations cost ZERO extra registrations — they close doors
   * rather than move a number, exactly as {@code MapReturnRatchetTest}'s {@code ResponseEntity}
   * widening did.
   */
  private static final Pattern NATURAL_KEY_SQL =
      Pattern.compile(
          "\\btradingsymbol\\s*(?:=|<>|!=)\\s*\\S"
              + "|\\btradingsymbol\\s+IN\\s*\\("
              + "|\\([^()]*\\btradingsymbol\\b[^()]*\\)\\s*=\\s*\\("
              + "|\\bUSING\\s*\\([^()]*\\btradingsymbol\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * The same resolution done in Java rather than SQL — {@code stream().filter(...).findFirst()} over
   * already-fetched rows. A whole class, not a one-off: it is how {@code RiskService} picks the row
   * whose risk it subtracts, and no SQL-shaped sweep can see it. Scoped by requiring the enclosing
   * method to mention {@code PositionRow}, which keeps instrument- and signal-level symbol
   * comparisons ({@code SignalEngine}, {@code OptionExchangeResolver}) out.
   *
   * <p>Widened in the same review round for {@code Objects.equals}, {@code equalsIgnoreCase}, and —
   * the sharpest — a METHOD REFERENCE such as {@code Collectors.toMap(PositionRow::tradingsymbol,…)}.
   * That last shape is map-keyed first-wins resolution, which is <i>precisely the shape of the
   * Python site this suite registers</i> ({@code _live_key_price}'s {@code setdefault}); the Java
   * half had no analogue and would have been blind to the exact defect its sibling exists to pin.
   */
  private static final Pattern NATURAL_KEY_JAVA =
      Pattern.compile(
          "\\.tradingsymbol\\(\\)\\s*\\.equals(?:IgnoreCase)?\\("
              + "|\\.equals(?:IgnoreCase)?\\(\\s*[A-Za-z_]\\w*\\.tradingsymbol\\(\\)"
              + "|\\bObjects\\.equals\\([^;]*\\btradingsymbol\\b"
              + "|::tradingsymbol\\b");

  /** A statement is in scope only if it actually addresses the paper tables. */
  private static final Pattern PAPER_TABLE = Pattern.compile("\\bpaper_(?:positions|orders)\\b");

  /** The row type the Java-side detector uses to tell a position filter from any other filter. */
  private static final Pattern POSITION_ROW = Pattern.compile("\\bPositionRow\\b");

  /**
   * A class-member declaration: optional modifiers, a type, a name, then the parameter list; or a
   * constructor, which has no return type.
   *
   * <p>⚠️ Two fixes, both found by red-proofing rather than by reading. (1) Matching the TYPE is
   * load-bearing: this used to require an explicit modifier, making every PACKAGE-PRIVATE method
   * invisible as a declaration — a site inside one was still CAUGHT, but attributed to whatever
   * declaration preceded it (measured: a package-private filter in {@code RiskService} reported as
   * {@code RiskService.enabled}). (2) The indent allowance was exactly two spaces, so members of a
   * NESTED class and constructors were invisible the same way — the same defect class as (1), which
   * the modifier fix did not reach.
   *
   * <p>Widening the indent is only safe because declaration scanning now SKIPS text-block interiors:
   * a SQL line such as {@code LEFT JOIN LATERAL (} otherwise parses as a member named
   * {@code LATERAL}. The assignment operator is excluded from the type character class, which keeps
   * a field initialiser like {@code private static final Pattern X = Pattern.compile(} from matching.
   */
  private static final Pattern MEMBER_DECL =
      Pattern.compile(
          "^ {2,6}(?:(?:public|protected|private|static|final|abstract|default|synchronized)\\s+)*"
              + "(?:[A-Za-z_$][\\w$<>\\[\\],.?\\s]*\\s[A-Za-z_$]\\w*|[A-Z]\\w*)\\s*\\(");

  /** The identifier immediately preceding the parameter list of a member declaration. */
  private static final Pattern MEMBER_NAME = Pattern.compile("(\\w+)\\s*\\(");

  /** One accepted natural-key resolution site. */
  private record Site(String key, int statements, String reason) {}

  private static Site site(String key, int statements, String reason) {
    return new Site(key, statements, reason);
  }

  /**
   * Every location allowed to resolve a paper position by natural key, with the reason it is
   * acceptable. Scoping status was MEASURED against the tree, not inferred: 11 of the 18 carry a
   * {@code strategy_id} predicate after #1275; 7 deliberately do not.
   */
  private static final List<Site> REGISTERED =
      List.of(
          // ── SCOPED BY V058 (#1275): these carry a strategy_id predicate ───────────────────────
          site(
              "PaperPositionRepository.findOpenForStrategy",
              1,
              "ADDED BY #1275, and the site this ratchet caught on its first day. Resolves the OPEN"
                  + " row for a key opened by exactly strategyId (AND strategy_id = ?), returning"
                  + " empty for a legacy NULL-strategy row so a scoped entry never averages into an"
                  + " unscoped one. Discriminating by construction."),
          site(
              "PaperPositionRepository.openForSignal",
              1,
              "sweep A1, now SCOPED: PaperService.closeForSignal settles every returned row, so an"
                  + " unscoped form would close a sibling. Carries the legacy-tolerant"
                  + " (p.strategy_id IS NULL OR p.strategy_id = sv.strategy_id) arm."),
          site(
              "PaperPositionRepository.intradayOpen",
              1,
              "sweep A2, now SCOPED: the 15:45 mark-to-close set; unscoped it would force-close a"
                  + " BTST twin. Joins paper_orders on the natural key to reach the strategy style."),
          site(
              "PaperPositionRepository.openStraddleLegs",
              1,
              "sweep A3, now SCOPED: StraddleExitMonitor closes every returned id. Both legs share"
                  + " the parent signal, which is what re-forms the CE+PE pair."),
          site(
              "PaperPositionRepository.findLatestForKey",
              1,
              "sweep A4, now SCOPED via a nullable CAST(? AS uuid) arm reproducing the pre-V058"
                  + " behaviour when null. The V2 idempotency read-back: deliberately status-agnostic"
                  + " and newest-first so a late replay resolves the row it created."),
          site(
              "PaperPositionRepository.notifyTargetFor",
              1,
              "sweep A8, now SCOPED: resolves the owning strategy's notification channel via the"
                  + " first signal-linked order, so one twin's expiry cannot page the other's"
                  + " channel. ORDER BY o.id LIMIT 1 makes the pick deterministic."),
          site(
              "PaperOrderRepository.legsForPosition",
              1,
              "sweep A7, now SCOPED: renders entry fills for the position detail pane. Orders carry"
                  + " no position_id, so the natural key is the only available join -- a schema"
                  + " limit, not a query choice. If that join is ever rewritten as USING(...) the"
                  + " detector still sees it; the widened pattern covers that shape explicitly."),
          site(
              "SwingPaperEffectRepository.openPositionIdsForSignals",
              1,
              "sweep A6, now SCOPED: ids bound by SwingBatchEngine's exit pass and closed via"
                  + " closeForPosition."),
          site(
              "PaperReconciliationRepository.closedPositionReconciliation",
              2,
              "sweep A5 + A10, and the two laterals DIFFER -- do not collapse them. The ENTRY lateral"
                  + " IS scoped (unscoped it double-counts both twins' legs and raises a nightly"
                  + " qty-mismatch alarm on a valid split). The EXIT lateral is NOT, and is a KNOWN"
                  + " DEFECT documented in-line: twin A's settle order satisfies the lateral for twin"
                  + " B, so missing-exit detection becomes a FALSE NEGATIVE -- it DELETES a real"
                  + " alarm rather than adding a false one. Not fixable by a predicate: doSettle"
                  + " writes exit orders with signal_id NULL, so nothing in today's schema ties a"
                  + " settle leg to one of two siblings. Pinned by"
                  + " PaperScopedResolutionPathsIntegrationTest."),
          site(
              "PaperReconciliationRepository.deadAnchorOrphanPositions",
              1,
              "sweep A9, now SCOPED: NOT EXISTS over signal-carrying orders on the natural key,"
                  + " detecting OPEN rows no exit driver will ever settle."),
          site(
              "PortfolioReader.expiryScan",
              1,
              "sweep B, now SCOPED anyway -- and correctly: expiry is a function of tradingsymbol and"
                  + " tradingsymbol is IN the join key, so no wrong-row selection could produce a"
                  + " wrong answer. Scoped because one unscoped instance of a pattern teaches the"
                  + " next reader it is optional."),

          // ── DELIBERATELY UNSCOPED: discriminating would CHANGE BEHAVIOUR ──────────────────────
          site(
              "PaperPositionRepository.findOpen",
              1,
              "DELIBERATELY unscoped: the per-book averaging key, retained as the pre-V058 form."
                  + " Takes the single OPEN row for the key -- unique today only because the"
                  + " scoped-books flag is EMPTY, so this rests on a CONFIG guarantee, not a schema"
                  + " one. findOpenForStrategy is the scoped counterpart."),
          site(
              "PaperPositionRepository.openSubAccountIdx",
              1,
              "DELIBERATELY unscoped, and scoping it would be a GOVERNOR CHANGE disguised as a lookup"
                  + " fix: a sibling inherits the key's existing sub-account, so all rows on a key"
                  + " share one idx by construction. Discriminating would route the sibling to a"
                  + " second account and halve the capital each pair charges."),
          site(
              "PaperPositionRepository.signalIdsFor",
              1,
              "DELIBERATELY unscoped: returns a SUPERSET (every signal id that opened this key). Safe"
                  + " only because TakenSignalResolver composes it with openForSignal, which DOES"
                  + " discriminate -- the dependency is real, so do not 'fix' this in isolation."),
          site(
              "RiskService.manasAggregateRiskCheck",
              1,
              "sweep A11, still UNSCOPED: the Java-side twin of the SQL sites --"
                  + " stream().filter(exchange/tradingsymbol/side).findFirst() over listOpen(book)."
                  + " Contained only by a property VALUE: inert unless book equals 'manas-arora', and"
                  + " a scalper signal stamps book 'scalper'. That containment is one line in a .env"
                  + " file wide, which is why it stays registered and visible."),

          // ── Over-detected by the regex: real natural-key text, but not a resolution ───────────
          site(
              "PaperPositionRepository.findOpenIdIfOpenedBy",
              1,
              "ALREADY DISCRIMINATING on a different axis: also requires opening_signal_id = ?, so it"
                  + " returns the open row only if that exact anchor opened it. Matched because the"
                  + " natural key is present too; the regex over-detects on purpose."),
          site(
              "PaperPositionRepository.listClosed",
              1,
              "NOT A RESOLUTION: an optional display filter on a closed-position LIST"
                  + " (?::text IS NULL OR tradingsymbol = ?). No single row is bound."),
          site(
              "PaperReconciliationRepository.strandedCarryPositions",
              2,
              "NOT A POSITION KEY: both correlations are signals-to-signals"
                  + " (x/n.tradingsymbol = s.tradingsymbol). The position is anchored by"
                  + " p.opening_signal_id. Matched because the same statement names paper_positions."));

  @Test
  void everyNaturalKeyResolutionSiteIsRegistered() throws IOException {
    Map<String, Integer> detected = detectAll(findRepoRoot());
    Map<String, Integer> registered = new TreeMap<>();
    for (Site s : REGISTERED) {
      registered.put(s.key(), s.statements());
    }

    TreeSet<String> unregistered = new TreeSet<>(detected.keySet());
    unregistered.removeAll(registered.keySet());
    assertThat(unregistered)
        .withFailMessage(
            """
            NEW position-attribution site(s) with no registration: %s

            These resolve a paper_positions row (or a per-position value) from the NATURAL KEY
            (book, exchange, tradingsymbol, side) or a subset -- not from id. Every such site
            depends on at most ONE open row existing per key, which since V058 is guaranteed by
            the scoped-books flag being EMPTY rather than by the schema. The manual count of
            these moved four times across four rounds; this test exists so the next one fails CI.

            If the site is correct, register it in PaperNaturalKeyRatchetTest.REGISTERED:
                site("<Class>.<method>", <statements>, "<why this is correct>")
            Read the class javadoc first -- four sites are correct BECAUSE they stay unscoped,
            so "add a strategy predicate" is not automatically the right fix.
            """,
            unregistered)
        .isEmpty();

    TreeSet<String> stale = new TreeSet<>(registered.keySet());
    stale.removeAll(detected.keySet());
    assertThat(stale)
        .withFailMessage(
            """
            Registered site(s) the detector no longer sees: %s

            TWO very different causes, needing OPPOSITE responses -- check which before touching
            the registry:

              (a) The method was genuinely removed, renamed, or moved to another file.
                  -> Update or delete its line in PaperNaturalKeyRatchetTest.REGISTERED.

              (b) The method still EXISTS but its query was REWRITTEN into a shape this detector
                  cannot see (fragment concatenation, or a descriptor that no longer spells
                  `tradingsymbol`).
                  -> DO NOT delete the line. The site is still live and would silently lose its
                     guard. Widen NATURAL_KEY_SQL / NATURAL_KEY_JAVA to cover the new shape,
                     then re-derive the whole set.

            Deleting a registration is only correct for (a). Confirm the method is really gone.
            """,
            stale)
        .isEmpty();

    for (Site s : REGISTERED) {
      assertThat(detected.get(s.key()))
          .withFailMessage(
              "%s now contains %s natural-key statement(s), registered for %d. A statement was"
                  + " added to or removed from an already-registered method -- re-read it, then"
                  + " update the count in PaperNaturalKeyRatchetTest.REGISTERED.",
              s.key(), detected.get(s.key()), s.statements())
          .isEqualTo(s.statements());
    }
  }

  /** A registration is a claim; make it cost a sentence, so it cannot be added thoughtlessly. */
  @Test
  void everyRegistrationStatesItsReason() {
    for (Site s : REGISTERED) {
      assertThat(s.reason().trim().length())
          .withFailMessage(
              "Registration for %s has a %d-character reason. Say WHY resolving by natural key is"
                  + " correct there -- the point of this registry is that the next reader can audit"
                  + " the claim without re-deriving it.",
              s.key(), s.reason().trim().length())
          .isGreaterThanOrEqualTo(40);
    }
    assertThat(REGISTERED.stream().map(Site::key).distinct().count())
        .withFailMessage("Duplicate keys in REGISTERED -- each site is registered exactly once.")
        .isEqualTo(REGISTERED.size());
  }

  /**
   * Guards the DETECTOR, not the codebase: pins the evasion shapes a cross-vendor review found the
   * original pattern missed. Without this, the widening is an untested claim — it produced no new
   * registrations, so nothing else in this file would fail if it silently stopped working.
   */
  @Test
  void theDetectorSeesEveryKnownEvasionShape() {
    List<String> sqlShapes =
        List.of(
            "WHERE (book, exchange, tradingsymbol, side) = (?,?,?,?)",
            "JOIN paper_orders o USING (book, exchange, tradingsymbol, side)",
            "WHERE tradingsymbol IN (?)",
            "WHERE tradingsymbol = CAST(? AS text)",
            "WHERE tradingsymbol = :sym",
            "WHERE book=? AND exchange=? AND tradingsymbol=? AND side=?",
            "ON o.tradingsymbol = p.tradingsymbol");
    for (String shape : sqlShapes) {
      assertThat(NATURAL_KEY_SQL.matcher(shape).find())
          .withFailMessage("NATURAL_KEY_SQL no longer sees a known evasion shape: %s", shape)
          .isTrue();
    }

    List<String> javaShapes =
        List.of(
            "if (Objects.equals(p.tradingsymbol(), sym)) {",
            "p.tradingsymbol().equalsIgnoreCase(sym)",
            "open.stream().collect(Collectors.toMap(PositionRow::tradingsymbol, x -> x))",
            "&& p.tradingsymbol().equals(tradingsymbol)");
    for (String shape : javaShapes) {
      assertThat(NATURAL_KEY_JAVA.matcher(shape).find())
          .withFailMessage("NATURAL_KEY_JAVA no longer sees a known evasion shape: %s", shape)
          .isTrue();
    }

    // A column list is NOT a resolution. Over-detecting these was measured to add 21 noise
    // registrations, so the pattern must stay quiet on them.
    assertThat(
            NATURAL_KEY_SQL
                .matcher("SELECT p.id, p.exchange, p.tradingsymbol, p.side FROM paper_positions p")
                .find())
        .withFailMessage("NATURAL_KEY_SQL fires on a plain column list -- it would flood the registry")
        .isFalse();
  }

  // ── detector ──────────────────────────────────────────────────────────────────────────────────

  /**
   * Scans every Java main-source tree under {@code services/} and {@code libs/}. Enumerated by
   * walking rather than by a hardcoded service list, so a NEW service or lib is covered the day it
   * appears — a site in an unscanned module is exactly the blind spot this test exists to remove.
   * Test sources are excluded: fixtures legitimately build natural keys.
   */
  private static Map<String, Integer> detectAll(Path repoRoot) throws IOException {
    Map<String, Integer> found = new TreeMap<>();
    for (String moduleRoot : List.of("services", "libs")) {
      Path root = repoRoot.resolve(moduleRoot);
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(root)) {
        for (Path f : files.filter(PaperNaturalKeyRatchetTest::isMainJavaSource).toList()) {
          detectIn(f).forEach((k, v) -> found.merge(k, v, Integer::sum));
        }
      }
    }
    return found;
  }

  private static boolean isMainJavaSource(Path f) {
    String p = f.toString().replace('\\', '/');
    return p.endsWith(".java") && p.contains("/src/main/java/") && !p.contains("/target/");
  }

  private static Map<String, Integer> detectIn(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String className = file.getFileName().toString().replace(".java", "");

    // Text-block interiors are computed ONCE and consulted by both passes below. Declaration
    // scanning MUST skip them, or SQL such as `LEFT JOIN LATERAL (` parses as a member named
    // LATERAL — which is what forced the old 2-space anchor, and what makes widening it safe.
    boolean[] insideTextBlock = new boolean[lines.size()];
    boolean inBlock = false;
    for (int i = 0; i < lines.size(); i++) {
      insideTextBlock[i] = inBlock;
      if (countOccurrences(lines.get(i), "\"\"\"") % 2 == 1) {
        inBlock = !inBlock;
      }
    }

    List<int[]> declLines = new ArrayList<>();
    List<String> declNames = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String ln = lines.get(i);
      String trimmed = ln.trim();
      if (insideTextBlock[i] || trimmed.startsWith("//") || trimmed.startsWith("*")) {
        continue;
      }
      if (MEMBER_DECL.matcher(ln).find()) {
        Matcher nm = MEMBER_NAME.matcher(ln);
        if (nm.find()) {
          declLines.add(new int[] {i});
          declNames.add(nm.group(1));
        }
      }
    }

    Map<String, Integer> hits = new LinkedHashMap<>();
    List<String> run = new ArrayList<>();
    int runStart = -1;
    for (int i = 0; i < lines.size(); i++) {
      String ln = lines.get(i);
      if (insideTextBlock[i] || ln.indexOf('"') >= 0) {
        if (run.isEmpty()) {
          runStart = i;
        }
        run.add(ln);
        continue;
      }
      flushRun(hits, className, declLines, declNames, run, runStart);
      run = new ArrayList<>();
      if (NATURAL_KEY_JAVA.matcher(ln).find()
          && POSITION_ROW.matcher(methodBody(lines, declLines, i)).find()) {
        hits.merge(className + "." + memberAt(declLines, declNames, i), 1, Integer::sum);
      }
    }
    flushRun(hits, className, declLines, declNames, run, runStart);
    return hits;
  }

  private static void flushRun(
      Map<String, Integer> hits,
      String className,
      List<int[]> declLines,
      List<String> declNames,
      List<String> run,
      int runStart) {
    if (run.isEmpty()) {
      return;
    }
    String text = String.join("\n", run);
    if (!PAPER_TABLE.matcher(text).find()) {
      return;
    }
    int n = (int) NATURAL_KEY_SQL.matcher(text).results().count();
    if (n > 0) {
      hits.merge(className + "." + memberAt(declLines, declNames, runStart), n, Integer::sum);
    }
  }

  /** The member declaration in force at a line — the nearest one at or above it. */
  private static String memberAt(List<int[]> declLines, List<String> declNames, int line) {
    String name = "<class-level>";
    for (int k = 0; k < declLines.size(); k++) {
      if (declLines.get(k)[0] <= line) {
        name = declNames.get(k);
      } else {
        break;
      }
    }
    return name;
  }

  /** A member's text, from its declaration to the next declaration — enough to type a filter. */
  private static String methodBody(List<String> lines, List<int[]> declLines, int line) {
    int start = 0;
    int end = lines.size();
    for (int[] d : declLines) {
      if (d[0] <= line) {
        start = d[0];
      } else {
        end = d[0];
        break;
      }
    }
    return String.join("\n", lines.subList(start, end));
  }

  private static int countOccurrences(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
      n++;
    }
    return n;
  }

  private static Path findRepoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("contracts"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repo root (contracts/ dir) above the module dir").isNotNull();
    return dir;
  }
}
