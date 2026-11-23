package com.mayshop.core.product.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link CatalogQueries} on the {@code product} schema. */
@Repository
class JdbcCatalogQueries implements CatalogQueries, RecommendationQueries {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<OptionRow>> OPTIONS = new TypeReference<>() {};

  /** The summary variant of a product is the one with the lowest position. */
  private static final String CARD_SELECT =
      """
      select p.id, p.slug, p.name, p.created_at, p.category_slug, v.sku, v.price, v.list_price,
             (select i.url from product.images i
               where i.product_id = p.id order by i.position limit 1) as image_url
        from product.products p
        join lateral (select sku, price, list_price from product.variants
                       where product_id = p.id order by position limit 1) v on true
      """;

  /** The slugs of a category and all its descendants, as the CTE {@code subtree}. */
  private static final String CATEGORY_SUBTREE =
      """
      with recursive subtree(slug) as (
        select slug from product.categories where slug = ?
        union all
        select c.slug from product.categories c join subtree t on c.parent_slug = t.slug)
      """;

  private final JdbcClient jdbc;
  private final String placeholderImageUrl;

  JdbcCatalogQueries(
      JdbcClient jdbc, @Value("${product.placeholder-image-url}") String placeholderImageUrl) {
    this.jdbc = jdbc;
    this.placeholderImageUrl = placeholderImageUrl;
  }

  @Override
  public List<CardRow> page(String category, PageKey after, int limit) {
    StringBuilder sql = new StringBuilder();
    List<Object> params = new ArrayList<>();
    if (category != null) {
      // The filter covers the category and all its descendants.
      sql.append(CATEGORY_SUBTREE);
      params.add(category);
    }
    sql.append(CARD_SELECT).append(" where true");
    if (category != null) {
      sql.append(" and p.category_slug in (select slug from subtree)");
    }
    if (after != null) {
      sql.append(" and (p.created_at, p.id) < (?, ?)");
      params.add(OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC));
      params.add(after.id());
    }
    sql.append(" order by p.created_at desc, p.id desc limit ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query(this::card).list();
  }

  @Override
  public Optional<List<CardRow>> relatedTo(String productSlug, int limit) {
    Optional<String> category =
        jdbc.sql("select category_slug from product.products where slug = ?")
            .param(productSlug)
            .query(String.class)
            .optional();
    if (category.isEmpty()) {
      return Optional.empty();
    }
    String sql =
        """
        with recursive parent(slug) as (
          select coalesce(
                   (select parent_slug from product.categories where slug = ?), ?)),
        subtree(slug) as (
          select slug from parent
          union all
          select c.slug from product.categories c join subtree t on c.parent_slug = t.slug)
        select * from (
        """
            + CARD_SELECT
            + """
            where p.slug <> ? and p.category_slug in (select slug from subtree)) related
            order by (case when category_slug = ? then 0 else 1 end), created_at desc, id desc
            limit ?
            """;
    return Optional.of(
        jdbc.sql(sql)
            .params(category.get(), category.get(), productSlug, category.get(), limit)
            .query(this::card)
            .list());
  }

  @Override
  public Optional<DetailRow> findBySlug(String slug) {
    Optional<CardRow> card =
        jdbc.sql(CARD_SELECT + " where p.slug = ?").param(slug).query(this::card).optional();
    if (card.isEmpty()) {
      return Optional.empty();
    }
    UUID id = card.get().id();
    var header =
        jdbc.sql("select brand, description, category_slug from product.products where id = ?")
            .param(id)
            .query((rs, n) ->
                new String[] {
                  rs.getString("brand"), rs.getString("description"), rs.getString("category_slug")
                })
            .single();
    List<ImageRow> images =
        jdbc.sql("select url, alt from product.images where product_id = ? order by position")
            .param(id)
            .query((rs, n) -> new ImageRow(rs.getString("url"), rs.getString("alt")))
            .list();
    List<VariantRow> variants =
        jdbc.sql(
                "select sku, options::text as options, price from product.variants"
                    + " where product_id = ? order by position")
            .param(id)
            .query((rs, n) ->
                new VariantRow(
                    rs.getString("sku"),
                    JSON.readValue(rs.getString("options"), OPTIONS),
                    rs.getLong("price")))
            .list();
    return Optional.of(
        new DetailRow(card.get(), header[0], header[1], images, categoryPath(header[2]), variants));
  }

  /** The category and its ancestors, root first. */
  private List<CategoryRow> categoryPath(String slug) {
    List<CategoryRow> path =
        new ArrayList<>(
            jdbc.sql(
                    """
                    with recursive chain(slug, name, parent_slug, depth) as (
                      select slug, name, parent_slug, 0 from product.categories where slug = ?
                      union all
                      select c.slug, c.name, c.parent_slug, chain.depth + 1
                        from product.categories c join chain on c.slug = chain.parent_slug)
                    select slug, name from chain order by depth
                    """)
                .param(slug)
                .query((rs, n) -> new CategoryRow(rs.getString("slug"), rs.getString("name")))
                .list());
    Collections.reverse(path);
    return path;
  }

  private CardRow card(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    long listPrice = rs.getLong("list_price");
    String image = rs.getString("image_url");
    return new CardRow(
        rs.getObject("id", UUID.class),
        rs.getString("slug"),
        rs.getString("name"),
        image != null ? image : placeholderImageUrl,
        rs.getString("sku"),
        rs.getLong("price"),
        rs.wasNull() ? null : listPrice,
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
