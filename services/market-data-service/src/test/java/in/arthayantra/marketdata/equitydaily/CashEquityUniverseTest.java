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
 * literal SQL text. It compiles, and it fails at runtime with a syntax error. The same class of
 * mistake is leaving a {@code %s} placeholder without the matching {@code .formatted(...)}.
 * Neither is visible to the compiler, and neither is visible to a test that only checks behaviour
 * on a path the SQL happens not to take.
 *
 * <p>So this asserts the produced STRINGS: every adopting constant must contain the real predicate
 * and must carry no unexpanded placeholder or stray concatenation fragment.
 */
class CashEquityUniverseTest {

  private static final String PREDICATE = "series IN ('EQ','BE')";

  @Test
  void predicateLiteralIsPinned() {
    // The house cash universe is EQ+BE: `artha.nse.bhavcopy.candle-series:EQ,BE`
    // (BhavcopyBackfillService:123). Ledger H24 widened 16 sites onto this exact string; if it
    // changes, that is a deliberate universe change and this assertion is where it gets noticed.
    assertThat(CashEquityUniverse.SERIES_PREDICATE).isEqualTo(PREDICATE);
  }

  @Test
  void qualifiedPrefixesTheAlias() {
    assertThat(CashEquityUniverse.qualified("b")).isEqualTo("b." + PREDICATE);
    assertThat(CashEquityUniverse.qualified("x")).isEqualTo("x." + PREDICATE);
  }

  @Test
  void everyAdoptingSqlConstantExpandedThePredicate() throws Exception {
    List<String> offenders = new ArrayList<>();
    for (Field f : AdjustedEquityDailySql.class.getDeclaredFields()) {
      if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) {
        continue;
      }
      f.setAccessible(true);
      String sql = (String) f.get(null);
      if (sql == null || !sql.contains("nse_eod_bhavcopy")) {
        continue;
      }
      // The two silent failures this guards, in order of how easy they are to ship:
      if (sql.contains("\" + CashEquityUniverse")) {
        offenders.add(f.getName() + ": concatenation fragment left as literal text (text-block trap)");
      }
      if (sql.contains("%s")) {
        offenders.add(f.getName() + ": unexpanded %s — missing .formatted(...)");
      }
      if (!sql.contains(PREDICATE)) {
        offenders.add(f.getName() + ": reads nse_eod_bhavcopy but carries no cash-universe predicate");
      }
    }
    assertThat(offenders).isEmpty();
  }
}
