package com.mayshop.core.product.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mayshop.core.product.ProductUpdated;
import com.mayshop.core.product.ProductUpdated.Change;
import com.mayshop.core.product.persistence.ProductUpdateStore.Variant;
import com.mayshop.core.product.update.ProductUpdates.Request;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which changes and tags a request produces, and when no event is published. */
class ProductUpdatesTest {

  private static final UUID PRODUCT = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  private static final UUID EVENT = new UUID(1, 1);
  private static final Instant NOW = Instant.parse("2026-12-12T00:00:05Z");
  private static final String TAG = "product-" + PRODUCT;

  private final FakeUpdateStore store = new FakeUpdateStore();
  private final List<Object> published = new ArrayList<>();
  private ProductUpdates updates;

  @BeforeEach
  void setUp() {
    published.clear();
    store.clear();
    store.put("SKU-A", new Variant(PRODUCT, "slug-a", 1_000, 2_000L, 10));
    updates =
        new ProductUpdates(store, published::add, Clock.fixed(NOW, ZoneOffset.UTC), () -> EVENT);
  }

  private ProductUpdated only() {
    assertThat(published).hasSize(1);
    return (ProductUpdated) published.get(0);
  }

  @Test
  void priceChangePublishesPriceWithProductAndProductsTags() {
    updates.update("SKU-A", new Request(900L, null, null));

    ProductUpdated event = only();
    assertThat(event.changes()).containsExactly(Change.PRICE);
    assertThat(event.tags()).containsExactly(TAG, "products");
    assertThat(event.eventId()).isEqualTo(EVENT);
    assertThat(event.occurredAt()).isEqualTo(NOW);
    assertThat(event.sku()).isEqualTo("SKU-A");
    assertThat(event.slug()).isEqualTo("slug-a");
    assertThat(event.productId()).isEqualTo(PRODUCT);
  }

  @Test
  void listPriceChangeIsAPriceChange() {
    updates.update("SKU-A", new Request(null, 3_000L, null));

    assertThat(only().changes()).containsExactly(Change.PRICE);
  }

  @Test
  void priceChangeOfAFlashSaleSkuAddsTheFlashSaleTag() {
    store.inFlashSale("SKU-A");
    updates.update("SKU-A", new Request(900L, null, null));

    assertThat(only().tags()).containsExactly(TAG, "products", "flash-sale");
  }

  @Test
  void stockOnlyChangeCarriesOnlyTheProductTagEvenInAFlashSale() {
    store.inFlashSale("SKU-A");
    updates.update("SKU-A", new Request(null, null, 5));

    ProductUpdated event = only();
    assertThat(event.changes()).containsExactly(Change.STOCK);
    assertThat(event.tags()).containsExactly(TAG);
  }

  @Test
  void priceAndStockTogetherPublishOneEventWithBothChanges() {
    updates.update("SKU-A", new Request(900L, null, 5));

    ProductUpdated event = only();
    assertThat(event.changes()).containsExactlyInAnyOrder(Change.PRICE, Change.STOCK);
    assertThat(event.tags()).containsExactly(TAG, "products");
  }

  @Test
  void aRequestThatChangesNothingPublishesNoEventAndWritesNothing() {
    updates.update("SKU-A", new Request(1_000L, 2_000L, 10));

    assertThat(published).isEmpty();
    assertThat(store.updates).isZero();
  }

  @Test
  void onlyTheChangedPartIsReportedWhenTheOtherValueIsSentUnchanged() {
    updates.update("SKU-A", new Request(1_000L, null, 4));

    assertThat(only().changes()).containsExactly(Change.STOCK);
  }

  @Test
  void listPriceNotAbovePriceIsRejectedAndNothingIsWritten() {
    assertThatThrownBy(() -> updates.update("SKU-A", new Request(2_000L, null, null)))
        .isInstanceOf(InvalidUpdateException.class);
    assertThatThrownBy(() -> updates.update("SKU-A", new Request(null, 1_000L, null)))
        .isInstanceOf(InvalidUpdateException.class);

    assertThat(published).isEmpty();
    assertThat(store.updates).isZero();
  }

  @Test
  void unknownSkuIsRejected() {
    assertThatThrownBy(() -> updates.update("SKU-NOPE", new Request(1L, null, null)))
        .isInstanceOf(UnknownSkuException.class);
  }
}
