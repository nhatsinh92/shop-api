package com.mayshop.core.product.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ProductUpdateStore} on the {@code product} schema. */
@Repository
class JdbcProductUpdateStore implements ProductUpdateStore {

  private final JdbcClient jdbc;

  JdbcProductUpdateStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Variant> lockBySku(String sku) {
    return jdbc.sql(
            """
            select v.product_id, p.slug, v.price, v.list_price,
                   coalesce((select s.quantity from product.stock s where s.sku = v.sku), 0)
                     as quantity
              from product.variants v
              join product.products p on p.id = v.product_id
             where v.sku = ?
               for update of v
            """)
        .param(sku)
        .query((rs, n) ->
            new Variant(
                rs.getObject("product_id", UUID.class),
                rs.getString("slug"),
                rs.getLong("price"),
                rs.getObject("list_price", Long.class),
                rs.getInt("quantity")))
        .optional();
  }

  @Override
  public void update(String sku, long price, Long listPrice, Integer stock) {
    jdbc.sql("update product.variants set price = ?, list_price = cast(? as bigint) where sku = ?")
        .param(price)
        .param(listPrice)
        .param(sku)
        .update();
    if (stock != null) {
      jdbc.sql(
              """
              insert into product.stock (sku, quantity, updated_at) values (?, ?, now())
              on conflict (sku) do update set quantity = excluded.quantity, updated_at = now()
              """)
          .param(sku)
          .param(stock)
          .update();
    }
  }

  @Override
  public boolean inCurrentFlashSale(String sku, Instant now) {
    return jdbc.sql(
            """
            select exists (
              select 1
                from product.flash_sale_items i
                join product.flash_sales f on f.id = i.sale_id
               where i.sku = ? and f.starts_at <= ? and ? < f.ends_at)
            """)
        .param(sku)
        .param(Timestamp.from(now))
        .param(Timestamp.from(now))
        .query(Boolean.class)
        .single();
  }
}
