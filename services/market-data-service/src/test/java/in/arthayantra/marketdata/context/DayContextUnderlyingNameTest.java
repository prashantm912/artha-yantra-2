package in.arthayantra.marketdata.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.canary.IngestHealthBoard;
import in.arthayantra.marketdata.instruments.UnderlyingRef;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.options.OptionsDigestService;
import in.arthayantra.marketdata.upstox.UpstoxGlobalInstrumentsClient;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * <b>26 rows spanning 2026-07-13 … 2026-08-19</b> with {@code expiry}, {@code pcr_eod},
 * {@code max_pain_eod}, {@code atm_straddle_eod} and {@code atm_iv_eod} <b>ALL NULL</b>, every row
 * stamped {@code options_name = 'NIFTY'} — while {@code MarketContextEodJob} logged
 * <i>"persisted market_context_days for … (1 row)"</i> on each of those nights.
 *
 * <p>⚠️ <b>Not "26 consecutive trading days" — that was an earlier, wrong phrasing and review caught
 * it.</b> The range holds <b>28</b> trading days; <b>2026-07-17 and 2026-08-12 have no row AND no
 * {@code MARKET_CONTEXT_DAY} run at all</b> (26 runs, all SUCCESS), and neither date is in
 * {@code nse-trading-holidays.csv}. So on those two nights the job did not merely log a hollow
 * success — <b>it did not run</b>. That is a SECOND, separate and still-unexplained hole, and the
 * tidier phrasing concealed it.
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
  @DisplayName("all THREE committed copies of the configured name are already canonical")
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

    // ⚠️ THE THIRD COPY, and it is the one review found missing. MarketContextEodJob carries its
    // OWN @Value and writes the result raw into market_context_days.options_name. An earlier cut of
    // this test asserted "both committed copies" while that copy still defaulted to the alias — a
    // guard whose NAME claimed completeness it did not have, which is the exact shape this PR exists
    // to prevent.
    String eodJobDefault =
        valueAfter(
            "services/market-data-service/src/main/java/in/arthayantra/marketdata/context/"
                + "MarketContextEodJob.java",
            "${artha.context.options-name:",
            "}");
    assertThat(eodJobDefault)
        .as("this value LABELS the persisted row, so a stale alias here mislabels correct scalars")
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
    // ⚠️ Asserts the env NAME, inside market-data-service's own block — NOT the default VALUE.
    // Pinning the value would false-RED an operator who legitimately points day-context at another
    // canonical index, and asserting "appears anywhere in the file" would pass on a line sitting in
    // a different service's environment map, where it would never reach this container.
    String compose =
        Files.readString(repoRoot().resolve("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
    int block = compose.indexOf("  market-data-service:");
    assertThat(block).as("market-data-service block not found in compose").isGreaterThan(-1);
    int next = compose.indexOf("  strategy-signal-service:", block);
    String marketDataBlock = next > block ? compose.substring(block, next) : compose.substring(block);
    assertThat(marketDataBlock)
        .as("market-data has no env_file, so an env name absent from ITS map never reaches it (#653)")
        .contains("ARTHA_CONTEXT_OPTIONS_NAME:");
  }

  /**
   * ⚠️ THE ONLY TEST HERE THAT PINS RUNTIME BEHAVIOUR RATHER THAN SOURCE TEXT — added on review.
   *
   * <p>Everything above reads committed files, and a name can be canonical in a file and still wrong
   * at runtime. This constructs the real {@link DayContextService} with the ALIAS and asserts the
   * digest is asked for the CANONICAL key, which is the actual behaviour the 26 empty rows came
   * from. It is also the only assertion here that would survive someone deleting the config guards.
   */
  @Test
  @DisplayName("an alias in config reaches the digest as the canonical key")
  void theAliasIsNormalisedOnTheWayToTheDigest() {
    OptionsDigestService digest = mock(OptionsDigestService.class);
    // Only the trust block needs a real shape: dayContext() fail-softs the digest and the quote
    // reads, but ingestTrust() dereferences board.sources() unguarded. Stub it empty rather than
    // wrapping the call in try/catch — a swallowed exception would let this test pass even if
    // dayContext() blew up BEFORE reaching the digest, which is the one thing it must not do.
    IngestHealthBoard board = mock(IngestHealthBoard.class);
    when(board.board(anyInt()))
        .thenReturn(
            new IngestHealthBoard.BoardReport(
                Instant.parse("2026-08-20T06:00:00Z"), null, null, 0, List.of()));
    DayContextService service =
        new DayContextService(
            digest,
            mock(QuoteGateway.class),
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(UpstoxGlobalInstrumentsClient.class),
            mock(CandleQueryService.class),
            board,
            MarketCalendar.nse(),
            Clock.fixed(Instant.parse("2026-08-20T06:00:00Z"), ZoneOffset.UTC),
            "NIFTY",
            "NSE",
            "NIFTY 50",
            "INDIA VIX",
            5,
            5,
            new BigDecimal("13"),
            new BigDecimal("17"),
            new BigDecimal("22"));

    service.dayContext();

    verify(digest).digest(eq("NIFTY 50"), any(), any());
    verify(digest, never()).digest(eq("NIFTY"), any(), any());
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
