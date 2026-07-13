package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RFC-4180 quoting and plain-decimal coverage for D4 exports. */
class CsvEncoderTest {

  @Test
  void quotesCommaQuoteAndNewlineAndKeepsDecimalsPlain() {
    String row =
        CsvEncoder.row(
            List.of(
                "plain",
                "comma,value",
                "quote\"value",
                "line\nvalue",
                new BigDecimal("100000000000000000000.2500")));

    assertThat(row)
        .isEqualTo(
            "plain,\"comma,value\",\"quote\"\"value\",\"line\nvalue\","
                + "100000000000000000000.2500\r\n");
  }
}
