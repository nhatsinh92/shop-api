package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mayshop.core.product.AvailabilitySupport.InMemoryAvailability;
import java.nio.charset.StandardCharsets;
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

/** The low stock threshold comes from {@code product.low-stock-threshold}. */
@SpringBootTest(properties = {ProductApiSupport.DB_FREE_EXCLUDES, "product.low-stock-threshold=5"})
@AutoConfigureMockMvc
@Import(AvailabilitySupport.Configuration.class)
class AvailabilityCustomThresholdTest {

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryAvailability store;

  private String level(int quantity) throws Exception {
    store.put(new UUID(0, 1), "SKU-B", 100_000, quantity, List.of());
    String json =
        mvc.perform(get("/api/products/SKU-B/availability").accept(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JsonMapper.builder().build().readTree(json).get("stockLevel").asString();
  }

  @Test
  void customThresholdMovesTheLowBoundary() throws Exception {
    assertThat(level(5)).isEqualTo("LOW");
    assertThat(level(6)).isEqualTo("IN_STOCK");
  }
}
