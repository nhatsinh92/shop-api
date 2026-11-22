package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.product.persistence.CatalogQueries.CardRow;
import com.mayshop.core.product.persistence.FlashSaleQueries;
import com.mayshop.core.product.persistence.FlashSaleQueries.ItemRow;
import com.mayshop.core.product.persistence.FlashSaleQueries.SaleRow;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/** {@code getCurrentFlashSale} against an in-memory store and a fixed clock. */
@SpringBootTest(properties = ProductApiSupport.DB_FREE_EXCLUDES)
@AutoConfigureMockMvc
@Import({ProductApiSupport.InMemoryCatalogConfiguration.class, FlashSaleEndpointTest.Config.class})
class FlashSaleEndpointTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-12-12T10:00:00Z");
  private static final UUID SALE = new UUID(1, 1);

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    InMemoryFlashSales inMemoryFlashSales() {
      return new InMemoryFlashSales();
    }

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    private Instant now = NOW;

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  static class InMemoryFlashSales implements FlashSaleQueries {
    private final List<SaleRow> sales = new ArrayList<>();

    @Override
    public Optional<SaleRow> findCurrent(Instant now) {
      return sales.stream()
          .filter(s -> !s.startsAt().isAfter(now) && now.isBefore(s.endsAt()))
          .findFirst();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryFlashSales store;
  @Autowired private MutableClock clock;

  @BeforeEach
  void reset() {
    store.sales.clear();
    clock.now = NOW;
  }

  private void sale(Instant start, Instant end, ItemRow... items) {
    store.sales.add(new SaleRow(SALE, start, end, List.of(items)));
  }

  private static ItemRow item(int n, long price, Long list, long salePrice, int stock, int sold) {
    var card =
        new CardRow(
            new UUID(0, n),
            "product-" + n,
            "Product " + n,
            "https://cdn.example.com/p" + n + ".jpg",
            "SKU-" + n,
            price,
            list,
            NOW);
    return new ItemRow(card, salePrice, stock, sold);
  }

  private MockHttpServletResponse call() throws Exception {
    return mvc.perform(get("/api/flash-sales/current").accept(MediaType.APPLICATION_JSON))
        .andReturn()
        .getResponse();
  }

  private static JsonNode body(MockHttpServletResponse r) throws Exception {
    return JSON.readTree(r.getContentAsString(StandardCharsets.UTF_8));
  }

  private void runningSale() {
    sale(
        NOW.minusSeconds(3600),
        NOW.plusSeconds(7200),
        item(1, 1_290_000, 1_790_000L, 890_000, 100, 82));
  }

  @Test
  void beforeTheWindowThereIsNoCurrentSale() throws Exception {
    sale(NOW.plusSeconds(1), NOW.plusSeconds(7200), item(1, 100, null, 50, 10, 1));
    var response = call();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(body(response).get("code").asString()).isEqualTo("FLASH_SALE_NONE");
  }

  @Test
  void duringTheWindowTheSaleIsCurrentAndStartIsInclusive() throws Exception {
    sale(NOW, NOW.plusSeconds(7200), item(1, 100, null, 50, 10, 1));
    var response = call();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(body(response).get("items")).hasSize(1);
  }

  @Test
  void afterTheWindowThereIsNoCurrentSaleAndEndIsExclusive() throws Exception {
    sale(NOW.minusSeconds(7200), NOW, item(1, 100, null, 50, 10, 1));
    assertThat(call().getStatus()).isEqualTo(404);
    clock.now = NOW.plusSeconds(1);
    assertThat(call().getStatus()).isEqualTo(404);
  }

  @Test
  void itemsKeepCardPricesAddSalePriceAndSoldPercentFloors() throws Exception {
    sale(
        NOW.minusSeconds(60),
        NOW.plusSeconds(60),
        item(1, 1_290_000, 1_790_000L, 890_000, 100, 82),
        item(2, 249_000, null, 189_000, 3, 1));
    JsonNode items = body(call()).get("items");

    assertThat(items.get(0).get("price").get("amount").asLong()).isEqualTo(1_290_000);
    assertThat(items.get(0).get("listPrice").get("amount").asLong()).isEqualTo(1_790_000);
    assertThat(items.get(0).get("salePrice").get("amount").asLong()).isEqualTo(890_000);
    assertThat(items.get(0).get("soldPercent").asInt()).isEqualTo(82);
    assertThat(items.get(1).has("listPrice")).isFalse();
    assertThat(items.get(1).get("soldPercent").asInt()).isEqualTo(33);
  }

  @Test
  void successCarriesCacheHeadersAndMatchesTheContract() throws Exception {
    runningSale();
    var response = call();
    assertThat(response.getHeader("Cache-Control"))
        .isEqualTo("public, max-age=60, stale-while-revalidate=120");
    assertThat(response.getHeader("x-cache-tags")).isEqualTo("flash-sale");
    ContractValidator.core().assertValid(response, "getCurrentFlashSale");
  }

  @Test
  void noSaleIsNoStoreAndMatchesTheContract() throws Exception {
    var response = call();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    ContractValidator.core().assertValid(response, "getCurrentFlashSale");
  }
}
