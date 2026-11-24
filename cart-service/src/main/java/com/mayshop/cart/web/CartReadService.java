package com.mayshop.cart.web;

import com.mayshop.cart.product.Money;
import com.mayshop.cart.product.ProductInfo;
import com.mayshop.cart.product.ProductLookupResult;
import com.mayshop.cart.product.ProductLookupService;
import com.mayshop.cart.repository.CartKey;
import com.mayshop.cart.repository.CartLine;
import com.mayshop.cart.repository.CartRepository;
import com.mayshop.cart.repository.CartSnapshot;
import com.mayshop.cart.security.CallerIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Builds the enriched cart and the badge count from the Redis cart and the product lookup. */
@Service
class CartReadService {

  private static final String CURRENCY = "VND";
  private static final String OUT_OF_STOCK = "OUT_OF_STOCK";

  private final CartRepository carts;
  private final ProductLookupService products;
  private final Clock clock;
  private final String placeholderImageUrl;

  @Autowired
  CartReadService(
      CartRepository carts,
      ProductLookupService products,
      @Value("${cart.placeholder-image-url}") String placeholderImageUrl) {
    this(carts, products, placeholderImageUrl, Clock.systemUTC());
  }

  CartReadService(
      CartRepository carts,
      ProductLookupService products,
      String placeholderImageUrl,
      Clock clock) {
    this.carts = carts;
    this.products = products;
    this.placeholderImageUrl = placeholderImageUrl;
    this.clock = clock;
  }

  /**
   * @throws com.mayshop.cart.product.ProductLookupException when {@code core} is needed and down
   */
  CartView.Cart cart(CallerIdentity identity) {
    return cart(snapshot(identity));
  }

  /**
   * Builds the enriched cart from a snapshot that is already read (the write endpoints answer with
   * the snapshot their script returned).
   *
   * @throws com.mayshop.cart.product.ProductLookupException when {@code core} is needed and down
   */
  CartView.Cart cart(CartSnapshot snapshot) {
    List<CartLine> lines = snapshot.items();
    ProductLookupResult found =
        lines.isEmpty()
            ? new ProductLookupResult(Map.of(), Set.of())
            : products.lookup(skus(lines));
    List<CartView.Item> items = new ArrayList<>(lines.size());
    int itemCount = 0;
    long subtotal = 0;
    for (CartLine line : lines) {
      CartView.Item item = item(line, found.known().get(line.sku()));
      items.add(item);
      itemCount += line.quantity();
      if (item.available()) {
        subtotal += item.lineTotal().amount();
      }
    }
    var updatedAt = snapshot.version() == 0 ? clock.instant() : snapshot.updatedAt();
    return new CartView.Cart(items, itemCount, new Money(subtotal, CURRENCY), updatedAt);
  }

  /** Reads the Redis hash only; never calls the product lookup. */
  CartView.Summary summary(CallerIdentity identity) {
    return new CartView.Summary(
        snapshot(identity).items().stream().mapToInt(CartLine::quantity).sum());
  }

  private CartSnapshot snapshot(CallerIdentity identity) {
    CartKey key =
        switch (identity) {
          case CallerIdentity.User user -> CartKey.forUser(user.id());
          case CallerIdentity.Guest guest ->
              guest.id() == null ? null : CartKey.forGuest(guest.id());
        };
    if (key == null) {
      return new CartSnapshot(List.of(), 0, Instant.EPOCH);
    }
    return carts.read(key);
  }

  private static Set<String> skus(List<CartLine> lines) {
    Set<String> skus = new LinkedHashSet<>();
    lines.forEach(line -> skus.add(line.sku()));
    return skus;
  }

  private CartView.Item item(CartLine line, ProductInfo info) {
    int quantity = line.quantity();
    if (info == null) {
      Money zero = new Money(0, CURRENCY);
      return new CartView.Item(
          line.sku(),
          line.sku().toLowerCase(Locale.ROOT),
          line.sku(),
          placeholderImageUrl,
          null,
          zero,
          null,
          null,
          quantity,
          zero,
          false);
    }
    Money unitPrice = info.price();
    Money listPrice =
        info.listPrice() != null && info.listPrice().amount() > unitPrice.amount()
            ? info.listPrice()
            : null;
    return new CartView.Item(
        line.sku(),
        info.slug(),
        info.name(),
        info.imageUrl(),
        info.variantLabel(),
        unitPrice,
        listPrice,
        info.stockLevel(),
        quantity,
        new Money(unitPrice.amount() * quantity, unitPrice.currency()),
        !OUT_OF_STOCK.equals(info.stockLevel()));
  }
}
