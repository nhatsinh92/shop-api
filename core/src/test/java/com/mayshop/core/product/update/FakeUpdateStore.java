package com.mayshop.core.product.update;

import com.mayshop.core.product.persistence.ProductUpdateStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** In-memory {@link ProductUpdateStore}; {@code onUpdate} lets a test mirror the new values. */
public class FakeUpdateStore implements ProductUpdateStore {

  private final Map<String, Variant> variants = new HashMap<>();
  private final Set<String> inSale = new HashSet<>();
  private Consumer<Written> onUpdate = w -> {};
  public int updates;

  public record Written(String sku, long price, Long listPrice, int stock) {}

  public void put(String sku, Variant variant) {
    variants.put(sku, variant);
  }

  public void inFlashSale(String sku) {
    inSale.add(sku);
  }

  public void onUpdate(Consumer<Written> onUpdate) {
    this.onUpdate = onUpdate;
  }

  public void clear() {
    variants.clear();
    inSale.clear();
    updates = 0;
  }

  @Override
  public Optional<Variant> lockBySku(String sku) {
    return Optional.ofNullable(variants.get(sku));
  }

  @Override
  public void update(String sku, long price, Long listPrice, Integer stock) {
    updates++;
    Variant old = variants.get(sku);
    int quantity = stock != null ? stock : old.stock();
    variants.put(sku, new Variant(old.productId(), old.slug(), price, listPrice, quantity));
    onUpdate.accept(new Written(sku, price, listPrice, quantity));
  }

  @Override
  public boolean inCurrentFlashSale(String sku, Instant now) {
    return inSale.contains(sku);
  }
}
