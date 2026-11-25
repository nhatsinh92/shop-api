package com.mayshop.core.flags.internal;

import java.util.Map;

/** Reads the raw flag values (field to value), for example from the Redis hash {@code flags}. */
interface FlagStore {

  /** Returns every stored field; throws when the store is unreachable. */
  Map<String, String> read();
}
