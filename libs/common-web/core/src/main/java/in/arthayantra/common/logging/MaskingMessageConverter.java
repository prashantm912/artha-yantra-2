package in.arthayantra.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback {@code %msg} converter applying the {@link LogMasker} rules (A.6.1) — wire it in any
 * pattern layout via
 * {@code <conversionRule conversionWord="msg" converterClass="...MaskingMessageConverter"/>}.
 * The ECS structured-console path applies the same rules through
 * {@link ArthaMaskingJsonCustomizer}.
 */
public class MaskingMessageConverter extends MessageConverter {

  @Override
  public String convert(ILoggingEvent event) {
    return LogMasker.mask(super.convert(event));
  }
}
