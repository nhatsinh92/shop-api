package com.mayshop.cart.writebehind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mayshop.cart.repository.CartKey;
import com.mayshop.cart.repository.CartLine;
import com.mayshop.cart.repository.CartSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The {@code cart.updated} record shape and the failure handling, without a broker. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartUpdatedPublisherTest {

  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
  private static final CartSnapshot SNAPSHOT =
      new CartSnapshot(
          List.of(
              new CartLine("SKU-B", 2, Instant.parse("2026-11-24T09:59:00Z")),
              new CartLine("SKU-A", 1, Instant.parse("2026-11-24T09:58:00Z"))),
          7,
          Instant.parse("2026-11-24T09:59:30Z"));

  private final JsonMapper json = JsonMapper.builder().build();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  @Mock private KafkaTemplate<String, String> kafka;
  @Mock private SendResult<String, String> sendResult;
  private CartUpdatedPublisher publisher;

  @BeforeEach
  void setUp() {
    when(kafka.send(any(String.class), any(String.class), any(String.class)))
        .thenReturn(CompletableFuture.completedFuture(sendResult));
    publisher = new CartUpdatedPublisher(kafka, json, meters, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    publisher.destroy();
  }

  private double failures() {
    return meters.counter("cart.write_behind.failed").count();
  }

  @Test
  void aWriteSendsOneRecordWithTheCartKeyAndTheSnapshotAsJson() {
    publisher.publish(CartKey.forGuest("abc"), SNAPSHOT);

    ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
    verify(kafka, timeout(2000)).send(eq("cart.updated"), eq("cart:guest:abc"), value.capture());
    JsonNode body = json.readTree(value.getValue());
    assertThat(body.get("cartKey").asString()).isEqualTo("cart:guest:abc");
    assertThat(body.get("version").asLong()).isEqualTo(7);
    assertThat(Instant.parse(body.get("updatedAt").asString()))
        .isEqualTo(Instant.parse("2026-11-24T09:59:30Z"));
    assertThat(body.get("items")).hasSize(2);
    assertThat(body.get("items").get(0).get("sku").asString()).isEqualTo("SKU-B");
    assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    assertThat(Instant.parse(body.get("items").get(0).get("addedAt").asString()))
        .isEqualTo(Instant.parse("2026-11-24T09:59:00Z"));
    assertThat(failures()).isZero();
  }

  @Test
  void aMergeSendsTheUserCartAndAnEmptySnapshotForTheGuestKey() {
    publisher.publishMerge(CartKey.forUser("u1"), SNAPSHOT, CartKey.forGuest("g1"));

    ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
    verify(kafka, timeout(2000)).send(eq("cart.updated"), eq("cart:u1"), any(String.class));
    verify(kafka, timeout(2000)).send(eq("cart.updated"), eq("cart:guest:g1"), value.capture());
    JsonNode guest = json.readTree(value.getValue());
    assertThat(guest.get("items")).isEmpty();
    assertThat(guest.get("cartKey").asString()).isEqualTo("cart:guest:g1");
  }

  @Test
  void aBrokerFailureIsCountedAndLoggedAndNeverReachesTheCaller() {
    when(kafka.send(any(String.class), any(String.class), any(String.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    publisher.publish(CartKey.forUser("u1"), SNAPSHOT);

    await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(failures()).isEqualTo(1));
  }

  @Test
  void aSendThatThrowsIsCountedAndNeverReachesTheCaller() {
    when(kafka.send(any(String.class), any(String.class), any(String.class)))
        .thenThrow(new IllegalStateException("metadata timeout"));

    publisher.publish(CartKey.forUser("u1"), SNAPSHOT);

    await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(failures()).isEqualTo(1));
  }

  @Test
  void aStoppedPublisherCountsTheRecordAsFailedInsteadOfThrowing() {
    publisher.destroy();

    publisher.publish(CartKey.forUser("u1"), SNAPSHOT);

    assertThat(failures()).isEqualTo(1);
    verify(kafka, never()).send(any(String.class), any(String.class), any(String.class));
  }
}
