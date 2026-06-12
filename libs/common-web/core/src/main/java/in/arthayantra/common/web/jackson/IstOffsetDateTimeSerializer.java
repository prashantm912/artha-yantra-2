package in.arthayantra.common.web.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import in.arthayantra.common.web.time.Ist;
import java.io.IOException;
import java.time.OffsetDateTime;

/** Serializes {@link OffsetDateTime} at the {@code +05:30} offset (COMMON §3 time convention). */
public class IstOffsetDateTimeSerializer extends JsonSerializer<OffsetDateTime> {

  @Override
  public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
      throws IOException {
    gen.writeString(value.withOffsetSameInstant(Ist.OFFSET).format(Ist.FORMATTER));
  }
}
