package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** RestClient lookup of contract metadata against {@code GET /api/v1/instruments/{exch}/{sym}}. */
@Component
public class RestContractInfoClient implements ContractInfoClient {

  private static final Logger log = LoggerFactory.getLogger(RestContractInfoClient.class);
  private static final Set<String> INDEX_PREFIXES =
      Set.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX");

  private final RestClient restClient;

  /** Wires the configured market-data base URL. */
  public RestContractInfoClient(
      RestClient.Builder builder, @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  @Override
  public Optional<ContractInfo> contract(String exchange, String tradingsymbol) {
    try {
      InstrumentDto dto =
          restClient
              .get()
              .uri("/api/v1/instruments/{exchange}/{tradingsymbol}", exchange, tradingsymbol)
              .retrieve()
              .body(InstrumentDto.class);
      if (dto == null || dto.expiry() == null) {
        return Optional.empty();
      }
      InstrumentClass cls =
          switch (dto.instrumentType() == null ? "" : dto.instrumentType()) {
            case "CE", "PE" -> InstrumentClass.OPTION;
            case "FUT" -> InstrumentClass.FUTURE;
            default -> InstrumentClass.EQUITY;
          };
      if (cls == InstrumentClass.EQUITY) {
        return Optional.empty(); // not a derivative — no expiry settlement
      }
      String underlying = dto.underlyingTradingsymbol() == null ? "" : dto.underlyingTradingsymbol();
      boolean index = INDEX_PREFIXES.stream().anyMatch(p -> underlying.toUpperCase().startsWith(p));
      return Optional.of(
          new ContractInfo(
              LocalDate.parse(dto.expiry()),
              dto.underlyingExchange() == null ? "NSE" : dto.underlyingExchange(),
              underlying,
              dto.strike() == null ? null : new BigDecimal(dto.strike()),
              "CE".equals(dto.instrumentType()) || "PE".equals(dto.instrumentType())
                  ? dto.instrumentType()
                  : null,
              cls,
              index));
    } catch (RestClientException | java.time.format.DateTimeParseException | NumberFormatException e) {
      log.warn("contract info lookup failed for {}:{}: {}", exchange, tradingsymbol, e.getMessage());
      return Optional.empty();
    }
  }

  /** The instrument-master fields the settlement needs. */
  private record InstrumentDto(
      String instrumentType,
      String expiry,
      String strike,
      String underlyingExchange,
      String underlyingTradingsymbol) {}
}
