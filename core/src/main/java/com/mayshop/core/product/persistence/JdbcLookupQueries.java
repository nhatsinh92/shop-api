package com.mayshop.core.product.persistence;

import com.mayshop.core.product.PriceRule.SalePrice;
import com.mayshop.core.product.persistence.AvailabilityQueries.AvailabilityRow;
import com.mayshop.core.product.persistence.CatalogQueries.OptionRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link LookupQueries} on the {@code product} schema: two queries whatever the SKU count. */
@Repository
class JdbcLookupQueries implements LookupQueries {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<OptionRow>> OPTIONS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final String placeholderImageUrl;

  JdbcLookupQueries(
      JdbcClient jdbc, @Value("${product.placeholder-image-url}") String placeholderImageUrl) {
    this.jdbc = jdbc;
    this.placeholderImageUrl = placeholderImageUrl;
  }

  @Override
  public List<LookupRow> findBySkus(Collection<String> skus) {
    if (skus.isEmpty()) {
      return List.of();
    }
    String[] array = skus.toArray(String[]::new);
    Map<String, List<SalePrice>> sales = salePrices(array);
    return jdbc.sql(
            """
            select v.sku, v.options::text as options, v.price, v.list_price,
                   p.id as product_id, p.slug, p.name,
                   coalesce(s.quantity, 0) as quantity,
                   (select count(*) from product.variants o where o.product_id = p.id)
                     as variant_count,
                   (select i.url from product.images i
                     where i.product_id = p.id order by i.position limit 1) as image_url
              from product.variants v
              join product.products p on p.id = v.product_id
              left join product.stock s on s.sku = v.sku
             where v.sku = any (?)
            """)
        .param(array)
        .query((rs, n) -> {
          String sku = rs.getString("sku");
          long listPrice = rs.getLong("list_price");
          Long list = rs.wasNull() ? null : listPrice;
          String image = rs.getString("image_url");
          var availability =
              new AvailabilityRow(
                  rs.getObject("product_id", UUID.class),
                  sku,
                  rs.getLong("price"),
                  rs.getInt("quantity"),
                  sales.getOrDefault(sku, List.of()));
          return new LookupRow(
              sku,
              rs.getString("slug"),
              rs.getString("name"),
              image != null ? image : placeholderImageUrl,
              JSON.readValue(rs.getString("options"), OPTIONS),
              rs.getInt("variant_count"),
              list,
              availability);
        })
        .list();
  }

  private Map<String, List<SalePrice>> salePrices(String[] skus) {
    Map<String, List<SalePrice>> bySku = new HashMap<>();
    jdbc.sql(
            """
            select i.sku, i.sale_price, f.starts_at, f.ends_at
              from product.flash_sale_items i
              join product.flash_sales f on f.id = i.sale_id
             where i.sku = any (?)
            """)
        .param(skus)
        .query((rs, n) -> {
          bySku
              .computeIfAbsent(rs.getString("sku"), k -> new ArrayList<>())
              .add(
                  new SalePrice(
                      rs.getLong("sale_price"),
                      rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                      rs.getObject("ends_at", OffsetDateTime.class).toInstant()));
          return null;
        })
        .list();
    return bySku;
  }
}
