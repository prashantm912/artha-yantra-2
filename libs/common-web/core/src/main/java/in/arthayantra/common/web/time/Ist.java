package in.arthayantra.common.web.time;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * IST constants for the platform-wide time convention (COMMON §3): time = {@code TIMESTAMPTZ} in
 * {@code Asia/Kolkata}, serialized with the explicit {@code +05:30} offset.
 */
public final class Ist {

  /** Fixed IST offset — India has no DST. */
  public static final ZoneOffset OFFSET = ZoneOffset.ofHoursMinutes(5, 30);

  /** Region zone for calendar math. */
  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

  /**
   * ISO-8601 with mandatory seconds, optional millis, explicit offset — e.g.
   * {@code 2026-01-05T10:42:00+05:30}. (Plain {@code ISO_OFFSET_DATE_TIME} drops {@code :00}
   * seconds, which would make golden-vector strings unstable.)
   */
  public static final DateTimeFormatter FORMATTER =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
          .appendFraction(ChronoField.NANO_OF_SECOND, 0, 3, true)
          .appendOffset("+HH:MM", "+00:00")
          .toFormatter();

  private Ist() {}
}
