package com.mayshop.core.product.persistence;

import com.mayshop.core.product.persistence.CatalogQueries.CardRow;
import java.util.List;
import java.util.Optional;

/** Read side of the recommendations that relate to one product. */
public interface RecommendationQueries {

  /**
   * Products related to the product with this slug, never the product itself: first those in its
   * own (leaf) category, then those elsewhere under its parent category (or under its own category
   * when it has no parent), each group newest first ({@code created_at DESC, id DESC}), at most
   * {@code limit} rows. Empty when no product has this slug.
   */
  Optional<List<CardRow>> relatedTo(String productSlug, int limit);
}
