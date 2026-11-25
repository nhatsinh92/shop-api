package com.mayshop.core.flags.internal;

/** Parses the {@code on} / {@code off} values. */
final class FlagValues {

  private FlagValues() {}

  /** {@code true} for {@code on}, {@code false} for {@code off}, {@code null} for anything else. */
  static Boolean parse(String value) {
    if (value == null) {
      return null;
    }
    return switch (value.trim()) {
      case "on" -> Boolean.TRUE;
      case "off" -> Boolean.FALSE;
      default -> null;
    };
  }
}
