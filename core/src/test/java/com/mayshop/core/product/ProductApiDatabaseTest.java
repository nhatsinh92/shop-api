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

/** The product endpoints on Postgres with the demo seed. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ProductApiDatabaseTest {

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

  private MockHttpServletResponse call(String uri) throws Exception {
    return mvc.perform(get(uri).accept(MediaType.APPLICATION_JSON)).andReturn().getResponse();
  }

  private static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void pagingTheSeededCatalogWithLimitThreeReturnsEveryProductExactlyOnce() throws Exception {
    List<String> seen = new ArrayList<>();
    String cursor = null;
    do {
      var response = call("/api/products?limit=3" + (cursor == null ? "" : "&cursor=" + cursor));
      assertThat(response.getStatus()).isEqualTo(200);
      ContractValidator.core().assertValid(response, "listProducts");
      JsonNode page = body(response);
      page.get("items").forEach(item -> seen.add(item.get("slug").asString()));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
    } while (cursor != null);

    assertThat(seen).hasSize(10).doesNotHaveDuplicates();
    assertThat(seen.get(0)).isEqualTo("tai-nghe-khong-day-anc-pro");
  }

  @Test
  void seededSummaryUsesTheFirstVariantAndOmitsListPriceWhenThereIsNone() throws Exception {
    JsonNode items = body(call("/api/products?limit=50")).get("items");

    for (JsonNode item : items) {
      switch (item.get("slug").asString()) {
        case "tai-nghe-khong-day-anc-pro" -> {
          assertThat(item.get("sku").asString()).isEqualTo("SKU-ANC-PRO-BLK");
          assertThat(item.get("listPrice").get("amount").asLong()).isEqualTo(1_790_000);
          assertThat(item.get("badges").get(0).asString()).isEqualTo("DISCOUNT");
        }
        case "chuot-khong-day-im-lang" -> {
          assertThat(item.has("listPrice")).isFalse();
          assertThat(item.get("badges")).isEmpty();
        }
        default -> {}
      }
    }
  }

  @Test
  void categoryFilterWorksOnPostgres() throws Exception {
    JsonNode items = body(call("/api/products?category=gia-dung")).get("items");

    assertThat(items).hasSize(3);
    assertThat(body(call("/api/products?category=nope")).get("items")).isEmpty();
  }

  @Test
  void detailOfTheSeededAncProHasThreeColourVariants() throws Exception {
    var response = call("/api/products/tai-nghe-khong-day-anc-pro");

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode detail = body(response);
    assertThat(detail.get("variants")).hasSize(3);
    assertThat(detail.get("variants").get(0).get("sku").asString()).isEqualTo("SKU-ANC-PRO-BLK");
    assertThat(detail.get("variants").get(1).get("options").get(0).get("value").asString())
        .isEqualTo("Trắng sứ");
    assertThat(detail.get("images")).hasSize(2);
    assertThat(detail.get("category").get("path").get(0).get("slug").asString())
        .isEqualTo("dien-tu");
    assertThat(response.getHeader("x-cache-tags"))
        .isEqualTo("product-00000000-0000-4000-8000-000000000001");
    ContractValidator.core().assertValid(response, "getProduct");
  }

  @Test
  void unknownSlugIs404OnPostgres() throws Exception {
    var response = call("/api/products/unknown");

    assertThat(response.getStatus()).isEqualTo(404);
    ContractValidator.core().assertValid(response, "getProduct");
  }
}
