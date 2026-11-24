package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.product.PriceRule.SalePrice;
import com.mayshop.core.product.persistence.AvailabilityQueries.AvailabilityRow;
import com.mayshop.core.product.persistence.CatalogQueries.OptionRow;
import com.mayshop.core.product.persistence.LookupQueries;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code lookupInternalProducts} against an in-memory store, token {@code secret}. */
@SpringBootTest(
    properties = {
      ProductApiSupport.DB_FREE_EXCLUDES,
      "product.internal.api-token=secret",
      "spring.modulith.events.externalization.enabled=false"
    })
@AutoConfigureMockMvc
@Import({AvailabilitySupport.Configuration.class, InternalProductLookupEndpointTest.Stores.class})
class InternalProductLookupEndpointTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID PRODUCT = new UUID(0, 7);

  static class FakeLookup implements LookupQueries {
    final Map<String, LookupRow> rows = new HashMap<>();
    final List<Collection<String>> calls = new ArrayList<>();

    @Override
    public List<LookupRow> findBySkus(Collection<String> skus) {
      calls.add(List.copyOf(skus));
      return skus.stream().map(rows::get).filter(r -> r != null).toList();
    }
  }

  static class Stores {
    @Bean
    @Primary
    FakeLookup fakeLookup() {
      return new FakeLookup();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private FakeLookup lookup;

  private static LookupQueries.LookupRow row(
      String sku,
      int variants,
      List<OptionRow> options,
      long price,
      Long list,
      int qty,
      List<SalePrice> sales) {
    return new LookupQueries.LookupRow(
        sku,
        "slug-" + sku.toLowerCase(),
        "Name " + sku,
        "https://cdn.example.com/" + sku + ".jpg",
        options,
        variants,
        list,
        new AvailabilityRow(PRODUCT, sku, price, qty, sales));
  }

  @BeforeEach
  void reset() {
    lookup.rows.clear();
    lookup.calls.clear();
    lookup.rows.put(
        "SKU-ANC-PRO-BLK",
        row(
            "SKU-ANC-PRO-BLK",
            3,
            List.of(new OptionRow("color", "Đen nhám")),
            1_290_000,
            1_790_000L,
            40,
            List.of()));
    lookup.rows.put(
        "SKU-TWO",
        row(
            "SKU-TWO",
            2,
            List.of(new OptionRow("color", "Đen"), new OptionRow("size", "L")),
            100_000,
            null,
            500,
            List.of()));
    lookup.rows.put("SKU-SOLO", row("SKU-SOLO", 1, List.of(), 100_000, 100_000L, 0, List.of()));
    // A sale running at NOW (2026-12-12T10:00Z) and one that already ended.
    lookup.rows.put(
        "SKU-SALE",
        row(
            "SKU-SALE",
            1,
            List.of(),
            500_000,
            800_000L,
            5,
            List.of(
                new SalePrice(
                    300_000,
                    AvailabilitySupport.NOW.minus(Duration.ofHours(1)),
                    AvailabilitySupport.NOW.plus(Duration.ofHours(1))),
                new SalePrice(
                    100_000,
                    AvailabilitySupport.NOW.minus(Duration.ofHours(5)),
                    AvailabilitySupport.NOW.minus(Duration.ofHours(4))))));
  }

  private MockHttpServletResponse lookup(String token, String body) throws Exception {
    var request =
        post("/internal/products/lookup")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(body);
    if (token != null) {
      request.header("X-Internal-Token", token);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private static JsonNode json(MockHttpServletResponse r) throws Exception {
    return JSON.readTree(r.getContentAsString(StandardCharsets.UTF_8));
  }

  private MockHttpServletResponse ok(String skusJson) throws Exception {
    var response = lookup("secret", "{\"skus\": " + skusJson + "}");
    assertThat(response.getStatus()).isEqualTo(200);
    ContractValidator.core().assertValid(response, "lookupInternalProducts");
    return response;
  }

  @Test
  void knownSkuAnswersEveryField() throws Exception {
    JsonNode item = json(ok("[\"SKU-ANC-PRO-BLK\"]")).get("items").get(0);

    assertThat(item.get("sku").asString()).isEqualTo("SKU-ANC-PRO-BLK");
    assertThat(item.get("slug").asString()).isEqualTo("slug-sku-anc-pro-blk");
    assertThat(item.get("name").asString()).isEqualTo("Name SKU-ANC-PRO-BLK");
    assertThat(item.get("imageUrl").asString())
        .isEqualTo("https://cdn.example.com/SKU-ANC-PRO-BLK.jpg");
    assertThat(item.get("price").get("amount").asLong()).isEqualTo(1_290_000);
    assertThat(item.get("listPrice").get("amount").asLong()).isEqualTo(1_790_000);
    assertThat(item.get("stockLevel").asString()).isEqualTo("LOW");
  }

  @Test
  void variantLabelJoinsTheOptionsAndIsAbsentForSingleVariantProducts() throws Exception {
    JsonNode items = json(ok("[\"SKU-ANC-PRO-BLK\", \"SKU-TWO\", \"SKU-SOLO\"]")).get("items");

    assertThat(items.get(0).get("variantLabel").asString()).isEqualTo("Màu: Đen nhám");
    assertThat(items.get(1).get("variantLabel").asString()).isEqualTo("Màu: Đen · Kích cỡ: L");
    assertThat(items.get(2).has("variantLabel")).isFalse();
  }

  @Test
  void priceIsTheSalePriceDuringARunningFlashSale() throws Exception {
    JsonNode item = json(ok("[\"SKU-SALE\"]")).get("items").get(0);

    assertThat(item.get("price").get("amount").asLong()).isEqualTo(300_000);
    assertThat(item.get("listPrice").get("amount").asLong()).isEqualTo(800_000);
  }

  @Test
  void listPriceIsAbsentUnlessHigherThanPrice() throws Exception {
    JsonNode items = json(ok("[\"SKU-SOLO\", \"SKU-TWO\"]")).get("items");

    assertThat(items.get(0).has("listPrice")).isFalse();
    assertThat(items.get(1).has("listPrice")).isFalse();
  }

  @Test
  void stockLevelUsesTheAvailabilityThresholds() throws Exception {
    JsonNode items = json(ok("[\"SKU-SOLO\", \"SKU-SALE\", \"SKU-TWO\"]")).get("items");

    assertThat(items.get(0).get("stockLevel").asString()).isEqualTo("OUT_OF_STOCK");
    assertThat(items.get(1).get("stockLevel").asString()).isEqualTo("LOW");
    assertThat(items.get(2).get("stockLevel").asString()).isEqualTo("IN_STOCK");
  }

  @Test
  void unknownSkusAreLeftOut() throws Exception {
    JsonNode items = json(ok("[\"SKU-NOPE\", \"SKU-SOLO\"]")).get("items");

    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("sku").asString()).isEqualTo("SKU-SOLO");
  }

  @Test
  void onlyUnknownSkusAnswer200WithNoItems() throws Exception {
    assertThat(json(ok("[\"SKU-NOPE\"]")).get("items")).isEmpty();
  }

  @Test
  void fiftyUniqueSkusAreAcceptedWithOneStoreCall() throws Exception {
    String skus =
        IntStream.rangeClosed(1, 50)
            .mapToObj(i -> "\"SKU-" + i + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));

    ok(skus);

    assertThat(lookup.calls).hasSize(1);
    assertThat(lookup.calls.get(0)).hasSize(50);
  }

  @Test
  void invalidBodiesAre400ValidationFailed() throws Exception {
    String fiftyOne =
        IntStream.rangeClosed(1, 51)
            .mapToObj(i -> "\"SKU-" + i + "\"")
            .collect(java.util.stream.Collectors.joining(",", "{\"skus\":[", "]}"));
    String[] bodies = {
      "{\"skus\": []}",
      fiftyOne,
      "{\"skus\": [\"SKU-A\", \"SKU-A\"]}",
      "{\"skus\": [\"\"]}",
      "{\"skus\": [\"bad sku!\"]}",
      "{\"skus\": [\"-A\"]}",
      "{\"skus\": [42]}",
      "{\"skus\": \"SKU-A\"}",
      "{}",
      "[]",
      "not json",
      ""
    };
    for (String body : bodies) {
      var response = lookup("secret", body);
      assertThat(response.getStatus()).as(body).isEqualTo(400);
      assertThat(json(response).get("code").asString()).as(body).isEqualTo("VALIDATION_FAILED");
      ContractValidator.core().assertValid(response, "lookupInternalProducts");
    }
    assertThat(lookup.calls).isEmpty();
  }

  @Test
  void missingOrWrongTokenIs404EvenWithAnInvalidBody() throws Exception {
    for (String token : new String[] {null, "", "nope", "secre", "SECRET"}) {
      for (String body : new String[] {"{\"skus\": [\"SKU-SOLO\"]}", "not json"}) {
        var response = lookup(token, body);
        assertThat(response.getStatus()).as("token '%s'", token).isEqualTo(404);
        ContractValidator.core().assertValid(response, "lookupInternalProducts");
      }
    }
    assertThat(lookup.calls).isEmpty();
  }
}
