package in.arthayantra.common.web.servlet;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Wires the servlet adapter (A.3) into any webmvc service that has this module on classpath. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ArthaServletAutoConfiguration {

  /** The shared D8 exception mapping. */
  @Bean
  @ConditionalOnMissingBean
  public GlobalExceptionHandler arthaGlobalExceptionHandler() {
    return new GlobalExceptionHandler();
  }

  /** Identity/correlation MDC binding, first in the chain. */
  @Bean
  public FilterRegistrationBean<ArthaIdentityFilter> arthaIdentityFilter() {
    FilterRegistrationBean<ArthaIdentityFilter> registration =
        new FilterRegistrationBean<>(new ArthaIdentityFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
