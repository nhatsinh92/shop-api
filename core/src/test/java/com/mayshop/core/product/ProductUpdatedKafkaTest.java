package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The internal update to a record on {@code product.updated}, on Postgres and Redpanda. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "product.internal.api-token=secret")
@AutoConfigureMockMvc
class ProductUpdatedKafkaTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SKU = "SKU-ANC-PRO-BLK";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Container
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.3.7");

  @DynamicPropertySource
  static void kafka(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
  }

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

  private int patchSku(String body) throws Exception {
    return mvc.perform(
            patch("/internal/products/" + SKU)
                .header("X-Internal-Token", "secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private String productId() {
    return jdbc.sql("select product_id::text from product.variants where sku = ?")
        .param(SKU)
        .query(String.class)
        .single();
  }

  private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records() {
    return ProductEventSupport.recordsWithKey(
        REDPANDA.getBootstrapServers(), "product.updated", SKU, Duration.ofSeconds(8));
  }

  @Test
  void aPriceChangeIsOneRecordKeyedBySkuValidAgainstTheContract() throws Exception {
    long before = records().size();

    assertThat(patchSku("{\"price\":{\"amount\":790000,\"currency\":\"VND\"}}")).isEqualTo(200);

    var all = records();
    assertThat(all).hasSize((int) before + 1);
    String value = all.get(all.size() - 1).value();
    assertThat(ProductEventSupport.violations(value)).isEmpty();
    JsonNode json = JSON.readTree(value);
    assertThat(json.get("sku").asString()).isEqualTo(SKU);
    assertThat(json.get("changes").get(0).asString()).isEqualTo("PRICE");
    assertThat(json.get("tags").valueStream().map(JsonNode::asString))
        .containsExactlyInAnyOrder("product-" + productId(), "products", "flash-sale");
  }

  @Test
  void aStockOnlyChangeIsOneRecordWithOnlyTheProductTag() throws Exception {
    long before = records().size();

    assertThat(patchSku("{\"stock\": 7}")).isEqualTo(200);

    var all = records();
    assertThat(all).hasSize((int) before + 1);
    String value = all.get(all.size() - 1).value();
    assertThat(ProductEventSupport.violations(value)).isEmpty();
    JsonNode json = JSON.readTree(value);
    assertThat(json.get("changes").valueStream().map(JsonNode::asString)).containsExactly("STOCK");
    assertThat(json.get("tags").valueStream().map(JsonNode::asString))
        .containsExactly("product-" + productId());
  }

  @Test
  void theRegistryHasItsOwnSchemaAndHistoryAndTheOtherHistoriesAreUnaffected() {
    assertThat(
            jdbc.sql(
                    "select count(*) from information_schema.tables"
                        + " where table_schema = 'modulith' and table_name = 'event_publication'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "select count(*) from information_schema.tables"
                        + " where table_schema = 'public' and table_name like 'event_publication%'")
                .query(Integer.class)
                .single())
        .isZero();
    for (String schema : new String[] {"auth", "product", "modulith"}) {
      assertThat(
              jdbc.sql("select version from " + schema + ".flyway_schema_history order by 1")
                  .query(String.class)
                  .list())
          .as("history of %s", schema)
          .contains("1");
    }
    assertThat(
            jdbc.sql("select description from modulith.flyway_schema_history where version = '1'")
                .query(String.class)
                .single())
        .isEqualTo("event publication");
    assertThat(
            jdbc.sql(
                    "select count(*) from auth.flyway_schema_history where description like '%event%'")
                .query(Integer.class)
                .single())
        .isZero();
  }
}
