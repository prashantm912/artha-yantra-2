package in.arthayantra.gateway.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.ErrorResponse;
import in.arthayantra.common.web.http.ArthaHeaders;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The reactive equivalent of common-web's servlet GlobalExceptionHandler (A.2.4): every error
 * leaving the gateway is the D8 {@code {code, message, details}} envelope — absent upstreams are
 * 503 {@code UPSTREAM_UNAVAILABLE} (A.2.2), everything unexpected is 500 {@code INTERNAL_ERROR}
 * with the correlation id, never a stack trace.
 */
@Component
@Order(-2) // before Boot's DefaultErrorWebExceptionHandler
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

  private final ObjectMapper objectMapper;

  /** Wires the envelope serializer. */
  public GatewayErrorWebExceptionHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Renders any gateway error as the D8 envelope. */
  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(ex);
    }
    String requestId = exchange.getResponse().getHeaders().getFirst(ArthaHeaders.X_REQUEST_ID);

    HttpStatus status;
    ErrorResponse envelope;
    if (ex instanceof ApiException apiException) {
      status = HttpStatus.valueOf(apiException.httpStatus());
      envelope = apiException.toErrorResponse();
    } else if (isUpstreamUnavailable(ex)) {
      status = HttpStatus.SERVICE_UNAVAILABLE;
      envelope =
          ErrorResponse.of(
              ErrorCodes.UPSTREAM_UNAVAILABLE,
              "Upstream service is not available yet; try again later");
    } else if (ex instanceof ResponseStatusException rse) {
      status = HttpStatus.valueOf(rse.getStatusCode().value());
      String code =
          switch (status) {
            case NOT_FOUND -> ErrorCodes.NOT_FOUND_RESOURCE;
            case SERVICE_UNAVAILABLE -> ErrorCodes.UPSTREAM_UNAVAILABLE;
            case UNAUTHORIZED -> ErrorCodes.AUTH_REQUIRED;
            case FORBIDDEN -> ErrorCodes.AUTH_FORBIDDEN;
            default -> status.is4xxClientError()
                ? ErrorCodes.VALIDATION_FAILED
                : ErrorCodes.INTERNAL_ERROR;
          };
      envelope = ErrorResponse.of(code, rse.getReason() == null ? status.getReasonPhrase() : rse.getReason());
    } else {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
      log.error("Unhandled gateway exception (correlationId={})", requestId, ex);
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("correlationId", requestId);
      envelope = new ErrorResponse(ErrorCodes.INTERNAL_ERROR, "Internal error", details);
    }

    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] body;
    try {
      body = objectMapper.writeValueAsBytes(envelope);
    } catch (Exception serializationFailure) {
      body = ("{\"code\":\"" + envelope.code() + "\"}").getBytes(StandardCharsets.UTF_8);
    }
    DataBuffer buffer = response.bufferFactory().wrap(body);
    return response.writeWith(Mono.just(buffer));
  }

  private static boolean isUpstreamUnavailable(Throwable ex) {
    Throwable current = ex;
    int depth = 0;
    while (current != null && depth < 10) {
      if (current instanceof ConnectException
          || current instanceof UnknownHostException
          || current instanceof org.springframework.cloud.gateway.support.NotFoundException) {
        return true;
      }
      current = current.getCause();
      depth++;
    }
    return false;
  }
}
