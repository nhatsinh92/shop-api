package com.mayshop.core.flags.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.flags.FeatureFlags;
import com.mayshop.core.flags.Flag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link FeatureFlags} against a fake store and a settable clock. */
class FeatureFlagsCacheTest {

  private final FakeStore store = new FakeStore();
  private final SettableClock clock = new SettableClock();

  private FeatureFlags flags(String defaults) {
    return new CachedFeatureFlags(store, new FlagSettings(defaults), clock);
  }

  @Test
  void aChangeIsSeenAfterAtMostTwoSeconds() {
    store.values.put("recommendations", "on");
    FeatureFlags flags = flags("");
    assertThat(flags.isEnabled(Flag.RECOMMENDATIONS)).isTrue();
    store.values.put("recommendations", "off");
    clock.advance(Duration.ofMillis(1999));
    assertThat(flags.isEnabled(Flag.RECOMMENDATIONS)).isTrue();
    clock.advance(Duration.ofMillis(1));
    assertThat(flags.isEnabled(Flag.RECOMMENDATIONS)).isFalse();
  }

  @Test
  void theStoreIsNotReadAgainWithinTwoSeconds() {
    FeatureFlags flags = flags("");
    flags.isEnabled(Flag.RECOMMENDATIONS);
    flags.isEnabled(Flag.MEMBER_PRICE);
    clock.advance(Duration.ofMillis(1500));
    flags.isEnabled(Flag.ONLINE_PAYMENT);
    assertThat(store.reads).isEqualTo(1);
    clock.advance(Duration.ofMillis(500));
    flags.isEnabled(Flag.ONLINE_PAYMENT);
    assertThat(store.reads).isEqualTo(2);
  }

  @Test
  void flagsAreAllOnWhenNothingIsConfiguredOrStored() {
    for (String unset : new String[] {null, ""}) {
      FeatureFlags flags = flags(unset);
      for (Flag flag : Flag.values()) {
        assertThat(flags.isEnabled(flag)).as(flag.key()).isTrue();
      }
    }
  }

  @Test
  void aMissingFieldGivesTheConfiguredDefault() {
    store.values.put("member-price", "on");
    FeatureFlags flags = flags("recommendations=off, online-payment=off");
    assertThat(flags.isEnabled(Flag.RECOMMENDATIONS)).isFalse();
    assertThat(flags.isEnabled(Flag.MEMBER_PRICE)).isTrue();
    assertThat(flags.isEnabled(Flag.ONLINE_PAYMENT)).isFalse();
  }

  @Test
  void aStoredValueWinsOverTheDefault() {
    store.values.put("online-payment", "on");
    store.values.put("recommendations", "off");
    FeatureFlags flags = flags("recommendations=on,online-payment=off");
    assertThat(flags.isEnabled(Flag.ONLINE_PAYMENT)).isTrue();
    assertThat(flags.isEnabled(Flag.RECOMMENDATIONS)).isFalse();
  }

  @Test
  void anUnknownValueGivesTheDefault() {
    store.values.put("recommendations", "maybe");
    store.values.put("member-price", "");
    FeatureFlags flags = flags("member-price=off");
    assertThat(flags.isEnabled(Flag.RECOMMENDATIONS)).isTrue();
    assertThat(flags.isEnabled(Flag.MEMBER_PRICE)).isFalse();
  }

  @Test
  void malformedDefaultsAreIgnored() {
    FeatureFlags flags = flags("recommendations, member-price=x,unknown=off,online-payment=off");
    assertThat(flags.isEnabled(Flag.RECOMMENDATIONS)).isTrue();
    assertThat(flags.isEnabled(Flag.MEMBER_PRICE)).isTrue();
    assertThat(flags.isEnabled(Flag.ONLINE_PAYMENT)).isFalse();
  }

  @Test
  void redisBeingDownGivesTheDefaultsAndDoesNotFail() {
    store.failing = true;
    FeatureFlags flags = flags("member-price=off");
    assertThat(flags.isEnabled(Flag.RECOMMENDATIONS)).isTrue();
    assertThat(flags.isEnabled(Flag.MEMBER_PRICE)).isFalse();
    // The failed read is cached too: one attempt per two seconds.
    assertThat(store.reads).isEqualTo(1);
    store.failing = false;
    store.values.put("member-price", "on");
    clock.advance(Duration.ofSeconds(2));
    assertThat(flags.isEnabled(Flag.MEMBER_PRICE)).isTrue();
  }

  @Test
  void theKeysAreKebabCase() {
    assertThat(Flag.RECOMMENDATIONS.key()).isEqualTo("recommendations");
    assertThat(Flag.MEMBER_PRICE.key()).isEqualTo("member-price");
    assertThat(Flag.ONLINE_PAYMENT.key()).isEqualTo("online-payment");
  }

  static class FakeStore implements FlagStore {
    final Map<String, String> values = new HashMap<>();
    int reads;
    boolean failing;

    @Override
    public Map<String, String> read() {
      reads++;
      if (failing) {
        throw new IllegalStateException("redis down");
      }
      return new HashMap<>(values);
    }
  }

  static class SettableClock extends Clock {
    private Instant now = Instant.parse("2026-12-12T10:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
