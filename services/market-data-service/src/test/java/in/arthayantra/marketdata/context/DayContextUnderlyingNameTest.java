package in.arthayantra.marketdata.context;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.instruments.UnderlyingRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that the index whose options digest day-context bundles is a CANONICAL cash-index
 * tradingsymbol, in every committed copy, and that a written alias is repaired rather than swallowed.
 *
 * <p>⚠️ <b>Why this exists.</b> {@code artha.context.options-name} defaulted to a bare {@code NIFTY}
 * for the entire life of the feature. {@code NIFTY} is not a canonical instrument key, so
 * {@code OptionsDigestService} answered <i>"no option expiries for NIFTY"</i>,
 * {@code DayContextService.dayContext()} caught it and fail-softed into a {@code notes} entry, and
 * nobody read the note. Measured live 2026-08-20: {@code marketdata.market_context_days} held
 * <b>26 consecutive trading days</b> (2026-07-13 … 2026-08-19) with {@code expiry}, {@code pcr_eod},
 * {@code max_pain_eod}, {@code atm_straddle_eod} and {@code atm_iv_eod} <b>ALL NULL</b>, every row
 * stamped {@code options_name = 'NIFTY'} — while {@code MarketContextEodJob} logged
 * <i>"persisted market_context_days for … (1 row)"</i> every single night.
 *
 * <p>⚠️ <b>The guard that was missing is the reason it survived 26 days.</b>
 * {@code MarketContextEodJob:91} warns when a digest anchors on the wrong date — but that branch
 * requires {@code o != null}, so a <em>null</em> digest takes the silent path and writes null
 * scalars with no warning at all. The success log line and the empty write are indistinguishable
 * from outside.
 *
 * <p>This is the same defect as #1420 on the strategy-signal side, in a second place nobody checked.
 * Hence the fix normalises at the point of USE ({@link UnderlyingRef#canonical}) rather than only
 * correcting this copy of the value — correcting copies is what left the second one wrong.
 */
class DayContextUnderlyingNameTest {

  @Test
  @DisplayName("the alias every config keeps being written with resolves to the canonical key")
  void theIndexAliasIsRepaired() {
    assertThat(UnderlyingRef.canonical("NIFTY"))
        .as("a bare NIFTY is what both configs were written with, and what the digest cannot answer")
        .isEqualTo("NIFTY 50");
    assertThat(UnderlyingRef.canonical("BANKNIFTY")).isEqualTo("NIFTY BANK");
  }

  @Test
  @DisplayName("an already-canonical or non-index name passes through untouched")
  void canonicalAndUnknownNamesArePassedThrough() {
    // SENSEX is already canonical and is NOT in the alias map — if normalisation mangled it, the
    // BSE half of the platform would break in exactly the way this fix exists to prevent.
    assertThat(UnderlyingRef.canonical("SENSEX")).isEqualTo("SENSEX");
    assertThat(UnderlyingRef.canonical("NIFTY 50")).isEqualTo("NIFTY 50");
    assertThat(UnderlyingRef.canonical("RELIANCE")).isEqualTo("RELIANCE");
    assertThat(UnderlyingRef.canonical(null)).isNull();
  }

  @Test
  @DisplayName("both committed copies of the configured name are already canonical")
  void everyCommittedCopyIsCanonical() throws IOException {
    // ⚠️ Normalisation makes an alias HARMLESS; it does not make it correct. These pin the source
    // text too, because the value is also what lands in market_context_days.options_name, which is
    // the column that made the 26-day gap legible after the fact.
    String annotationDefault =
        valueAfter(
            "services/market-data-service/src/main/java/in/arthayantra/marketdata/context/"
                + "DayContextService.java",
            "${artha.context.options-name:",
            "}");
    assertThat(annotationDefault)
        .as("the @Value fallback is what runs when no env var is set — which is the live case today")
        .isEqualTo("NIFTY 50");

    String yml =
        valueAfter(
            "services/market-data-service/src/main/resources/application.yml",
            "options-name: ${ARTHA_CONTEXT_OPTIONS_NAME:",
            "}");
    assertThat(yml).isEqualTo("NIFTY 50");
  }

  @Test
  @DisplayName("the knob is reachable from compose, not just from source")
  void theKnobIsWiredThrough() throws IOException {
    // The #653 class, and #1427 hit it again on 2026-08-20: a property with no compose passthrough
    // cannot be overridden on the live stack at all, so the default is the only value that exists.
    String compose =
        Files.readString(repoRoot().resolve("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
    assertThat(compose)
        .as("market-data has no env_file, so an env name absent from this map never reaches it")
        .contains("ARTHA_CONTEXT_OPTIONS_NAME: ${ARTHA_CONTEXT_OPTIONS_NAME:-NIFTY 50}");
  }

  /** First value between {@code prefix} and the next {@code suffix}; fails loudly if absent. */
  private static String valueAfter(String file, String prefix, String suffix) throws IOException {
    List<String> lines = Files.readAllLines(repoRoot().resolve(file), StandardCharsets.UTF_8);
    for (String line : lines) {
      int at = line.indexOf(prefix);
      if (at < 0) {
        continue;
      }
      int from = at + prefix.length();
      int to = line.indexOf(suffix, from);
      if (to > from) {
        return line.substring(from, to).trim();
      }
    }
    throw new IllegalStateException("no '" + prefix + "' found in " + file);
  }

  /**
   * Surefire's working directory is the MODULE, not the repo root. Fails rather than skipping — a
   * source-walking test that quietly finds no file is a guard that checks nothing.
   */
  private static Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
      if (Files.exists(dir.resolve("deploy/docker-compose.yml"))) {
        return dir;
      }
    }
    throw new IllegalStateException(
        "could not locate the repo root from " + Paths.get("").toAbsolutePath());
  }
}
