package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.StringJoiner;

/** Minimal RFC-4180 row encoder shared by the per-run export endpoints. */
final class CsvEncoder {

  private CsvEncoder() {}

  /** Encodes one row with CRLF termination. */
  static String row(List<?> values) {
    StringJoiner joined = new StringJoiner(",");
    for (Object value : values) {
      joined.add(escape(text(value)));
    }
    return joined + "\r\n";
  }

  private static String text(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.toPlainString();
    }
    if (value instanceof JsonNode node) {
      if (node.isNull() || node.isMissingNode()) {
        return "";
      }
      if (node.isFloatingPointNumber()) {
        return node.decimalValue().toPlainString();
      }
      if (node.isValueNode()) {
        return node.asText();
      }
      return node.toString();
    }
    return value.toString();
  }

  private static String escape(String value) {
    boolean quoted =
        value.indexOf(',') >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\r') >= 0
            || value.indexOf('\n') >= 0;
    return quoted ? '"' + value.replace("\"", "\"\"") + '"' : value;
  }
}
