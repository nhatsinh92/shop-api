package com.mayshop.core.flags.internal;

import com.mayshop.core.flags.Flag;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Flag settings.
 *
 * @param defaults comma-separated {@code name=on|off} pairs (for example {@code
 *     recommendations=off,member-price=on}) used when Redis has no usable value; a flag that is not
 *     listed is on
 */
@ConfigurationProperties("flags")
public record FlagSettings(String defaults) {

  /** The default of every flag. */
  Map<Flag, Boolean> defaultValues() {
    Map<Flag, Boolean> result = new EnumMap<>(Flag.class);
    for (Flag flag : Flag.values()) {
      result.put(flag, true);
    }
    if (defaults == null) {
      return result;
    }
    for (String pair : defaults.split(",")) {
      String[] parts = pair.split("=", 2);
      if (parts.length != 2) {
        continue;
      }
      String name = parts[0].trim().toLowerCase(Locale.ROOT);
      Boolean value = FlagValues.parse(parts[1]);
      if (value == null) {
        continue;
      }
      for (Flag flag : Flag.values()) {
        if (flag.key().equals(name)) {
          result.put(flag, value);
        }
      }
    }
    return result;
  }
}
