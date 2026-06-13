package in.arthayantra.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CD-8 contract surface for the gateway's OWN endpoints (auth/session/system). The aggregated
 * Swagger UI (plan 5.3, mock profile) additionally lists the proxied service specs via the
 * {@code /docs/*} routes in application.yml.
 */
@Configuration
public class OpenApiConfig {

  /** Spec identity. */
  @Bean
  public OpenAPI gatewayOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("edge-gateway")
                .version("v1")
                .description("Sole ingress (Stage A): owner auth, sessions, WS bridge, routing"));
  }

  /** Adds the COMMON 8.3 envelope schema + a default error response to every operation. */
  @Bean
  public GlobalOpenApiCustomizer errorEnvelopeCustomizer() {
    return openApi -> {
      Schema<?> envelope =
          new ObjectSchema()
              .addProperty("code", new StringSchema())
              .addProperty("message", new StringSchema())
              .addProperty("details", new ObjectSchema());
      openApi.getComponents().addSchemas("ErrorResponse", envelope);
      openApi
          .getPaths()
          .values()
          .forEach(
              path ->
                  path.readOperations()
                      .forEach(
                          operation ->
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      "default",
                                      new ApiResponse()
                                          .description("Error envelope (COMMON 8.3)")
                                          .content(
                                              new Content()
                                                  .addMediaType(
                                                      "application/json",
                                                      new io.swagger.v3.oas.models.media.MediaType()
                                                          .schema(
                                                              new Schema<>()
                                                                  .$ref(
                                                                      "#/components/schemas/ErrorResponse")))))));
    };
  }
}
