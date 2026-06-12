package in.arthayantra.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

/** The logback %msg converter applies the A.6.1 rules. */
class MaskingMessageConverterTest {

  @Test
  void convertedMessageIsMasked() {
    LoggerContext context = new LoggerContext();
    LoggingEvent event =
        new LoggingEvent(
            "test",
            context.getLogger("masking"),
            Level.INFO,
            "kite session password={} api_secret=verysecret99",
            null,
            new Object[] {"hunter22"});
    MaskingMessageConverter converter = new MaskingMessageConverter();

    String converted = converter.convert(event);

    assertThat(converted).doesNotContain("hunter22").doesNotContain("verysecret99");
    assertThat(converted).contains("password=***").contains("api_secret=***");
  }
}
