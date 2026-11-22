package com.mayshop.core.product.persistence;

import com.mayshop.core.product.PriceRule.SalePrice;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AvailabilityQueries} on the {@code product} schema. */
@Repository
class JdbcAvailabilityQueries implements AvailabilityQueries {

  private final JdbcClient jdbc;

  JdbcAvailabilityQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AvailabilityRow> findBySku(String sku) {
    return jdbc.sql(
            """
            select v.product_id, v.sku, v.price, coalesce(s.quantity, 0) as quantity
              from product.variants v
              left join product.stock s on s.sku = v.sku
             where v.sku = ?
            """)
        .param(sku)
        .query((rs, n) ->
            new AvailabilityRow(
                rs.getObject("product_id", UUID.class),
                rs.getString("sku"),
                rs.getLong("price"),
                rs.getInt("quantity"),
                salePrices(sku)))
        .optional();
  }

  private List<SalePrice> salePrices(String sku) {
    return jdbc.sql(
            """
            select i.sale_price, f.starts_at, f.ends_at
              from product.flash_sale_items i
              join product.flash_sales f on f.id = i.sale_id
             where i.sku = ?
            """)
        .param(sku)
        .query((rs, n) ->
            new SalePrice(
                rs.getLong("sale_price"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant()))
        .list();
  }
}
