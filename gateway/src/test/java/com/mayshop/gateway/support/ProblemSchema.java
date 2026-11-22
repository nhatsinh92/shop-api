package com.mayshop.gateway.support;

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
import java.util.List;

/** Validates a response body against the {@code Problem} schema of {@code errors.yaml}. */
public final class ProblemSchema {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonSchema SCHEMA = compile();

  private ProblemSchema() {}

  private static JsonSchema compile() {
    try {
      JsonNode errors = YAML.readTree(new File("../contracts/openapi/errors.yaml"));
      ObjectNode root = JsonNodeFactory.instance.objectNode();
      root.put("$ref", "#/components/schemas/Problem");
      root.set("components", errors.path("components"));
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Returns the violations of the schema; empty when the body is a valid problem. */
  public static List<String> violations(String body) {
    try {
      return SCHEMA.validate(JSON.readTree(body)).stream()
          .map(ValidationMessage::getMessage)
          .toList();
    } catch (IOException e) {
      return List.of("body is not JSON: " + e.getMessage());
    }
  }
}
