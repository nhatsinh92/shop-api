package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.flags.FeatureFlags;
import com.mayshop.core.flags.Flag;
import com.mayshop.core.product.persistence.CatalogQueries;
import com.mayshop.core.product.persistence.FlashSaleQueries;
import com.mayshop.core.product.persistence.RecommendationQueries;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** The {@code RECOMMENDATIONS} flag on {@code listRecommendations}, with mock query beans. */
@SpringBootTest(properties = ProductApiSupport.DB_FREE_EXCLUDES)
@AutoConfigureMockMvc
@Import(ProductApiSupport.InMemoryCatalogConfiguration.class)
class RecommendationFlagTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @MockitoBean private FeatureFlags flags;
  @MockitoBean private RecommendationQueries related;
  @MockitoBean private CatalogQueries catalog;
  @MockitoBean private FlashSaleQueries flashSales;

  @Autowired private MockMvc mvc;

  private MockHttpServletResponse call(String query) throws Exception {
    return mvc.perform(get("/api/recommendations" + query).accept(MediaType.APPLICATION_JSON))
        .andReturn()
        .getResponse();
  }

  @Test
  void withRecommendationsOffTheAnswerIsEmptyAndNoQueryRuns() throws Exception {
    when(flags.isEnabled(Flag.RECOMMENDATIONS)).thenReturn(false);

    var response = call("?productSlug=product-1&limit=4");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private");
    var body = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    assertThat(body.get("items").isArray()).isTrue();
    assertThat(body.get("items")).isEmpty();
    ContractValidator.core().assertValid(response, "listRecommendations");
    verifyNoInteractions(related, catalog, flashSales);
  }

  @Test
  void withRecommendationsOffAnInvalidLimitIsStillRejected() throws Exception {
    when(flags.isEnabled(Flag.RECOMMENDATIONS)).thenReturn(false);

    assertThat(call("?limit=0").getStatus()).isEqualTo(400);
    verifyNoInteractions(related, catalog, flashSales);
  }

  @Test
  void withRecommendationsOnTheQueriesRunAsBefore() throws Exception {
    when(flags.isEnabled(Flag.RECOMMENDATIONS)).thenReturn(true);
    when(flashSales.findCurrent(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
    when(catalog.page(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyInt()))
        .thenReturn(java.util.List.of());
    when(related.relatedTo(anyString(), anyInt())).thenReturn(Optional.empty());

    var response = call("?productSlug=product-1");

    assertThat(response.getStatus()).isEqualTo(200);
    verify(related).relatedTo("product-1", 8);
  }
}
