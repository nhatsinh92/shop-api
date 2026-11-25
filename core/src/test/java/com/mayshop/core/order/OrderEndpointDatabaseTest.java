package com.mayshop.core.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.contract.ContractValidator;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code placeOrder} and {@code getOrder} on Postgres with a fixed clock and contract checks. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrderSupport.Configuration.class)
class OrderEndpointDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String KEY = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
  private static final String KEY_B = "7c9e6679-7425-40de-944b-e07fc1f90ae8";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("delete from orders.orders").update();
    jdbc.sql("delete from orders.idempotency_keys").update();
    jdbc.sql("delete from checkout.reservations").update();
    jdbc.sql("delete from product.products").update();
    jdbc.sql("delete from product.categories").update();
    jdbc.sql("insert into product.categories (slug, name) values ('c', 'C')").update();
    UUID product = UUID.randomUUID();
    jdbc.sql(
            "insert into product.products (id, slug, name, brand, category_slug)"
                + " values (?, 'slug-a', 'Name A', 'B', 'c')")
        .param(product)
        .update();
    jdbc.sql(
            "insert into product.variants (sku, product_id, position, price) values ('SKU-A', ?, 1, 890000)")
        .param(product)
        .update();
    jdbc.sql("insert into product.stock (sku, quantity) values ('SKU-A', 5)").update();
  }

  /** An active reservation of two SKU-A at 845 500 each, expiring in 10 minutes. */
  private UUID reservation(String user, String status, Instant expiresAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into checkout.reservations (id, user_id, status, created_at, expires_at)"
                + " values (?, ?, ?, ?, ?)")
        .params(
            id,
            UUID.fromString(user),
            status,
            Timestamp.from(OrderSupport.NOW.minusSeconds(60)),
            Timestamp.from(expiresAt))
        .update();
    jdbc.sql(
            "insert into checkout.reservation_items (reservation_id, sku, position, quantity,"
                + " unit_price, base_price, price_changed, took_sale_stock, name, image_url)"
                + " values (?, 'SKU-A', 1, 2, 845500, 890000, false, false, 'Name A', 'https://x/a.jpg')")
        .param(id)
        .update();
    return id;
  }

  private UUID activeReservation() {
    return reservation(OrderSupport.USER, "ACTIVE", OrderSupport.NOW.plusSeconds(600));
  }

  private static String body(UUID reservation, String method, String phone) {
    return """
    {"reservationId": "%s", "shippingOptionId": "EXPRESS_2H", "paymentMethod": "%s",
     "shippingAddress": {"fullName": "Nguyen Van An", "phone": "%s", "line1": "12 Nguyen Hue",
       "ward": "Ben Nghe", "province": "Ho Chi Minh City"}, "note": "Call first"}
    """
        .formatted(reservation, method, phone);
  }

  private static String cod(UUID reservation) {
    return body(reservation, "COD", "0901234567");
  }

  private MockHttpServletResponse place(String key, String json, String user) throws Exception {
    MockHttpServletRequestBuilder request =
        post("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + OrderSupport.token(user))
            .content(json);
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse place(String key, String json) throws Exception {
    return place(key, json, OrderSupport.USER);
  }

  private MockHttpServletResponse read(String id, String user) throws Exception {
    return mvc.perform(
            get("/api/orders/" + id)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + OrderSupport.token(user)))
        .andReturn()
        .getResponse();
  }

  private static JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private int count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
  }

  private int stock() {
    return jdbc.sql("select quantity from product.stock where sku = 'SKU-A'")
        .query(Integer.class)
        .single();
  }

  private void assertProblem(MockHttpServletResponse response, int status, String code)
      throws Exception {
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(json(response).get("code").asString()).isEqualTo(code);
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    ContractValidator.core().assertValid(response, "placeOrder");
  }

  @Test
  void aCodOrderIsPlacedFromTheReservationAndMatchesTheContract() throws Exception {
    UUID reservation = activeReservation();
    var response = place(KEY, cod(reservation));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeader("Idempotent-Replayed")).isNull();
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    ContractValidator.core().assertValid(response, "placeOrder");
    JsonNode order = json(response);
    assertThat(order.get("status").asString()).isEqualTo("PLACED");
    assertThat(order.get("payment").get("status").asString()).isEqualTo("NOT_REQUIRED");
    assertThat(order.get("payment").get("method").asString()).isEqualTo("COD");
    assertThat(order.get("number").asString()).matches("MY-1212-\\d{4,}");
    assertThat(order.get("subtotal").get("amount").asLong()).isEqualTo(1_691_000);
    assertThat(order.get("shippingFee").get("amount").asLong()).isEqualTo(35_000);
    assertThat(order.get("total").get("amount").asLong()).isEqualTo(1_726_000);
    assertThat(order.get("items").get(0).get("lineTotal").get("amount").asLong())
        .isEqualTo(1_691_000);
    assertThat(
            jdbc.sql("select status from checkout.reservations where id = ?")
                .param(reservation)
                .query(String.class)
                .single())
        .isEqualTo("CONSUMED");
    assertThat(jdbc.sql("select state from orders.idempotency_keys").query(String.class).single())
        .isEqualTo("COMPLETED");
  }

  @Test
  void aNullDistrictIsStoredWhenItIsAbsent() throws Exception {
    place(KEY, cod(activeReservation()));
    assertThat(
            jdbc.sql("select shipping_address->>'district' from orders.orders")
                .query(String.class)
                .optional())
        .isEmpty();
    assertThat(
            jdbc.sql("select jsonb_exists(shipping_address, 'district') from orders.orders")
                .query(Boolean.class)
                .single())
        .isFalse();
  }

  @Test
  void aMissingOrNonUuidKeyIsIdempotencyKeyMissingBeforeBodyValidation() throws Exception {
    assertProblem(place(null, "{}"), 400, "IDEMPOTENCY_KEY_MISSING");
    assertProblem(place("not-a-uuid", "{}"), 400, "IDEMPOTENCY_KEY_MISSING");
    assertThat(count("orders.idempotency_keys")).isZero();
  }

  @Test
  void schemaViolationsAreValidationFailed() throws Exception {
    var response = place(KEY, "{\"reservationId\": \"nope\"}");
    assertProblem(response, 400, "VALIDATION_FAILED");
    assertThat(json(response).get("errors").size()).isGreaterThan(1);
  }

  @Test
  void anInvalidPhoneIsValidationFailedNamingTheField() throws Exception {
    var response = place(KEY, body(activeReservation(), "COD", "0123"));
    assertProblem(response, 400, "VALIDATION_FAILED");
    assertThat(json(response).get("errors").get(0).get("field").asString())
        .isEqualTo("shippingAddress.phone");
  }

  @Test
  void anUnknownReservationIsNotFound() throws Exception {
    assertProblem(place(KEY, cod(UUID.randomUUID())), 404, "CHECKOUT_RESERVATION_NOT_FOUND");
  }

  @Test
  void anotherUsersReservationIsNotFound() throws Exception {
    UUID reservation =
        reservation(OrderSupport.OTHER_USER, "ACTIVE", OrderSupport.NOW.plusSeconds(600));
    assertProblem(place(KEY, cod(reservation)), 404, "CHECKOUT_RESERVATION_NOT_FOUND");
  }

  @Test
  void aReleasedReservationIsExpired() throws Exception {
    UUID reservation =
        reservation(OrderSupport.USER, "RELEASED", OrderSupport.NOW.plusSeconds(600));
    assertProblem(place(KEY, cod(reservation)), 410, "CHECKOUT_RESERVATION_EXPIRED");
  }

  @Test
  void anExpiredReservationIsGoneAndItsStockIsBackAfterTheResponse() throws Exception {
    UUID reservation = reservation(OrderSupport.USER, "ACTIVE", OrderSupport.NOW.minusSeconds(1));
    int before = stock();

    var response =
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> place(KEY, cod(reservation)));

    assertProblem(response, 410, "CHECKOUT_RESERVATION_EXPIRED");
    assertThat(stock()).isEqualTo(before + 2);
    assertThat(count("orders.idempotency_keys")).isZero();
    assertThat(
            jdbc.sql("select status from checkout.reservations where id = ?")
                .param(reservation)
                .query(String.class)
                .single())
        .isEqualTo("EXPIRED");
  }

  @Test
  void aConsumedReservationIsGoneWithItsOwnCode() throws Exception {
    UUID reservation =
        reservation(OrderSupport.USER, "CONSUMED", OrderSupport.NOW.plusSeconds(600));
    assertProblem(place(KEY, cod(reservation)), 410, "CHECKOUT_RESERVATION_CONSUMED");
  }

  @Test
  void aDomainErrorLeavesNoIdempotencyRowSoARetryWithTheSameKeyIsProcessedAsNew() throws Exception {
    UUID reservation = activeReservation();
    var body = cod(reservation);
    jdbc.sql("update checkout.reservations set status = 'RELEASED' where id = ?")
        .param(reservation)
        .update();
    assertProblem(place(KEY, body), 410, "CHECKOUT_RESERVATION_EXPIRED");
    assertThat(count("orders.idempotency_keys")).isZero();

    jdbc.sql("update checkout.reservations set status = 'ACTIVE' where id = ?")
        .param(reservation)
        .update();
    var retry = place(KEY, body);
    assertThat(retry.getStatus()).isEqualTo(201);
    assertThat(retry.getHeader("Idempotent-Replayed")).isNull();
  }

  @Test
  void theSameKeyAndBodyAfterCompletionReplaysTheStoredAnswer() throws Exception {
    var body = cod(activeReservation());
    var first = place(KEY, body);
    var replay = place(KEY, body);

    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(json(replay)).isEqualTo(json(first));
    ContractValidator.core().assertValid(replay, "placeOrder");
    assertThat(count("orders.orders")).isEqualTo(1);
  }

  @Test
  void aKeyStillInProgressIsConflict() throws Exception {
    var body = cod(activeReservation());
    var fingerprint = com.mayshop.core.order.idempotency.RequestFingerprint.of(body, null);
    jdbc.sql(
            "insert into orders.idempotency_keys (user_id, key, request_hash, state, created_at)"
                + " values (?, ?, ?, 'IN_PROGRESS', now())")
        .params(UUID.fromString(OrderSupport.USER), KEY, fingerprint)
        .update();
    assertProblem(place(KEY, body), 409, "IDEMPOTENCY_IN_PROGRESS");
  }

  @Test
  void theSameKeyWithADifferentBodyIsKeyReused() throws Exception {
    var body = cod(activeReservation());
    assertThat(place(KEY, body).getStatus()).isEqualTo(201);
    assertProblem(
        place(KEY, body.replace("Call first", "Leave at the door")), 422, "IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void aDifferentKeyOnAnAlreadyOrderedReservationIsConsumed() throws Exception {
    var body = cod(activeReservation());
    assertThat(place(KEY, body).getStatus()).isEqualTo(201);
    assertProblem(place(KEY_B, body), 410, "CHECKOUT_RESERVATION_CONSUMED");
    assertThat(count("orders.orders")).isEqualTo(1);
  }

  @Test
  void onlineMethodsAreUnavailableUntilTheOnlinePaymentTask() throws Exception {
    // Interim behaviour until #140: EWALLET and CARD answer 422 and consume nothing.
    UUID reservation = activeReservation();
    assertProblem(
        place(KEY, body(reservation, "EWALLET", "0901234567")),
        422,
        "CHECKOUT_PAYMENT_METHOD_UNAVAILABLE");
    assertProblem(
        place(KEY_B, body(reservation, "CARD", "0901234567")),
        422,
        "CHECKOUT_PAYMENT_METHOD_UNAVAILABLE");
    assertThat(
            jdbc.sql("select status from checkout.reservations where id = ?")
                .param(reservation)
                .query(String.class)
                .single())
        .isEqualTo("ACTIVE");
    assertThat(count("orders.idempotency_keys")).isZero();
  }

  @Test
  void theCallersOrderIsReadBackAndMatchesTheContract() throws Exception {
    var created = json(place(KEY, cod(activeReservation())));
    var response = read(created.get("id").asString(), OrderSupport.USER);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    ContractValidator.core().assertValid(response, "getOrder");
    assertThat(json(response)).isEqualTo(created);
  }

  @Test
  void anUnknownOrAnotherUsersOrderIsNotFound() throws Exception {
    var created = json(place(KEY, cod(activeReservation())));
    for (var response :
        new MockHttpServletResponse[] {
          read(created.get("id").asString(), OrderSupport.OTHER_USER),
          read(UUID.randomUUID().toString(), OrderSupport.USER),
          read("not-a-uuid", OrderSupport.USER)
        }) {
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(json(response).get("code").asString()).isEqualTo("ORDER_NOT_FOUND");
      ContractValidator.core().assertValid(response, "getOrder");
    }
  }

  @Test
  void ordersNeedAToken() throws Exception {
    var response =
        mvc.perform(post("/api/orders").header("Idempotency-Key", KEY).content("{}"))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(401);
  }
}
