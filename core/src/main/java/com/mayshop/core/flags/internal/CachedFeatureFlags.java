package com.mayshop.core.flags.internal;

import com.mayshop.core.flags.FeatureFlags;
import com.mayshop.core.flags.Flag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Flags from the store through a short in-process cache: at most one read per {@link #TTL}. A
 * missing field, an unknown value or an unreachable store gives the default.
 */
@Component
@EnableConfigurationProperties(FlagSettings.class)
class CachedFeatureFlags implements FeatureFlags {

  static final Duration TTL = Duration.ofSeconds(2);

  private static final Logger LOG = LoggerFactory.getLogger(CachedFeatureFlags.class);

  private record Snapshot(Instant loadedAt, Map<Flag, Boolean> values) {}

  private final FlagStore store;
  private final Map<Flag, Boolean> defaults;
  private final Clock clock;
  private volatile Snapshot snapshot;

  @Autowired
  CachedFeatureFlags(FlagStore store, FlagSettings settings, ObjectProvider<Clock> clock) {
    this(store, settings, clock.getIfAvailable(Clock::systemUTC));
  }

  CachedFeatureFlags(FlagStore store, FlagSettings settings, Clock clock) {
    this.store = store;
    this.defaults = settings.defaultValues();
    this.clock = clock;
  }

  @Override
  public boolean isEnabled(Flag flag) {
    return current().values().get(flag);
  }

  private Snapshot current() {
    Snapshot seen = snapshot;
    if (seen != null && fresh(seen)) {
      return seen;
    }
    synchronized (this) {
      seen = snapshot;
      if (seen == null || !fresh(seen)) {
        seen = load();
        snapshot = seen;
      }
      return seen;
    }
  }

  private boolean fresh(Snapshot seen) {
    return Duration.between(seen.loadedAt(), clock.instant()).compareTo(TTL) < 0;
  }

  /** A failed read is cached like a good one, so a Redis outage costs one attempt per TTL. */
  private Snapshot load() {
    Instant now = clock.instant();
    Map<Flag, Boolean> values = new EnumMap<>(defaults);
    try {
      Map<String, String> raw = store.read();
      for (Flag flag : Flag.values()) {
        Boolean parsed = FlagValues.parse(raw.get(flag.key()));
        if (parsed != null) {
          values.put(flag, parsed);
        }
      }
    } catch (RuntimeException e) {
      LOG.warn("Flags unavailable, using the defaults: {}", e.toString());
    }
    return new Snapshot(now, values);
  }
}
