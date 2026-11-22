package com.mayshop.core.product.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of the catalog: what the public product endpoints need from the product tables. */
public interface CatalogQueries {

  /**
   * Products newest first ({@code created_at DESC, id DESC}), strictly after {@code after} when it
   * is given, at most {@code limit} rows. A {@code null} category lists every category.
   */
  List<CardRow> page(String category, PageKey after, int limit);

  /** The product with this slug, or empty. */
  Optional<DetailRow> findBySlug(String slug);

  /** Position of a product in the list order; the cursor encodes it. */
  record PageKey(Instant createdAt, UUID id) {}

  /** A product card: the product and its summary (lowest-position) variant. */
  record CardRow(
      UUID id,
      String slug,
      String name,
      String imageUrl,
      String sku,
      long price,
      Long listPrice,
      Instant createdAt) {}

  /** A product with everything the product page shows. */
  record DetailRow(
      CardRow card,
      String brand,
      String description,
      List<ImageRow> images,
      List<CategoryRow> categoryPath,
      List<VariantRow> variants) {}

  /** One image of a product. */
  record ImageRow(String url, String alt) {}

  /** A category; {@code categoryPath} lists the root first and the product's category last. */
  record CategoryRow(String slug, String name) {}

  /** One option of a variant, such as {@code color = Đen}. */
  record OptionRow(String name, String value) {}

  /** A purchasable variant. */
  record VariantRow(String sku, List<OptionRow> options, long price) {}
}
