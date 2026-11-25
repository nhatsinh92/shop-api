package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.product.StockHolds;
import com.mayshop.core.product.StockHolds.HeldLine;
import com.mayshop.core.product.StockHolds.HoldResult;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code listShippingOptions} and {@code listPaymentMethods} over MockMvc without a database. */
@SpringBootTest(
    properties = {
      "checkout.payment.disabled-methods=CARD",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
@AutoConfigureMockMvc
@Import({CheckoutSupport.JwtConfiguration.class, OptionsEndpointTest.Doubles.class})
class OptionsEndpointTest {

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

    @Bean
    JdbcClient jdbcClient() {
      return Mockito.mock(JdbcClient.class);
    }
  }

  @BeforeEach
  void reset() {
    store.clear();
    when(holds.hold(anyCollection(), any(), any()))
        .thenReturn(
            new HoldResult(
                List.of(
                    new HeldLine(
                        "SKU-A", 1, 100, 100, false, "N", null, "https://cdn.example.com/a.jpg")),
                List.of()));
  }

  private static String user() {
    return CheckoutSupport.token(CheckoutSupport.USER, "GOLD");
  }

  private MockHttpServletResponse call(String uri, String token) throws Exception {
    MockHttpServletRequestBuilder request = get(uri).accept(MediaType.APPLICATION_JSON);
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private String reserve(String token) throws Exception {
    var response =
        mvc.perform(
                post("/api/checkout/reservations")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .content("{\"items\": [{\"sku\": \"SKU-A\", \"quantity\": 1}]}"))
            .andReturn()
            .getResponse();
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("id").asString();
  }

  private static JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private static boolean valid(MockHttpServletResponse response, String operationId) {
    return ContractValidator.core().validate(response, operationId).isEmpty();
  }

  @Test
  void shippingOptionsListStandardAndExpressMatchingTheArtboard() throws Exception {
    MockHttpServletResponse response = call("/api/checkout/shipping-options", user());
    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode items = json(response).get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("id").asString()).isEqualTo("STANDARD");
    assertThat(items.get(0).get("fee").get("amount").asLong()).isZero();
    assertThat(items.get(0).get("estimatedDelivery").asString()).isEqualTo("Nhận 13–14/12");
    assertThat(items.get(1).get("id").asString()).isEqualTo("EXPRESS_2H");
    assertThat(items.get(1).get("fee").get("amount").asLong()).isEqualTo(35_000);
    assertThat(items.get(1).get("estimatedDelivery").asString())
        .isEqualTo("Nhận trong hôm nay (nội thành)");
  }

  @Test
  void shippingOptionsResponseMatchesTheContractAndIsNotStored() throws Exception {
    MockHttpServletResponse response = call("/api/checkout/shipping-options", user());
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(valid(response, "listShippingOptions")).isTrue();
  }

  @Test
  void shippingOptionsWithTheCallersReservationAreTheSameList() throws Exception {
    String id = reserve(user());
    MockHttpServletResponse withId =
        call("/api/checkout/shipping-options?reservationId=" + id, user());
    assertThat(withId.getStatus()).isEqualTo(200);
    assertThat(json(withId)).isEqualTo(json(call("/api/checkout/shipping-options", user())));
    assertThat(valid(withId, "listShippingOptions")).isTrue();
  }

  @Test
  void shippingOptionsWithAnotherUsersReservationAreAnswered404() throws Exception {
    String id = reserve(user());
    MockHttpServletResponse response =
        call(
            "/api/checkout/shipping-options?reservationId=" + id,
            CheckoutSupport.token(CheckoutSupport.OTHER_USER, "STANDARD"));
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(json(response).get("code").asString()).isEqualTo("CHECKOUT_RESERVATION_NOT_FOUND");
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(valid(response, "listShippingOptions")).isTrue();
  }

  @Test
  void shippingOptionsWithAnUnknownReservationAreAnswered404() throws Exception {
    MockHttpServletResponse response =
        call("/api/checkout/shipping-options?reservationId=" + UUID.randomUUID(), user());
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(json(response).get("code").asString()).isEqualTo("CHECKOUT_RESERVATION_NOT_FOUND");
    assertThat(valid(response, "listShippingOptions")).isTrue();
  }

  @Test
  void shippingOptionsWithAReservationIdThatIsNotAUuidAreAnswered400() throws Exception {
    MockHttpServletResponse response =
        call("/api/checkout/shipping-options?reservationId=nope", user());
    assertThat(response.getStatus()).isEqualTo(400);
    JsonNode json = json(response);
    assertThat(json.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(json.get("errors").get(0).get("field").asString()).isEqualTo("reservationId");
    assertThat(valid(response, "listShippingOptions")).isTrue();
  }

  @Test
  void shippingOptionsWithoutATokenAreAnswered401() throws Exception {
    MockHttpServletResponse response = call("/api/checkout/shipping-options", null);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(valid(response, "listShippingOptions")).isTrue();
  }

  @Test
  void paymentMethodsListTheThreeMethodsWithOnlyTheDisabledOneUnavailable() throws Exception {
    MockHttpServletResponse response = call("/api/checkout/payment-methods", user());
    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode items = json(response).get("items");
    assertThat(items).hasSize(3);
    assertThat(items.get(0).get("id").asString()).isEqualTo("COD");
    assertThat(items.get(0).get("name").asString()).isEqualTo("Thanh toán khi nhận hàng (COD)");
    assertThat(items.get(0).get("available").asBoolean()).isTrue();
    assertThat(items.get(1).get("id").asString()).isEqualTo("EWALLET");
    assertThat(items.get(1).get("name").asString()).isEqualTo("Ví điện tử");
    assertThat(items.get(1).get("available").asBoolean()).isTrue();
    assertThat(items.get(2).get("id").asString()).isEqualTo("CARD");
    assertThat(items.get(2).get("name").asString()).isEqualTo("Thẻ ngân hàng / thẻ tín dụng");
    assertThat(items.get(2).get("available").asBoolean()).isFalse();
  }

  @Test
  void paymentMethodsResponseMatchesTheContractAndIsNotStored() throws Exception {
    MockHttpServletResponse response = call("/api/checkout/payment-methods", user());
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(valid(response, "listPaymentMethods")).isTrue();
  }

  @Test
  void paymentMethodsWithoutATokenAreAnswered401() throws Exception {
    MockHttpServletResponse response = call("/api/checkout/payment-methods", null);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(valid(response, "listPaymentMethods")).isTrue();
  }
}
