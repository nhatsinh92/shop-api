package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.product.StockHolds.HoldResult;
import com.mayshop.core.product.StockHolds.Line;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** {@link StockHolds} on Postgres: concurrency, lock order, flash sale stock and rollback. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class StockHoldsDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final Instant NOW = Instant.now();

  @Autowired private StockHolds holds;
  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate tx;

  @BeforeEach
  void clean() {
    jdbc.sql("delete from product.flash_sales").update();
    jdbc.sql("delete from product.products").update();
    jdbc.sql("delete from product.categories").update();
    jdbc.sql("insert into product.categories (slug, name) values ('c', 'C')").update();
  }

  private UUID product(String sku, long price, int stock) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into product.products (id, slug, name, brand, category_slug)"
                + " values (?, ?, ?, 'B', 'c')")
        .params(id, "slug-" + sku, "Name " + sku)
        .update();
    jdbc.sql("insert into product.variants (sku, product_id, position, price) values (?, ?, 1, ?)")
        .params(sku, id, price)
        .update();
    jdbc.sql("insert into product.stock (sku, quantity) values (?, ?)").params(sku, stock).update();
    return id;
  }

  private int stock(String sku) {
    return jdbc.sql("select quantity from product.stock where sku = ?")
        .param(sku)
        .query(Integer.class)
        .single();
  }

  private int sold(String sku) {
    return jdbc.sql("select sold from product.flash_sale_items where sku = ?")
        .param(sku)
        .query(Integer.class)
        .single();
  }

  private void saleOf(String sku, long price, int saleStock) {
    UUID sale = UUID.randomUUID();
    jdbc.sql("insert into product.flash_sales (id, starts_at, ends_at) values (?, ?, ?)")
        .params(
            sale,
            java.sql.Timestamp.from(NOW.minusSeconds(3600)),
            java.sql.Timestamp.from(NOW.plusSeconds(3600)))
        .update();
    jdbc.sql(
            "insert into product.flash_sale_items (sale_id, sku, sale_price, sale_stock)"
                + " values (?, ?, ?, ?)")
        .params(sale, sku, price, saleStock)
        .update();
  }

  @Test
  void twentyParallelHoldsOfThreeAgainstStockOfFortyLetThirteenSucceed() throws Exception {
    product("SKU-RACE", 100_000, 40);
    int callers = 20;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<HoldResult>> results = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      results.add(
          pool.submit(() -> {
            start.await();
            return tx.execute(
                s -> holds.hold(List.of(new Line("SKU-RACE", 3)), MemberTier.STANDARD, NOW));
          }));
    }
    start.countDown();
    int succeeded = 0;
    int shortOnes = 0;
    for (Future<HoldResult> result : results) {
      if (result.get(30, TimeUnit.SECONDS).succeeded()) {
        succeeded++;
      } else {
        shortOnes++;
      }
    }
    pool.shutdown();

    assertThat(succeeded).isEqualTo(13);
    assertThat(shortOnes).isEqualTo(7);
    assertThat(stock("SKU-RACE")).isEqualTo(1);
  }

  @Test
  void twoHoldsOfTheSameSkusInOppositeOrderRunInParallelWithoutDeadlock() throws Exception {
    product("SKU-A", 100_000, 1000);
    product("SKU-B", 100_000, 1000);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Line> forward = List.of(new Line("SKU-A", 1), new Line("SKU-B", 1));
    List<Line> backward = List.of(new Line("SKU-B", 1), new Line("SKU-A", 1));
    List<Future<Integer>> futures = new ArrayList<>();
    for (List<Line> lines : List.of(forward, backward)) {
      futures.add(
          pool.submit(() -> {
            start.await();
            int ok = 0;
            for (int i = 0; i < 100; i++) {
              ok +=
                  tx.execute(s -> holds.hold(lines, MemberTier.STANDARD, NOW).succeeded()) ? 1 : 0;
            }
            return ok;
          }));
    }
    start.countDown();
    for (Future<Integer> future : futures) {
      assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo(100);
    }
    pool.shutdown();

    assertThat(stock("SKU-A")).isEqualTo(800);
    assertThat(stock("SKU-B")).isEqualTo(800);
  }

  @Test
  void flashSaleStockIsTakenAndGivenBack() {
    product("SKU-SALE", 890_000, 10);
    saleOf("SKU-SALE", 700_000, 3);

    HoldResult first =
        tx.execute(s -> holds.hold(List.of(new Line("SKU-SALE", 2)), MemberTier.GOLD, NOW));
    assertThat(first.held().get(0).unitPrice()).isEqualTo(665_000);
    assertThat(first.held().get(0).tookSaleStock()).isTrue();
    assertThat(sold("SKU-SALE")).isEqualTo(2);

    HoldResult second =
        tx.execute(s -> holds.hold(List.of(new Line("SKU-SALE", 2)), MemberTier.STANDARD, NOW));
    assertThat(second.held().get(0).unitPrice()).isEqualTo(890_000);
    assertThat(second.held().get(0).tookSaleStock()).isFalse();
    assertThat(sold("SKU-SALE")).isEqualTo(2);
    assertThat(stock("SKU-SALE")).isEqualTo(6);

    tx.executeWithoutResult(s ->
        holds.release(
            List.of(first.held().get(0).toReleaseLine(), second.held().get(0).toReleaseLine())));
    assertThat(sold("SKU-SALE")).isZero();
    assertThat(stock("SKU-SALE")).isEqualTo(10);
  }

  @Test
  void nothingIsPublishedWhenTheCallersTransactionRollsBack() {
    product("SKU-RB", 100_000, 5);
    int before = publications("SKU-RB");

    tx.executeWithoutResult(s -> {
      holds.hold(List.of(new Line("SKU-RB", 1)), MemberTier.STANDARD, NOW);
      s.setRollbackOnly();
    });

    assertThat(stock("SKU-RB")).isEqualTo(5);
    assertThat(publications("SKU-RB")).isEqualTo(before);

    tx.executeWithoutResult(
        s -> holds.hold(List.of(new Line("SKU-RB", 1)), MemberTier.STANDARD, NOW));
    assertThat(publications("SKU-RB")).isEqualTo(before + 1);
  }

  private int publications(String sku) {
    return jdbc.sql("select count(*) from modulith.event_publication where serialized_event like ?")
        .param("%" + sku + "%")
        .query(Integer.class)
        .single();
  }
}
