package com.mayshop.core.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.mayshop.core.contract.ContractValidator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The online payment path of {@code placeOrder} against Postgres and a WireMock gateway. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "checkout.sweep.enabled=false",
      "order.recovery.enabled=false",
      "checkout.payment.timeout=1s",
      "checkout.payment.mock-outcome-header=true"
    })
@AutoConfigureMockMvc
@Import(OrderSupport.Configuration.class)
class OrderOnlinePaymentDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final WireMockServer GATEWAY =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String KEY = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeAll
  static void startGateway() {
    GATEWAY.start();
  }

  @AfterAll
  static void stopGateway() {
    GATEWAY.stop();
  }

  @DynamicPropertySource
  static void gatewayUrl(DynamicPropertyRegistry registry) {
    GATEWAY.start();
    registry.add("checkout.payment.url", () -> "http://localhost:" + GATEWAY.port());
  }

  @BeforeEach
  void clean() {
    OrderFixtures.reset(jdbc);
    GATEWAY.resetAll();
    GATEWAY.stubFor(post("/payments").willReturn(aResponse().withStatus(201)));
  }

  private MockHttpServletResponse place(String key, String body, String outcome) throws Exception {
    MockHttpServletRequestBuilder request =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + OrderSupport.token(OrderSupport.USER))
            .header("Idempotency-Key", key)
            .content(body);
    if (outcome != null) {
      request.header("X-Mock-Payment-Outcome", outcome);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private static JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private String reservationStatus(UUID reservation) {
    return OrderFixtures.single(
        jdbc, "select status from checkout.reservations where id = ?", reservation);
  }

  @Test
  void aSuccessfulOnlinePaymentMarksTheOrderPaid() throws Exception {
    UUID reservation = OrderFixtures.activeReservation(jdbc);
    var response = place(KEY, OrderFixtures.body(reservation, "EWALLET"), null);

    assertThat(response.getStatus()).isEqualTo(201);
    ContractValidator.core().assertValid(response, "placeOrder");
    JsonNode order = json(response);
    assertThat(order.get("status").asString()).isEqualTo("PAID");
    assertThat(order.get("payment").get("method").asString()).isEqualTo("EWALLET");
    assertThat(order.get("payment").get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(OrderFixtures.stock(jdbc)).isEqualTo(3);
    assertThat(reservationStatus(reservation)).isEqualTo("CONSUMED");
    assertThat(OrderFixtures.single(jdbc, "select state from orders.idempotency_keys"))
        .isEqualTo("COMPLETED");
    GATEWAY.verify(1, postRequestedFor(urlEqualTo("/payments")));
  }

  @Test
  void aFailedPaymentGivesTheStockBackAndRecordsPaymentFailed() throws Exception {
    GATEWAY.stubFor(
        post("/payments")
            .withHeader("X-Mock-Outcome", equalTo("fail"))
            .willReturn(aResponse().withStatus(402)));
    UUID reservation = OrderFixtures.activeReservation(jdbc);

    var response = place(KEY, OrderFixtures.body(reservation, "CARD"), "fail");

    assertThat(response.getStatus()).isEqualTo(201);
    ContractValidator.core().assertValid(response, "placeOrder");
    JsonNode order = json(response);
    assertThat(order.get("status").asString()).isEqualTo("PAYMENT_FAILED");
    assertThat(order.get("payment").get("status").asString()).isEqualTo("FAILED");
    assertThat(OrderFixtures.stock(jdbc)).isEqualTo(5);
    assertThat(OrderFixtures.single(jdbc, "select state from orders.idempotency_keys"))
        .isEqualTo("COMPLETED");
  }

  @Test
  void aTimedOutPaymentFailsWithinTheReadTimeoutAndGivesTheStockBack() throws Exception {
    GATEWAY.stubFor(
        post("/payments")
            .withHeader("X-Mock-Outcome", equalTo("timeout"))
            .willReturn(aResponse().withStatus(504).withFixedDelay(30_000)));
    UUID reservation = OrderFixtures.activeReservation(jdbc);

    long start = System.nanoTime();
    var response = place(KEY, OrderFixtures.body(reservation, "EWALLET"), "timeout");
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(took).isLessThan(Duration.ofSeconds(4));
    assertThat(response.getStatus()).isEqualTo(201);
    ContractValidator.core().assertValid(response, "placeOrder");
    JsonNode order = json(response);
    assertThat(order.get("status").asString()).isEqualTo("PAYMENT_FAILED");
    assertThat(order.get("payment").get("status").asString()).isEqualTo("TIMED_OUT");
    assertThat(OrderFixtures.stock(jdbc)).isEqualTo(5);
  }

  @Test
  void aReplayOfAFailedAttemptReturnsTheStoredAnswerAndDoesNotChargeAgain() throws Exception {
    GATEWAY.stubFor(post("/payments").willReturn(aResponse().withStatus(402)));
    UUID reservation = OrderFixtures.activeReservation(jdbc);
    var body = OrderFixtures.body(reservation, "CARD");
    var first = place(KEY, body, null);

    var replay = place(KEY, body, null);

    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(json(replay)).isEqualTo(json(first));
    ContractValidator.core().assertValid(replay, "placeOrder");
    GATEWAY.verify(1, postRequestedFor(urlEqualTo("/payments")));
    assertThat(OrderFixtures.stock(jdbc)).isEqualTo(5);
  }

  @Test
  void tenParallelSubmitsWithOneKeyCreateExactlyOneOrder() throws Exception {
    GATEWAY.stubFor(post("/payments").willReturn(aResponse().withStatus(201).withFixedDelay(300)));
    UUID reservation = OrderFixtures.activeReservation(jdbc);
    var body = OrderFixtures.body(reservation, "EWALLET");

    ExecutorService pool = Executors.newFixedThreadPool(10);
    List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 10; i++) {
        Callable<MockHttpServletResponse> call = () -> place(KEY, body, null);
        futures.add(pool.submit(call));
      }
      int created = 0;
      for (Future<MockHttpServletResponse> future : futures) {
        MockHttpServletResponse response = future.get();
        if (response.getStatus() == 409) {
          assertThat(json(response).get("code").asString()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
        } else {
          assertThat(response.getStatus()).isEqualTo(201);
          if (response.getHeader("Idempotent-Replayed") == null) {
            created++;
          } else {
            assertThat(response.getHeader("Idempotent-Replayed")).isEqualTo("true");
            assertThat(json(response).get("status").asString()).isEqualTo("PAID");
          }
        }
      }
      assertThat(created).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(jdbc.sql("select count(*) from orders.orders").query(Integer.class).single())
        .isEqualTo(1);
    GATEWAY.verify(1, postRequestedFor(urlEqualTo("/payments")));
  }
}
