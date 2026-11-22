package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mayshop.core.contract.ContractValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code listRecommendations} on Postgres with the demo seed (leaf and parent categories). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class RecommendationDatabaseTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Autowired private MockMvc mvc;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void loadSeed() {
    jdbc.sql("delete from product.flash_sales").update();
    jdbc.sql("delete from product.products").update();
    jdbc.sql("delete from product.categories").update();
    new ResourceDatabasePopulator(
            new FileSystemResource(Path.of("..", "seed", "products.sql").toAbsolutePath()))
        .execute(dataSource);
  }

  private MockHttpServletResponse call(String query) throws Exception {
    return mvc.perform(get("/api/recommendations" + query).accept(MediaType.APPLICATION_JSON))
        .andReturn()
        .getResponse();
  }

  private static List<String> slugs(MockHttpServletResponse response) throws Exception {
    JsonNode body = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    List<String> slugs = new ArrayList<>();
    body.get("items").forEach(i -> slugs.add(i.get("slug").asString()));
    return slugs;
  }

  @Test
  void leafCategoryProductsComeFirstThenTheParentCategoryNewestFirstWithoutTheProduct()
      throws Exception {
    // ban-phim-co-75-hot-swap is in phu-kien-may-tinh (parent dien-tu); the only other product
    // under dien-tu is the ANC Pro in am-thanh.
    var response = call("?productSlug=ban-phim-co-75-hot-swap&limit=20");

    assertThat(response.getStatus()).isEqualTo(200);
    ContractValidator.core().assertValid(response, "listRecommendations");
    assertThat(slugs(response))
        .containsExactly(
            "chuot-khong-day-im-lang",
            "gia-do-laptop-nhom",
            "tui-chong-soc-14-inch",
            "lot-chuot-co-lon-90cm",
            "tai-nghe-khong-day-anc-pro");
  }

  @Test
  void aProductAloneInItsLeafCategoryGetsTheParentCategoryProducts() throws Exception {
    var response = call("?productSlug=tai-nghe-khong-day-anc-pro&limit=3");

    assertThat(slugs(response))
        .containsExactly(
            "ban-phim-co-75-hot-swap", "chuot-khong-day-im-lang", "gia-do-laptop-nhom");
  }

  @Test
  void aRootCategoryProductGetsOnlyItsCategoryAndDescendants() throws Exception {
    var response = call("?productSlug=noi-chien-khong-dau-6l&limit=20");

    assertThat(slugs(response))
        .containsExactly("binh-giu-nhiet-inox-750ml", "den-ban-led-chong-can");
  }

  @Test
  void unknownProductSlugGivesTheSeededSaleItemsThenTheNewest() throws Exception {
    var response = call("?productSlug=no-such-product&limit=8");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private");
    List<String> slugs = slugs(response);
    assertThat(slugs).hasSize(8).doesNotHaveDuplicates();
    assertThat(slugs.subList(0, 5))
        .containsExactlyInAnyOrder(
            "tai-nghe-khong-day-anc-pro",
            "noi-chien-khong-dau-6l",
            "ao-khoac-gio-unisex-chong-nuoc",
            "ban-phim-co-75-hot-swap",
            "binh-giu-nhiet-inox-750ml");
    assertThat(slugs.subList(5, 8))
        .containsExactly("chuot-khong-day-im-lang", "gia-do-laptop-nhom", "tui-chong-soc-14-inch");
  }

  @Test
  void withoutASaleTheNewestProductsAreListed() throws Exception {
    jdbc.sql("update product.flash_sales set ends_at = now() - interval '1 second'").update();

    assertThat(slugs(call("?limit=2")))
        .containsExactly("tai-nghe-khong-day-anc-pro", "noi-chien-khong-dau-6l");
  }
}
