package com.mayshop.cart.writebehind;

import com.mayshop.cart.repository.CartKey;
import com.mayshop.cart.repository.CartSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends {@code cart.updated} after a successful write. A single worker thread with a bounded queue
 * does the send, so a slow or stopped broker never blocks or fails a request: when the queue is
 * full or a send fails, the record is dropped, logged and counted as {@code
 * cart.write_behind.failed} (the next write of the cart carries the full cart again).
 */
@Component
public class CartUpdatedPublisher implements DisposableBean {

  public static final String TOPIC = "cart.updated";

  private static final Logger LOG = LoggerFactory.getLogger(CartUpdatedPublisher.class);
  private static final int QUEUE_CAPACITY = 10_000;

  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper json;
  private final Clock clock;
  private final Counter failed;
  private final ExecutorService sender;

  @Autowired
  public CartUpdatedPublisher(
      KafkaTemplate<String, String> kafka, JsonMapper json, MeterRegistry meters) {
    this(kafka, json, meters, Clock.systemUTC());
  }

  CartUpdatedPublisher(
      KafkaTemplate<String, String> kafka, JsonMapper json, MeterRegistry meters, Clock clock) {
    this.kafka = kafka;
    this.json = json;
    this.clock = clock;
    this.failed = Counter.builder("cart.write_behind.failed").register(meters);
    this.sender =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
              Thread thread = new Thread(runnable, "cart-updated-sender");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** Publishes the cart after an add, update or remove. */
  public void publish(CartKey key, CartSnapshot snapshot) {
    submit(CartUpdated.of(key.redisKey(), snapshot));
  }

  /** Publishes the user cart and an empty snapshot for the guest key after a merge. */
  public void publishMerge(CartKey user, CartSnapshot merged, CartKey guest) {
    submit(CartUpdated.of(user.redisKey(), merged));
    submit(CartUpdated.empty(guest.redisKey(), clock.instant()));
  }

  private void submit(CartUpdated record) {
    try {
      sender.execute(() -> send(record));
    } catch (RejectedExecutionException e) {
      fail(record, e);
    }
  }

  private void send(CartUpdated record) {
    try {
      String value = json.writeValueAsString(record);
      kafka
          .send(TOPIC, record.cartKey(), value)
          .whenComplete((result, error) -> {
            if (error != null) {
              fail(record, error);
            }
          });
    } catch (RuntimeException e) {
      fail(record, e);
    }
  }

  private void fail(CartUpdated record, Throwable error) {
    failed.increment();
    LOG.warn(
        "Could not publish cart.updated for {} (version {})",
        record.cartKey(),
        record.version(),
        error);
  }

  @Override
  public void destroy() {
    sender.shutdown();
  }
}
