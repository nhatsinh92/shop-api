package com.mayshop.cart.writebehind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.mayshop.cart.product.Money;
import com.mayshop.cart.product.ProductInfo;
import com.mayshop.cart.product.ProductLookupResult;
import com.mayshop.cart.product.ProductLookupService;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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

/**
 * The service starts and the cart API answers while Postgres is stopped; once Postgres starts, the
 * consumer retries, runs the migration and the write reaches {@code cart_snapshot}.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PostgresDownAtStartupTest {

  private static final int POSTGRES_PORT = freePort();

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

  @Container
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.3.7");

  /** Not a {@code @Container}: it is started by the test, after the application is up. */
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18-alpine")
          .withCreateContainerCmdModifier(cmd ->
              cmd.getHostConfig()
                  .withPortBindings(
                      new PortBinding(
                          Ports.Binding.bindPort(POSTGRES_PORT), new ExposedPort(5432))));

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("REDIS_HOST", REDIS::getHost);
    registry.add("REDIS_PORT", () -> REDIS.getMappedPort(6379));
    registry.add("KAFKA_BOOTSTRAP_SERVERS", REDPANDA::getBootstrapServers);
    registry.add("CART_DB_URL", () -> "jdbc:postgresql://localhost:" + POSTGRES_PORT + "/test");
    registry.add("CART_DB_USER", () -> "test");
    registry.add("CART_DB_PASSWORD", () -> "test");
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Autowired private MockMvcTester mvc;
  @MockitoBean private ProductLookupService products;

  @BeforeEach
  void catalog() {
    when(products.lookup(any()))
        .thenReturn(
            new ProductLookupResult(
                Map.of(
                    "SKU-A",
                    new ProductInfo(
                        "SKU-A",
                        "sku-a",
                        "Name",
                        "https://img.example/a.jpg",
                        null,
                        new Money(100_000, "VND"),
                        null,
                        "IN_STOCK")),
                Set.of()));
  }

  @Test
  void theCartApiAnswersWithPostgresStoppedAndAWriteReachesCartSnapshotOnceItStarts() {
    assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();

    assertThat(
            mvc.post()
                .uri("/api/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"SKU-A\",\"quantity\":1}")
                .cookie(new Cookie("guest_cart", "DDDDDDDDDDDDDDDDDDDDDD"))
                .exchange())
        .hasStatusOk();

    POSTGRES.start();
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(ds);

    await()
        .atMost(Duration.ofMinutes(2))
        .ignoreExceptions()
        .untilAsserted(() ->
            assertThat(
                    jdbc.queryForObject(
                        "SELECT version FROM cart_snapshot WHERE cart_key = ?",
                        Long.class,
                        "cart:guest:DDDDDDDDDDDDDDDDDDDDDD"))
                .isEqualTo(1L));
  }
}
