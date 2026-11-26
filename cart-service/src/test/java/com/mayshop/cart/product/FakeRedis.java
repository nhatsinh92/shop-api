package com.mayshop.cart.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** An in-memory stand-in for the value operations the product cache uses, with TTLs on a clock. */
final class FakeRedis {

  /** A clock the test moves by hand. */
  static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-26T00:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private record Entry(String value, Instant expiresAt) {}

  private final Map<String, Entry> entries = new HashMap<>();
  private final MutableClock clock;
  final StringRedisTemplate template = mock(StringRedisTemplate.class);

  FakeRedis(MutableClock clock) {
    this.clock = clock;
    ValueOperations<?, ?> ops = mock(ValueOperations.class);
    doAnswer(call -> ops).when(template).opsForValue();
    doAnswer(
            call -> {
              List<String> values = new ArrayList<>();
              for (Object key : call.<Collection<?>>getArgument(0)) {
                values.add(get(key.toString()));
              }
              return values;
            })
        .when(ops)
        .multiGet(any());
    doAnswer(
            call -> {
              entries.put(
                  call.getArgument(0),
                  new Entry(
                      call.getArgument(1), clock.instant().plus((Duration) call.getArgument(2))));
              return null;
            })
        .when(ops)
        .set(any(), any(), any(Duration.class));
    when(template.delete(anyString()))
        .thenAnswer(call -> entries.remove((String) call.getArgument(0)) != null);
  }

  String get(String key) {
    Entry entry = entries.get(key);
    if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
      entries.remove(key);
      return null;
    }
    return entry.value();
  }

  void put(String key, String value, Duration ttl) {
    entries.put(key, new Entry(value, clock.instant().plus(ttl)));
  }
}
