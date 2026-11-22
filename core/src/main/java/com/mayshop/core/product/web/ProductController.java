package com.mayshop.core.product.web;

import com.mayshop.core.product.persistence.CatalogQueries;
import com.mayshop.core.product.persistence.CatalogQueries.CardRow;
import com.mayshop.core.product.persistence.CatalogQueries.DetailRow;
import com.mayshop.core.product.persistence.CatalogQueries.PageKey;
import com.mayshop.core.product.web.ProductDtos.Category;
import com.mayshop.core.product.web.ProductDtos.CategoryRef;
import com.mayshop.core.product.web.ProductDtos.Detail;
import com.mayshop.core.product.web.ProductDtos.Image;
import com.mayshop.core.product.web.ProductDtos.Money;
import com.mayshop.core.product.web.ProductDtos.Option;
import com.mayshop.core.product.web.ProductDtos.Page;
import com.mayshop.core.product.web.ProductDtos.Summary;
import com.mayshop.core.product.web.ProductDtos.Variant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code listProducts} and {@code getProduct} of {@code contracts/openapi/core.yaml}. */
@RestController
class ProductController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 50;

  /** The contract's catalog cache policy; error responses override it with {@code no-store}. */
  static final String CACHE_CONTROL = "public, max-age=120, stale-while-revalidate=600";

  static final String CACHE_TAGS = "x-cache-tags";

  private static final String BADGE_DISCOUNT = "DISCOUNT";

  private final CatalogQueries catalog;

  ProductController(CatalogQueries catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/api/products")
  ResponseEntity<Page> listProducts(
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String limit) {
    int pageSize = parseLimit(limit);
    PageKey after = cursor == null ? null : ProductCursor.decode(cursor);
    String categorySlug = category == null || category.isBlank() ? null : category;

    // One extra row tells whether another page follows.
    List<CardRow> rows = catalog.page(categorySlug, after, pageSize + 1);
    boolean more = rows.size() > pageSize;
    List<CardRow> items = more ? rows.subList(0, pageSize) : rows;
    String next = null;
    if (more) {
      CardRow last = items.get(items.size() - 1);
      next = ProductCursor.encode(new PageKey(last.createdAt(), last.id()));
    }
    return ResponseEntity.ok()
        .header("Cache-Control", CACHE_CONTROL)
        .header(CACHE_TAGS, "products")
        .body(new Page(items.stream().map(ProductController::summary).toList(), next));
  }

  @GetMapping("/api/products/{slug}")
  ResponseEntity<Detail> getProduct(@PathVariable String slug) {
    DetailRow row = catalog.findBySlug(slug).orElseThrow(ProductNotFoundException::new);
    CardRow card = row.card();
    Summary summary = summary(card);
    List<CategoryRef> path =
        row.categoryPath().stream().map(c -> new CategoryRef(c.slug(), c.name())).toList();
    CategoryRef own = path.get(path.size() - 1);
    Detail body =
        new Detail(
            card.id(),
            summary.sku(),
            summary.slug(),
            summary.name(),
            row.brand(),
            summary.imageUrl(),
            summary.price(),
            summary.listPrice(),
            summary.badges(),
            row.description(),
            row.images().stream().map(i -> new Image(i.url(), i.alt())).toList(),
            new Category(own.slug(), own.name(), path),
            row.variants().stream()
                .map(v ->
                    new Variant(
                        v.sku(),
                        v.options().stream().map(o -> new Option(o.name(), o.value())).toList(),
                        Money.vnd(v.price())))
                .toList());
    return ResponseEntity.ok()
        .header("Cache-Control", CACHE_CONTROL)
        .header(CACHE_TAGS, "product-" + card.id())
        .body(body);
  }

  /** {@code listPrice} is shown only when it is higher than {@code price}; that is the discount. */
  private static Summary summary(CardRow card) {
    boolean discounted = card.listPrice() != null && card.listPrice() > card.price();
    return new Summary(
        card.sku(),
        card.slug(),
        card.name(),
        card.imageUrl(),
        Money.vnd(card.price()),
        discounted ? Money.vnd(card.listPrice()) : null,
        discounted ? List.of(BADGE_DISCOUNT) : List.of());
  }

  private static int parseLimit(String limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    try {
      int value = Integer.parseInt(limit);
      if (value < 1 || value > MAX_LIMIT) {
        throw new InvalidLimitException();
      }
      return value;
    } catch (NumberFormatException e) {
      throw new InvalidLimitException();
    }
  }
}
