package in.arthayantra.common.web.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.core.Ordered;

/**
 * Marks the always-emitted properties of record-backed RESPONSE schemas as {@code required}, so the
 * CI contract gate can see a removed or renamed response key.
 *
 * <p>Position matters, and it is the whole reason this runs on the assembled document rather than in
 * {@link RecordRequiredModelConverter}:
 *
 * <ul>
 *   <li><b>Responses</b> — {@code required} states "the server always emits this key". True for a
 *       record under Jackson's default inclusion, and it is what gives the gate teeth: dropping a
 *       required response property is {@code incompatible.response.required.decreased}.
 *   <li><b>Requests</b> — {@code required} would state "the client MUST send this key". That is
 *       false: Jackson binds an omitted component to null, so the server does accept its absence.
 *       Declaring it required would publish a contract we do not enforce, tighten the generated TS
 *       client, and trip the gate's {@code request.parameter.required.increased} rule for no gain.
 * </ul>
 *
 * <p>So a schema is only touched when it is reachable from a response and NOT from any request body
 * or parameter. A schema reachable from BOTH is left alone — correctness beats coverage. Today the
 * subtraction removes nothing that would otherwise be marked: no record is reachable from both
 * positions ({@code ItemRequest} is request-only; {@code JsonNode} appears in both but is not a
 * record, so it never carries {@code required} regardless). The guard is for the future shared
 * record, not a present one.
 *
 * <p>Runs last so the reachability walk sees every operation other customizers have added.
 */
public class ResponseRequiredCustomizer implements GlobalOpenApiCustomizer, Ordered {

  private static final String REF_PREFIX = "#/components/schemas/";

  private final RecordRequiredModelConverter facts;

  public ResponseRequiredCustomizer(RecordRequiredModelConverter facts) {
    this.facts = facts;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void customise(OpenAPI openApi) {
    Map<String, Schema> schemas =
        openApi.getComponents() == null ? null : openApi.getComponents().getSchemas();
    if (schemas == null || schemas.isEmpty() || openApi.getPaths() == null) {
      return;
    }

    Set<String> responseOnly = responseOnlyNames(openApi, schemas);

    Map<String, Set<String>> alwaysEmitted = facts.alwaysEmittedProperties();
    for (String name : responseOnly) {
      Set<String> required = alwaysEmitted.get(name);
      Schema<?> schema = schemas.get(name);
      if (required == null || required.isEmpty() || schema == null) {
        continue;
      }
      Map<String, Schema> properties = schema.getProperties();
      if (properties == null) {
        continue;
      }
      // Sorted + rebuilt from scratch: the committed spec is byte-compared, so the array must not
      // depend on model-resolution order or accumulate across captures.
      List<String> present = new ArrayList<>(new TreeSet<>(required));
      present.retainAll(properties.keySet());
      if (!present.isEmpty()) {
        schema.setRequired(present);
      }
    }
  }

  /**
   * Component names reachable from a RESPONSE and from no request body or parameter.
   *
   * <p>Shared with {@link NullableRefCustomizer}: both must touch response-only schemas and nothing
   * else. On a request body, asserting a response-side fact about a key the server accepts as absent
   * is a lie in the other direction, and a schema reachable from BOTH positions is left alone —
   * correctness beats coverage.
   */
  static Set<String> responseOnlyNames(OpenAPI openApi, Map<String, Schema> schemas) {
    Set<String> fromResponses = new HashSet<>();
    Set<String> fromRequests = new HashSet<>();
    openApi
        .getPaths()
        .values()
        .forEach(
            path ->
                path.readOperations()
                    .forEach(
                        operation -> {
                          collectResponseSeeds(operation, fromResponses);
                          collectRequestSeeds(operation, fromRequests);
                        }));
    Set<String> responseOnly = new TreeSet<>(closure(fromResponses, schemas));
    responseOnly.removeAll(closure(fromRequests, schemas));
    return responseOnly;
  }

  private static void collectResponseSeeds(Operation operation, Set<String> seeds) {
    if (operation.getResponses() != null) {
      refsIn(operation.getResponses().values(), seeds);
    }
  }

  private static void collectRequestSeeds(Operation operation, Set<String> seeds) {
    if (operation.getRequestBody() != null) {
      refsIn(List.of(operation.getRequestBody()), seeds);
    }
    if (operation.getParameters() != null) {
      refsIn(operation.getParameters(), seeds);
    }
  }

  /** Every schema name transitively reachable from the seeds. */
  private static Set<String> closure(Set<String> seeds, Map<String, Schema> schemas) {
    Set<String> seen = new HashSet<>(seeds);
    Deque<String> pending = new ArrayDeque<>(seeds);
    while (!pending.isEmpty()) {
      Schema<?> schema = schemas.get(pending.pop());
      if (schema == null) {
        continue;
      }
      Set<String> next = new HashSet<>();
      refsIn(List.of(schema), next);
      for (String name : next) {
        if (seen.add(name)) {
          pending.push(name);
        }
      }
    }
    return seen;
  }

  /**
   * Walks arbitrary swagger model objects for {@code $ref} strings. The models are POJOs, not a
   * uniform tree, so this reflects over the shapes that actually carry refs.
   */
  private static void refsIn(Collection<?> nodes, Set<String> out) {
    for (Object node : nodes) {
      refsIn(node, out, new HashSet<>());
    }
  }

  private static void refsIn(Object node, Set<String> out, Set<Object> seen) {
    if (node == null || !seen.add(node)) {
      return;
    }
    if (node instanceof Schema<?> schema) {
      addRef(schema.get$ref(), out);
      refsIn(schema.getItems(), out, seen);
      refsIn(schema.getAdditionalProperties(), out, seen);
      refsIn(schema.getNot(), out, seen);
      refsInAll(schema.getProperties() == null ? null : schema.getProperties().values(), out, seen);
      refsInAll(schema.getAllOf(), out, seen);
      refsInAll(schema.getAnyOf(), out, seen);
      refsInAll(schema.getOneOf(), out, seen);
    } else if (node instanceof io.swagger.v3.oas.models.responses.ApiResponse response) {
      addRef(response.get$ref(), out);
      refsIn(response.getContent(), out, seen);
    } else if (node instanceof io.swagger.v3.oas.models.parameters.RequestBody body) {
      addRef(body.get$ref(), out);
      refsIn(body.getContent(), out, seen);
    } else if (node instanceof io.swagger.v3.oas.models.parameters.Parameter parameter) {
      addRef(parameter.get$ref(), out);
      refsIn(parameter.getSchema(), out, seen);
      refsIn(parameter.getContent(), out, seen);
    } else if (node instanceof io.swagger.v3.oas.models.media.Content content) {
      refsInAll(content.values(), out, seen);
    } else if (node instanceof io.swagger.v3.oas.models.media.MediaType mediaType) {
      refsIn(mediaType.getSchema(), out, seen);
    }
  }

  private static void refsInAll(Collection<?> nodes, Set<String> out, Set<Object> seen) {
    if (nodes == null) {
      return;
    }
    for (Object node : nodes) {
      refsIn(node, out, seen);
    }
  }

  private static void addRef(String ref, Set<String> out) {
    if (ref != null && ref.startsWith(REF_PREFIX)) {
      out.add(ref.substring(REF_PREFIX.length()));
    }
  }
}
