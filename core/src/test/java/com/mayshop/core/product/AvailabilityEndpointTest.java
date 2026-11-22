package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.product.AvailabilitySupport.InMemoryAvailability;
import com.mayshop.core.product.PriceRule.SalePrice;
import java.nio.charset.StandardCharsets;
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

/** {@code getProductAvailability} against an in-memory store, default threshold 50. */
@SpringBootTest(properties = ProductApiSupport.DB_FREE_EXCLUDES)
@AutoConfigureMockMvc
@Import(AvailabilitySupport.Configuration.class)
class AvailabilityEndpointTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID PRODUCT = new UUID(0, 7);
  private static final String CACHE = "public, max-age=5, stale-while-revalidate=10";

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryAvailability store;

  @BeforeEach
  void reset() {
    store.clear();
  }

  private MockHttpServletResponse call(String sku) throws Exception {
    return mvc.perform(
            get("/api/products/" + sku + "/availability").accept(MediaType.APPLICATION_JSON))
        .andReturn()
        .getResponse();
  }

  private static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private void stock(int quantity) {
    store.put(PRODUCT, "SKU-A", 1_290_000, quantity, List.of());
  }

  @Test
  void outOfStockAtZeroHasNoApproxQuantity() throws Exception {
    stock(0);
    JsonNode json = body(call("SKU-A"));
    assertThat(json.get("stockLevel").asString()).isEqualTo("OUT_OF_STOCK");
    assertThat(json.has("approxQuantity")).isFalse();
  }

  @Test
  void lowAtOneAndAtTheThresholdOfFifty() throws Exception {
    stock(1);
    JsonNode one = body(call("SKU-A"));
    assertThat(one.get("stockLevel").asString()).isEqualTo("LOW");
    assertThat(one.get("approxQuantity").asInt()).isEqualTo(1);

    stock(50);
    JsonNode fifty = body(call("SKU-A"));
    assertThat(fifty.get("stockLevel").asString()).isEqualTo("LOW");
    assertThat(fifty.get("approxQuantity").asInt()).isEqualTo(50);
  }

  @Test
  void inStockAboveTheThresholdHasNoApproxQuantity() throws Exception {
    stock(51);
    JsonNode json = body(call("SKU-A"));
    assertThat(json.get("stockLevel").asString()).isEqualTo("IN_STOCK");
    assertThat(json.has("approxQuantity")).isFalse();
  }

  @Test
  void approxQuantityIsRoundedDownToATenWithMinimumOne() throws Exception {
    int[][] cases = {{40, 40}, {47, 40}, {9, 1}, {1, 1}};
    for (int[] c : cases) {
      stock(c[0]);
      assertThat(body(call("SKU-A")).get("approxQuantity").asInt()).isEqualTo(c[1]);
    }
  }

  @Test
  void priceIsTheSalePriceWhileTheSaleWindowContainsNow() throws Exception {
    store.put(
        PRODUCT,
        "SKU-A",
        1_290_000,
        40,
        List.of(
            new SalePrice(
                890_000,
                AvailabilitySupport.NOW.minusSeconds(3600),
                AvailabilitySupport.NOW.plusSeconds(7200))));

    assertThat(body(call("SKU-A")).get("price").get("amount").asLong()).isEqualTo(890_000);
  }

  @Test
  void priceIsTheVariantPriceWhenTheSaleIsOver() throws Exception {
    store.put(
        PRODUCT,
        "SKU-A",
        1_290_000,
        40,
        List.of(
            new SalePrice(
                890_000,
                AvailabilitySupport.NOW.minusSeconds(7200),
                AvailabilitySupport.NOW.minusSeconds(3600))));

    assertThat(body(call("SKU-A")).get("price").get("amount").asLong()).isEqualTo(1_290_000);
  }

  @Test
  void successCarriesCacheHeadersAndMatchesTheContract() throws Exception {
    stock(40);
    var response = call("SKU-A");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo(CACHE);
    assertThat(response.getHeader("x-cache-tags")).isEqualTo("product-" + PRODUCT);
    ContractValidator.core().assertValid(response, "getProductAvailability");
  }

  @Test
  void unknownSkuIsProductNotFoundWithNoStoreAndMatchesTheContract() throws Exception {
    var response = call("SKU-MISSING");

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(body(response).get("code").asString()).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    ContractValidator.core().assertValid(response, "getProductAvailability");
  }
}
