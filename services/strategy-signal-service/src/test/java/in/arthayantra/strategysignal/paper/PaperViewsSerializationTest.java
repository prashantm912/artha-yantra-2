package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The wire contract of the D3 paper retypings, pinned at the SERIALIZED form (ledger D3 slice 1).
 *
 * <p>The integration tests assert that keys EXIST on a populated response. That is not the same as
 * pinning the contract: it cannot see member ORDER, and it cannot reach the zero-trade branch where
 * two summary members go null. Both are exactly what a record conversion can silently change, so
 * they get their own assertions here (cross-vendor review of the conversion, round 1).
 *
 * <p>Scope: keys, order and null-vs-absent only. Value FORMATTING is set at the mapper — {@code
 * ArthaJacksonAutoConfiguration} routes every {@code BigDecimal} through {@code ToStringSerializer} —
 * which applies to a record exactly as it did to the map, so a bare mapper is the right instrument
 * for the question being asked.
 */
class PaperViewsSerializationTest {

  private static final ObjectMapper OM = new ObjectMapper();

  private static List<String> keysOf(Object value) {
    List<String> keys = new ArrayList<>();
    OM.valueToTree(value).fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  /**
   * {@code PnlSummary} is the ONE paper body whose order was already deterministic — it mirrored a
   * {@code LinkedHashMap}, so a reordered component genuinely moves bytes a client may have been
   * reading. This is the assertion that makes that order load-bearing rather than incidental.
   */
  @Test
  void pnlSummaryKeepsTheFormerLinkedHashMapOrder() {
    PaperViews.PnlSummary summary =
        new PaperViews.PnlSummary(
            new BigDecimal("1234.50"), 4, new BigDecimal("0.7500"), new BigDecimal("308.6250"));

    assertThat(keysOf(summary))
        .containsExactly("realizedTotal", "trades", "winRate", "expectancy");
  }

  /**
   * A book with no closed trades: {@code winRate} and {@code expectancy} are UNDEFINED, not zero
   * (0/0 is not a 0% win rate). They must stay PRESENT-and-null on the wire, as the map made them —
   * the service configures no {@code NON_NULL} inclusion, and a client distinguishing "no data" from
   * "zero" depends on it.
   */
  @Test
  void anEmptyBookEmitsWinRateAndExpectancyAsPresentAndNull() {
    PaperViews.PnlSummary empty = new PaperViews.PnlSummary(BigDecimal.ZERO, 0, null, null);

    assertThat(keysOf(empty)).containsExactly("realizedTotal", "trades", "winRate", "expectancy");
    assertThat(OM.valueToTree(empty).get("winRate").isNull()).isTrue();
    assertThat(OM.valueToTree(empty).get("expectancy").isNull()).isTrue();
  }

  /**
   * The envelope and the curve point. Both came from a MULTI-KEY {@code Map.of}, whose iteration
   * order varies between JVM runs, so these assertions pin a NEW guarantee rather than preserving an
   * old one — the conversion deliberately normalizes the order (see {@link PaperViews}).
   */
  @Test
  void thePnlEnvelopeAndCurvePointSerializeInDeclarationOrder() {
    PaperViews.Pnl pnl =
        new PaperViews.Pnl(
            List.of(new PaperViews.EquityPoint("2026-07-29", new BigDecimal("100.25"))),
            new PaperViews.PnlSummary(BigDecimal.ZERO, 0, null, null));

    assertThat(keysOf(pnl)).containsExactly("points", "summary");
    assertThat(keysOf(pnl.points().get(0))).containsExactly("date", "equity");
  }

  /**
   * The two page envelopes. {@code PositionList} is single-key, so its serialization really is
   * unchanged; {@code TradePage} came from a multi-key {@code Map.of} and its order is NORMALIZED
   * here, not preserved — same distinction as the envelope test above.
   */
  @Test
  void thePageEnvelopesCarryItemsThenTheWindow() {
    assertThat(keysOf(new PaperViews.TradePage(List.of(), 50, 0)))
        .containsExactly("items", "limit", "offset");
    assertThat(keysOf(new PaperViews.PositionList(List.of()))).containsExactly("items");
  }
}
