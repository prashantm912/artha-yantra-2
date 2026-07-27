package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Binds the ENTRY emission's sizing arguments to the OPTION LEG, so reverting them to the index
 * future cannot pass (cross-vendor review M1).
 *
 * <p>Sizing the index future instead of the option is the defect that left scalper auto-paper dead
 * for three weeks: 15/15 entries carried a NULL qty because FLOOR(15000 / (24092 × 65)) is 0. The
 * unit tests around it assert the pure {@code tradeableLeg} helper and, separately, that the guard
 * sizes correctly given that helper's output — but nothing tied the two together at the CALL SITE.
 * With those tests alone, changing {@code emitEntry}'s arguments back to
 * {@code exchange, tradingsymbol, entryPrice} compiles and the whole suite stays green.
 *
 * <p>A source-level assertion is the pragmatic guard here because {@code emitEntry} is private and
 * driving it needs the full published-strategy + market-data harness. It follows an established
 * pattern in this repo — {@code MapReturnRatchetTest} and {@code GatewayRouteAllowlistTest} are
 * pure-file tests that walk sources from the repo root for exactly this kind of invariant. A
 * behavioural integration test that asserts the persisted {@code signals.suggested_qty} is non-null
 * and lot-aligned is the stronger form and is chipped separately.
 */
class SignalEngineSizesFromOptionLegTest {

  @Test
  void entryEmissionSizesAndStampsFromTheTradeableOptionLeg() throws IOException {
    String src = Files.readString(engineSource());

    // Window from the call onward rather than a balanced-paren match: the argument list itself
    // contains nested parens, so a non-greedy match to the first ")" stops inside the first argument.
    Matcher call =
        Pattern.compile("suggestedQty\\(\\s*strategy\\.definition\\(\\)\\.sizing\\(\\),", Pattern.DOTALL)
            .matcher(src);

    assertThat(call.find())
        .as("SignalEngine must still size the ENTRY through EmissionGuard.suggestedQty")
        .isTrue();

    String args = src.substring(call.end(), Math.min(src.length(), call.end() + 250));
    assertThat(args)
        .as(
            "ENTRY sizing must use the TRADEABLE OPTION LEG (tradeable.exchange/tradingsymbol/"
                + "premium). Passing the index future's exchange/tradingsymbol/entryPrice makes"
                + " premium_budget floor to ZERO lots on every scalper entry — the defect that left"
                + " scalper auto-paper dead from 2026-07-07 to 2026-07-27.")
        .contains("tradeable.exchange()")
        .contains("tradeable.tradingsymbol()")
        .contains("tradeable.premium()");
  }

  private static Path engineSource() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("services"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repo root (a parent containing services/)").isNotNull();
    Path src =
        dir.resolve("services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal")
            .resolve("signals/SignalEngine.java");
    assertThat(Files.exists(src)).as("SignalEngine.java at %s", src).isTrue();
    return src;
  }
}
