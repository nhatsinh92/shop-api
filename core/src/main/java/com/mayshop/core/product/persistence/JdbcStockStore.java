package com.mayshop.core.product.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link StockStore} on the {@code product} schema. */
@Repository
class JdbcStockStore implements StockStore {

  private final JdbcClient jdbc;

  JdbcStockStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean take(String sku, int quantity) {
    return jdbc.sql(
                """
                update product.stock set quantity = quantity - ?, updated_at = now()
                 where sku = ? and quantity >= ?
                """)
            .param(quantity)
            .param(sku)
            .param(quantity)
            .update()
        == 1;
  }

  @Override
  public int available(String sku) {
    return jdbc.sql("select quantity from product.stock where sku = ?")
        .param(sku)
        .query(Integer.class)
        .optional()
        .orElse(0);
  }

  @Override
  public void give(String sku, int quantity) {
    jdbc.sql(
            """
            insert into product.stock (sku, quantity, updated_at) values (?, ?, now())
            on conflict (sku) do update
              set quantity = product.stock.quantity + excluded.quantity, updated_at = now()
            """)
        .param(sku)
        .param(quantity)
        .update();
  }

  @Override
  public Optional<SaleItem> currentSaleItem(String sku, Instant now) {
    Timestamp at = Timestamp.from(now);
    return jdbc.sql(
            """
            select i.sale_id, i.sku, i.sale_price
              from product.flash_sale_items i
              join product.flash_sales f on f.id = i.sale_id
             where i.sku = ? and f.starts_at <= ? and ? < f.ends_at
             order by i.sale_price, i.sale_id
             limit 1
            """)
        .param(sku)
        .param(at)
        .param(at)
        .query((rs, n) ->
            new SaleItem(
                rs.getObject("sale_id", UUID.class), rs.getString("sku"), rs.getLong("sale_price")))
        .optional();
  }

  @Override
  public boolean takeSaleStock(SaleItem item, int quantity) {
    return jdbc.sql(
                """
                update product.flash_sale_items set sold = sold + ?
                 where sale_id = ? and sku = ? and sold + ? <= sale_stock
                """)
            .param(quantity)
            .param(item.saleId())
            .param(item.sku())
            .param(quantity)
            .update()
        == 1;
  }

  @Override
  public void giveSaleStock(String sku, int quantity) {
    jdbc.sql(
            """
            update product.flash_sale_items set sold = sold - ?
             where sku = ? and sale_id = (
               select i.sale_id
                 from product.flash_sale_items i
                 join product.flash_sales f on f.id = i.sale_id
                where i.sku = ? and i.sold >= ?
                order by f.starts_at desc
                limit 1)
            """)
        .param(quantity)
        .param(sku)
        .param(sku)
        .param(quantity)
        .update();
  }
}
