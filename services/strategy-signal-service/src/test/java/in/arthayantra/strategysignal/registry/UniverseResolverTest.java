package in.arthayantra.strategysignal.registry;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.registry.UniverseResolver.Constituent;
import in.arthayantra.strategysignal.registry.UniverseResolver.ResolvedUniverse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Pure resolver math (Phase 44): canonical checksum + explicit/exclude resolution, no REST. */
class UniverseResolverTest {

  private final UniverseResolver resolver =
      new UniverseResolver(RestClient.builder(), "http://unused");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void checksumIsDeterministicAndOrderSensitive() {
    List<Constituent> a = List.of(new Constituent("NSE", "RELIANCE"), new Constituent("NSE", "TCS"));
    List<Constituent> b = List.of(new Constituent("NSE", "TCS"), new Constituent("NSE", "RELIANCE"));
    assertThat(UniverseResolver.checksum(a)).isEqualTo(UniverseResolver.checksum(a)); // deterministic
    assertThat(UniverseResolver.checksum(a)).isNotEqualTo(UniverseResolver.checksum(b)); // order matters
    // a constituent rebalance (different membership) yields a different checksum
    List<Constituent> c = List.of(new Constituent("NSE", "RELIANCE"), new Constituent("NSE", "INFY"));
    assertThat(UniverseResolver.checksum(a)).isNotEqualTo(UniverseResolver.checksum(c));
  }

  @Test
  void explicitUniverseResolvesFromTheConfigList() throws Exception {
    var config =
        mapper.readTree(
            """
            {"universe":{"mode":"explicit","instruments":[
              {"exchange":"NSE","tradingsymbol":"RELIANCE"},
              {"exchange":"NSE","tradingsymbol":"TCS"}]}}
            """);
    ResolvedUniverse u = resolver.resolve(config);
    assertThat(u.mode()).isEqualTo("explicit");
    assertThat(u.items()).hasSize(2);
    assertThat(u.checksum()).isNotBlank();
    assertThat(u.survivorshipCaveat()).isNull();
  }
}
