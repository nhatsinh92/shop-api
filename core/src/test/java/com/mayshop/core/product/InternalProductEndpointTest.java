package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.product.AvailabilitySupport.InMemoryAvailability;
import com.mayshop.core.product.persistence.ProductUpdateStore.Variant;
import com.mayshop.core.product.update.FakeUpdateStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** {@code updateInternalProduct} against in-memory stores, token {@code secret}. */
@SpringBootTest(
    properties = {
      ProductApiSupport.DB_FREE_EXCLUDES,
      "product.internal.api-token=secret",
      "spring.modulith.events.externalization.enabled=false"
    })
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import({AvailabilitySupport.Configuration.class, InternalProductEndpointTest.Stores.class})
class InternalProductEndpointTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID PRODUCT = new UUID(0, 7);

  static class Stores {
    @Bean
    @Primary
    FakeUpdateStore fakeUpdateStore(InMemoryAvailability availability) {
      FakeUpdateStore store = new FakeUpdateStore();
      store.onUpdate(w -> availability.put(PRODUCT, w.sku(), w.price(), w.stock(), List.of()));
      return store;
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private FakeUpdateStore store;
  @Autowired private InMemoryAvailability availability;
  @Autowired private ApplicationEvents events;

  @BeforeEach
  void reset() {
    store.clear();
    availability.clear();
    store.put("SKU-A", new Variant(PRODUCT, "slug-a", 1_000_000, 2_000_000L, 100));
    availability.put(PRODUCT, "SKU-A", 1_000_000, 100, List.of());
  }

  private MockHttpServletResponse patchSku(String sku, String token, String body) throws Exception {
    var request =
        patch("/internal/products/" + sku)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(body);
    if (token != null) {
      request.header("X-Internal-Token", token);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private long published() {
    return events.stream(ProductUpdated.class).count();
  }

  private static String code(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8))
        .get("code")
        .asString();
  }

  @Test
  void missingTokenIs404AndChangesNothing() throws Exception {
    var response = patchSku("SKU-A", null, "{\"stock\": 5}");

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(code(response)).isEqualTo("PRODUCT_NOT_FOUND");
    ContractValidator.core().assertValid(response, "updateInternalProduct");
    assertThat(store.updates).isZero();
    assertThat(published()).isZero();
  }

  @Test
  void wrongTokenIs404AndChangesNothing() throws Exception {
    for (String wrong : new String[] {"nope", "secre", "secret ", "SECRET", ""}) {
      var response = patchSku("SKU-A", wrong, "{\"stock\": 5}");
      assertThat(response.getStatus()).as("token '%s'", wrong).isEqualTo(404);
      ContractValidator.core().assertValid(response, "updateInternalProduct");
    }
    assertThat(store.updates).isZero();
  }

  @Test
  void aWrongTokenWithAnInvalidBodyIsStill404NotAValidationError() throws Exception {
    assertThat(patchSku("SKU-A", "nope", "not json").getStatus()).isEqualTo(404);
    assertThat(patchSku("SKU-A", "nope", "{}").getStatus()).isEqualTo(404);
  }

  @Test
  void wrongTokenAndUnknownSkuAreIndistinguishable() throws Exception {
    var wrongToken = patchSku("SKU-A", "nope", "{\"stock\": 5}");
    var unknownSku = patchSku("SKU-NOPE", "secret", "{\"stock\": 5}");

    assertThat(wrongToken.getStatus()).isEqualTo(unknownSku.getStatus());
    assertThat(code(wrongToken)).isEqualTo(code(unknownSku));
  }

  @Test
  void validTokenAndStockChangeAnswersTheUpdatedAvailability() throws Exception {
    var response = patchSku("SKU-A", "secret", "{\"stock\": 20}");

    assertThat(response.getStatus()).isEqualTo(200);
    ContractValidator.core().assertValid(response, "updateInternalProduct");
    var json = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    assertThat(json.get("stockLevel").asString()).isEqualTo("LOW");
    assertThat(json.get("approxQuantity").asInt()).isEqualTo(20);
    assertThat(published()).isEqualTo(1);
  }

  @Test
  void priceChangeAnswersTheNewPrice() throws Exception {
    var response =
        patchSku(
            "SKU-A",
            "secret",
            "{\"price\":{\"amount\":790000,\"currency\":\"VND\"},"
                + "\"listPrice\":{\"amount\":1790000,\"currency\":\"VND\"}}");

    assertThat(response.getStatus()).isEqualTo(200);
    ContractValidator.core().assertValid(response, "updateInternalProduct");
    assertThat(
            JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .get("price")
                .get("amount")
                .asLong())
        .isEqualTo(790_000);
    assertThat(published()).isEqualTo(1);
  }

  @Test
  void aRequestThatChangesNothingIs200WithoutAnEvent() throws Exception {
    var response = patchSku("SKU-A", "secret", "{\"stock\": 100}");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(published()).isZero();
  }

  @Test
  void invalidBodiesAre400WithValidationFailed() throws Exception {
    String[] bodies = {
      "",
      "not json",
      "[]",
      "{}",
      "{\"unknown\": 1}",
      "{\"stock\": -1}",
      "{\"stock\": 1.5}",
      "{\"stock\": \"5\"}",
      "{\"price\": 5}",
      "{\"price\": {\"amount\": 0, \"currency\": \"VND\"}}",
      "{\"price\": {\"amount\": 100, \"currency\": \"USD\"}}",
      "{\"listPrice\": {\"amount\": 1.5, \"currency\": \"VND\"}}"
    };
    for (String body : bodies) {
      var response = patchSku("SKU-A", "secret", body);
      assertThat(response.getStatus()).as("body '%s'", body).isEqualTo(400);
      assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
      ContractValidator.core().assertValid(response, "updateInternalProduct");
    }
    assertThat(published()).isZero();
  }

  @Test
  void unknownSkuWithTheRightTokenIs404() throws Exception {
    var response = patchSku("SKU-NOPE", "secret", "{\"stock\": 5}");

    assertThat(response.getStatus()).isEqualTo(404);
    ContractValidator.core().assertValid(response, "updateInternalProduct");
  }

  @Test
  void listPriceNotAbovePriceIs422() throws Exception {
    var response =
        patchSku("SKU-A", "secret", "{\"listPrice\":{\"amount\":1000000,\"currency\":\"VND\"}}");

    assertThat(response.getStatus()).isEqualTo(422);
    assertThat(code(response)).isEqualTo("PRODUCT_UPDATE_INVALID");
    ContractValidator.core().assertValid(response, "updateInternalProduct");
    assertThat(store.updates).isZero();
    assertThat(published()).isZero();
  }

  @Test
  void theInternalPathNeedsNoJwt() throws Exception {
    // No Authorization header anywhere above; the auth chain would answer 401.
    assertThat(patchSku("SKU-A", "secret", "{\"stock\": 99}").getStatus()).isEqualTo(200);
  }

  @Test
  void otherPathsKeepRequiringAToken() throws Exception {
    var response =
        mvc.perform(patch("/api/products/SKU-A").contentType(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(401);
  }
}
