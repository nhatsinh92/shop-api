package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.product.persistence.CatalogQueries.CardRow;
import com.mayshop.core.product.persistence.FlashSaleQueries;
import com.mayshop.core.product.persistence.FlashSaleQueries.ItemRow;
import com.mayshop.core.product.persistence.FlashSaleQueries.SaleRow;
import com.mayshop.core.product.persistence.RecommendationQueries;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code listRecommendations} against in-memory stores and a fixed clock. */
@SpringBootTest(properties = ProductApiSupport.DB_FREE_EXCLUDES)
@AutoConfigureMockMvc
@Import({
  ProductApiSupport.InMemoryCatalogConfiguration.class,
  RecommendationEndpointTest.Config.class
})
class RecommendationEndpointTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-12-12T10:00:00Z");

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    Related related() {
      return new Related();
    }

    @Bean
    @Primary
    Sales sales() {
      return new Sales();
    }

    @Bean
    @Primary
    Clock testClock() {
      return new Fixed();
    }
  }

  static class Fixed extends Clock {
    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return NOW;
    }
  }

  /** Answers the configured list per product slug; ranking is covered by the database test. */
  static class Related implements RecommendationQueries {
    final Map<String, List<CardRow>> bySlug = new HashMap<>();

    @Override
    public Optional<List<CardRow>> relatedTo(String productSlug, int limit) {
      return Optional.ofNullable(bySlug.get(productSlug))
          .map(list -> list.stream().limit(limit).toList());
    }
  }

  static class Sales implements FlashSaleQueries {
    final List<SaleRow> sales = new ArrayList<>();

    @Override
    public Optional<SaleRow> findCurrent(Instant now) {
      return sales.stream()
          .filter(s -> !s.startsAt().isAfter(now) && now.isBefore(s.endsAt()))
          .findFirst();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private ProductApiSupport.InMemoryCatalog catalog;
  @Autowired private Related related;
  @Autowired private Sales sales;

  @BeforeEach
  void reset() {
    catalog.clear();
    related.bySlug.clear();
    sales.sales.clear();
  }

  private static CardRow card(int n, Instant createdAt) {
    return new CardRow(
        new UUID(0, n),
        "product-" + n,
        "Product " + n,
        "https://cdn.example.com/p" + n + ".jpg",
        "SKU-" + n,
        100_000,
        n % 2 == 0 ? 150_000L : null,
        createdAt);
  }

  /** Products 1..count, product 1 the newest. */
  private List<CardRow> catalog(int count) {
    List<CardRow> cards = new ArrayList<>();
    for (int n = 1; n <= count; n++) {
      CardRow card = card(n, NOW.minusSeconds(60L * n));
      cards.add(card);
      catalog.add(
          ProductApiSupport.product(n, card.createdAt(), 100_000, card.listPrice(), 1), "c");
    }
    return cards;
  }

  private void sale(Instant start, Instant end, CardRow... cards) {
    List<ItemRow> items = new ArrayList<>();
    for (CardRow card : cards) {
      items.add(new ItemRow(card, 50_000, 100, 10));
    }
    sales.sales.add(new SaleRow(new UUID(1, 1), start, end, items));
  }

  private MockHttpServletResponse call(String query) throws Exception {
    return mvc.perform(get("/api/recommendations" + query).accept(MediaType.APPLICATION_JSON))
        .andReturn()
        .getResponse();
  }

  private static JsonNode body(MockHttpServletResponse r) throws Exception {
    return JSON.readTree(r.getContentAsString(StandardCharsets.UTF_8));
  }

  private static List<String> slugs(MockHttpServletResponse r) throws Exception {
    List<String> slugs = new ArrayList<>();
    body(r).get("items").forEach(i -> slugs.add(i.get("slug").asString()));
    return slugs;
  }

  @Test
  void withoutProductSlugTheSaleItemsComeFirstThenTheNewestWithoutDuplicates() throws Exception {
    List<CardRow> cards = catalog(10);
    sale(NOW.minusSeconds(60), NOW.plusSeconds(60), cards.get(4), cards.get(1));

    var response = call("?limit=5");

    assertThat(slugs(response))
        .containsExactly("product-5", "product-2", "product-1", "product-3", "product-4");
  }

  @Test
  void outsideTheSaleWindowTheNewestProductsAreListed() throws Exception {
    List<CardRow> cards = catalog(10);
    sale(NOW.plusSeconds(1), NOW.plusSeconds(60), cards.get(4));

    assertThat(slugs(call("?limit=3"))).containsExactly("product-1", "product-2", "product-3");

    sales.sales.clear();
    sale(NOW.minusSeconds(60), NOW, cards.get(4));
    assertThat(slugs(call("?limit=3"))).containsExactly("product-1", "product-2", "product-3");
  }

  @Test
  void aSaleLargerThanTheLimitIsCutAtTheLimit() throws Exception {
    List<CardRow> cards = catalog(10);
    sale(NOW.minusSeconds(60), NOW.plusSeconds(60), cards.get(9), cards.get(8), cards.get(7));

    assertThat(slugs(call("?limit=2"))).containsExactly("product-10", "product-9");
  }

  @Test
  void withProductSlugTheRelatedProductsAreReturned() throws Exception {
    List<CardRow> cards = catalog(10);
    related.bySlug.put("product-1", List.of(cards.get(6), cards.get(2)));

    assertThat(slugs(call("?productSlug=product-1"))).containsExactly("product-7", "product-3");
  }

  @Test
  void unknownProductSlugGivesTheFallbackList() throws Exception {
    List<CardRow> cards = catalog(10);
    sale(NOW.minusSeconds(60), NOW.plusSeconds(60), cards.get(4));

    var response = call("?productSlug=no-such-product&limit=2");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(slugs(response)).containsExactly("product-5", "product-1");
  }

  @Test
  void limitDefaultsToEightAndTwentyIsAccepted() throws Exception {
    catalog(30);

    assertThat(slugs(call(""))).hasSize(8);
    assertThat(slugs(call("?limit=20"))).hasSize(20);
    assertThat(slugs(call("?limit=1"))).hasSize(1);
  }

  @Test
  void invalidLimitIsRejectedWithValidationFailed() throws Exception {
    catalog(3);
    for (String bad : List.of("0", "21", "-1", "abc", "")) {
      var response = call("?limit=" + bad);

      assertThat(response.getStatus()).as("limit=%s", bad).isEqualTo(400);
      JsonNode problem = body(response);
      assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
      assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("limit");
    }
  }

  @Test
  void successIsPrivateAndMatchesTheContract() throws Exception {
    catalog(3);
    var response = call("");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private");
    ContractValidator.core().assertValid(response, "listRecommendations");
    JsonNode second = body(response).get("items").get(1);
    assertThat(second.get("badges").get(0).asString()).isEqualTo("DISCOUNT");
    assertThat(second.get("listPrice").get("amount").asLong()).isEqualTo(150_000);
  }

  @Test
  void errorIsNoStoreAndMatchesTheContract() throws Exception {
    var response = call("?limit=0");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    ContractValidator.core().assertValid(response, "listRecommendations");
  }
}
