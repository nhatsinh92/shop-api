package com.mayshop.core.product.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mayshop.core.product.persistence.LookupQueries.LookupRow;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The lookup SQL on Postgres with the demo seed: results and a bounded number of queries. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class LookupQueriesDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

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

  private static LookupRow row(List<LookupRow> rows, String sku) {
    return rows.stream().filter(r -> r.sku().equals(sku)).findFirst().orElseThrow();
  }

  @Test
  void seededSkuHasItsOptionsFirstImageAndVariantCount() {
    var queries = new JdbcLookupQueries(jdbc, "https://placeholder.example/x.png");

    var rows = queries.findBySkus(List.of("SKU-ANC-PRO-BLK", "SKU-PAD-90", "SKU-NOPE"));

    assertThat(rows).hasSize(2);
    LookupRow anc = row(rows, "SKU-ANC-PRO-BLK");
    assertThat(anc.variantCount()).isEqualTo(3);
    assertThat(anc.options())
        .extracting("name", "value")
        .containsExactly(org.assertj.core.groups.Tuple.tuple("color", "Đen nhám"));
    assertThat(anc.imageUrl()).endsWith("-1");
    assertThat(anc.listPrice()).isEqualTo(1_790_000L);
    assertThat(anc.availability().sales()).hasSize(1);
    assertThat(row(rows, "SKU-PAD-90").variantCount()).isEqualTo(1);
  }

  @Test
  void theNumberOfSqlQueriesDoesNotDependOnTheNumberOfSkus() {
    JdbcClient oneSpy = spy(jdbc);
    new JdbcLookupQueries(oneSpy, "x").findBySkus(List.of("SKU-PAD-90"));
    JdbcClient manySpy = spy(jdbc);
    new JdbcLookupQueries(manySpy, "x")
        .findBySkus(
            List.of(
                "SKU-ANC-PRO-BLK",
                "SKU-ANC-PRO-WHT",
                "SKU-ANC-PRO-BLU",
                "SKU-AIRFRY-6L",
                "SKU-JACKET-WP",
                "SKU-KB-75",
                "SKU-BOTTLE-750",
                "SKU-MOUSE-SIL"));

    verify(oneSpy, times(2)).sql(org.mockito.ArgumentMatchers.anyString());
    verify(manySpy, times(2)).sql(org.mockito.ArgumentMatchers.anyString());
  }
}
