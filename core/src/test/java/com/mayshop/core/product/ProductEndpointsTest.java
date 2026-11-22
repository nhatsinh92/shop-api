package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.product.ProductApiSupport.InMemoryCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

/** {@code listProducts} and {@code getProduct} against an in-memory catalog. */
@SpringBootTest(properties = ProductApiSupport.DB_FREE_EXCLUDES)
@AutoConfigureMockMvc
@Import(ProductApiSupport.InMemoryCatalogConfiguration.class)
class ProductEndpointsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
  private static final String CATALOG_CACHE = "public, max-age=120, stale-while-revalidate=600";

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryCatalog catalog;

  @BeforeEach
  void seed() {
    catalog.clear();
    catalog.failWith(null);
    // Product 1 is the newest; products 4 and 5 share a creation time, so the id breaks the tie.
    catalog.add(
        ProductApiSupport.product(1, T0.plusSeconds(60), 1_290_000, 1_790_000L, 3), "am-thanh");
    catalog.add(ProductApiSupport.product(2, T0.plusSeconds(50), 249_000, null, 1), "gia-dung");
    catalog.add(ProductApiSupport.product(3, T0.plusSeconds(40), 459_000, 459_000L, 1), "gia-dung");
    catalog.add(ProductApiSupport.product(4, T0.plusSeconds(30), 100_000, 150_000L, 1), "am-thanh");
    catalog.add(ProductApiSupport.product(5, T0.plusSeconds(30), 100_000, null, 1), "am-thanh");
  }

  private MockHttpServletResponse call(String uri) throws Exception {
    return mvc.perform(get(uri).accept(MediaType.APPLICATION_JSON)).andReturn().getResponse();
  }

  private static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private static List<String> slugs(JsonNode page) {
    List<String> slugs = new ArrayList<>();
    page.get("items").forEach(item -> slugs.add(item.get("slug").asString()));
    return slugs;
  }

  @Test
  void listIsOrderedNewestFirstWithIdAsTieBreaker() throws Exception {
    var response = call("/api/products");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(slugs(body(response)))
        .containsExactly("product-1", "product-2", "product-3", "product-5", "product-4");
    assertThat(body(response).get("nextCursor").isNull()).isTrue();
  }

  @Test
  void pagingWithLimitTwoReturnsEveryProductExactlyOnce() throws Exception {
    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      var response = call("/api/products?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
      assertThat(response.getStatus()).isEqualTo(200);
      ContractValidator.core().assertValid(response, "listProducts");
      JsonNode page = body(response);
      seen.addAll(slugs(page));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
      pages++;
    } while (cursor != null);

    assertThat(seen)
        .containsExactly("product-1", "product-2", "product-3", "product-5", "product-4");
    assertThat(pages).isEqualTo(3);
  }

  @Test
  void nextCursorIsBase64UrlJsonOfTheLastItemsCreatedAtAndId() throws Exception {
    String cursor = body(call("/api/products?limit=1")).get("nextCursor").asString();

    JsonNode decoded =
        JSON.readTree(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
    assertThat(decoded.get("createdAt").asString()).isEqualTo(T0.plusSeconds(60).toString());
    assertThat(decoded.get("id").asString()).isEqualTo("00000000-0000-0000-0000-000000000001");
    assertThat(cursor).doesNotContain("=");
  }

  @Test
  void limitDefaultsToTwentyAndIsCappedByTheContractMaximum() throws Exception {
    assertThat(slugs(body(call("/api/products")))).hasSize(5);
    assertThat(call("/api/products?limit=50").getStatus()).isEqualTo(200);
    assertThat(call("/api/products?limit=51").getStatus()).isEqualTo(400);
  }

  @Test
  void invalidLimitIsAValidationErrorThatMatchesTheContract() throws Exception {
    for (String limit : List.of("0", "51", "-1", "abc", "")) {
      var response = call("/api/products?limit=" + limit);

      assertThat(response.getStatus()).as("limit=" + limit).isEqualTo(400);
      assertThat(body(response).get("code").asString()).isEqualTo("VALIDATION_FAILED");
      assertThat(body(response).get("errors").get(0).get("field").asString()).isEqualTo("limit");
      assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
      ContractValidator.core().assertValid(response, "listProducts");
    }
  }

  @Test
  void aCursorThatDoesNotDecodeIs400PaginationCursorInvalid() throws Exception {
    String notJson =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("nope".getBytes(StandardCharsets.UTF_8));
    String missingId =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"createdAt\":\"2026-11-20T10:00:00Z\"}".getBytes(StandardCharsets.UTF_8));
    String badId =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"createdAt\":\"2026-11-20T10:00:00Z\",\"id\":\"x\"}"
                    .getBytes(StandardCharsets.UTF_8));

    for (String cursor : List.of("!!!", notJson, missingId, badId)) {
      var response = call("/api/products?cursor=" + cursor);

      assertThat(response.getStatus()).as("cursor=" + cursor).isEqualTo(400);
      assertThat(body(response).get("code").asString()).isEqualTo("PAGINATION_CURSOR_INVALID");
      assertThat(response.getContentType()).startsWith("application/problem+json");
      assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
      ContractValidator.core().assertValid(response, "listProducts");
    }
  }

  @Test
  void categoryFilterKeepsOnlyThatCategoryAndAnUnknownOneIsAnEmptyPage() throws Exception {
    var filtered = call("/api/products?category=am-thanh");
    var unknown = call("/api/products?category=does-not-exist");

    assertThat(slugs(body(filtered))).containsExactly("product-1", "product-5", "product-4");
    assertThat(unknown.getStatus()).isEqualTo(200);
    assertThat(body(unknown).get("items")).isEmpty();
    assertThat(body(unknown).get("nextCursor").isNull()).isTrue();
    ContractValidator.core().assertValid(unknown, "listProducts");
  }

  @Test
  void summaryUsesTheLowestPositionVariantAndShowsListPriceOnlyWhenHigher() throws Exception {
    JsonNode items = body(call("/api/products")).get("items");

    JsonNode discounted = items.get(0);
    assertThat(discounted.get("sku").asString()).isEqualTo("SKU-1-1");
    assertThat(discounted.get("price").get("amount").asLong()).isEqualTo(1_290_000);
    assertThat(discounted.get("price").get("currency").asString()).isEqualTo("VND");
    assertThat(discounted.get("listPrice").get("amount").asLong()).isEqualTo(1_790_000);
    assertThat(discounted.get("badges").get(0).asString()).isEqualTo("DISCOUNT");

    JsonNode plain = items.get(1);
    assertThat(plain.has("listPrice")).isFalse();
    assertThat(plain.get("badges")).isEmpty();

    JsonNode equalPrices = items.get(2);
    assertThat(equalPrices.has("listPrice")).as("listPrice equal to price is omitted").isFalse();
    assertThat(equalPrices.get("badges")).isEmpty();
  }

  @Test
  void listCarriesTheCatalogCacheControlAndTheProductsTag() throws Exception {
    var response = call("/api/products");

    assertThat(response.getHeader("Cache-Control")).isEqualTo(CATALOG_CACHE);
    assertThat(response.getHeader("x-cache-tags")).isEqualTo("products");
    ContractValidator.core().assertValid(response, "listProducts");
  }

  @Test
  void detailReturnsVariantsImagesCategoryPathAndTheProductTag() throws Exception {
    var response = call("/api/products/product-1");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo(CATALOG_CACHE);
    assertThat(response.getHeader("x-cache-tags"))
        .isEqualTo("product-00000000-0000-0000-0000-000000000001");
    JsonNode detail = body(response);
    assertThat(detail.get("id").asString()).isEqualTo("00000000-0000-0000-0000-000000000001");
    assertThat(detail.get("sku").asString()).isEqualTo("SKU-1-1");
    assertThat(detail.get("variants")).hasSize(3);
    assertThat(detail.get("variants").get(2).get("options").get(0).get("value").asString())
        .isEqualTo("Color 3");
    assertThat(detail.get("images")).hasSize(1);
    assertThat(detail.get("category").get("slug").asString()).isEqualTo("am-thanh");
    assertThat(detail.get("category").get("path")).hasSize(2);
    assertThat(detail.get("category").get("path").get(0).get("slug").asString())
        .isEqualTo("dien-tu");
    ContractValidator.core().assertValid(response, "getProduct");
  }

  @Test
  void anUnknownSlugIs404ProductNotFoundWithNoStore() throws Exception {
    var response = call("/api/products/unknown");

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(body(response).get("code").asString()).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("x-cache-tags")).isNull();
    ContractValidator.core().assertValid(response, "getProduct");
  }

  @Test
  void anUnexpectedFailureIsAProblemResponseWithNoStore() throws Exception {
    catalog.failWith(new IllegalStateException("database down"));

    var response = call("/api/products");

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(body(response).get("code").asString()).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }
}
