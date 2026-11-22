package com.mayshop.core.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Validates a response (status, headers, body) against the bundled {@code core.yaml}.
 *
 * <p>The response schemas are checked with JSON Schema 2020-12, which is the dialect of OpenAPI 3.1
 * ({@code type: [string, 'null']} included). The spec path comes from the {@code
 * contract.core.spec} system property that the Gradle {@code test} task sets after it has bundled
 * the spec.
 */
public final class ContractValidator {

  private static final String SPEC_PROPERTY = "contract.core.spec";
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> METHODS =
      List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  private static volatile ContractValidator shared;

  private final JsonNode spec;
  private final JsonSchemaFactory registry =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  ContractValidator(JsonNode spec) {
    this.spec = spec;
  }

  /** The validator for the bundled core spec; the spec is parsed once. */
  public static ContractValidator core() {
    ContractValidator local = shared;
    if (local == null) {
      synchronized (ContractValidator.class) {
        if (shared == null) {
          shared = new ContractValidator(load(System.getProperty(SPEC_PROPERTY)));
        }
        local = shared;
      }
    }
    return local;
  }

  static ContractValidator forSpec(String yaml) {
    try {
      return new ContractValidator(YAML.readTree(yaml));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode load(String path) {
    if (path == null) {
      throw new IllegalStateException(
          "System property "
              + SPEC_PROPERTY
              + " is not set; run the tests through Gradle (:core:test bundles the spec first)");
    }
    try {
      return YAML.readTree(new File(path));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the bundled spec " + path, e);
    }
  }

  /**
   * Asserts that the response matches the spec of the operation.
   *
   * @throws AssertionError naming the operation, the status and each violated field
   */
  public void assertValid(MockHttpServletResponse response, String operationId) {
    List<String> violations = validate(response, operationId);
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "Response does not match the contract for operation "
              + operationId
              + " (status "
              + response.getStatus()
              + "):\n - "
              + String.join("\n - ", violations));
    }
  }

  /** Returns every violation of the spec, empty when the response matches. */
  public List<String> validate(MockHttpServletResponse response, String operationId) {
    JsonNode operation =
        findOperation(operationId)
            .orElseThrow(
                () -> new AssertionError("Operation " + operationId + " is not in the contract"));
    int status = response.getStatus();
    Optional<JsonNode> documented = findResponse(operation, status);
    if (documented.isEmpty()) {
      return List.of(
          "status "
              + status
              + " is not documented (documented: "
              + String.join(", ", fieldNames(operation.path("responses")))
              + ")");
    }
    List<String> violations = new ArrayList<>();
    JsonNode responseSpec = resolve(documented.get());
    checkHeaders(response, responseSpec.path("headers"), violations);
    checkBody(response, responseSpec.path("content"), violations);
    return violations;
  }

  private Optional<JsonNode> findOperation(String operationId) {
    for (JsonNode pathItem : spec.path("paths")) {
      for (String method : METHODS) {
        JsonNode operation = pathItem.path(method);
        if (operationId.equals(operation.path("operationId").asText(null))) {
          return Optional.of(operation);
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<JsonNode> findResponse(JsonNode operation, int status) {
    JsonNode responses = operation.path("responses");
    String exact = String.valueOf(status);
    String range = (status / 100) + "XX";
    for (String key : List.of(exact, range, "default")) {
      if (responses.has(key)) {
        return Optional.of(responses.get(key));
      }
    }
    return Optional.empty();
  }

  private void checkHeaders(
      MockHttpServletResponse response, JsonNode headers, List<String> violations) {
    for (String name : fieldNames(headers)) {
      if ("content-type".equalsIgnoreCase(name)) {
        continue;
      }
      JsonNode header = resolve(headers.get(name));
      List<String> values = response.getHeaders(name);
      if (values.isEmpty()) {
        if (header.path("required").asBoolean(false)) {
          violations.add("header " + name + " is required by the contract but missing");
        }
        continue;
      }
      JsonNode schema = header.path("schema");
      if (!schema.isMissingNode()) {
        for (String error : validateAgainst(schema, scalar(values.get(0), schema))) {
          violations.add("header " + name + ": " + error);
        }
      }
    }
  }

  private void checkBody(MockHttpServletResponse response, JsonNode content, List<String> out) {
    String body = bodyOf(response);
    if (content.isMissingNode() || content.isEmpty()) {
      if (!body.isEmpty()) {
        out.add("the contract documents no body for this response, but the body is not empty");
      }
      return;
    }
    String contentType = response.getContentType();
    if (contentType == null) {
      out.add(
          "Content-Type is missing; the contract documents "
              + String.join(", ", fieldNames(content)));
      return;
    }
    MediaType actual = MediaType.parseMediaType(contentType);
    Optional<String> key =
        fieldNames(content).stream()
            .filter(candidate -> MediaType.parseMediaType(candidate).isCompatibleWith(actual))
            .findFirst();
    if (key.isEmpty()) {
      out.add(
          "Content-Type "
              + actual.getType()
              + "/"
              + actual.getSubtype()
              + " is not documented (documented: "
              + String.join(", ", fieldNames(content))
              + ")");
      return;
    }
    JsonNode schema = content.get(key.get()).path("schema");
    if (schema.isMissingNode() || !actual.getSubtype().toLowerCase(Locale.ROOT).endsWith("json")) {
      return;
    }
    if (body.isEmpty()) {
      out.add("body is empty but the contract documents a " + key.get() + " body");
      return;
    }
    JsonNode json;
    try {
      json = JSON.readTree(body);
    } catch (IOException e) {
      out.add("body is not valid JSON: " + e.getMessage());
      return;
    }
    out.addAll(validateAgainst(schema, json));
  }

  /** Validates against a schema of the spec; {@code #/components/...} refs resolve in the spec. */
  private List<String> validateAgainst(JsonNode schema, JsonNode instance) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.put("$ref", "#/target");
    root.set("target", schema);
    root.set("components", spec.path("components"));
    JsonSchema compiled = registry.getSchema(root);
    return compiled.validate(instance).stream().map(ValidationMessage::getMessage).toList();
  }

  private JsonNode resolve(JsonNode node) {
    JsonNode current = node;
    for (int depth = 0; depth < 10 && current.has("$ref"); depth++) {
      String ref = current.get("$ref").asText();
      if (!ref.startsWith("#/")) {
        throw new AssertionError("Only local $ref is supported in the bundled spec: " + ref);
      }
      current = spec.at(ref.substring(1));
      if (current.isMissingNode()) {
        throw new AssertionError("Unresolvable $ref in the bundled spec: " + ref);
      }
    }
    return current;
  }

  private static JsonNode scalar(String value, JsonNode schema) {
    JsonNode type = schema.path("type");
    String declared = type.isArray() ? type.path(0).asText() : type.asText("string");
    return switch (declared) {
      case "integer" ->
          parseOr(value, () -> JSON.getNodeFactory().numberNode(Long.parseLong(value)));
      case "number" ->
          parseOr(value, () -> JSON.getNodeFactory().numberNode(Double.parseDouble(value)));
      case "boolean" -> JSON.getNodeFactory().booleanNode(Boolean.parseBoolean(value));
      default -> JSON.getNodeFactory().textNode(value);
    };
  }

  private static JsonNode parseOr(String value, java.util.function.Supplier<JsonNode> parse) {
    try {
      return parse.get();
    } catch (NumberFormatException e) {
      return JSON.getNodeFactory().textNode(value);
    }
  }

  private static String bodyOf(MockHttpServletResponse response) {
    try {
      return response.getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.UnsupportedEncodingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
