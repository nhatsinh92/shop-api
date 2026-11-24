package com.mayshop.core.product.revalidate;

import com.mayshop.core.product.ProductUpdated;
import com.mayshop.core.product.ProductUpdated.Change;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells shop-web to revalidate its cached pages after a price or detail change. Runs after the
 * commit through Spring Modulith's event publication registry, so an event that was not handled
 * (for example the process died) is handled again after a restart: delivery is at least once. A
 * delivery that fails all its attempts is logged and counted in {@code product.revalidate.failed};
 * the listener then returns normally so the publication completes.
 *
 * <p>Registered only when {@code WEB_REVALIDATE_URL} is set; the URL needs a {@code
 * REVALIDATE_SECRET}.
 */
@Component
@ConditionalOnExpression("!'${product.revalidate.url:}'.isBlank()")
class RevalidateWebhook {

  static final String FAILED_COUNTER = "product.revalidate.failed";
  static final String SIGNATURE_HEADER = "X-Shop-Signature";
  private static final int ATTEMPTS = 3;
  private static final Logger LOG = LoggerFactory.getLogger(RevalidateWebhook.class);

  private final URI url;
  private final String secret;
  private final Duration timeout;
  private final Duration backoff;
  private final JsonMapper json;
  private final MeterRegistry meters;
  private final Clock clock;
  private final HttpClient client;

  RevalidateWebhook(
      @Value("${product.revalidate.url}") String url,
      @Value("${product.revalidate.secret:}") String secret,
      @Value("${product.revalidate.timeout:2s}") String timeout,
      @Value("${product.revalidate.backoff:500ms}") String backoff,
      JsonMapper json,
      MeterRegistry meters) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "WEB_REVALIDATE_URL is set but REVALIDATE_SECRET is blank: the revalidation webhook"
              + " must be signed. Set REVALIDATE_SECRET or unset WEB_REVALIDATE_URL.");
    }
    this.url = URI.create(url.strip());
    this.secret = secret;
    this.timeout = DurationStyle.detectAndParse(timeout);
    this.backoff = DurationStyle.detectAndParse(backoff);
    this.json = json;
    this.meters = meters;
    this.clock = Clock.systemUTC();
    // HTTP/1.1 on purpose: the default HTTP_2 client sends "Upgrade: h2c" on http:// URLs, and
    // `next start` closes such connections without a response (issue #95).
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(this.timeout)
            .build();
    meters.counter(FAILED_COUNTER);
  }

  @ApplicationModuleListener
  void on(ProductUpdated event) {
    if (!event.changes().contains(Change.PRICE) && !event.changes().contains(Change.DETAIL)) {
      return;
    }
    byte[] body = json.writeValueAsBytes(event);
    String lastFailure = "no attempt";
    Duration wait = backoff;
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try {
        int status = send(body);
        if (status >= 200 && status < 300) {
          return;
        }
        lastFailure = "status " + status;
      } catch (IOException e) {
        lastFailure = e.getClass().getSimpleName();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        lastFailure = "interrupted";
        break;
      }
      if (attempt < ATTEMPTS && !pause(wait)) {
        lastFailure = "interrupted";
        break;
      }
      wait = wait.multipliedBy(2);
    }
    meters.counter(FAILED_COUNTER).increment();
    LOG.warn(
        "Revalidation webhook for product {} (event {}) failed after {} attempts: {}",
        event.sku(),
        event.eventId(),
        ATTEMPTS,
        lastFailure);
  }

  private int send(byte[] body) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(url)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header(
                SIGNATURE_HEADER,
                RevalidateSignature.header(secret, clock.instant().getEpochSecond(), body))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static boolean pause(Duration wait) {
    try {
      Thread.sleep(wait);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
