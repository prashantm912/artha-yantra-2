package in.arthayantra.common.web.servlet;

import in.arthayantra.common.web.http.ArthaHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the gateway-injected identity ({@code X-Artha-User}) and correlation id
 * ({@code X-Request-Id}) to the MDC for every request (A.3 / A.6.3). Services sit on the private
 * compose network with no published ports, so the headers are trusted as injected by the gateway;
 * a missing correlation id (direct host-run call, T2) gets a locally generated UUID so logs stay
 * correlated either way. The id is echoed on the response.
 */
public class ArthaIdentityFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = request.getHeader(ArthaHeaders.X_REQUEST_ID);
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }
    String user = request.getHeader(ArthaHeaders.X_ARTHA_USER);

    MDC.put(ArthaHeaders.MDC_REQUEST_ID, requestId);
    if (user != null && !user.isBlank()) {
      MDC.put(ArthaHeaders.MDC_USER, user);
    }
    response.setHeader(ArthaHeaders.X_REQUEST_ID, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(ArthaHeaders.MDC_REQUEST_ID);
      MDC.remove(ArthaHeaders.MDC_USER);
    }
  }
}
