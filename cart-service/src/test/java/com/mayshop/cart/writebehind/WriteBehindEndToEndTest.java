package com.mayshop.cart.writebehind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.mayshop.cart.product.Money;
import com.mayshop.cart.product.ProductInfo;
import com.mayshop.cart.product.ProductLookupResult;
import com.mayshop.cart.product.ProductLookupService;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/** One write produces one record and, once consumed, one row (Redpanda, Postgres and Redis). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class WriteBehindEndToEndTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

  @Container
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.3.7");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("REDIS_HOST", REDIS::getHost);
    registry.add("REDIS_PORT", () -> REDIS.getMappedPort(6379));
    registry.add("KAFKA_BOOTSTRAP_SERVERS", REDPANDA::getBootstrapServers);
    registry.add("CART_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("CART_DB_USER", POSTGRES::getUsername);
    registry.add("CART_DB_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired private MockMvcTester mvc;
  @Autowired private KafkaTemplate<String, String> kafka;
  @MockitoBean private ProductLookupService products;

  @BeforeEach
  void catalog() {
    when(products.lookup(any()))
        .thenAnswer(call -> {
          Set<String> skus = call.getArgument(0);
          Map<String, ProductInfo> known = new LinkedHashMap<>();
          skus.forEach(sku ->
              known.put(
                  sku,
                  new ProductInfo(
                      sku,
                      sku.toLowerCase(),
                      "Name " + sku,
                      "https://img.example/" + sku + ".jpg",
                      null,
                      new Money(100_000, "VND"),
                      null,
                      "IN_STOCK")));
          return new ProductLookupResult(known, Set.of());
        });
  }

  private static JdbcTemplate jdbc() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return new JdbcTemplate(ds);
  }

  private void call(String method, String uri, String body, Cookie guest) {
    var request =
        switch (method) {
          case "POST" -> mvc.post().uri(uri);
          case "PATCH" -> mvc.patch().uri(uri);
          default -> mvc.delete().uri(uri);
        };
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    assertThat(request.cookie(guest).exchange()).hasStatusOk();
  }

  private static Long storedVersion(String key) {
    List<Long> rows =
        jdbc()
            .queryForList("SELECT version FROM cart_snapshot WHERE cart_key = ?", Long.class, key);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Test
  void aWriteReachesCartSnapshotWithTheSameVersionAndAnOlderOrDuplicateRecordChangesNothing() {
    String guestId = "CCCCCCCCCCCCCCCCCCCCCC";
    String key = "cart:guest:" + guestId;
    Cookie guest = new Cookie("guest_cart", guestId);

    call("POST", "/api/cart/items", "{\"sku\":\"SKU-A\",\"quantity\":1}", guest);
    call("POST", "/api/cart/items", "{\"sku\":\"SKU-B\",\"quantity\":2}", guest);

    await()
        .atMost(Duration.ofSeconds(60))
        .ignoreExceptions()
        .untilAsserted(() -> assertThat(storedVersion(key)).isEqualTo(2L));

    // A replayed older record and a duplicate of the current one leave the row unchanged.
    String older =
        "{\"cartKey\":\"%s\",\"version\":1,\"updatedAt\":\"2026-11-24T10:00:00Z\",".formatted(key)
            + "\"items\":[{\"sku\":\"SKU-OLD\",\"quantity\":9,\"addedAt\":\"2026-11-24T10:00:00Z\"}]}";
    kafka.send("cart.updated", key, older);
    kafka.send("cart.updated", key, older.replace("\"version\":1", "\"version\":2"));
    kafka.send("cart.updated", "cart:sentinel", older.replace(key, "cart:sentinel"));
    await()
        .atMost(Duration.ofSeconds(60))
        .ignoreExceptions()
        .untilAsserted(() -> assertThat(storedVersion("cart:sentinel")).isNotNull());
    assertThat(storedVersion(key)).isEqualTo(2L);
    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT items::text FROM cart_snapshot WHERE cart_key = ?", String.class, key))
        .doesNotContain("SKU-OLD")
        .contains("SKU-A", "SKU-B");

    // Emptying the cart deletes the row.
    call("DELETE", "/api/cart/items/SKU-A", null, guest);
    call("DELETE", "/api/cart/items/SKU-B", null, guest);
    await()
        .atMost(Duration.ofSeconds(60))
        .ignoreExceptions()
        .untilAsserted(() -> assertThat(storedVersion(key)).isNull());
  }
}
