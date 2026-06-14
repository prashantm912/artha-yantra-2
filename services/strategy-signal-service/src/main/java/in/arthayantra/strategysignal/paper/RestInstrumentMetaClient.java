package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** RestClient lookup against {@code GET /api/v1/instruments/{exchange}/{tradingsymbol}}. */
@Component
public class RestInstrumentMetaClient implements InstrumentMetaClient {

  private static final Logger log = LoggerFactory.getLogger(RestInstrumentMetaClient.class);
  private static final InstrumentMeta EQUITY_PROXY =
      new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);

  private final RestClient restClient;

  /** Wires the configured market-data base URL. */
  public RestInstrumentMetaClient(
      RestClient.Builder builder, @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  @Override
  public InstrumentMeta meta(String exchange, String tradingsymbol) {
    try {
      InstrumentDto dto =
          restClient
              .get()
              .uri("/api/v1/instruments/{exchange}/{tradingsymbol}", exchange, tradingsymbol)
              .retrieve()
              .body(InstrumentDto.class);
      if (dto == null) {
        return EQUITY_PROXY;
      }
      return new InstrumentMeta(
          classOf(dto.instrumentType()),
          dto.tickSize() == null ? new BigDecimal("0.05") : new BigDecimal(dto.tickSize()),
          dto.lotSize() == null || dto.lotSize() <= 0 ? 1 : dto.lotSize());
    } catch (RestClientException | NumberFormatException e) {
      log.warn("instrument meta lookup failed for {}:{} — equity proxy: {}", exchange, tradingsymbol, e.getMessage());
      return EQUITY_PROXY;
    }
  }

  private static InstrumentClass classOf(String instrumentType) {
    if (instrumentType == null) {
      return InstrumentClass.EQUITY;
    }
    return switch (instrumentType) {
      case "CE", "PE" -> InstrumentClass.OPTION;
      case "FUT" -> InstrumentClass.FUTURE;
      default -> InstrumentClass.EQUITY;
    };
  }

  /** The slice of the instrument-master row this client needs. */
  private record InstrumentDto(String instrumentType, String tickSize, Long lotSize) {}
}
