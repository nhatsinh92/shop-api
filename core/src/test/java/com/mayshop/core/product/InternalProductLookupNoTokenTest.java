package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.contract.ContractValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** With no {@code INTERNAL_API_TOKEN} configured the lookup is 404 whatever it sends. */
@SpringBootTest(
    properties = {
      ProductApiSupport.DB_FREE_EXCLUDES,
      "product.internal.api-token=",
      "spring.modulith.events.externalization.enabled=false"
    })
@AutoConfigureMockMvc
@Import(AvailabilitySupport.Configuration.class)
class InternalProductLookupNoTokenTest {

  @Autowired private MockMvc mvc;

  @Test
  void everyRequestIs404WhenNoTokenIsConfigured() throws Exception {
    for (String token : new String[] {null, "", "anything"}) {
      var request =
          post("/internal/products/lookup")
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .content("{\"skus\": [\"SKU-A\"]}");
      if (token != null) {
        request.header("X-Internal-Token", token);
      }
      var response = mvc.perform(request).andReturn().getResponse();
      assertThat(response.getStatus()).as("token '%s'", token).isEqualTo(404);
      ContractValidator.core().assertValid(response, "lookupInternalProducts");
    }
  }
}
