package com.mayshop.core.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.order.internal.OrderRecovery;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Recovery of online orders whose payment never resolved, with a fixed clock. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"checkout.sweep.enabled=false", "order.recovery.enabled=false"})
@Import(OrderSupport.Configuration.class)
class OrderRecoveryDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final String KEY = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  @Autowired private JdbcClient jdbc;
  @Autowired private OrderRecovery recovery;
  @Autowired private MeterRegistry meters;

  @BeforeEach
  void clean() {
    OrderFixtures.reset(jdbc);
  }

  /** A consumed reservation, a PENDING_PAYMENT order created at {@code createdAt} and its key. */
  private UUID pendingOrder(Instant createdAt) {
    UUID reservation =
        OrderFixtures.reservation(
            jdbc, OrderSupport.USER, "CONSUMED", OrderSupport.NOW.plusSeconds(600));
    UUID order = UUID.randomUUID();
    jdbc.sql(
            """
            insert into orders.orders (id, number, user_id, reservation_id, status, subtotal,
                shipping_option, shipping_fee, total, payment_method, payment_status,
                shipping_address, created_at, updated_at)
            values (?, 'MY-1212-0001', ?, ?, 'PENDING_PAYMENT', 1691000, 'EXPRESS_2H', 35000,
                1726000, 'EWALLET', 'PENDING', '{}'::jsonb, ?, ?)
            """)
        .params(
            order,
            UUID.fromString(OrderSupport.USER),
            reservation,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt))
        .update();
    jdbc.sql(
            "insert into orders.order_items (order_id, sku, quantity, unit_price, line_total)"
                + " values (?, 'SKU-A', 2, 845500, 1691000)")
        .param(order)
        .update();
    jdbc.sql(
            "insert into orders.idempotency_keys (user_id, key, request_hash, state, order_id,"
                + " created_at) values (?, ?, 'hash', 'IN_PROGRESS', ?, ?)")
        .params(UUID.fromString(OrderSupport.USER), KEY, order, Timestamp.from(createdAt))
        .update();
    return order;
  }

  private double recovered() {
    var counter = meters.find("order.payment.recovered").counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void anOrderPendingForMoreThanTwoMinutesIsFailedAndItsStockGivenBackOnce() {
    UUID order = pendingOrder(OrderSupport.NOW);
    double before = recovered();
    Instant later = OrderSupport.NOW.plus(Duration.ofMinutes(2)).plusSeconds(1);

    assertThat(recovery.recoverStale(later, 100)).isEqualTo(1);

    assertThat(OrderFixtures.single(jdbc, "select status from orders.orders where id = ?", order))
        .isEqualTo("PAYMENT_FAILED");
    assertThat(
            OrderFixtures.single(
                jdbc, "select payment_status from orders.orders where id = ?", order))
        .isEqualTo("TIMED_OUT");
    assertThat(OrderFixtures.stock(jdbc)).isEqualTo(5);
    assertThat(recovered() - before).isEqualTo(1);
    assertThat(OrderFixtures.single(jdbc, "select state from orders.idempotency_keys"))
        .isEqualTo("COMPLETED");
    assertThat(
            OrderFixtures.single(jdbc, "select response_status::text from orders.idempotency_keys"))
        .isEqualTo("201");
    assertThat(
            OrderFixtures.single(
                jdbc, "select response_body->>'status' from orders.idempotency_keys"))
        .isEqualTo("PAYMENT_FAILED");
    assertThat(
            OrderFixtures.single(
                jdbc, "select response_body->'payment'->>'status' from orders.idempotency_keys"))
        .isEqualTo("TIMED_OUT");

    assertThat(recovery.recoverStale(later, 100)).isZero();
    assertThat(OrderFixtures.stock(jdbc)).isEqualTo(5);
  }

  @Test
  void anOrderPendingForLessThanTwoMinutesIsLeftAlone() {
    UUID order = pendingOrder(OrderSupport.NOW);

    assertThat(recovery.recoverStale(OrderSupport.NOW.plusSeconds(119), 100)).isZero();

    assertThat(OrderFixtures.single(jdbc, "select status from orders.orders where id = ?", order))
        .isEqualTo("PENDING_PAYMENT");
    assertThat(OrderFixtures.stock(jdbc)).isEqualTo(3);
  }
}
