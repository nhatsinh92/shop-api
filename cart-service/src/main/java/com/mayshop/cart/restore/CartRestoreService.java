package com.mayshop.cart.restore;

import com.mayshop.cart.repository.CartKey;
import com.mayshop.cart.writebehind.CartUpdated;
import com.mayshop.cart.writebehind.store.CartSnapshotStore;
import com.mayshop.cart.writebehind.store.StoredCart;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Rebuilds lost Redis carts from {@code cart_snapshot}. A cart is written only when its key is
 * missing (one script call, so a concurrent write is never overwritten). Guest carts get the
 * remaining part of their TTL; guest snapshots already past it are skipped, so a restore never
 * brings back a cart the nightly cleanup would delete.
 */
public class CartRestoreService {

  static final int PAGE_SIZE = 500;
  private static final String GUEST_PREFIX = "cart:guest:";

  private static final Logger LOG = LoggerFactory.getLogger(CartRestoreService.class);

  private final StringRedisTemplate redis;
  private final CartSnapshotStore store;
  private final Clock clock;
  private final DefaultRedisScript<Long> script;

  public CartRestoreService(StringRedisTemplate redis, CartSnapshotStore store, Clock clock) {
    this.redis = redis;
    this.store = store;
    this.clock = clock;
    this.script = new DefaultRedisScript<>();
    this.script.setLocation(new ClassPathResource("redis/restore.lua"));
    this.script.setResultType(Long.class);
  }

  /** What one restore did. */
  public record Result(long restored, long skippedExisting, long skippedExpired, long malformed) {}

  /**
   * Restores every snapshot whose key is missing.
   *
   * @throws RuntimeException when Redis or Postgres is unreachable
   */
  public Result restore() {
    // Fail fast when Redis is down, even if there is nothing to restore.
    redis.execute((RedisCallback<String>) connection -> connection.ping());
    long restored = 0;
    long existing = 0;
    long expired = 0;
    long malformed = 0;
    String after = "";
    List<StoredCart> page;
    do {
      page = store.findPage(after, PAGE_SIZE);
      for (StoredCart cart : page) {
        after = cart.cartKey();
        switch (restoreOne(cart)) {
          case RESTORED -> restored++;
          case EXISTING -> existing++;
          case EXPIRED -> expired++;
          case MALFORMED -> malformed++;
        }
      }
    } while (page.size() == PAGE_SIZE);
    Result result = new Result(restored, existing, expired, malformed);
    LOG.info(
        "Cart restore finished: restored={} skippedExisting={} skippedExpired={} malformed={}",
        restored,
        existing,
        expired,
        malformed);
    return result;
  }

  private Outcome restoreOne(StoredCart cart) {
    if (cart.items() == null || cart.items().isEmpty()) {
      return Outcome.MALFORMED;
    }
    Duration ttl = remainingTtl(cart);
    if (ttl.isZero() || ttl.isNegative()) {
      return Outcome.EXPIRED;
    }
    List<String> args = new ArrayList<>();
    args.add(Long.toString(Math.max(1, ttl.plusMillis(999).toSeconds())));
    args.add("version");
    args.add(Long.toString(cart.version()));
    args.add("updatedAt");
    args.add(Long.toString(cart.updatedAt().toEpochMilli()));
    for (CartUpdated.Item item : cart.items()) {
      args.add("q:" + item.sku());
      args.add(Integer.toString(item.quantity()));
      args.add("t:" + item.sku());
      args.add(Long.toString(item.addedAt().toEpochMilli()));
    }
    Long written = redis.execute(script, List.of(cart.cartKey()), args.toArray());
    return written != null && written == 1 ? Outcome.RESTORED : Outcome.EXISTING;
  }

  private Duration remainingTtl(StoredCart cart) {
    if (!cart.cartKey().startsWith(GUEST_PREFIX)) {
      return CartKey.USER_TTL;
    }
    Instant expiresAt = cart.updatedAt().plus(CartKey.GUEST_TTL);
    return Duration.between(clock.instant(), expiresAt);
  }

  private enum Outcome {
    RESTORED,
    EXISTING,
    EXPIRED,
    MALFORMED
  }
}
