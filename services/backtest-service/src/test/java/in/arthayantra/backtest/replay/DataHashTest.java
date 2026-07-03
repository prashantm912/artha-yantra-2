package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link DataHash} tuple composition (audit row 6, datahash-partial-coverage): the extra
 * {@link DataHash.SeriesLeg}s fold every consumed series into the hash — differing premium/context
 * coverage is no longer identical-data — while a primary-only run (empty leg list) hashes
 * byte-identically to the pre-extension algorithm.
 */
class DataHashTest {

  private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-06-01T09:15:00+05:30");
  private static final OffsetDateTime TO = OffsetDateTime.parse("2026-06-05T15:30:00+05:30");
  private static final OffsetDateTime FETCHED = OffsetDateTime.parse("2026-06-05T16:00:00+05:30");

  private static String hash(List<DataHash.SeriesLeg> legs) {
    return DataHash.of("NSE", "NIFTY 50", "1m", FROM, TO, 1875, FETCHED, legs);
  }

  @Test
  void sameInputsProduceTheSameHash() {
    List<DataHash.SeriesLeg> legs =
        List.of(new DataHash.SeriesLeg("NFO", "NIFTY25000CE", "1m", 375, FETCHED));
    assertThat(hash(legs)).isEqualTo(hash(legs));
  }

  @Test
  void premiumSeriesCoverageChangeChangesTheHash() {
    // same primary tuple, differing premium-series bar count / freshness / presence ⇒ NOT like-for-like
    String fullCoverage =
        hash(List.of(new DataHash.SeriesLeg("NFO", "NIFTY25000CE", "1m", 375, FETCHED)));
    String partialCoverage =
        hash(List.of(new DataHash.SeriesLeg("NFO", "NIFTY25000CE", "1m", 210, FETCHED)));
    String refetched =
        hash(
            List.of(
                new DataHash.SeriesLeg("NFO", "NIFTY25000CE", "1m", 375, FETCHED.plusHours(1))));
    assertThat(fullCoverage).isNotEqualTo(partialCoverage);
    assertThat(fullCoverage).isNotEqualTo(refetched);
    assertThat(fullCoverage).isNotEqualTo(hash(List.of())); // a leg at all differs from none
  }

  @Test
  void primaryOnlyRunHashMatchesThePreExtensionAlgorithm() throws Exception {
    // the frozen original tuple: exchange|symbol|interval|fromInstant|toInstant|barCount|maxFetchedAt
    String legacyTuple =
        String.join(
            "|",
            "NSE",
            "NIFTY 50",
            "1m",
            FROM.toInstant().toString(),
            TO.toInstant().toString(),
            "1875",
            FETCHED.toInstant().toString());
    String legacyHash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(legacyTuple.getBytes(StandardCharsets.UTF_8)));
    assertThat(hash(List.of())).isEqualTo(legacyHash); // empty leg list appends nothing
  }

  @Test
  void nullFetchedAtHashesAsNoneOnEveryLeg() {
    String primaryNone = DataHash.of("NSE", "NIFTY 50", "1m", FROM, TO, 1875, null, List.of());
    assertThat(primaryNone).isNotEqualTo(hash(List.of()));
    String legNone = hash(List.of(new DataHash.SeriesLeg("NFO", "NIFTY25000CE", "1m", 375, null)));
    assertThat(legNone)
        .isNotEqualTo(hash(List.of(new DataHash.SeriesLeg("NFO", "NIFTY25000CE", "1m", 375, FETCHED))));
  }
}
