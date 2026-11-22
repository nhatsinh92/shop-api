package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.product.availability.StockLevel;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StockLevelTest {

  @ParameterizedTest
  @CsvSource({
    "0,50,OUT_OF_STOCK",
    "1,50,LOW",
    "50,50,LOW",
    "51,50,IN_STOCK",
    "0,5,OUT_OF_STOCK",
    "5,5,LOW",
    "6,5,IN_STOCK",
    "200,50,IN_STOCK"
  })
  void levelFollowsTheThreshold(int quantity, int threshold, StockLevel expected) {
    assertThat(StockLevel.of(quantity, threshold)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"40,40", "47,40", "9,1", "1,1", "10,10", "50,50"})
  void approxQuantityIsRoundedDownToATenWithMinimumOne(int quantity, int expected) {
    assertThat(StockLevel.LOW.approxQuantity(quantity)).isEqualTo(OptionalInt.of(expected));
  }

  @Test
  void approxQuantityIsAbsentWhenNotLow() {
    assertThat(StockLevel.OUT_OF_STOCK.approxQuantity(0)).isEmpty();
    assertThat(StockLevel.IN_STOCK.approxQuantity(51)).isEmpty();
  }
}
