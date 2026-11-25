package com.mayshop.core.order.persistence;

import com.mayshop.core.order.internal.OrderView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the {@code orders} and {@code order_items} tables. */
@Repository
public class OrderStore {

  private final JdbcClient jdbc;

  OrderStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** What an order row needs besides its lines. */
  public record NewOrder(
      UUID id,
      String number,
      UUID userId,
      UUID reservationId,
      String status,
      long subtotal,
      String shippingOption,
      long shippingFee,
      String paymentMethod,
      String paymentStatus,
      String shippingAddressJson,
      String note,
      Instant createdAt) {

    public long total() {
      return subtotal + shippingFee;
    }
  }

  /** One order line to store. */
  public record NewItem(String sku, int quantity, long unitPrice) {}

  /** Inserts the order and its lines in the caller's transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(NewOrder order, List<NewItem> items) {
    jdbc.sql(
            """
            insert into orders.orders (id, number, user_id, reservation_id, status, subtotal,
                shipping_option, shipping_fee, total, payment_method, payment_status,
                shipping_address, note, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            """)
        .param(order.id())
        .param(order.number())
        .param(order.userId())
        .param(order.reservationId())
        .param(order.status())
        .param(order.subtotal())
        .param(order.shippingOption())
        .param(order.shippingFee())
        .param(order.total())
        .param(order.paymentMethod())
        .param(order.paymentStatus())
        .param(order.shippingAddressJson())
        .param(order.note())
        .param(Timestamp.from(order.createdAt()))
        .param(Timestamp.from(order.createdAt()))
        .update();
    for (NewItem item : items) {
      jdbc.sql(
              """
              insert into orders.order_items (order_id, sku, quantity, unit_price, line_total)
              values (?, ?, ?, ?, ?)
              """)
          .param(order.id())
          .param(item.sku())
          .param(item.quantity())
          .param(item.unitPrice())
          .param(item.unitPrice() * item.quantity())
          .update();
    }
  }

  /** The order, only when it belongs to {@code userId}. */
  @Transactional(readOnly = true)
  public Optional<OrderView> find(UUID id, UUID userId) {
    return jdbc.sql(
            """
            select id, number, status, subtotal, shipping_fee, total, payment_method,
                   payment_status, created_at
              from orders.orders where id = ? and user_id = ?
            """)
        .param(id)
        .param(userId)
        .query((rs, n) ->
            new OrderView(
                rs.getObject("id", UUID.class),
                rs.getString("number"),
                rs.getString("status"),
                List.of(),
                money(rs.getLong("subtotal")),
                money(rs.getLong("shipping_fee")),
                money(rs.getLong("total")),
                new OrderView.Payment(
                    rs.getString("payment_method"), rs.getString("payment_status")),
                rs.getTimestamp("created_at").toInstant().toString()))
        .optional()
        .map(order -> order.withItems(items(id)));
  }

  private List<OrderView.Item> items(UUID orderId) {
    return jdbc.sql(
            """
            select sku, quantity, unit_price, line_total from orders.order_items
             where order_id = ? order by sku
            """)
        .param(orderId)
        .query((rs, n) ->
            new OrderView.Item(
                rs.getString("sku"),
                rs.getInt("quantity"),
                money(rs.getLong("unit_price")),
                money(rs.getLong("line_total"))))
        .list();
  }

  private static OrderView.Money money(long amount) {
    return new OrderView.Money(amount, "VND");
  }
}
