package in.arthayantra.common.web.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ErrorResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Pins the platform Jackson conventions (A.3): decimal strings, +05:30 offsets, envelope shape. */
class ArthaJacksonConventionsTest {

  private ObjectMapper mapper;

  @BeforeEach
  void buildMapperTheWayBootWould() {
    Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
    new ArthaJacksonAutoConfiguration().arthaJacksonCustomizer().customize(builder);
    mapper = builder.build();
  }

  @Test
  void bigDecimalSerializesAsExactString() throws Exception {
    record Quote(BigDecimal lastPrice) {}

    String json = mapper.writeValueAsString(new Quote(new BigDecimal("21750.0500")));

    assertThat(json).isEqualTo("{\"lastPrice\":\"21750.0500\"}");
  }

  @Test
  void offsetDateTimeSerializesAtIstOffset() throws Exception {
    record Event(OffsetDateTime at) {}
    OffsetDateTime utc = OffsetDateTime.of(2026, 1, 5, 4, 30, 0, 0, ZoneOffset.UTC);

    String json = mapper.writeValueAsString(new Event(utc));

    assertThat(json).isEqualTo("{\"at\":\"2026-01-05T10:00:00+05:30\"}");
  }

  @Test
  void millisSurviveWithoutTrailingZeros() throws Exception {
    record Event(OffsetDateTime at) {}
    OffsetDateTime withMillis = OffsetDateTime.of(2026, 1, 5, 4, 30, 0, 123_000_000, ZoneOffset.UTC);

    String json = mapper.writeValueAsString(new Event(withMillis));

    assertThat(json).isEqualTo("{\"at\":\"2026-01-05T10:00:00.123+05:30\"}");
  }

  @Test
  void errorEnvelopeKeepsCodeMessageDetailsShape() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("retryAfterMs", 1200);
    details.put("jobId", null);

    String json =
        mapper.writeValueAsString(
            new ErrorResponse("KITE_RATE_LIMITED", "Kite historical API budget exhausted", details));

    assertThat(json)
        .isEqualTo(
            "{\"code\":\"KITE_RATE_LIMITED\",\"message\":\"Kite historical API budget exhausted\","
                + "\"details\":{\"retryAfterMs\":1200,\"jobId\":null}}");
  }

  @Test
  void nullDetailsNormalizeToEmptyMap() {
    ErrorResponse envelope = new ErrorResponse("INTERNAL_ERROR", "boom", null);

    assertThat(envelope.details()).isEmpty();
  }
}
