package com.mayshop.cart.product;

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
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls {@code POST /internal/products/lookup} on {@code core}. The JDK client keeps connections
 * pooled; the timeout covers the whole exchange (connect, headers and body).
 */
@Component
public class CoreProductClient {

  private static final int MAX_SKUS = 50;

  private final HttpClient http;
  private final JsonMapper json;
  private final URI uri;
  private final String token;
  private final long timeoutNanos;

  public CoreProductClient(ProductLookupProperties properties, JsonMapper json) {
    this.json = json;
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
      items.addAll(lookupBatch(all.subList(from, Math.min(all.size(), from + MAX_SKUS))));
    }
    return items;
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
      String what = cause instanceof TimeoutException ? "timed out" : "failed";
      throw new ProductLookupException("Product lookup " + what + ": " + cause, cause);
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
