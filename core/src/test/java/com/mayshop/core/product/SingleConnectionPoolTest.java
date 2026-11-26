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

/**
 * The current flash sale and the availability of a SKU on sale, served by a pool of one connection:
 * a read that took a second connection while holding the first one would wait for itself until the
 * connection timeout (issue #193).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.datasource.hikari.connection-timeout=2000"
    })
@AutoConfigureMockMvc
class SingleConnectionPoolTest {

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

  private MockHttpServletResponse call(String path) throws Exception {
    return mvc.perform(get(path).accept(MediaType.APPLICATION_JSON)).andReturn().getResponse();
  }

  private static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void currentFlashSaleWithItemsIsServedByAPoolOfOneConnection() throws Exception {
    var response = call("/api/flash-sales/current");

    assertThat(response.getStatus()).isEqualTo(200);
    ContractValidator.core().assertValid(response, "getCurrentFlashSale");
    assertThat(body(response).get("items")).hasSize(5);
  }

  @Test
  void availabilityWithSalePricesIsServedByAPoolOfOneConnection() throws Exception {
    var response = call("/api/products/SKU-ANC-PRO-BLK/availability");

    assertThat(response.getStatus()).isEqualTo(200);
    ContractValidator.core().assertValid(response, "getProductAvailability");
    assertThat(body(response).get("price").get("amount").asLong()).isEqualTo(890_000);
  }
}
