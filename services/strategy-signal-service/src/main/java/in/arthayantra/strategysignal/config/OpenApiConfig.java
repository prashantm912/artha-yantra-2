package in.arthayantra.strategysignal.config;

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
 * CD-8 contract surface: service identity + the D8 error envelope registered on every operation
 * as the {@code default} response, so the committed spec carries (and the CI lint can assert)
 * the {@code {code, message, details}} shape.
 */
@Configuration
public class OpenApiConfig {

  /** Spec identity. */
  @Bean
  public OpenAPI strategySignalOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("strategy-signal-service")
                .version("v1")
                .description(
                    "Strategy registry + live signal engine (Stage C): D18 lifecycle, signals with score breakdowns"));
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
