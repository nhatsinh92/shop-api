package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.checkout.persistence.ReservationStore;
import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.product.StockHolds;
import com.mayshop.core.product.StockHolds.HeldLine;
import com.mayshop.core.product.StockHolds.HoldResult;
import com.mayshop.core.product.StockHolds.ShortLine;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code createCheckoutReservation} and {@code getCheckoutReservation} over MockMvc without a
 * database: an in-memory store, a mocked {@link StockHolds} and a fixed clock.
 */
@SpringBootTest(
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
            + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
            + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
@Import({CheckoutSupport.JwtConfiguration.class, ReservationEndpointTest.Doubles.class})
class ReservationEndpointTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-12-12T03:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryReservationStore store;
  @MockitoBean private StockHolds holds;

  @TestConfiguration(proxyBeanMethods = false)
  static class Doubles {

    @Bean
    @Primary
    InMemoryReservationStore inMemoryReservationStore() {
      return new InMemoryReservationStore();
    }

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    // The JDBC store is still created; it never runs because the fake is primary.
    @Bean
    JdbcClient jdbcClient() {
      return Mockito.mock(JdbcClient.class);
    }
  }

  @BeforeEach
  void reset() {
    store.clear();
    holdSucceeds(held("SKU-A", 2, 845_500, 890_000));
  }

  private static HeldLine held(String sku, int quantity, long unit, long base) {
    return new HeldLine(
        sku,
        quantity,
        unit,
        base,
        false,
        "Tai nghe " + sku,
        "Màu: Đen",
        "https://cdn.example.com/" + sku + ".jpg");
  }

  private void holdSucceeds(HeldLine... lines) {
    when(holds.hold(anyCollection(), any(), any()))
        .thenReturn(new HoldResult(List.of(lines), List.of()));
  }

  private static String body(String items) {
    return "{\"items\": " + items + "}";
  }

  private static final String ONE_LINE = body("[{\"sku\": \"SKU-A\", \"quantity\": 2}]");

  private MockHttpServletResponse create(String json, String token) throws Exception {
    var request =
        post("/api/checkout/reservations")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(json);
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse read(String id, String token) throws Exception {
    var request = get("/api/checkout/reservations/" + id).accept(MediaType.APPLICATION_JSON);
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private static String user() {
    return CheckoutSupport.token(CheckoutSupport.USER, "GOLD");
  }

  private static JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private static boolean valid(MockHttpServletResponse response, String operationId) {
    return ContractValidator.core().validate(response, operationId).isEmpty();
  }

  @Test
  void postAnswers201WithAnActiveReservationHeldForTenMinutes() throws Exception {
    MockHttpServletResponse response = create(ONE_LINE, user());
    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode json = json(response);
    assertThat(json.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(Instant.parse(json.get("expiresAt").asString())).isEqualTo(NOW.plusSeconds(600));
    JsonNode line = json.get("items").get(0);
    assertThat(line.get("sku").asString()).isEqualTo("SKU-A");
    assertThat(line.get("name").asString()).isEqualTo("Tai nghe SKU-A");
    assertThat(line.get("imageUrl").asString()).isEqualTo("https://cdn.example.com/SKU-A.jpg");
    assertThat(line.get("variantLabel").asString()).isEqualTo("Màu: Đen");
    assertThat(line.get("unitPrice").get("amount").asLong()).isEqualTo(845_500);
    assertThat(line.get("lineTotal").get("amount").asLong()).isEqualTo(1_691_000);
    assertThat(json.get("subtotal").get("amount").asLong()).isEqualTo(1_691_000);
  }

  @Test
  void createReservationResponseMatchesTheContract() throws Exception {
    assertThat(valid(create(ONE_LINE, user()), "createCheckoutReservation")).isTrue();
  }

  @Test
  void aSingleVariantLineOmitsTheVariantLabel() throws Exception {
    holdSucceeds(
        new HeldLine("SKU-A", 2, 100, 100, false, "N", null, "https://cdn.example.com/a.jpg"));
    MockHttpServletResponse response = create(ONE_LINE, user());
    assertThat(json(response).get("items").get(0).has("variantLabel")).isFalse();
    assertThat(valid(response, "createCheckoutReservation")).isTrue();
  }

  @Test
  void responsesAreNotStored() throws Exception {
    assertThat(create(ONE_LINE, user()).getHeader("Cache-Control")).contains("no-store");
    assertThat(create("{}", user()).getHeader("Cache-Control")).contains("no-store");
  }

  @Test
  void priceChangedIsFalseWhenNoExpectedPriceIsGiven() throws Exception {
    assertThat(json(create(ONE_LINE, user())).get("items").get(0).get("priceChanged").asBoolean())
        .isFalse();
  }

  @Test
  void priceChangedIsFalseWhenOnlyTheMemberDiscountDiffers() throws Exception {
    String request =
        body(
            "[{\"sku\": \"SKU-A\", \"quantity\": 2,"
                + " \"expectedUnitPrice\": {\"amount\": 890000, \"currency\": \"VND\"}}]");
    JsonNode line = json(create(request, user())).get("items").get(0);
    assertThat(line.get("priceChanged").asBoolean()).isFalse();
    assertThat(line.get("unitPrice").get("amount").asLong()).isEqualTo(845_500);
  }

  @Test
  void priceChangedIsTrueWhenTheExpectedPriceDiffersFromTheCurrentPrice() throws Exception {
    String request =
        body(
            "[{\"sku\": \"SKU-A\", \"quantity\": 2,"
                + " \"expectedUnitPrice\": {\"amount\": 950000, \"currency\": \"VND\"}}]");
    MockHttpServletResponse response = create(request, user());
    assertThat(json(response).get("items").get(0).get("priceChanged").asBoolean()).isTrue();
    assertThat(valid(response, "createCheckoutReservation")).isTrue();
  }

  @Test
  void aUserNeverHasTwoActiveReservations() throws Exception {
    String first = json(create(ONE_LINE, user())).get("id").asString();
    String second = json(create(ONE_LINE, user())).get("id").asString();

    assertThat(json(read(first, user())).get("status").asString()).isEqualTo("RELEASED");
    assertThat(json(read(second, user())).get("status").asString()).isEqualTo("ACTIVE");
    assertThat(store.all().stream().filter(r -> r.status() == Reservation.Status.ACTIVE).toList())
        .hasSize(1);
  }

  @Test
  void aShortLineIsAnswered409OutOfStockListingEveryShortLine() throws Exception {
    when(holds.hold(anyCollection(), any(), any()))
        .thenReturn(
            new HoldResult(
                List.of(), List.of(new ShortLine("SKU-A", 2, 1), new ShortLine("SKU-B", 1, 0))));
    MockHttpServletResponse response = create(ONE_LINE, user());
    assertThat(response.getStatus()).isEqualTo(409);
    JsonNode json = json(response);
    assertThat(json.get("code").asString()).isEqualTo("CHECKOUT_OUT_OF_STOCK");
    assertThat(json.get("unavailable")).hasSize(2);
    assertThat(json.get("unavailable").get(0).get("available").asInt()).isEqualTo(1);
    assertThat(json.get("traceId").asString()).isNotBlank();
    assertThat(valid(response, "createCheckoutReservation")).isTrue();
    assertThat(store.all()).isEmpty();
  }

  @Test
  void duplicateSkusAreAnswered400ValidationFailed() throws Exception {
    MockHttpServletResponse response =
        create(
            body("[{\"sku\": \"SKU-A\", \"quantity\": 1}, {\"sku\": \"SKU-A\", \"quantity\": 2}]"),
            user());
    assertThat(response.getStatus()).isEqualTo(400);
    JsonNode json = json(response);
    assertThat(json.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(json.get("errors").get(0).get("field").asString()).isEqualTo("items[1].sku");
    assertThat(valid(response, "createCheckoutReservation")).isTrue();
    assertThat(store.all()).isEmpty();
  }

  @Test
  void schemaViolationsAreAnswered400ValidationFailed() throws Exception {
    List<String> bad =
        List.of(
            "",
            "not json",
            "[]",
            "{}",
            body("[]"),
            body("{}"),
            body("[1]"),
            body("[{\"quantity\": 1}]"),
            body("[{\"sku\": \"\", \"quantity\": 1}]"),
            body("[{\"sku\": 5, \"quantity\": 1}]"),
            body("[{\"sku\": \"A\"}]"),
            body("[{\"sku\": \"A\", \"quantity\": 0}]"),
            body("[{\"sku\": \"A\", \"quantity\": 100}]"),
            body("[{\"sku\": \"A\", \"quantity\": 1.5}]"),
            body("[{\"sku\": \"A\", \"quantity\": \"2\"}]"),
            body("[{\"sku\": \"A\", \"quantity\": 1, \"expectedUnitPrice\": 5}]"),
            body(
                "[{\"sku\": \"A\", \"quantity\": 1, \"expectedUnitPrice\": {\"amount\": -1, \"currency\": \"VND\"}}]"),
            body(
                "[{\"sku\": \"A\", \"quantity\": 1, \"expectedUnitPrice\": {\"amount\": 1, \"currency\": \"USD\"}}]"),
            body(
                "["
                    + java.util.stream.IntStream.range(0, 51)
                        .mapToObj(i -> "{\"sku\": \"S" + i + "\", \"quantity\": 1}")
                        .collect(java.util.stream.Collectors.joining(","))
                    + "]"));
    for (String request : bad) {
      MockHttpServletResponse response = create(request, user());
      assertThat(response.getStatus()).as(request).isEqualTo(400);
      assertThat(json(response).get("code").asString()).as(request).isEqualTo("VALIDATION_FAILED");
      assertThat(valid(response, "createCheckoutReservation")).as(request).isTrue();
    }
    assertThat(store.all()).isEmpty();
  }

  @Test
  void noTokenIsAnswered401ForBothOperations() throws Exception {
    MockHttpServletResponse created = create(ONE_LINE, null);
    assertThat(created.getStatus()).isEqualTo(401);
    assertThat(json(created).get("code").asString()).isEqualTo("AUTH_UNAUTHENTICATED");
    assertThat(valid(created, "createCheckoutReservation")).isTrue();

    MockHttpServletResponse fetched = read(UUID.randomUUID().toString(), null);
    assertThat(fetched.getStatus()).isEqualTo(401);
    assertThat(valid(fetched, "getCheckoutReservation")).isTrue();
  }

  @Test
  void getReturnsTheReservationOfTheCaller() throws Exception {
    JsonNode created = json(create(ONE_LINE, user()));
    MockHttpServletResponse response = read(created.get("id").asString(), user());
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(json(response)).isEqualTo(created);
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(valid(response, "getCheckoutReservation")).isTrue();
  }

  @Test
  void anotherUsersReservationIsAnswered404() throws Exception {
    String id = json(create(ONE_LINE, user())).get("id").asString();
    MockHttpServletResponse response =
        read(id, CheckoutSupport.token(CheckoutSupport.OTHER_USER, "STANDARD"));
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(json(response).get("code").asString()).isEqualTo("CHECKOUT_RESERVATION_NOT_FOUND");
    assertThat(valid(response, "getCheckoutReservation")).isTrue();
  }

  @Test
  void anUnknownIdIsAnswered404() throws Exception {
    MockHttpServletResponse response = read(UUID.randomUUID().toString(), user());
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(json(response).get("code").asString()).isEqualTo("CHECKOUT_RESERVATION_NOT_FOUND");
    assertThat(valid(response, "getCheckoutReservation")).isTrue();
  }

  @Test
  void anIdThatIsNotAUuidIsAnswered404() throws Exception {
    MockHttpServletResponse response = read("nope", user());
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(valid(response, "getCheckoutReservation")).isTrue();
  }

  @Test
  void theStoreInTheContextIsTheFake() {
    assertThat((ReservationStore) store).isInstanceOf(InMemoryReservationStore.class);
  }
}
