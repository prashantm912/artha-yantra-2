package in.arthayantra.marketdata.mockfeed;

import in.arthayantra.marketdata.kite.InstrumentDumpGateway;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * CD-14: the mock instrument dump — a committed classpath CSV fixture (indices, top NSE stocks,
 * one NFO expiry ladder) with deterministic tokens.
 */
@Component
@Profile("mock")
public class MockInstrumentDumpGateway implements InstrumentDumpGateway {

  private static final String FIXTURE = "/instruments-fixture.csv";

  @Override
  public List<InstrumentRecord> fetchDump() {
    try (InputStream in = MockInstrumentDumpGateway.class.getResourceAsStream(FIXTURE)) {
      if (in == null) {
        throw new IllegalStateException("missing fixture " + FIXTURE);
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        return reader
            .lines()
            .filter(line -> !line.isBlank() && !line.startsWith("#"))
            .skip(1) // header
            .map(MockInstrumentDumpGateway::parse)
            .collect(Collectors.toList());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed reading " + FIXTURE, e);
    }
  }

  private static InstrumentRecord parse(String line) {
    String[] fields = line.split(",", -1);
    return new InstrumentRecord(
        Long.parseLong(fields[0]),
        0L, // mock fixture carries no exchange_token
        fields[1],
        fields[2],
        fields[3],
        fields[4],
        fields[5],
        fields[6].isEmpty() ? null : LocalDate.parse(fields[6]),
        fields[7].isEmpty() ? null : new BigDecimal(fields[7]),
        Integer.parseInt(fields[8]),
        new BigDecimal(fields[9]));
  }
}
