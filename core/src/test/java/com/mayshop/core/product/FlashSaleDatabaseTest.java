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

/** {@code getCurrentFlashSale} on Postgres with the demo seed. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class FlashSaleDatabaseTest {

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

  private MockHttpServletResponse call() throws Exception {
    return mvc.perform(get("/api/flash-sales/current").accept(MediaType.APPLICATION_JSON))
        .andReturn()
        .getResponse();
  }

  private static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void seededSaleShowsFiveProductsWithTheArtboardSoldNumbersAndAncProOnce() throws Exception {
    var response = call();

    assertThat(response.getStatus()).isEqualTo(200);
    ContractValidator.core().assertValid(response, "getCurrentFlashSale");
    JsonNode items = body(response).get("items");
    assertThat(items).hasSize(5);
    List<Integer> sold = new ArrayList<>();
    int ancPro = 0;
    for (JsonNode item : items) {
      sold.add(item.get("soldPercent").asInt());
      if (item.get("slug").asString().equals("tai-nghe-khong-day-anc-pro")) {
        ancPro++;
        assertThat(item.get("sku").asString()).isEqualTo("SKU-ANC-PRO-BLK");
        assertThat(item.get("price").get("amount").asLong()).isEqualTo(1_290_000);
        assertThat(item.get("listPrice").get("amount").asLong()).isEqualTo(1_790_000);
        assertThat(item.get("salePrice").get("amount").asLong()).isEqualTo(890_000);
      }
    }
    assertThat(ancPro).isEqualTo(1);
    assertThat(sold).containsExactlyInAnyOrder(82, 64, 47, 91, 30);
  }

  @Test
  void aSaleThatEndedIsNotCurrent() throws Exception {
    jdbc.sql("update product.flash_sales set ends_at = now() - interval '1 second'").update();

    var response = call();

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    ContractValidator.core().assertValid(response, "getCurrentFlashSale");
  }

  @Test
  void aSaleThatHasNotStartedIsNotCurrent() throws Exception {
    jdbc.sql(
            "update product.flash_sales set starts_at = now() + interval '1 hour',"
                + " ends_at = now() + interval '2 hours'")
        .update();

    assertThat(call().getStatus()).isEqualTo(404);
  }
}
