package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.product.AvailabilitySupport.InMemoryAvailability;
import com.mayshop.core.product.PriceRule.SalePrice;
import com.mayshop.core.product.ProductApiSupport.InMemoryCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code getMemberPrice} with default discounts; the clock is fixed at 2026-12-12T10:00Z. */
@SpringBootTest(properties = ProductApiSupport.DB_FREE_EXCLUDES)
@AutoConfigureMockMvc
@Import(MemberPriceSupport.Configuration.class)
class MemberPriceEndpointTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryCatalog catalog;
  @Autowired private InMemoryAvailability availability;

  @BeforeEach
  void seed() {
    catalog.clear();
    availability.clear();
    addProduct(1, 890_000, List.of());
  }

  private void addProduct(int n, long price, List<SalePrice> sales) {
    catalog.add(ProductApiSupport.product(n, LONG_AGO, price, null, 1), "dien-tu");
    availability.put(new UUID(0, n), "SKU-" + n + "-1", price, 10, sales);
  }

  private MockHttpServletResponse call(String slug, String token) throws Exception {
    var request = get("/api/products/" + slug + "/member-price").accept(MediaType.APPLICATION_JSON);
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void goldMemberGetsFivePercentOffRoundedDownToFiveHundred() throws Exception {
    MockHttpServletResponse response = call("product-1", MemberPriceSupport.token("GOLD"));
    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode json = body(response);
    assertThat(json.get("sku").asString()).isEqualTo("SKU-1-1");
    assertThat(json.get("memberTier").asString()).isEqualTo("GOLD");
    assertThat(json.get("price").get("amount").asLong()).isEqualTo(845_500);
    assertThat(json.get("price").get("currency").asString()).isEqualTo("VND");
  }

  @Test
  void getMemberPriceMatchesTheContractFor200() throws Exception {
    MockHttpServletResponse response = call("product-1", MemberPriceSupport.token("GOLD"));
    assertThat(ContractValidator.core().validate(response, "getMemberPrice")).isEmpty();
  }

  @Test
  void successResponseIsPrivateAndNotStored() throws Exception {
    MockHttpServletResponse response = call("product-1", MemberPriceSupport.token("SILVER"));
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
  }

  @Test
  void theFlashSalePriceIsTheBaseWhileASaleRuns() throws Exception {
    addProduct(
        2,
        1_000_000,
        List.of(
            new SalePrice(
                845_500,
                Instant.parse("2026-12-12T00:00:00Z"),
                Instant.parse("2026-12-13T00:00:00Z"))));
    JsonNode json = body(call("product-2", MemberPriceSupport.token("GOLD")));
    assertThat(json.get("price").get("amount").asLong()).isEqualTo(803_000);
  }

  @Test
  void aSaleThatHasEndedDoesNotChangeTheBase() throws Exception {
    addProduct(
        3,
        890_000,
        List.of(
            new SalePrice(
                100_000,
                Instant.parse("2026-12-01T00:00:00Z"),
                Instant.parse("2026-12-02T00:00:00Z"))));
    JsonNode json = body(call("product-3", MemberPriceSupport.token("GOLD")));
    assertThat(json.get("price").get("amount").asLong()).isEqualTo(845_500);
  }

  @Test
  void standardTierPaysTheBasePrice() throws Exception {
    JsonNode json = body(call("product-1", MemberPriceSupport.token("STANDARD")));
    assertThat(json.get("memberTier").asString()).isEqualTo("STANDARD");
    assertThat(json.get("price").get("amount").asLong()).isEqualTo(890_000);
  }

  @Test
  void aMissingTierClaimIsTreatedAsStandard() throws Exception {
    JsonNode json = body(call("product-1", MemberPriceSupport.token(null)));
    assertThat(json.get("memberTier").asString()).isEqualTo("STANDARD");
    assertThat(json.get("price").get("amount").asLong()).isEqualTo(890_000);
  }

  @Test
  void anUnknownTierClaimIsTreatedAsStandard() throws Exception {
    JsonNode json = body(call("product-1", MemberPriceSupport.token("DIAMOND")));
    assertThat(json.get("memberTier").asString()).isEqualTo("STANDARD");
    assertThat(json.get("price").get("amount").asLong()).isEqualTo(890_000);
  }

  @Test
  void noTokenIsAnswered401AuthUnauthenticated() throws Exception {
    MockHttpServletResponse response = call("product-1", null);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(body(response).get("code").asString()).isEqualTo("AUTH_UNAUTHENTICATED");
    assertThat(ContractValidator.core().validate(response, "getMemberPrice")).isEmpty();
  }

  @Test
  void aTokenSignedByAnotherKeyIsAnswered401() throws Exception {
    MockHttpServletResponse response = call("product-1", "not-a-token");
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void anUnknownSlugIsAnswered404ProductNotFound() throws Exception {
    MockHttpServletResponse response = call("no-such-product", MemberPriceSupport.token("GOLD"));
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(body(response).get("code").asString()).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(ContractValidator.core().validate(response, "getMemberPrice")).isEmpty();
  }
}
