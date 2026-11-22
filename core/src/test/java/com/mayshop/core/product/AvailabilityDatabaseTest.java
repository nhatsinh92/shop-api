package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mayshop.core.contract.ContractValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

/** The availability endpoint on Postgres with the demo seed (its sale window contains now). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class AvailabilityDatabaseTest {

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

  private MockHttpServletResponse call(String sku) throws Exception {
    return mvc.perform(
            get("/api/products/" + sku + "/availability").accept(MediaType.APPLICATION_JSON))
        .andReturn()
        .getResponse();
  }

  private static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void everyAncProVariantAnswers890000DuringTheSeededSale() throws Exception {
    for (String sku : new String[] {"SKU-ANC-PRO-BLK", "SKU-ANC-PRO-WHT", "SKU-ANC-PRO-BLU"}) {
      var response = call(sku);
      assertThat(response.getStatus()).isEqualTo(200);
      JsonNode json = body(response);
      assertThat(json.get("price").get("amount").asLong()).isEqualTo(890_000);
      assertThat(json.get("stockLevel").asString()).isEqualTo("LOW");
      assertThat(json.get("approxQuantity").asInt()).isEqualTo(40);
      ContractValidator.core().assertValid(response, "getProductAvailability");
    }
  }

  @Test
  void priceFallsBackToTheVariantPriceOnceTheSaleHasEnded() throws Exception {
    jdbc.sql(
            "update product.flash_sales set starts_at = now() - interval '3 hours',"
                + " ends_at = now() - interval '1 hour'")
        .update();

    assertThat(body(call("SKU-ANC-PRO-BLK")).get("price").get("amount").asLong())
        .isEqualTo(1_290_000);
  }

  @Test
  void unknownSkuIs404OnPostgres() throws Exception {
    var response = call("SKU-NOPE");

    assertThat(response.getStatus()).isEqualTo(404);
    ContractValidator.core().assertValid(response, "getProductAvailability");
  }
}
