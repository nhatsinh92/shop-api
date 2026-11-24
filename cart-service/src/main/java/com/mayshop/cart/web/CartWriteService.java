package com.mayshop.cart.web;

import com.mayshop.cart.product.ProductInfo;
import com.mayshop.cart.product.ProductLookupService;
import com.mayshop.cart.repository.CartKey;
import com.mayshop.cart.repository.CartRepository;
import com.mayshop.cart.repository.CartSnapshot;
import com.mayshop.cart.repository.WriteResult;
import com.mayshop.cart.writebehind.CartUpdatedPublisher;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The add, update and remove operations: stock check (advisory), the atomic repository write and
 * the mapping of its outcome to the documented errors. Remove never calls {@code core}.
 */
@Service
class CartWriteService {

  private static final String OUT_OF_STOCK = "OUT_OF_STOCK";

  private final CartRepository carts;
  private final ProductLookupService products;
  private final CartReadService reads;
  private final CartUpdatedPublisher updates;

  CartWriteService(
      CartRepository carts,
      ProductLookupService products,
      CartReadService reads,
      CartUpdatedPublisher updates) {
    this.carts = carts;
    this.products = products;
    this.reads = reads;
    this.updates = updates;
  }

  /**
   * @throws com.mayshop.cart.product.ProductLookupException when the SKU is not cached and {@code
   *     core} is down (nothing is written)
   */
  CartView.Cart add(CartKey key, String sku, int quantity) {
    ProductInfo info = products.lookup(Set.of(sku)).known().get(sku);
    if (info == null) {
      throw CartProblemException.productNotFound();
    }
    if (OUT_OF_STOCK.equals(info.stockLevel())) {
      throw CartProblemException.outOfStock();
    }
    return respond(key, carts.add(key, sku, quantity));
  }

  CartView.Cart update(CartKey key, String sku, int quantity) {
    // A SKU that core no longer knows skips the stock check; the write answers 404 when it is not
    // in the cart, and lets the user lower the quantity of a line that is.
    ProductInfo info = products.lookup(Set.of(sku)).known().get(sku);
    if (info != null && OUT_OF_STOCK.equals(info.stockLevel())) {
      throw CartProblemException.outOfStock();
    }
    return respond(key, carts.set(key, sku, quantity));
  }

  CartView.Cart remove(CartKey key, String sku) {
    return respond(key, carts.remove(key, sku));
  }

  /**
   * Merges the guest cart into the user cart and answers with the enriched user cart. Never calls
   * {@code core} for the merge itself; the enrichment follows {@code GET /api/cart}.
   *
   * @throws com.mayshop.cart.product.ProductLookupException when enrichment needs {@code core} and
   *     it is down (the merge has already happened and is idempotent)
   */
  CartView.Cart merge(CartKey user, CartKey guest) {
    CartSnapshot merged = carts.merge(user, guest).snapshot();
    updates.publishMerge(user, merged, guest);
    return reads.cart(merged);
  }

  private CartView.Cart respond(CartKey key, WriteResult result) {
    return switch (result.status()) {
      case OK -> {
        updates.publish(key, result.snapshot());
        yield reads.cart(result.snapshot());
      }
      case QTY_LIMIT -> throw CartProblemException.quantityLimit(CartRepository.MAX_QUANTITY);
      case ITEMS_LIMIT -> throw CartProblemException.itemsLimit(CartRepository.MAX_SKUS);
      case ITEM_NOT_FOUND -> throw CartProblemException.itemNotFound();
    };
  }
}
