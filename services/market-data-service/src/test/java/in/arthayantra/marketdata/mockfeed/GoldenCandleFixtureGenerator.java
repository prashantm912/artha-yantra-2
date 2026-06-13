package in.arthayantra.marketdata.mockfeed;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * WRITE-ONCE golden candle fixture generator (docs/golden-vectors.md §1): the seeded mock
 * generator (CD-10, seed 42, trend-up) replayed through a 1m roll-up — 4 ticks per minute, five
 * trading sessions 2026-01-05..2026-01-09, NIFTY 50 (token 256265) + INDIA VIX (token 264969)
 * as the context fixture. Gated behind {@code -Dgolden.generate=true}; the outputs are COMMITTED
 * and never regenerated (regeneration is a format-breaking event per the freeze doc).
 */
class GoldenCandleFixtureGenerator {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate[] DAYS = {
    LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 7),
    LocalDate.of(2026, 1, 8), LocalDate.of(2026, 1, 9)
  };
  private static final int TICKS_PER_MINUTE = 4;
  private static final int MINUTES_PER_SESSION = 375; // 09:15 .. 15:29 IST

  @Test
  @EnabledIfSystemProperty(named = "golden.generate", matches = "true")
  void generateFrozenFixtures() throws IOException {
    Path goldenDir = locateGoldenCandlesDir();
    write(goldenDir, "NSE", "NIFTY 50", "NSE_NIFTY50", 256265L);
    write(goldenDir, "NSE", "INDIA VIX", "NSE_INDIAVIX", 264969L);
  }

  private static void write(
      Path dir, String exchange, String tradingsymbol, String filePrefix, long token)
      throws IOException {
    MockTickGenerator generator = new MockTickGenerator(42L, "trend-up");
    long previousCumVolume = 0;
    for (int day = 0; day < DAYS.length; day++) {
      List<String> lines = new ArrayList<>(MINUTES_PER_SESSION + 1);
      lines.add("exchange,tradingsymbol,interval,bucket,open,high,low,close,volume");
      OffsetDateTime open = DAYS[day].atTime(9, 15).atOffset(IST);
      for (int minute = 0; minute < MINUTES_PER_SESSION; minute++) {
        OffsetDateTime bucket = open.plusMinutes(minute);
        BigDecimal first = null;
        BigDecimal high = null;
        BigDecimal low = null;
        BigDecimal last = null;
        long cumVolume = previousCumVolume;
        for (int tick = 0; tick < TICKS_PER_MINUTE; tick++) {
          MockTickGenerator.Step step = generator.next(token);
          BigDecimal price = step.lastPrice();
          if (first == null) {
            first = price;
            high = price;
            low = price;
          }
          if (price.compareTo(high) > 0) {
            high = price;
          }
          if (price.compareTo(low) < 0) {
            low = price;
          }
          last = price;
          cumVolume = step.cumulativeDayVolume();
        }
        long barVolume = cumVolume - previousCumVolume;
        previousCumVolume = cumVolume;
        lines.add(
            exchange + ',' + tradingsymbol + ",1m," + bucket + ','
                + first.toPlainString() + ',' + high.toPlainString() + ','
                + low.toPlainString() + ',' + last.toPlainString() + ',' + barVolume);
      }
      Path out = dir.resolve(filePrefix + "_1m_day" + (day + 1) + ".csv");
      Files.write(out, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }
  }

  private static Path locateGoldenCandlesDir() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate =
          dir.resolve("libs/strategy-engine/src/test/resources/golden/candles");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("golden candles dir not found");
  }
}
