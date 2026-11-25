package com.mayshop.core.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Database rows and request bodies shared by the online payment tests. */
final class OrderFixtures {

  private OrderFixtures() {}

  /** Empties the tables the order tests touch and seeds SKU-A with stock 3 (two are held). */
  static void reset(JdbcClient jdbc) {
    jdbc.sql("delete from orders.orders").update();
    jdbc.sql("delete from orders.idempotency_keys").update();
    jdbc.sql("delete from checkout.reservations").update();
    jdbc.sql("delete from product.products").update();
    jdbc.sql("delete from product.categories").update();
    jdbc.sql("insert into product.categories (slug, name) values ('c', 'C')").update();
    UUID product = UUID.randomUUID();
    jdbc.sql(
            "insert into product.products (id, slug, name, brand, category_slug)"
                + " values (?, 'slug-a', 'Name A', 'B', 'c')")
        .param(product)
        .update();
    jdbc.sql(
            "insert into product.variants (sku, product_id, position, price)"
                + " values ('SKU-A', ?, 1, 890000)")
        .param(product)
        .update();
    jdbc.sql("insert into product.stock (sku, quantity) values ('SKU-A', 3)").update();
  }

  /** A reservation of two SKU-A at 845 500 each; the stock of the two units is already held. */
  static UUID reservation(JdbcClient jdbc, String user, String status, Instant expiresAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into checkout.reservations (id, user_id, status, created_at, expires_at)"
                + " values (?, ?, ?, ?, ?)")
        .params(
            id,
            UUID.fromString(user),
            status,
            Timestamp.from(OrderSupport.NOW.minusSeconds(60)),
            Timestamp.from(expiresAt))
        .update();
    jdbc.sql(
            "insert into checkout.reservation_items (reservation_id, sku, position, quantity,"
                + " unit_price, base_price, price_changed, took_sale_stock, name, image_url)"
                + " values (?, 'SKU-A', 1, 2, 845500, 890000, false, false, 'Name A',"
                + " 'https://x/a.jpg')")
        .param(id)
        .update();
    return id;
  }

  static UUID activeReservation(JdbcClient jdbc) {
    return reservation(jdbc, OrderSupport.USER, "ACTIVE", OrderSupport.NOW.plusSeconds(600));
  }

  static String body(UUID reservation, String method) {
    return """
    {"reservationId": "%s", "shippingOptionId": "EXPRESS_2H", "paymentMethod": "%s",
     "shippingAddress": {"fullName": "Nguyen Van An", "phone": "0901234567",
       "line1": "12 Nguyen Hue", "ward": "Ben Nghe", "province": "Ho Chi Minh City"},
     "note": "Call first"}
    """
        .formatted(reservation, method);
  }

  static int stock(JdbcClient jdbc) {
    return jdbc.sql("select quantity from product.stock where sku = 'SKU-A'")
        .query(Integer.class)
        .single();
  }

  static String single(JdbcClient jdbc, String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(String.class).single();
  }
}
