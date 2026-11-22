package com.mayshop.core.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Helpers for the tests that read {@code product.updated} from Redpanda. */
final class ProductEventSupport {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private ProductEventSupport() {}

  /** Violations of the {@code ProductUpdated} schema of the bundled contract (none = valid). */
  static List<String> violations(String json) {
    try {
      JsonNode spec = YAML.readTree(new File(System.getProperty("contract.core.spec")));
      ObjectNode root = JsonNodeFactory.instance.objectNode();
      root.put("$ref", "#/target");
      root.set("target", spec.at("/components/schemas/ProductUpdated"));
      root.set("components", spec.path("components"));
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
          .getSchema(root)
          .validate(new ObjectMapper().readTree(json))
          .stream()
          .map(ValidationMessage::getMessage)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Reads {@code topic} from the start for {@code wait} and returns the records with this key. */
  static List<ConsumerRecord<String, String>> recordsWithKey(
      String bootstrap, String topic, String key, Duration wait) {
    Properties props = new Properties();
    props.putAll(
        Map.of(
            "bootstrap.servers",
            bootstrap,
            "group.id",
            "test-" + System.nanoTime(),
            "auto.offset.reset",
            "earliest",
            "key.deserializer",
            StringDeserializer.class.getName(),
            "value.deserializer",
            StringDeserializer.class.getName()));
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      long end = System.nanoTime() + wait.toNanos();
      while (System.nanoTime() < end) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
          if (key.equals(r.key())) {
            found.add(r);
          }
        }
      }
    }
    return found;
  }
}
