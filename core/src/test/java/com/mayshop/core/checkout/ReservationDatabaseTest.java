package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.contract.ContractValidator;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reservations on Postgres: the schema, stock held and given back, one active per user. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@Import(CheckoutSupport.JwtConfiguration.class)
class ReservationDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("delete from checkout.reservations").update();
    jdbc.sql("delete from product.flash_sales").update();
    jdbc.sql("delete from product.products").update();
    jdbc.sql("delete from product.categories").update();
    jdbc.sql("insert into product.categories (slug, name) values ('c', 'C')").update();
  }

  private void product(String sku, long price, int stock) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into product.products (id, slug, name, brand, category_slug)"
                + " values (?, ?, ?, 'B', 'c')")
        .params(id, "slug-" + sku, "Name " + sku)
        .update();
    jdbc.sql("insert into product.variants (sku, product_id, position, price) values (?, ?, 1, ?)")
        .params(sku, id, price)
        .update();
    jdbc.sql("insert into product.stock (sku, quantity) values (?, ?)").params(sku, stock).update();
  }

  private void saleOf(String sku, long price, int saleStock) {
    UUID sale = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.sql("insert into product.flash_sales (id, starts_at, ends_at) values (?, ?, ?)")
        .params(sale, Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now.plusSeconds(3600)))
        .update();
    jdbc.sql(
            "insert into product.flash_sale_items (sale_id, sku, sale_price, sale_stock)"
                + " values (?, ?, ?, ?)")
        .params(sale, sku, price, saleStock)
        .update();
  }

  private int stock(String sku) {
    return jdbc.sql("select quantity from product.stock where sku = ?")
        .param(sku)
        .query(Integer.class)
        .single();
  }

  private int sold(String sku) {
    return jdbc.sql("select sold from product.flash_sale_items where sku = ?")
        .param(sku)
        .query(Integer.class)
        .single();
  }

  private String status(String id) {
    return jdbc.sql("select status from checkout.reservations where id = ?")
        .param(UUID.fromString(id))
        .query(String.class)
        .single();
  }

  private int activeCount(String userId) {
    return jdbc.sql(
            "select count(*) from checkout.reservations where user_id = ? and status = 'ACTIVE'")
        .param(UUID.fromString(userId))
        .query(Integer.class)
        .single();
  }

  private MockHttpServletResponse reserve(String userId, String sku, int quantity)
      throws Exception {
    return mvc.perform(
            post("/api/checkout/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + CheckoutSupport.token(userId, "GOLD"))
                .content(
                    "{\"items\": [{\"sku\": \"" + sku + "\", \"quantity\": " + quantity + "}]}"))
        .andReturn()
        .getResponse();
  }

  private static JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void theCheckoutSchemaIsMigratedWithItsTables() {
    List<String> tables =
        jdbc.sql("select table_name from information_schema.tables where table_schema = 'checkout'")
            .query(String.class)
            .list();
    assertThat(tables).contains("reservations", "reservation_items", "flyway_schema_history");
    List<String> columns =
        jdbc.sql(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'checkout' and table_name = 'reservations'")
            .query(String.class)
            .list();
    assertThat(columns)
        .containsExactlyInAnyOrder(
            "id", "user_id", "status", "created_at", "expires_at", "released_at", "restocked_at");
  }

  @Test
  void postHoldsTheStockAndStoresTheReservation() throws Exception {
    product("SKU-1", 890_000, 10);
    MockHttpServletResponse response = reserve(CheckoutSupport.USER, "SKU-1", 3);
    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(ContractValidator.core().validate(response, "createCheckoutReservation")).isEmpty();
    JsonNode json = json(response);
    assertThat(json.get("items").get(0).get("unitPrice").get("amount").asLong()).isEqualTo(845_500);
    assertThat(stock("SKU-1")).isEqualTo(7);
    assertThat(status(json.get("id").asString())).isEqualTo("ACTIVE");
    Instant created =
        jdbc.sql("select created_at from checkout.reservations where id = ?")
            .param(UUID.fromString(json.get("id").asString()))
            .query((rs, n) -> rs.getTimestamp(1).toInstant())
            .single();
    assertThat(Instant.parse(json.get("expiresAt").asString())).isEqualTo(created.plusSeconds(600));
  }

  @Test
  void aNewReservationReleasesThePreviousOneAndItsStock() throws Exception {
    product("SKU-1", 100_000, 10);
    String first = json(reserve(CheckoutSupport.USER, "SKU-1", 4)).get("id").asString();
    assertThat(stock("SKU-1")).isEqualTo(6);

    String second = json(reserve(CheckoutSupport.USER, "SKU-1", 2)).get("id").asString();

    assertThat(status(first)).isEqualTo("RELEASED");
    assertThat(status(second)).isEqualTo("ACTIVE");
    assertThat(
            jdbc.sql("select released_at is not null from checkout.reservations where id = ?")
                .param(UUID.fromString(first))
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(stock("SKU-1")).isEqualTo(8);
    assertThat(activeCount(CheckoutSupport.USER)).isEqualTo(1);
  }

  @Test
  void theReplacementCanReuseTheStockOfThePreviousReservation() throws Exception {
    product("SKU-1", 100_000, 5);
    reserve(CheckoutSupport.USER, "SKU-1", 5);
    assertThat(stock("SKU-1")).isZero();

    MockHttpServletResponse again = reserve(CheckoutSupport.USER, "SKU-1", 5);

    assertThat(again.getStatus()).isEqualTo(201);
    assertThat(stock("SKU-1")).isZero();
  }

  @Test
  void aShortLineKeepsThePreviousReservationActiveWithItsStockHeld() throws Exception {
    product("SKU-1", 100_000, 10);
    product("SKU-2", 100_000, 1);
    String first = json(reserve(CheckoutSupport.USER, "SKU-1", 4)).get("id").asString();

    MockHttpServletResponse response = reserve(CheckoutSupport.USER, "SKU-2", 2);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(ContractValidator.core().validate(response, "createCheckoutReservation")).isEmpty();
    assertThat(json(response).get("code").asString()).isEqualTo("CHECKOUT_OUT_OF_STOCK");
    assertThat(status(first)).isEqualTo("ACTIVE");
    assertThat(stock("SKU-1")).isEqualTo(6);
    assertThat(stock("SKU-2")).isEqualTo(1);
    assertThat(jdbc.sql("select count(*) from checkout.reservations").query(Integer.class).single())
        .isEqualTo(1);
  }

  @Test
  void flashSaleStockIsGivenBackWhenAReservationIsReplaced() throws Exception {
    product("SKU-1", 100_000, 10);
    saleOf("SKU-1", 60_000, 5);
    reserve(CheckoutSupport.USER, "SKU-1", 3);
    assertThat(sold("SKU-1")).isEqualTo(3);

    reserve(CheckoutSupport.USER, "SKU-1", 1);

    assertThat(sold("SKU-1")).isEqualTo(1);
    assertThat(stock("SKU-1")).isEqualTo(9);
  }

  @Test
  void twoUsersEachKeepTheirOwnActiveReservation() throws Exception {
    product("SKU-1", 100_000, 10);
    reserve(CheckoutSupport.USER, "SKU-1", 1);
    reserve(CheckoutSupport.OTHER_USER, "SKU-1", 1);
    assertThat(activeCount(CheckoutSupport.USER)).isEqualTo(1);
    assertThat(activeCount(CheckoutSupport.OTHER_USER)).isEqualTo(1);
    assertThat(stock("SKU-1")).isEqualTo(8);
  }

  @Test
  void theDatabaseRefusesASecondActiveReservationOfOneUser() {
    UUID user = UUID.fromString(CheckoutSupport.USER);
    String insert =
        "insert into checkout.reservations (id, user_id, status, created_at, expires_at)"
            + " values (?, ?, 'ACTIVE', now(), now() + interval '10 minutes')";
    jdbc.sql(insert).params(UUID.randomUUID(), user).update();
    assertThatThrownBy(() -> jdbc.sql(insert).params(UUID.randomUUID(), user).update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void parallelRequestsOfOneUserLeaveOneActiveReservationAndConsistentStock() throws Exception {
    product("SKU-1", 100_000, 100);
    int callers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> statuses = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      statuses.add(
          pool.submit(() -> {
            start.await();
            return reserve(CheckoutSupport.USER, "SKU-1", 5).getStatus();
          }));
    }
    start.countDown();
    for (Future<Integer> status : statuses) {
      assertThat(status.get(30, TimeUnit.SECONDS)).isEqualTo(201);
    }
    pool.shutdown();

    assertThat(activeCount(CheckoutSupport.USER)).isEqualTo(1);
    assertThat(stock("SKU-1")).isEqualTo(95);
  }
}
