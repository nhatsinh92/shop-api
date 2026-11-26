package com.mayshop.core.product.persistence;

import com.mayshop.core.product.persistence.CatalogQueries.CardRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link FlashSaleQueries} on the {@code product} schema. */
@Repository
class JdbcFlashSaleQueries implements FlashSaleQueries {

  /**
   * A sale covers whole products: one row per product, taken from its summary (lowest-position)
   * variant, so a product with several variants in the sale appears once.
   */
  private static final String ITEMS =
      """
      select p.id, p.slug, p.name, p.created_at, v.sku, v.price, v.list_price,
             (select im.url from product.images im
               where im.product_id = p.id order by im.position limit 1) as image_url,
             i.sale_price, i.sale_stock, i.sold
        from product.flash_sale_items i
        join product.variants v on v.sku = i.sku
        join product.products p on p.id = v.product_id
       where i.sale_id = ?
         and v.position = (select min(v2.position) from product.variants v2
                            where v2.product_id = p.id)
       order by p.created_at desc, p.id desc
      """;

  private final JdbcClient jdbc;
  private final String placeholderImageUrl;

  JdbcFlashSaleQueries(
      JdbcClient jdbc, @Value("${product.placeholder-image-url}") String placeholderImageUrl) {
    this.jdbc = jdbc;
    this.placeholderImageUrl = placeholderImageUrl;
  }

  /**
   * The items are read after the sale query has returned: reading them from inside its row mapper
   * would take a second pool connection while the first is still held, which deadlocks the pool
   * under concurrent requests.
   */
  @Override
  public Optional<SaleRow> findCurrent(Instant now) {
    return jdbc.sql(
            """
            select id, starts_at, ends_at from product.flash_sales
             where starts_at <= ? and ? < ends_at
             order by starts_at desc, id limit 1
            """)
        .params(
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .query((rs, n) ->
            new SaleHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant()))
        .optional()
        .map(sale -> new SaleRow(sale.id(), sale.startsAt(), sale.endsAt(), items(sale.id())));
  }

  private record SaleHeader(UUID id, Instant startsAt, Instant endsAt) {}

  private List<ItemRow> items(UUID saleId) {
    return jdbc.sql(ITEMS)
        .param(saleId)
        .query((rs, n) -> {
          long listPrice = rs.getLong("list_price");
          Long list = rs.wasNull() ? null : listPrice;
          String image = rs.getString("image_url");
          CardRow card =
              new CardRow(
                  rs.getObject("id", UUID.class),
                  rs.getString("slug"),
                  rs.getString("name"),
                  image != null ? image : placeholderImageUrl,
                  rs.getString("sku"),
                  rs.getLong("price"),
                  list,
                  rs.getObject("created_at", OffsetDateTime.class).toInstant());
          return new ItemRow(
              card, rs.getLong("sale_price"), rs.getInt("sale_stock"), rs.getInt("sold"));
        })
        .list();
  }
}
