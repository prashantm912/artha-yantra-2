package in.arthayantra.marketdata.equitydaily;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the ONE definition of the cash-equity series predicate, and pins that every SQL constant
 * which adopted it actually EXPANDED it.
 *
 * <p>⚠️ The second half is not theoretical. Converting the inline copies to this constant, the
 * obvious mechanical edit — replacing the literal with {@code " + CashEquityUniverse.SERIES_PREDICATE
 * + "} — is <b>silently wrong inside a Java text block</b>: it is not concatenation there, it is
 * literal SQL text. It compiles, and it fails at runtime. The sibling mistake is leaving a
 * {@code %s} without its {@code .formatted(...)}. Neither is visible to the compiler.
 *
 * <p>So this asserts the produced STRINGS: every adopting constant must contain the real predicate
 * and carry no unexpanded placeholder or stray concatenation fragment.
 *
 * <p>⚠️ What this class does NOT prove is that a predicate sits in the RIGHT PLACE. The real gate
 * for that is the integration suite, which executes every one of these constants against Postgres —
 * a misplaced alias fails there with {@code missing FROM-clause entry for table "b"}. The one
 * positional case cheap enough to pin here is {@link #lineageSqlKeepsTheUnaliasedPredicateFirst()}.
 */
class CashEquityUniverseTest {

  private static final String PREDICATE = "series IN ('EQ','BE')";
  private static final String ALIASED = "b." + PREDICATE;
  private static final String LINEAGE = "in.arthayantra.marketdata.lineage.SymbolLineageDetector";
  private static final String PROBE =
      "in.arthayantra.marketdata.screener.minervini.PlaneDivergenceProbe";

  @Test
  void predicateLiteralIsPinned() {
    // The house cash universe is EQ+BE: `artha.nse.bhavcopy.candle-series:EQ,BE`
    // (BhavcopyBackfillService:123). Ledger H24 moved 16 sites onto this exact string; if it
    // changes, that is a deliberate universe change and this assertion is where it gets noticed.
    assertThat(CashEquityUniverse.SERIES_PREDICATE).isEqualTo(PREDICATE);
  }

  @Test
  void qualifiedPrefixesTheAlias() {
    assertThat(CashEquityUniverse.qualified("b")).isEqualTo(ALIASED);
    assertThat(CashEquityUniverse.qualified("x")).isEqualTo("x." + PREDICATE);
  }

  @Test
  void everyAdoptingSqlConstantExpandedThePredicate() throws Exception {
    List<String> offenders = new ArrayList<>();
    for (Class<?> owner :
        List.of(AdjustedEquityDailySql.class, Class.forName(LINEAGE), Class.forName(PROBE))) {
      for (Field f : owner.getDeclaredFields()) {
        if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) {
          continue;
        }
        f.setAccessible(true);
        String sql = (String) f.get(null);
        if (sql == null || !sql.contains("nse_eod_bhavcopy")) {
          continue;
        }
        String where = owner.getSimpleName() + "." + f.getName();
        if (sql.contains("\" + CashEquityUniverse")) {
          offenders.add(where + ": concatenation fragment left as literal text (text-block trap)");
        }
        if (sql.contains("%s")) {
          offenders.add(where + ": unexpanded %s - missing .formatted(...)");
        }
        if (!sql.contains(PREDICATE)) {
          offenders.add(where + ": reads nse_eod_bhavcopy but carries no cash-universe predicate");
        }
      }
    }
    assertThat(offenders).isEmpty();
  }

  /**
   * The alias SWAP — the one failure the {@code contains} checks above structurally CANNOT see,
   * because {@link CashEquityUniverse#qualified(String)} has the bare predicate as a substring, so a
   * swapped {@code .formatted(...)} satisfies every one of them.
   *
   * <p>{@code SymbolLineageDetector.DETECT_SQL} is the only constant taking two placeholders: the
   * first is in the unaliased {@code cal} CTE, the second inside {@code FROM nse_eod_bhavcopy b}.
   * Swapping them yields VALID SQL that fails only at runtime, so compilation and the checks above
   * both wave it through.
   */
  @Test
  void lineageSqlKeepsTheUnaliasedPredicateFirst() throws Exception {
    Field f = Class.forName(LINEAGE).getDeclaredField("DETECT_SQL");
    f.setAccessible(true);
    String sql = (String) f.get(null);

    int aliasedAt = sql.indexOf(ALIASED);
    assertThat(aliasedAt).as("an aliased predicate must exist").isNotNegative();

    int bareAt = -1;
    for (int i = sql.indexOf(PREDICATE); i >= 0; i = sql.indexOf(PREDICATE, i + 1)) {
      if (i < 2 || !"b.".equals(sql.substring(i - 2, i))) {
        bareAt = i;
        break;
      }
    }
    assertThat(bareAt).as("an unaliased predicate must exist").isNotNegative();
    assertThat(bareAt)
        .as("unaliased must come FIRST - the .formatted(...) args are swapped")
        .isLessThan(aliasedAt);
  }
}
