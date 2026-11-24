package com.mayshop.core.product.lookup;

import com.mayshop.core.product.persistence.CatalogQueries.OptionRow;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The human-readable label of a variant's options, such as {@code Màu: Đen nhám}. Option names map
 * to a fixed Vietnamese label; any other name is used as is.
 */
public final class VariantLabel {

  private static final Map<String, String> LABELS = Map.of("color", "Màu", "size", "Kích cỡ");
  private static final String SEPARATOR = " · ";

  private VariantLabel() {}

  /** The options as {@code <label>: <value>} joined by ` · `; {@code null} when there are none. */
  public static String of(List<OptionRow> options) {
    if (options.isEmpty()) {
      return null;
    }
    return options.stream()
        .map(o -> LABELS.getOrDefault(o.name(), o.name()) + ": " + o.value())
        .collect(Collectors.joining(SEPARATOR));
  }
}
