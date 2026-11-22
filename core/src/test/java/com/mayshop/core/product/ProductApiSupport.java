package com.mayshop.core.product;

import com.mayshop.core.product.persistence.CatalogQueries;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Test doubles for the product endpoints that need no database. */
final class ProductApiSupport {

  static final String DB_FREE_EXCLUDES =
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

  private ProductApiSupport() {}

  static class InMemoryCatalogConfiguration {

    @Bean
    @Primary
    InMemoryCatalog inMemoryCatalog() {
      return new InMemoryCatalog();
    }

    // The JDBC implementation is still created; it never runs because the fake is primary.
    @Bean
    JdbcClient jdbcClient() {
      return Mockito.mock(JdbcClient.class);
    }
  }

  /** Same ordering and cursor semantics as the SQL: newest first, ties broken by id. */
  static class InMemoryCatalog implements CatalogQueries {

    private final List<Entry> entries = new ArrayList<>();

    record Entry(DetailRow detail, String category) {}

    private RuntimeException failure;

    void failWith(RuntimeException failure) {
      this.failure = failure;
    }

    void clear() {
      entries.clear();
    }

    void add(DetailRow detail, String category) {
      entries.add(new Entry(detail, category));
    }

    @Override
    public List<CardRow> page(String category, PageKey after, int limit) {
      if (failure != null) {
        throw failure;
      }
      Comparator<CardRow> newestFirst =
          Comparator.comparing(CardRow::createdAt).thenComparing(CardRow::id).reversed();
      return entries.stream()
          .filter(e -> category == null || category.equals(e.category()))
          .map(e -> e.detail().card())
          .sorted(newestFirst)
          .filter(c -> after == null || isAfter(c, after))
          .limit(limit)
          .toList();
    }

    private static boolean isAfter(CardRow card, PageKey key) {
      int byTime = card.createdAt().compareTo(key.createdAt());
      return byTime < 0 || (byTime == 0 && card.id().compareTo(key.id()) < 0);
    }

    @Override
    public Optional<DetailRow> findBySlug(String slug) {
      return entries.stream()
          .map(Entry::detail)
          .filter(d -> d.card().slug().equals(slug))
          .findFirst();
    }
  }

  static CatalogQueries.DetailRow product(
      int n, Instant createdAt, long price, Long listPrice, int variants) {
    UUID id = new UUID(0, n);
    String slug = "product-" + n;
    var card =
        new CatalogQueries.CardRow(
            id,
            slug,
            "Product " + n,
            "https://cdn.example.com/" + slug + "/1.jpg",
            "SKU-" + n + "-1",
            price,
            listPrice,
            createdAt);
    List<CatalogQueries.VariantRow> rows = new ArrayList<>();
    for (int v = 1; v <= variants; v++) {
      rows.add(
          new CatalogQueries.VariantRow(
              "SKU-" + n + "-" + v,
              List.of(new CatalogQueries.OptionRow("color", "Color " + v)),
              price));
    }
    return new CatalogQueries.DetailRow(
        card,
        "Brand",
        "Description " + n,
        List.of(new CatalogQueries.ImageRow(card.imageUrl(), "Image of " + slug)),
        List.of(
            new CatalogQueries.CategoryRow("dien-tu", "Điện tử"),
            new CatalogQueries.CategoryRow("am-thanh", "Âm thanh")),
        rows);
  }
}
