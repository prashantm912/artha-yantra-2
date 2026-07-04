package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MV-6.8: the {@code minervini_detail} side-channel (V020). A fired minervini swing signal carries its
 * setup detail (setup type, stage, VCP footprint, pivot, gate booleans) here — OUTSIDE the frozen
 * score breakdown, mirroring the V009 {@code scalper_detail} pattern; every non-minervini signal
 * reads {@code null}. Proves stamp → read round-trip + the null default.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class MinerviniDetailIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private SignalRepository repo;

  private long insertSignal(String sym) {
    UUID versionId = jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
    return repo.insert(
        versionId, "NSE", sym, "1d", "ENTRY", "BUY",
        new BigDecimal("150.00"), new BigDecimal("138.00"), null, new BigDecimal("0.70"),
        "{}", OffsetDateTime.parse("2026-07-03T09:15:00+05:30"), null);
  }

  @Test
  void stampsAndReadsBackTheMinerviniDetailWhileNonMinerviniStaysNull() {
    long stamped = insertSignal("MVDETWIN" + UUID.randomUUID().toString().substring(0, 6));
    long plain = insertSignal("MVDETNULL" + UUID.randomUUID().toString().substring(0, 6));

    String detail =
        "{\"setup\":\"vcp\",\"stage\":2,\"footprint\":\"40W 31/3 4T\",\"pivot\":\"150.00\","
            + "\"gates\":[true,true,true,true,true,true,true,true]}";
    repo.stampMinerviniDetail(stamped, detail);

    var stampedRow = repo.find(stamped).orElseThrow();
    assertThat(stampedRow.minerviniDetail()).isNotNull();
    assertThat(stampedRow.minerviniDetail().get("setup").asText()).isEqualTo("vcp");
    assertThat(stampedRow.minerviniDetail().get("stage").asInt()).isEqualTo(2);
    assertThat(stampedRow.minerviniDetail().get("footprint").asText()).isEqualTo("40W 31/3 4T");
    // the side-channel does NOT touch the frozen score breakdown or the scalper channel
    assertThat(stampedRow.scalperDetail()).isNull();

    assertThat(repo.find(plain).orElseThrow().minerviniDetail())
        .as("a signal that was never stamped carries a null minervini_detail")
        .isNull();
  }
}
