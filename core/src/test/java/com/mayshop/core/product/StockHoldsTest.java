package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.product.PriceRule.SalePrice;
import com.mayshop.core.product.StockHolds.HeldLine;
import com.mayshop.core.product.StockHolds.HoldResult;
import com.mayshop.core.product.StockHolds.Line;
import com.mayshop.core.product.StockHolds.ReleaseLine;
import com.mayshop.core.product.StockHolds.ShortLine;
import com.mayshop.core.product.persistence.AvailabilityQueries.AvailabilityRow;
import com.mayshop.core.product.persistence.LookupQueries;
import com.mayshop.core.product.persistence.LookupQueries.LookupRow;
import com.mayshop.core.product.persistence.StockStore;
import com.mayshop.core.product.pricing.MemberPriceRule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/** The rules of {@link StockHolds} against in-memory stores (no database). */
class StockHoldsTest {

  private static final Instant NOW = Instant.parse("2026-12-12T10:00:00Z");
  private static final UUID SALE = UUID.randomUUID();
  private static final UUID PRODUCT = UUID.randomUUID();

  private final FakeStore store = new FakeStore();
  private final List<Object> published = new ArrayList<>();
  private final Map<String, LookupRow> rows = new HashMap<>();
  private StockHolds holds;

  @BeforeEach
  void setUp() {
    LookupQueries lookup = skus -> skus.stream().map(rows::get).filter(r -> r != null).toList();
    ApplicationEventPublisher events =
        new ApplicationEventPublisher() {
          @Override
          public void publishEvent(Object event) {
            published.add(event);
          }

          @Override
          public void publishEvent(ApplicationEvent event) {
            published.add(event);
          }
        };
    var rule =
        new MemberPriceRule(
            Map.of(
                com.mayshop.core.product.pricing.MemberTier.GOLD, 5,
                com.mayshop.core.product.pricing.MemberTier.PLATINUM, 8));
    holds =
        new StockHolds(
            store, lookup, rule, events, Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
  }

  private void product(String sku, long price, int stock, List<SalePrice> sales) {
    rows.put(
        sku,
        new LookupRow(
            sku,
            "slug-" + sku,
            "Name " + sku,
            "https://img/" + sku,
            List.of(),
            1,
            null,
            new AvailabilityRow(PRODUCT, sku, price, stock, sales)));
    store.stock.put(sku, stock);
  }

  private static SalePrice sale(long amount) {
    return new SalePrice(amount, NOW.minusSeconds(60), NOW.plusSeconds(3600));
  }

  private void inSale(String sku, long price, int saleStock) {
    store.sales.put(sku, new StockStore.SaleItem(SALE, sku, price));
    store.saleStock.put(sku, saleStock);
    store.sold.put(sku, 0);
  }

  @Test
  void goldMemberPriceOf890000Is845500() {
    product("A", 890_000, 5, List.of());

    HoldResult result = holds.hold(List.of(new Line("A", 2)), MemberTier.GOLD, NOW);

    assertThat(result.succeeded()).isTrue();
    HeldLine line = result.held().get(0);
    assertThat(line.unitPrice()).isEqualTo(845_500);
    assertThat(line.basePrice()).isEqualTo(890_000);
    assertThat(line.tookSaleStock()).isFalse();
    assertThat(line.name()).isEqualTo("Name A");
    assertThat(line.imageUrl()).isEqualTo("https://img/A");
    assertThat(line.variantLabel()).isNull();
    assertThat(store.stock.get("A")).isEqualTo(3);
  }

  @Test
  void aShortLineTakesNothingAndEveryShortLineIsListed() {
    product("A", 100_000, 5, List.of());
    product("B", 100_000, 1, List.of());
    product("C", 100_000, 0, List.of());

    HoldResult result =
        holds.hold(
            List.of(new Line("C", 2), new Line("A", 5), new Line("B", 4), new Line("X", 1)),
            MemberTier.STANDARD,
            NOW);

    assertThat(result.succeeded()).isFalse();
    assertThat(result.held()).isEmpty();
    assertThat(result.unavailable())
        .containsExactly(
            new ShortLine("B", 4, 1), new ShortLine("C", 2, 0), new ShortLine("X", 1, 0));
    assertThat(store.stock).containsEntry("A", 5).containsEntry("B", 1).containsEntry("C", 0);
    assertThat(published).isEmpty();
  }

  @Test
  void linesAreTakenInSkuOrderWhateverTheRequestOrder() {
    product("A", 100_000, 5, List.of());
    product("B", 100_000, 5, List.of());

    holds.hold(List.of(new Line("B", 1), new Line("A", 1)), MemberTier.STANDARD, NOW);

    assertThat(store.takeOrder).containsExactly("A", "B");
  }

  @Test
  void aSkuInTheCurrentSaleGetsTheSalePriceAndTakesSaleStock() {
    product("A", 890_000, 10, List.of(sale(700_000)));
    inSale("A", 700_000, 5);

    HeldLine line = holds.hold(List.of(new Line("A", 2)), MemberTier.STANDARD, NOW).held().get(0);

    assertThat(line.unitPrice()).isEqualTo(700_000);
    assertThat(line.basePrice()).isEqualTo(700_000);
    assertThat(line.tookSaleStock()).isTrue();
    assertThat(store.sold.get("A")).isEqualTo(2);
    assertThat(store.stock.get("A")).isEqualTo(8);
  }

  @Test
  void whenSaleStockIsShortTheLineIsPricedAtTheVariantPriceAndStillHoldsStock() {
    product("A", 890_000, 10, List.of(sale(700_000)));
    inSale("A", 700_000, 1);

    HeldLine line = holds.hold(List.of(new Line("A", 2)), MemberTier.STANDARD, NOW).held().get(0);

    assertThat(line.unitPrice()).isEqualTo(890_000);
    assertThat(line.basePrice()).isEqualTo(890_000);
    assertThat(line.tookSaleStock()).isFalse();
    assertThat(store.sold.get("A")).isZero();
    assertThat(store.stock.get("A")).isEqualTo(8);
  }

  @Test
  void releaseGivesStockBackAndDecrementsSoldOnlyForLinesThatTookSaleStock() {
    product("A", 890_000, 8, List.of(sale(700_000)));
    inSale("A", 700_000, 5);
    store.sold.put("A", 2);
    product("B", 100_000, 3, List.of());

    holds.release(List.of(new ReleaseLine("A", 2, true), new ReleaseLine("B", 1, false)));

    assertThat(store.stock).containsEntry("A", 10).containsEntry("B", 4);
    assertThat(store.sold.get("A")).isZero();
    assertThat(store.saleGiven).containsExactly("A");
  }

  @Test
  void eachChangedSkuPublishesAStockOnlyEvent() {
    product("A", 100_000, 5, List.of());
    product("B", 100_000, 5, List.of());

    holds.hold(List.of(new Line("B", 1), new Line("A", 1)), MemberTier.STANDARD, NOW);
    holds.release(List.of(new ReleaseLine("A", 1, false)));

    assertThat(published)
        .hasSize(3)
        .allSatisfy(e -> assertThat(e).isInstanceOf(ProductUpdated.class));
    ProductUpdated first = (ProductUpdated) published.get(0);
    assertThat(first.sku()).isEqualTo("A");
    assertThat(first.changes()).containsExactly(ProductUpdated.Change.STOCK);
    assertThat(first.tags()).containsExactly("product-" + PRODUCT);
    assertThat(first.occurredAt()).isEqualTo(NOW);
  }

  @Test
  void repeatedSkusAreAddedUp() {
    product("A", 100_000, 5, List.of());

    HoldResult result =
        holds.hold(List.of(new Line("A", 2), new Line("A", 3)), MemberTier.STANDARD, NOW);

    assertThat(result.held()).hasSize(1);
    assertThat(result.held().get(0).quantity()).isEqualTo(5);
    assertThat(store.stock.get("A")).isZero();
  }

  @Test
  void theTierEnumMirrorsTheInternalOne() {
    assertThat(List.of(MemberTier.values()).stream().map(Enum::name).toList())
        .isEqualTo(
            List.of(com.mayshop.core.product.pricing.MemberTier.values()).stream()
                .map(Enum::name)
                .toList());
    assertThat(MemberTier.fromClaim("GOLD")).isEqualTo(MemberTier.GOLD);
    assertThat(MemberTier.fromClaim("gold")).isEqualTo(MemberTier.STANDARD);
    assertThat(MemberTier.fromClaim(null)).isEqualTo(MemberTier.STANDARD);
  }

  /** In-memory {@link StockStore}. */
  static final class FakeStore implements StockStore {

    final Map<String, Integer> stock = new HashMap<>();
    final Map<String, SaleItem> sales = new HashMap<>();
    final Map<String, Integer> saleStock = new HashMap<>();
    final Map<String, Integer> sold = new HashMap<>();
    final List<String> takeOrder = new ArrayList<>();
    final List<String> saleGiven = new ArrayList<>();

    @Override
    public boolean take(String sku, int quantity) {
      takeOrder.add(sku);
      int have = stock.getOrDefault(sku, 0);
      if (have < quantity) {
        return false;
      }
      stock.put(sku, have - quantity);
      return true;
    }

    @Override
    public int available(String sku) {
      return stock.getOrDefault(sku, 0);
    }

    @Override
    public void give(String sku, int quantity) {
      stock.merge(sku, quantity, Integer::sum);
    }

    @Override
    public Optional<SaleItem> currentSaleItem(String sku, Instant now) {
      return Optional.ofNullable(sales.get(sku));
    }

    @Override
    public boolean takeSaleStock(SaleItem item, int quantity) {
      if (sold.get(item.sku()) + quantity > saleStock.get(item.sku())) {
        return false;
      }
      sold.merge(item.sku(), quantity, Integer::sum);
      return true;
    }

    @Override
    public void giveSaleStock(String sku, int quantity) {
      saleGiven.add(sku);
      sold.merge(sku, -quantity, Integer::sum);
    }
  }
}
