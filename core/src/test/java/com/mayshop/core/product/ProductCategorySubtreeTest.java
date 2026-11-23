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

/** The listProducts category filter covers a category and its descendants (issue #93). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ProductCategorySubtreeTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The seeded products of am-thanh and phu-kien-may-tinh, newest first. */
  private static final List<String> DIEN_TU =
      List.of(
          "tai-nghe-khong-day-anc-pro",
          "ban-phim-co-75-hot-swap",
          "chuot-khong-day-im-lang",
          "gia-do-laptop-nhom",
          "tui-chong-soc-14-inch",
          "lot-chuot-co-lon-90cm");

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
  void aRootCategoryListsTheProductsOfItsSubcategoriesNewestFirst() throws Exception {
    var response = call("/api/products?category=dien-tu&limit=50");

    assertThat(response.getStatus()).isEqualTo(200);
    ContractValidator.core().assertValid(response, "listProducts");
    assertThat(slugs(body(response))).containsExactlyElementsOf(DIEN_TU);
  }

  @Test
  void pagingInsideASubtreeReturnsEveryProductExactlyOnceInOrder() throws Exception {
    List<String> seen = new ArrayList<>();
    String cursor = null;
    do {
      var response =
          call(
              "/api/products?category=dien-tu&limit=4"
                  + (cursor == null ? "" : "&cursor=" + cursor));
      assertThat(response.getStatus()).isEqualTo(200);
      JsonNode page = body(response);
      seen.addAll(slugs(page));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
    } while (cursor != null);

    assertThat(seen).containsExactlyElementsOf(DIEN_TU);
  }

  @Test
  void aLeafAndARootWithoutChildrenAreUnchanged() throws Exception {
    assertThat(slugs(body(call("/api/products?category=am-thanh"))))
        .containsExactly("tai-nghe-khong-day-anc-pro");
    assertThat(slugs(body(call("/api/products?category=gia-dung")))).hasSize(3);
    assertThat(slugs(body(call("/api/products?category=does-not-exist")))).isEmpty();
  }
}
