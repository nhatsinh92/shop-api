package com.mayshop.core.flags;

/** Public API of the {@code flags} module. */
public interface FeatureFlags {

  /**
   * Whether the flag is on. Never fails: when the value cannot be read, the configured default
   * applies. The answer may be up to 2 seconds old.
   */
  boolean isEnabled(Flag flag);
}
