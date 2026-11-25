package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.product.MemberTier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The expiry sweep on Postgres with a movable clock. The scheduled job is off. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "checkout.sweep.enabled=false")
@Import({CheckoutSupport.JwtConfiguration.class, ReservationSweepTest.MovableClock.class})
class ReservationSweepTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  /** A clock the test moves. */
  static final class Mutable extends Clock {
    private volatile Instant now = Instant.parse("2026-12-12T01:00:00Z");

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

  @TestConfiguration
  static class MovableClock {
    @Bean
    @Primary
    Mutable testClock() {
      return new Mutable();
    }
  }

  @Autowired private Reservations reservations;
  @Autowired private Mutable clock;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void clean() {
    clock.now = Instant.parse("2026-12-12T01:00:00Z");
    jdbc.sql("delete from checkout.reservations").update();
    jdbc.sql("delete from product.flash_sales").update();
    jdbc.sql("delete from product.products").update();
    jdbc.sql("delete from product.categories").update();
    jdbc.sql("insert into product.categories (slug, name) values ('c', 'C')").update();
  }

  private void product(String sku, int stock) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into product.products (id, slug, name, brand, category_slug)"
                + " values (?, ?, ?, 'B', 'c')")
        .params(id, "slug-" + sku, "Name " + sku)
        .update();
    jdbc.sql(
            "insert into product.variants (sku, product_id, position, price) values (?, ?, 1, 1000)")
        .params(sku, id)
        .update();
    jdbc.sql("insert into product.stock (sku, quantity) values (?, ?)").params(sku, stock).update();
  }

  private int stock(String sku) {
    return jdbc.sql("select quantity from product.stock where sku = ?")
        .param(sku)
        .query(Integer.class)
        .single();
  }

  private Reservation reserve(String sku, int quantity) {
    return reservations.create(
        UUID.randomUUID(),
        MemberTier.STANDARD,
        List.of(new Reservations.Line(sku, quantity, null)));
  }

  private String status(UUID id) {
    return jdbc.sql("select status from checkout.reservations where id = ?")
        .param(id)
        .query(String.class)
        .single();
  }

  @Test
  void sweepGivesTheStockBackOnceAfterExpiry() {
    product("SKU-1", 10);
    Reservation r = reserve("SKU-1", 4);
    assertThat(stock("SKU-1")).isEqualTo(6);

    clock.now = r.expiresAt().plusSeconds(1);
    assertThat(reservations.expireDue(clock.instant(), 100)).isEqualTo(1);
    assertThat(stock("SKU-1")).isEqualTo(10);
    assertThat(status(r.id())).isEqualTo("EXPIRED");

    assertThat(reservations.expireDue(clock.instant(), 100)).isZero();
    assertThat(stock("SKU-1")).isEqualTo(10);
  }

  @Test
  void sweepLeavesAReservationThatIsNotDue() {
    product("SKU-1", 10);
    Reservation r = reserve("SKU-1", 4);

    clock.now = r.expiresAt().minusSeconds(1);

    assertThat(reservations.expireDue(clock.instant(), 100)).isZero();
    assertThat(stock("SKU-1")).isEqualTo(6);
    assertThat(status(r.id())).isEqualTo("ACTIVE");
  }

  @Test
  void sweepTakesAtMostTheBatchSize() {
    product("SKU-1", 10);
    Reservation a = reserve("SKU-1", 1);
    reserve("SKU-1", 1);
    clock.now = a.expiresAt().plus(Duration.ofMinutes(1));

    assertThat(reservations.expireDue(clock.instant(), 1)).isEqualTo(1);
    assertThat(reservations.expireDue(clock.instant(), 1)).isEqualTo(1);
    assertThat(stock("SKU-1")).isEqualTo(10);
  }

  @Test
  void expireReleasesStockOnlyOnceEvenWhenCalledTwice() {
    product("SKU-1", 10);
    Reservation r = reserve("SKU-1", 3);
    Instant later = r.expiresAt().plusSeconds(1);

    assertThat(reservations.expire(r.id(), later)).isTrue();
    assertThat(reservations.expire(r.id(), later)).isFalse();
    assertThat(stock("SKU-1")).isEqualTo(10);
  }

  @Test
  void getReportsExpiredBeforeAnySweep() {
    product("SKU-1", 10);
    UUID user = UUID.randomUUID();
    Reservation r =
        reservations.create(
            user, MemberTier.STANDARD, List.of(new Reservations.Line("SKU-1", 1, null)));

    clock.now = r.expiresAt();

    assertThat(reservations.find(r.id(), user).orElseThrow().status())
        .isEqualTo(Reservation.Status.EXPIRED);
    assertThat(status(r.id())).isEqualTo("ACTIVE");
  }

  @Test
  void twoSweepsInParallelReleaseEachReservationOnce() throws Exception {
    product("SKU-1", 100);
    Reservation last = null;
    for (int i = 0; i < 20; i++) {
      last = reserve("SKU-1", 1);
    }
    assertThat(stock("SKU-1")).isEqualTo(80);
    clock.now = last.expiresAt().plusSeconds(1);
    Instant now = clock.instant();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      Callable<Integer> sweep =
          () -> {
            go.await();
            return reservations.expireDue(now, 100);
          };
      Future<Integer> one = pool.submit(sweep);
      Future<Integer> two = pool.submit(sweep);
      go.countDown();
      assertThat(one.get(30, TimeUnit.SECONDS) + two.get(30, TimeUnit.SECONDS)).isEqualTo(20);
    } finally {
      pool.shutdownNow();
    }
    assertThat(stock("SKU-1")).isEqualTo(100);
    assertThat(
            jdbc.sql("select count(*) from checkout.reservations where status = 'EXPIRED'")
                .query(Integer.class)
                .single())
        .isEqualTo(20);
  }
}
