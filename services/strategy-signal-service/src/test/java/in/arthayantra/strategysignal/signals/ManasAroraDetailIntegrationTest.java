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
 * The {@code manas_arora_detail} side-channel (V022). A fired Manas swing signal carries its setup
 * detail (setup type, base footprint, pivot) here — OUTSIDE the frozen score breakdown, mirroring the
 * V020 {@code minervini_detail} pattern; every non-manas signal reads {@code null}. Proves the stamp →
 * read round-trip + the null default, and that stamping manas leaves minervini/scalper untouched.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class ManasAroraDetailIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private SignalRepository repo;

  private long insertSignal(String sym) {
    UUID versionId = jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
    return repo.insert(
        versionId, "NSE", sym, "1d", "ENTRY", "BUY",
        new BigDecimal("150.00"), new BigDecimal("135.00"), null, new BigDecimal("0.70"),
        "{}", OffsetDateTime.parse("2026-07-03T09:15:00+05:30"), null);
  }

  @Test
  void stampsAndReadsBackTheManasDetailWhileNonManasStaysNull() {
    long stamped = insertSignal("MADETWIN" + UUID.randomUUID().toString().substring(0, 6));
    long plain = insertSignal("MADETNULL" + UUID.randomUUID().toString().substring(0, 6));

    String detail =
        "{\"setup\":\"manas-arora-breakout\",\"setupType\":\"vcp\",\"footprint\":\"12W 3T\","
            + "\"pivot\":\"150.00\"}";
    repo.stampManasAroraDetail(stamped, detail);

    var stampedRow = repo.find(stamped).orElseThrow();
    assertThat(stampedRow.manasAroraDetail()).isNotNull();
    assertThat(stampedRow.manasAroraDetail().get("setup").asText()).isEqualTo("manas-arora-breakout");
    assertThat(stampedRow.manasAroraDetail().get("setupType").asText()).isEqualTo("vcp");
    assertThat(stampedRow.manasAroraDetail().get("footprint").asText()).isEqualTo("12W 3T");
    // the side-channel does NOT touch the frozen score breakdown, the scalper, or the minervini channel
    assertThat(stampedRow.scalperDetail()).isNull();
    assertThat(stampedRow.minerviniDetail()).isNull();

    assertThat(repo.find(plain).orElseThrow().manasAroraDetail())
        .as("a signal that was never stamped carries a null manas_arora_detail")
        .isNull();
  }
}
