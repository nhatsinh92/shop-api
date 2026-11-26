package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.product.ProductUpdated.Change;
import com.mayshop.core.product.update.SaleWindowBoundaries;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The sale-window job on Postgres with a settable clock: events at the start and the end. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@RecordApplicationEvents
@Import(SaleWindowBoundaryDatabaseTest.ClockConfiguration.class)
class SaleWindowBoundaryDatabaseTest {

  private static final Instant START = Instant.parse("2026-12-12T10:00:00Z");
  private static final Instant END = Instant.parse("2026-12-12T12:00:00Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @TestConfiguration
  static class ClockConfiguration {
    @Bean
    @Primary
    SettableClock saleWindowTestClock() {
      return new SettableClock();
    }
  }

  static class SettableClock extends Clock {
    private volatile Instant now = START;

    void set(Instant instant) {
      now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Autowired private SaleWindowBoundaries boundaries;
  @Autowired private SettableClock clock;
  @Autowired private JdbcClient jdbc;
  @Autowired private ApplicationEvents events;

  private UUID sale;

  @BeforeEach
  void seed() {
    jdbc.sql("delete from product.flash_sales").update(); // cascades to the boundary rows
    jdbc.sql("delete from product.products").update();
    jdbc.sql("delete from product.categories").update();
    jdbc.sql("insert into product.categories (slug, name) values ('c', 'C')").update();
    for (int n = 1; n <= 3; n++) {
      UUID product = new UUID(5, n);
      jdbc.sql(
              "insert into product.products (id, slug, name, brand, category_slug)"
                  + " values (?, ?, ?, 'B', 'c')")
          .params(product, "product-" + n, "Product " + n)
          .update();
      jdbc.sql(
              "insert into product.variants (sku, product_id, position, price) values (?, ?, 1,"
                  + " 100000)")
          .params("SKU-" + n, product)
          .update();
    }
    sale = UUID.randomUUID();
    addWindow(sale, START, END, "SKU-1", "SKU-2", "SKU-3");
    clock.set(START.minusSeconds(60));
  }

  private void addWindow(UUID id, Instant start, Instant end, String... skus) {
    jdbc.sql("insert into product.flash_sales (id, starts_at, ends_at) values (?, ?, ?)")
        .params(id, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end))
        .update();
    for (String sku : skus) {
      jdbc.sql(
              "insert into product.flash_sale_items (sale_id, sku, sale_price, sale_stock)"
                  + " values (?, ?, 50000, 10)")
          .params(id, sku)
          .update();
    }
  }

  private List<ProductUpdated> published() {
    return events.stream(ProductUpdated.class).toList();
  }

  @Test
  void theStartPublishesOnePriceEventPerSkuAndTheEndAnotherOne() {
    assertThat(boundaries.fireDue()).isZero();
    assertThat(published()).isEmpty();

    clock.set(START.plusSeconds(15));
    assertThat(boundaries.fireDue()).isEqualTo(3);
    assertThat(boundaries.fireDue()).isZero();
    assertThat(published()).hasSize(3);
    assertThat(published())
        .extracting(ProductUpdated::sku)
        .containsExactlyInAnyOrder("SKU-1", "SKU-2", "SKU-3");
    assertThat(published()).allSatisfy(e -> assertThat(e.changes()).containsExactly(Change.PRICE));

    clock.set(END.plusSeconds(15));
    assertThat(boundaries.fireDue()).isEqualTo(3);
    assertThat(boundaries.fireDue()).isZero();
    assertThat(published()).hasSize(6);
    assertThat(
            jdbc.sql("select count(*) from product.sale_window_boundary where fired")
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void twoJobsRunningAtOncePublishExactlyOneEventPerSkuAndBoundary() throws Exception {
    clock.set(START.plusSeconds(15));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Integer>> runs = new java.util.ArrayList<>();
      for (int i = 0; i < 2; i++) {
        runs.add(
            pool.submit(() -> {
              go.await();
              return boundaries.fireDue();
            }));
      }
      go.countDown();
      int total = 0;
      for (Future<Integer> run : runs) {
        total += run.get();
      }

      assertThat(total).isEqualTo(3);
      assertThat(published())
          .extracting(ProductUpdated::sku)
          .containsExactlyInAnyOrder("SKU-1", "SKU-2", "SKU-3");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aBoundaryOlderThanTheLookbackIsRecordedAsFiredWithoutPublishing() {
    clock.set(START.plusSeconds(3600)); // the start passed an hour ago, the end is in the future

    assertThat(boundaries.fireDue()).isZero();

    assertThat(published()).isEmpty();
    assertThat(
            jdbc.sql("select fired from product.sale_window_boundary where boundary = 'START'")
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(
            jdbc.sql("select count(*) from product.sale_window_boundary where boundary = 'END'")
                .query(Integer.class)
                .single())
        .isZero();

    clock.set(END.plusSeconds(15));
    assertThat(boundaries.fireDue()).isEqualTo(3);
  }

  @Test
  void aBoundaryInTheFutureDoesNotFireAndLeavesNoRow() {
    clock.set(START.minusSeconds(1));

    assertThat(boundaries.fireDue()).isZero();

    assertThat(
            jdbc.sql("select count(*) from product.sale_window_boundary")
                .query(Integer.class)
                .single())
        .isZero();
  }
}
