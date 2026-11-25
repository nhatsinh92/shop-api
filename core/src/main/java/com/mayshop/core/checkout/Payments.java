package com.mayshop.core.checkout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The payment methods of checkout and whether each can be used. Public API of the {@code checkout}
 * module. A method is unavailable only when listed in {@code CHECKOUT_DISABLED_PAYMENT_METHODS}.
 * Also charges the mock payment gateway within a bounded time.
 */
@Service
public class Payments {

  /** The methods of the contract, in display order. */
  public enum Method {
    COD("Thanh toán khi nhận hàng (COD)"),
    EWALLET("Ví điện tử"),
    CARD("Thẻ ngân hàng / thẻ tín dụng");

    private final String displayName;

    Method(String displayName) {
      this.displayName = displayName;
    }

    public String displayName() {
      return displayName;
    }
  }

  /** A method as shown on the checkout page. */
  public record MethodStatus(Method method, boolean available) {}

  /** Result of {@link #charge}. */
  public enum Outcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT
  }

  private static final Logger LOG = LoggerFactory.getLogger(Payments.class);
  private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
  private static final String MOCK_OUTCOME_HEADER = "X-Mock-Outcome";

  private final Set<String> disabled;
  private final RestClient client;
  private final boolean forwardMockOutcome;
  private final Timer timer;
  private final MeterRegistry meters;

  /** Defaults for everything but the disabled methods. Used where no charge is made. */
  Payments(String disabledMethods) {
    this(
        disabledMethods,
        "http://localhost:8090",
        Duration.ofSeconds(3),
        false,
        new SimpleMeterRegistry());
  }

  @Autowired
  Payments(
      @Value("${checkout.payment.disabled-methods:}") String disabledMethods,
      @Value("${checkout.payment.url:http://localhost:8090}") String url,
      @Value("${checkout.payment.timeout:3s}") Duration timeout,
      @Value("${checkout.payment.mock-outcome-header:false}") boolean forwardMockOutcome,
      MeterRegistry meters) {
    this.disabled =
        Arrays.stream(disabledMethods.split(","))
            .map(value -> value.trim().toUpperCase(Locale.ROOT))
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(timeout);
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    this.forwardMockOutcome = forwardMockOutcome;
    this.meters = meters;
    this.timer = Timer.builder("checkout.payment").register(meters);
  }

  /**
   * Charges the gateway: {@code POST /payments}. 201 is {@link Outcome#SUCCEEDED}, 402 and any
   * other status or I/O error {@link Outcome#FAILED}, a read timeout {@link Outcome#TIMED_OUT}.
   *
   * @param mockOutcome the demo header value ({@code fail} or {@code timeout}), forwarded only when
   *     {@code PAYMENT_MOCK_OUTCOME_HEADER=true}; may be null
   */
  public Outcome charge(UUID orderId, long amount, Method method, String mockOutcome) {
    Outcome outcome = timer.record(() -> send(orderId, amount, method, mockOutcome));
    Counter.builder("checkout.payment.outcome")
        .tag("outcome", outcome.name())
        .register(meters)
        .increment();
    return outcome;
  }

  private Outcome send(UUID orderId, long amount, Method method, String mockOutcome) {
    try {
      HttpStatusCode status =
          client
              .post()
              .uri("/payments")
              .headers(headers -> {
                if (forwardMockOutcome && mockOutcome != null && !mockOutcome.isBlank()) {
                  headers.set(MOCK_OUTCOME_HEADER, mockOutcome);
                }
              })
              .body(
                  Map.of("orderId", orderId.toString(), "amount", amount, "method", method.name()))
              .exchange((request, response) -> response.getStatusCode());
      if (status.value() == 201) {
        return Outcome.SUCCEEDED;
      }
      if (status.value() != 402) {
        LOG.warn("Payment gateway answered {} for order {}", status.value(), orderId);
      }
      return Outcome.FAILED;
    } catch (ResourceAccessException e) {
      if (e.getCause() instanceof HttpTimeoutException
          && !(e.getCause() instanceof HttpConnectTimeoutException)) {
        LOG.warn("Payment gateway timed out for order {}", orderId);
        return Outcome.TIMED_OUT;
      }
      LOG.warn("Payment gateway unreachable for order {}: {}", orderId, e.getMessage());
      return Outcome.FAILED;
    } catch (RuntimeException e) {
      LOG.warn("Payment call failed for order {}", orderId, e);
      return Outcome.FAILED;
    }
  }

  /** Every method, in display order, with its availability. */
  public List<MethodStatus> list() {
    return Arrays.stream(Method.values())
        .map(method -> new MethodStatus(method, isAvailable(method)))
        .toList();
  }

  public boolean isAvailable(Method method) {
    return !disabled.contains(method.name());
  }
}
