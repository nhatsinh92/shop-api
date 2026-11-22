package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.mayshop.core.product.AvailabilitySupport.InMemoryAvailability;
import com.mayshop.core.product.persistence.ProductUpdateStore.Variant;
import com.mayshop.core.product.update.FakeUpdateStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** With no {@code INTERNAL_API_TOKEN} configured every request is 404, whatever it sends. */
@SpringBootTest(
    properties = {
      ProductApiSupport.DB_FREE_EXCLUDES,
      "product.internal.api-token=",
      "spring.modulith.events.externalization.enabled=false"
    })
@AutoConfigureMockMvc
@Import({AvailabilitySupport.Configuration.class, InternalProductNoTokenTest.Stores.class})
class InternalProductNoTokenTest {

  static class Stores {
    @Bean
    @Primary
    FakeUpdateStore fakeUpdateStore(InMemoryAvailability availability) {
      FakeUpdateStore store = new FakeUpdateStore();
      store.put("SKU-A", new Variant(new UUID(0, 7), "slug-a", 1_000, null, 1));
      return store;
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private FakeUpdateStore store;

  private int status(String token) throws Exception {
    var request =
        patch("/internal/products/SKU-A")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"stock\": 5}");
    if (token != null) {
      request.header("X-Internal-Token", token);
    }
    return mvc.perform(request).andReturn().getResponse().getStatus();
  }

  @Test
  void everyRequestIs404WhenNoTokenIsConfigured() throws Exception {
    assertThat(status(null)).isEqualTo(404);
    assertThat(status("")).isEqualTo(404);
    assertThat(status("anything")).isEqualTo(404);
    assertThat(store.updates).isZero();
  }
}
