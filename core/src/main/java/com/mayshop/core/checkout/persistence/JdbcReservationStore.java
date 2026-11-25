package com.mayshop.core.checkout.persistence;

import com.mayshop.core.checkout.Reservation;
import com.mayshop.core.checkout.Reservation.Item;
import com.mayshop.core.checkout.Reservation.Status;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ReservationStore} on the {@code checkout} schema. */
@Repository
class JdbcReservationStore implements ReservationStore {

  private final JdbcClient jdbc;

  JdbcReservationStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockUser(UUID userId) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("checkout.reservations:" + userId)
        .query()
        .singleRow();
  }

  @Override
  public List<Reservation> findActiveForUpdate(UUID userId) {
    List<Header> headers =
        jdbc.sql(
                """
                select id, user_id, status, created_at, expires_at from checkout.reservations
                 where user_id = ? and status = 'ACTIVE' for update
                """)
            .param(userId)
            .query((rs, n) -> header(rs))
            .list();
    return headers.stream().map(this::withItems).toList();
  }

  @Override
  public boolean markReleased(UUID id, Instant now) {
    return jdbc.sql(
                """
                update checkout.reservations set status = 'RELEASED', released_at = ?
                 where id = ? and status = 'ACTIVE' and released_at is null
                """)
            .param(Timestamp.from(now))
            .param(id)
            .update()
        == 1;
  }

  @Override
  public void insert(Reservation reservation) {
    jdbc.sql(
            """
            insert into checkout.reservations (id, user_id, status, created_at, expires_at)
            values (?, ?, ?, ?, ?)
            """)
        .param(reservation.id())
        .param(reservation.userId())
        .param(reservation.status().name())
        .param(Timestamp.from(reservation.createdAt()))
        .param(Timestamp.from(reservation.expiresAt()))
        .update();
    int position = 0;
    for (Item item : reservation.items()) {
      jdbc.sql(
              """
              insert into checkout.reservation_items
                (reservation_id, sku, position, quantity, unit_price, base_price, price_changed,
                 took_sale_stock, name, variant_label, image_url)
              values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              """)
          .params(
              java.util.Arrays.asList(
                  reservation.id(),
                  item.sku(),
                  position++,
                  item.quantity(),
                  item.unitPrice(),
                  item.basePrice(),
                  item.priceChanged(),
                  item.tookSaleStock(),
                  item.name(),
                  item.variantLabel(),
                  item.imageUrl()))
          .update();
    }
  }

  @Override
  public Optional<Reservation> find(UUID id, UUID userId) {
    return jdbc.sql(
            """
            select id, user_id, status, created_at, expires_at from checkout.reservations
             where id = ? and user_id = ?
            """)
        .param(id)
        .param(userId)
        .query((rs, n) -> header(rs))
        .optional()
        .map(this::withItems);
  }

  private Reservation withItems(Header header) {
    List<Item> items =
        jdbc.sql(
                """
                select sku, name, variant_label, image_url, quantity, unit_price, base_price,
                       price_changed, took_sale_stock
                  from checkout.reservation_items where reservation_id = ? order by position
                """)
            .param(header.id())
            .query((rs, n) ->
                new Item(
                    rs.getString("sku"),
                    rs.getString("name"),
                    rs.getString("variant_label"),
                    rs.getString("image_url"),
                    rs.getInt("quantity"),
                    rs.getLong("unit_price"),
                    rs.getLong("base_price"),
                    rs.getBoolean("price_changed"),
                    rs.getBoolean("took_sale_stock")))
            .list();
    return new Reservation(
        header.id(),
        header.userId(),
        header.status(),
        header.createdAt(),
        header.expiresAt(),
        items);
  }

  private static Header header(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Header(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        Status.valueOf(rs.getString("status")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant());
  }

  private record Header(
      UUID id, UUID userId, Status status, Instant createdAt, Instant expiresAt) {}
}
