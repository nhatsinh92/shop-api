package com.mayshop.core.product.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.product.persistence.CatalogQueries.OptionRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class VariantLabelTest {

  @Test
  void colorUsesTheVietnameseLabel() {
    assertThat(VariantLabel.of(List.of(new OptionRow("color", "Đen nhám"))))
        .isEqualTo("Màu: Đen nhám");
  }

  @Test
  void twoOptionsAreJoinedWithAMiddleDot() {
    var options = List.of(new OptionRow("color", "Đen"), new OptionRow("size", "L"));

    assertThat(VariantLabel.of(options)).isEqualTo("Màu: Đen · Kích cỡ: L");
  }

  @Test
  void anUnknownOptionNameIsUsedAsIs() {
    assertThat(VariantLabel.of(List.of(new OptionRow("material", "Da")))).isEqualTo("material: Da");
  }

  @Test
  void noOptionsGiveNoLabel() {
    assertThat(VariantLabel.of(List.of())).isNull();
  }
}
