package com.mayshop.cart.repository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis hash per cart, one Lua script per write, run with EVALSHA (see {@code resources/redis}).
 */
@Repository
public class RedisCartRepository implements CartRepository, SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(RedisCartRepository.class);

  private final StringRedisTemplate redis;
  private final Clock clock;
  private final Counter droppedLines;
  private final Script add = load("add");
  private final Script set = load("set");
  private final Script remove = load("remove");
  private final Script merge = load("merge");

  @Autowired
  public RedisCartRepository(StringRedisTemplate redis, MeterRegistry meters) {
    this(redis, meters, Clock.systemUTC());
  }

  RedisCartRepository(StringRedisTemplate redis, MeterRegistry meters, Clock clock) {
    this.redis = redis;
    this.clock = clock;
    this.droppedLines = Counter.builder("cart.merge.dropped_lines").register(meters);
  }

  /** Loads the scripts at start-up; a missing script is reloaded on the first NOSCRIPT anyway. */
  @Override
  public void afterSingletonsInstantiated() {
    try {
      for (Script script : List.of(add, set, remove, merge)) {
        redis.execute(
            connection -> connection.scriptingCommands().scriptLoad(script.bytes()), true);
      }
    } catch (RuntimeException e) {
      LOG.warn("Could not load the cart scripts at start-up; they load on first use", e);
    }
  }

  @Override
  public CartSnapshot read(CartKey key) {
    Map<String, String> hash = redis.<String, String>opsForHash().entries(key.redisKey());
    return snapshot(hash);
  }

  @Override
  public WriteResult add(CartKey key, String sku, int quantity) {
    requireQuantity(quantity);
    return write(add, key, sku, String.valueOf(quantity), now(), ttl(key));
  }

  @Override
  public WriteResult set(CartKey key, String sku, int quantity) {
    requireQuantity(quantity);
    return write(set, key, sku, String.valueOf(quantity), now(), ttl(key));
  }

  @Override
  public WriteResult remove(CartKey key, String sku) {
    return write(remove, key, sku, now(), ttl(key));
  }

  @Override
  public MergeResult merge(CartKey user, CartKey guest) {
    Reply reply = run(merge, List.of(user.redisKey(), guest.redisKey()), now(), ttl(user));
    if (reply.dropped() > 0) {
      droppedLines.increment(reply.dropped());
    }
    return new MergeResult(snapshot(reply.hash()), reply.dropped());
  }

  private WriteResult write(Script script, CartKey key, String... args) {
    Reply reply = run(script, List.of(key.redisKey()), args);
    return new WriteResult(WriteStatus.valueOf(reply.status()), snapshot(reply.hash()));
  }

  private Reply run(Script script, List<String> keys, String... args) {
    List<byte[]> params = new ArrayList<>();
    keys.forEach(k -> params.add(bytes(k)));
    for (String arg : args) {
      params.add(bytes(arg));
    }
    byte[][] keysAndArgs = params.toArray(byte[][]::new);
    List<?> raw;
    try {
      raw = evalSha(script, keys.size(), keysAndArgs);
    } catch (RuntimeException e) {
      if (!isNoScript(e)) {
        throw e;
      }
      raw = evalSha(script, keys.size(), keysAndArgs, true);
    }
    return Reply.parse(raw);
  }

  private List<?> evalSha(Script script, int numKeys, byte[][] keysAndArgs) {
    return evalSha(script, numKeys, keysAndArgs, false);
  }

  private List<?> evalSha(Script script, int numKeys, byte[][] keysAndArgs, boolean loadFirst) {
    return redis.execute(
        connection -> {
          if (loadFirst) {
            connection.scriptingCommands().scriptLoad(script.bytes());
          }
          return connection
              .scriptingCommands()
              .<List<?>>evalSha(script.sha(), ReturnType.MULTI, numKeys, keysAndArgs);
        },
        true);
  }

  private static boolean isNoScript(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      String message = t.getMessage();
      if (message != null && message.contains("NOSCRIPT")) {
        return true;
      }
    }
    return false;
  }

  private static CartSnapshot snapshot(Map<String, String> hash) {
    List<CartLine> lines = new ArrayList<>();
    hash.forEach((field, value) -> {
      if (field.startsWith("q:")) {
        String sku = field.substring(2);
        long addedAt = parseLong(hash.get("t:" + sku));
        lines.add(new CartLine(sku, Integer.parseInt(value), Instant.ofEpochMilli(addedAt)));
      }
    });
    lines.sort(Comparator.comparing(CartLine::addedAt).reversed().thenComparing(CartLine::sku));
    return new CartSnapshot(
        lines,
        parseLong(hash.get("version")),
        Instant.ofEpochMilli(parseLong(hash.get("updatedAt"))));
  }

  private static long parseLong(String value) {
    return value == null ? 0 : Long.parseLong(value);
  }

  private static void requireQuantity(int quantity) {
    if (quantity < 1 || quantity > MAX_QUANTITY) {
      throw new IllegalArgumentException("quantity must be 1 to " + MAX_QUANTITY);
    }
  }

  private String now() {
    return String.valueOf(clock.millis());
  }

  private static String ttl(CartKey key) {
    return String.valueOf(key.ttl().toSeconds());
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(Object value) {
    return value instanceof byte[] raw
        ? new String(raw, StandardCharsets.UTF_8)
        : String.valueOf(value);
  }

  /** Parsed script reply: status, dropped lines and the cart hash. */
  private record Reply(String status, int dropped, Map<String, String> hash) {

    static Reply parse(List<?> raw) {
      Map<String, String> hash = new HashMap<>();
      for (int i = 2; i + 1 < raw.size(); i += 2) {
        hash.put(text(raw.get(i)), text(raw.get(i + 1)));
      }
      return new Reply(text(raw.get(0)), Integer.parseInt(text(raw.get(1))), hash);
    }
  }

  private record Script(String source) {

    byte[] bytes() {
      return source.getBytes(StandardCharsets.UTF_8);
    }

    String sha() {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes()));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static Script load(String name) {
    try {
      String common =
          new ClassPathResource("redis/common.lua").getContentAsString(StandardCharsets.UTF_8);
      String body =
          new ClassPathResource("redis/" + name + ".lua")
              .getContentAsString(StandardCharsets.UTF_8);
      return new Script(common + "\n" + body);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
