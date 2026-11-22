package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mayshop.core.product.AvailabilitySupport.InMemoryAvailability;
import com.mayshop.core.product.ProductApiSupport.InMemoryCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** The discounts come from {@code product.member-price.discounts}. */
@SpringBootTest(
    properties = {ProductApiSupport.DB_FREE_EXCLUDES, "product.member-price.discounts.GOLD=10"})
@AutoConfigureMockMvc
@Import(MemberPriceSupport.Configuration.class)
class MemberPriceConfigurationTest {

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryCatalog catalog;
  @Autowired private InMemoryAvailability availability;

  @Test
  void aConfiguredDiscountOverridesTheDefaultAndOtherTiersKeepTheirs() throws Exception {
    catalog.clear();
    availability.clear();
    catalog.add(
        ProductApiSupport.product(1, Instant.parse("2026-01-01T00:00:00Z"), 890_000, null, 1),
        "dien-tu");
    availability.put(new UUID(0, 1), "SKU-1-1", 890_000, 10, List.of());

    assertThat(amount("GOLD")).isEqualTo(801_000);
    assertThat(amount("PLATINUM")).isEqualTo(818_500);
  }

  private long amount(String tier) throws Exception {
    String json =
        mvc.perform(
                get("/api/products/product-1/member-price")
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + MemberPriceSupport.token(tier)))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JsonMapper.builder().build().readTree(json).get("price").get("amount").asLong();
  }
}
