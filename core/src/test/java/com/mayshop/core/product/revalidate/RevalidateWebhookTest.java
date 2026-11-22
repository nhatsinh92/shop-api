package com.mayshop.core.product.revalidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mayshop.core.product.ProductUpdated;
import com.mayshop.core.product.ProductUpdated.Change;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import tools.jackson.databind.json.JsonMapper;

class RevalidateWebhookTest {

  private static final String SECRET = "s3cret-value-for-tests";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(RevalidateWebhook.class);

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  private RevalidateWebhook webhook(String url) {
    return new RevalidateWebhook(url, SECRET, "500ms", "10ms", JSON, meters);
  }

  private static ProductUpdated event(Change... changes) {
    UUID productId = UUID.randomUUID();
    return new ProductUpdated(
        UUID.randomUUID(),
        Instant.parse("2026-12-12T00:00:05Z"),
        productId,
        "SKU-ANC-PRO-BLK",
        "tai-nghe",
        List.of(changes),
        List.of("product-" + productId, "products"));
  }

  private double failures() {
    return meters.counter(RevalidateWebhook.FAILED_COUNTER).count();
  }

  @Test
  void aPriceChangeIsSentOnceWithAValidSignatureAndBody() throws Exception {
    try (StubServer stub = new StubServer(n -> 204)) {
      webhook(stub.url()).on(event(Change.PRICE));

      assertThat(stub.calls()).hasSize(1);
      StubServer.Call call = stub.calls().get(0);
      assertThat(call.contentType()).isEqualTo("application/json");
      Matcher m = Pattern.compile("^t=(\\d+),v1=([0-9a-f]{64})$").matcher(call.signature());
      assertThat(m.matches()).as("signature header %s", call.signature()).isTrue();
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((m.group(1) + ".").getBytes(StandardCharsets.UTF_8));
      assertThat(HexFormat.of().formatHex(mac.doFinal(call.body()))).isEqualTo(m.group(2));
      assertThat(Math.abs(Instant.now().getEpochSecond() - Long.parseLong(m.group(1))))
          .isLessThan(10);
      assertThat(violations(new String(call.body(), StandardCharsets.UTF_8))).isEmpty();
      assertThat(failures()).isZero();
    }
  }

  @Test
  void aDetailChangeIsSentOnce() throws Exception {
    try (StubServer stub = new StubServer(n -> 204)) {
      webhook(stub.url()).on(event(Change.STOCK, Change.DETAIL));
      assertThat(stub.calls()).hasSize(1);
    }
  }

  @Test
  void aStockOnlyChangeIsNotSent() throws Exception {
    try (StubServer stub = new StubServer(n -> 204)) {
      webhook(stub.url()).on(event(Change.STOCK));
      assertThat(stub.calls()).isEmpty();
      assertThat(failures()).isZero();
    }
  }

  @Test
  void aServerErrorIsRetriedThreeTimesThenCountedAndLogged() throws Exception {
    try (StubServer stub = new StubServer(n -> 500)) {
      webhook(stub.url()).on(event(Change.PRICE));
      assertThat(stub.calls()).hasSize(3);
      assertThat(failures()).isEqualTo(1);
      assertThat(logs.list)
          .anySatisfy(e -> {
            assertThat(e.getLevel().toString()).isEqualTo("WARN");
            assertThat(e.getFormattedMessage()).contains("failed after 3 attempts");
          });
    }
  }

  @Test
  void aTimeoutIsRetriedThreeTimesThenCounted() throws Exception {
    try (StubServer stub = new StubServer(n -> -1)) {
      webhook(stub.url()).on(event(Change.DETAIL));
      assertThat(stub.calls()).hasSize(3);
      assertThat(failures()).isEqualTo(1);
    }
  }

  @Test
  void aDeliverySucceedingOnTheSecondAttemptIsNotCounted() throws Exception {
    try (StubServer stub = new StubServer(n -> n == 1 ? 503 : 204)) {
      webhook(stub.url()).on(event(Change.PRICE));
      assertThat(stub.calls()).hasSize(2);
      assertThat(failures()).isZero();
    }
  }

  @Test
  void anUnreachableServerIsCounted() {
    webhook("http://127.0.0.1:1/hooks/revalidate").on(event(Change.PRICE));
    assertThat(failures()).isEqualTo(1);
  }

  @Test
  void theSecretNeverAppearsInTheLogsOfAFailedDelivery() throws Exception {
    try (StubServer stub = new StubServer(n -> 500)) {
      webhook(stub.url()).on(event(Change.PRICE));
      assertThat(logs.list).isNotEmpty();
      assertThat(logs.list)
          .allSatisfy(e -> {
            assertThat(e.getFormattedMessage()).doesNotContain(SECRET);
            assertThat(String.valueOf(e.getThrowableProxy())).doesNotContain(SECRET);
          });
      String signature = stub.calls().get(0).signature();
      assertThat(logs.list)
          .allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(signature));
    }
  }

  @Test
  void aUrlWithoutASecretFailsStartupWithAClearMessage() {
    assertThatThrownBy(
            () -> new RevalidateWebhook("http://localhost/x", " ", "2s", "10ms", JSON, meters))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REVALIDATE_SECRET")
        .hasMessageContaining("WEB_REVALIDATE_URL");
  }

  @Test
  void theListenerRunsAfterCommitThroughTheEventRegistry() throws Exception {
    assertThat(
            RevalidateWebhook.class
                .getDeclaredMethod("on", ProductUpdated.class)
                .isAnnotationPresent(ApplicationModuleListener.class))
        .isTrue();
  }

  private static List<String> violations(String body) throws Exception {
    ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    var spec = yaml.readTree(new File(System.getProperty("contract.core.spec")));
    var root = yaml.createObjectNode();
    root.put("$ref", "#/target");
    root.set("target", spec.at("/components/schemas/ProductUpdated"));
    root.set("components", spec.path("components"));
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(root)
        .validate(new ObjectMapper().readTree(body))
        .stream()
        .map(v -> v.getMessage())
        .toList();
  }
}
