package com.mayshop.cart.product;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls {@code POST /internal/products/lookup} on {@code core}. The JDK client keeps connections
 * pooled; the timeout covers the whole exchange (connect, headers and body). Every batch goes
 * through breaker {@code product-lookup}; while it is open no call is made and {@link
 * ProductLookupException.Reason#CIRCUIT_OPEN} is raised.
 */
@Component
public class CoreProductClient {

  static final String BREAKER = "product-lookup";
  private static final int MAX_SKUS = 50;

  private final HttpClient http;
  private final JsonMapper json;
  private final URI uri;
  private final String token;
  private final long timeoutNanos;
  private final CircuitBreakerFactory<?, ?> breakers;

  /** No breaker: every call goes straight to {@code core}. */
  public CoreProductClient(ProductLookupProperties properties, JsonMapper json) {
    this(properties, json, null);
  }

  @Autowired
  public CoreProductClient(
      ProductLookupProperties properties, JsonMapper json, CircuitBreakerFactory<?, ?> breakers) {
    this.json = json;
    this.breakers = breakers;
    this.uri = URI.create(trimTrailingSlash(properties.coreUrl()) + "/internal/products/lookup");
    this.token = properties.internalToken() == null ? "" : properties.internalToken();
    this.timeoutNanos = properties.timeout().toNanos();
    this.http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.timeout())
            .build();
  }

  /** Returns the known products among {@code skus}; unknown SKUs are left out by {@code core}. */
  public List<ProductInfo> lookup(Collection<String> skus) {
    List<ProductInfo> items = new ArrayList<>();
    List<String> all = List.copyOf(skus);
    for (int from = 0; from < all.size(); from += MAX_SKUS) {
      items.addAll(guarded(all.subList(from, Math.min(all.size(), from + MAX_SKUS))));
    }
    return items;
  }

  private List<ProductInfo> guarded(List<String> skus) {
    if (breakers == null) {
      return lookupBatch(skus);
    }
    return breakers.create(BREAKER).run(() -> lookupBatch(skus), CoreProductClient::translate);
  }

  /** Maps what the breaker rethrew, possibly wrapped, to a {@link ProductLookupException}. */
  private static List<ProductInfo> translate(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ProductLookupException ple) {
        throw ple;
      }
      if (cause instanceof CallNotPermittedException) {
        throw new ProductLookupException(
            ProductLookupException.Reason.CIRCUIT_OPEN, "Product lookup breaker is open", cause);
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    throw new ProductLookupException("Product lookup failed: " + error, error);
  }

  private List<ProductInfo> lookupBatch(List<String> skus) {
    try {
      byte[] body = json.writeValueAsBytes(new Request(skus));
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .header("X-Internal-Token", token)
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
              .build();
      HttpResponse<byte[]> response =
          http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
              .orTimeout(timeoutNanos, TimeUnit.NANOSECONDS)
              .get();
      if (response.statusCode() / 100 != 2) {
        throw new ProductLookupException("core answered " + response.statusCode());
      }
      return json.readValue(response.body(), Response.class).items();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProductLookupException("Product lookup interrupted", e);
    } catch (ExecutionException | CompletionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      boolean timedOut = cause instanceof TimeoutException;
      throw new ProductLookupException(
          timedOut ? ProductLookupException.Reason.TIMEOUT : ProductLookupException.Reason.ERROR,
          "Product lookup " + (timedOut ? "timed out" : "failed") + ": " + cause,
          cause);
    } catch (RuntimeException e) {
      if (e instanceof ProductLookupException ple) {
        throw ple;
      }
      throw new ProductLookupException("Product lookup failed: " + e, e);
    }
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private record Request(List<String> skus) {}

  private record Response(List<ProductInfo> items) {
    Response {
      items = items == null ? List.of() : items;
    }
  }
}
