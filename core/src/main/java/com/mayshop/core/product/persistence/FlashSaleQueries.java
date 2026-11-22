package com.mayshop.core.product.persistence;

import com.mayshop.core.product.persistence.CatalogQueries.CardRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of the flash sale: the sale running at a given instant. */
public interface FlashSaleQueries {

  /** The sale whose window contains {@code now} ({@code starts_at <= now < ends_at}), or empty. */
  Optional<SaleRow> findCurrent(Instant now);

  /** A sale with one item per product. */
  record SaleRow(UUID id, Instant startsAt, Instant endsAt, List<ItemRow> items) {}

  /**
   * A product in the sale: its card (summary SKU) and that SKU's sale numbers.
   *
   * @param salePrice the sale price of the summary SKU
   * @param saleStock the stock set aside for the sale
   * @param sold units of the sale stock already sold
   */
  record ItemRow(CardRow card, long salePrice, int saleStock, int sold) {}
}
