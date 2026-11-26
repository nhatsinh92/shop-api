package com.mayshop.core.product.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link SaleWindowStore} on the {@code product} schema. */
@Repository
class JdbcSaleWindowStore implements SaleWindowStore {

  private final JdbcClient jdbc;

  JdbcSaleWindowStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void recordPassed(Instant now) {
    // Ordered, so two jobs inserting at once take the row locks in the same order.
    jdbc.sql(
            """
            insert into product.sale_window_boundary (sale_id, boundary)
            select f.id, b.boundary
              from product.flash_sales f
              cross join (values ('START'), ('END')) as b (boundary)
             where case b.boundary when 'START' then f.starts_at else f.ends_at end <= ?
             order by f.id, b.boundary
            on conflict (sale_id, boundary) do nothing
            """)
        .param(Timestamp.from(now))
        .update();
  }

  @Override
  public List<Due> lockDue(Instant now, int limit) {
    return jdbc.sql(
            """
            select w.sale_id, w.boundary,
                   case w.boundary when 'START' then f.starts_at else f.ends_at end as boundary_at
              from product.sale_window_boundary w
              join product.flash_sales f on f.id = w.sale_id
             where not w.fired
               and case w.boundary when 'START' then f.starts_at else f.ends_at end <= ?
             order by boundary_at, w.sale_id, w.boundary
             limit ?
               for update of w skip locked
            """)
        .param(Timestamp.from(now))
        .param(limit)
        .query((rs, n) ->
            new Due(
                rs.getObject("sale_id", UUID.class),
                Boundary.valueOf(rs.getString("boundary")),
                rs.getTimestamp("boundary_at").toInstant()))
        .list();
  }

  @Override
  public List<Sku> skus(UUID saleId) {
    return jdbc.sql(
            """
            select i.sku, v.product_id, p.slug
              from product.flash_sale_items i
              join product.variants v on v.sku = i.sku
              join product.products p on p.id = v.product_id
             where i.sale_id = ?
             order by i.sku
            """)
        .param(saleId)
        .query((rs, n) ->
            new Sku(
                rs.getString("sku"), rs.getObject("product_id", UUID.class), rs.getString("slug")))
        .list();
  }

  @Override
  public void markFired(UUID saleId, Boundary boundary, Instant now) {
    jdbc.sql(
            """
            update product.sale_window_boundary set fired = true, fired_at = ?
             where sale_id = ? and boundary = ?
            """)
        .param(Timestamp.from(now))
        .param(saleId)
        .param(boundary.name())
        .update();
  }
}
