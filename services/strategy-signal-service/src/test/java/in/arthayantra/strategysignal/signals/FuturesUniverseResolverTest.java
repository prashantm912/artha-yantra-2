package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** E1 §3.3: the pure screener-pick parse (conviction order, side, cap, dedupe) — no REST. */
class FuturesUniverseResolverTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void screenerPicksRanksBySideAndCapsAtMaxPicks() throws Exception {
    var screen =
        mapper.readTree(
            """
            {"longCandidates":[{"symbol":"HDFCBANK"},{"symbol":"ICICIBANK"},{"symbol":"AXISBANK"}],
             "shortCandidates":[{"symbol":"PNB"}]}
            """);
    assertThat(FuturesUniverseResolver.screenerPicks(screen, "long", 2))
        .containsExactly("HDFCBANK", "ICICIBANK"); // top-2 in conviction order
    assertThat(FuturesUniverseResolver.screenerPicks(screen, "short", 5))
        .containsExactly("PNB");
  }

  @Test
  void screenerPicksDedupesAndSkipsBlanksAndMissingLists() throws Exception {
    var screen =
        mapper.readTree(
            """
            {"longCandidates":[{"symbol":"SBIN"},{"symbol":""},{"symbol":"SBIN"},{"symbol":"CANBK"}]}
            """);
    assertThat(FuturesUniverseResolver.screenerPicks(screen, "long", 9))
        .containsExactly("SBIN", "CANBK"); // blank skipped, duplicate collapsed
    assertThat(FuturesUniverseResolver.screenerPicks(mapper.readTree("{}"), "long", 5)).isEmpty();
  }
}
