package com.mayshop.cart.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mayshop.cart.product.Money;
import com.mayshop.cart.product.ProductInfo;
import com.mayshop.cart.product.ProductLookupResult;
import com.mayshop.cart.product.ProductLookupService;
import com.mayshop.cart.repository.CartKey;
import com.mayshop.cart.repository.CartRepository;
import com.mayshop.cart.repository.CartSnapshot;
import com.mayshop.cart.repository.MergeResult;
import com.mayshop.cart.repository.WriteResult;
import com.mayshop.cart.repository.WriteStatus;
import com.mayshop.cart.writebehind.CartUpdatedPublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Successful writes publish {@code cart.updated}; failed writes publish nothing. */
class CartWriteServiceUpdatesTest {

  private static final CartSnapshot SNAPSHOT = new CartSnapshot(List.of(), 3, Instant.EPOCH);
  private static final CartKey KEY = CartKey.forUser("u1");

  private CartRepository carts;
  private CartUpdatedPublisher updates;
  private CartWriteService service;

  @BeforeEach
  void setUp() {
    carts = mock(CartRepository.class);
    updates = mock(CartUpdatedPublisher.class);
    ProductLookupService products = mock(ProductLookupService.class);
    when(products.lookup(any()))
        .thenReturn(
            new ProductLookupResult(
                Map.of(
                    "SKU-A",
                    new ProductInfo(
                        "SKU-A", "sku-a", "A", "img", null, new Money(1, "VND"), null, "IN_STOCK")),
                Set.of()));
    CartReadService reads = mock(CartReadService.class);
    service = new CartWriteService(carts, products, reads, updates);
  }

  @Test
  void addUpdateAndRemovePublishTheSnapshotOfTheWrite() {
    when(carts.add(KEY, "SKU-A", 1)).thenReturn(new WriteResult(WriteStatus.OK, SNAPSHOT));
    when(carts.set(KEY, "SKU-A", 2)).thenReturn(new WriteResult(WriteStatus.OK, SNAPSHOT));
    when(carts.remove(KEY, "SKU-A")).thenReturn(new WriteResult(WriteStatus.OK, SNAPSHOT));

    service.add(KEY, "SKU-A", 1);
    service.update(KEY, "SKU-A", 2);
    service.remove(KEY, "SKU-A");

    verify(updates, org.mockito.Mockito.times(3)).publish(KEY, SNAPSHOT);
  }

  @Test
  void mergePublishesTheUserCartAndTheEmptyGuestKey() {
    CartKey guest = CartKey.forGuest("g1");
    when(carts.merge(KEY, guest)).thenReturn(new MergeResult(SNAPSHOT, 0));

    service.merge(KEY, guest);

    verify(updates).publishMerge(KEY, SNAPSHOT, guest);
  }

  @Test
  void failedWritesPublishNothing() {
    for (WriteStatus status :
        List.of(WriteStatus.QTY_LIMIT, WriteStatus.ITEMS_LIMIT, WriteStatus.ITEM_NOT_FOUND)) {
      when(carts.add(any(), anyString(), anyInt())).thenReturn(new WriteResult(status, SNAPSHOT));
      assertThatThrownBy(() -> service.add(KEY, "SKU-A", 1))
          .isInstanceOf(CartProblemException.class);
    }
    verifyNoInteractions(updates);
  }
}
