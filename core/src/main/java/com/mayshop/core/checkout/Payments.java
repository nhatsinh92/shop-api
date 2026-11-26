package com.mayshop.core.checkout;

import com.mayshop.core.flags.FeatureFlags;
import com.mayshop.core.flags.Flag;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The payment methods of checkout and whether each can be used. Public API of the {@code checkout}
 * module. A method is unavailable when listed in {@code CHECKOUT_DISABLED_PAYMENT_METHODS}; the
 * online methods are also reported unavailable while the {@code ONLINE_PAYMENT} flag is off or the
 * {@code payment} circuit breaker is open. Also charges the mock payment gateway within a bounded
 * time, through that breaker.
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
  private static final String BREAKER = "payment";

  /** A call that counts as a failure for the breaker; carries what {@code charge} answers. */
  private static final class GatewayFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient Outcome outcome;
    private final String reason;

    GatewayFailure(Outcome outcome, String reason) {
      super(reason, null, false, false);
      this.outcome = outcome;
      this.reason = reason;
    }
  }

  private final Set<String> disabled;
  private final RestClient client;
  private final boolean forwardMockOutcome;
  private final Timer timer;
  private final MeterRegistry meters;
  private final FeatureFlags flags;
  private final CircuitBreakerFactory<?, ?> breakers;
  private final CircuitBreakerRegistry breakerRegistry;

  /** Defaults for everything but the disabled methods. Used where no charge is made. */
  Payments(String disabledMethods) {
    this(
        disabledMethods,
        "http://localhost:8090",
        Duration.ofSeconds(3),
        false,
        new SimpleMeterRegistry());
  }

  /** No breaker and no flags: every charge goes straight to the gateway. */
  Payments(
      String disabledMethods,
      String url,
      Duration timeout,
      boolean forwardMockOutcome,
      MeterRegistry meters) {
    this(disabledMethods, url, timeout, forwardMockOutcome, meters, null, null, null);
  }

  @Autowired
  Payments(
      @Value("${checkout.payment.disabled-methods:}") String disabledMethods,
      @Value("${checkout.payment.url:http://localhost:8090}") String url,
      @Value("${checkout.payment.timeout:3s}") Duration timeout,
      @Value("${checkout.payment.mock-outcome-header:false}") boolean forwardMockOutcome,
      MeterRegistry meters,
      FeatureFlags flags,
      CircuitBreakerFactory<?, ?> breakers,
      CircuitBreakerRegistry breakerRegistry) {
    this.flags = flags;
    this.breakers = breakers;
    this.breakerRegistry = breakerRegistry;
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
   * Charges the gateway through breaker {@code payment}: {@code POST /payments}. 201 is {@link
   * Outcome#SUCCEEDED}, 402 and any other status or I/O error {@link Outcome#FAILED}, a read
   * timeout {@link Outcome#TIMED_OUT}. Timeouts, connect errors and 5xx count as breaker failures;
   * a 402 decline does not. While the breaker is open the gateway is not called and the answer is
   * {@link Outcome#FAILED}.
   *
   * @param mockOutcome the demo header value ({@code fail} or {@code timeout}), forwarded only when
   *     {@code PAYMENT_MOCK_OUTCOME_HEADER=true}; may be null
   */
  public Outcome charge(UUID orderId, long amount, Method method, String mockOutcome) {
    return timer.record(() -> guarded(orderId, amount, method, mockOutcome));
  }

  private Outcome guarded(UUID orderId, long amount, Method method, String mockOutcome) {
    if (breakers == null) {
      try {
        return record(send(orderId, amount, method, mockOutcome));
      } catch (GatewayFailure failure) {
        return record(failure.outcome, failure.reason);
      }
    }
    return breakers
        .create(BREAKER)
        .run(
            () -> record(send(orderId, amount, method, mockOutcome)),
            error -> fallback(orderId, error));
  }

  /** Maps what the breaker rethrew, possibly wrapped, to the answer of {@link #charge}. */
  private Outcome fallback(UUID orderId, Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof CallNotPermittedException) {
        LOG.warn("Payment breaker open, order {} not charged", orderId);
        return record(Outcome.FAILED, "circuit_open");
      }
      if (cause instanceof GatewayFailure failure) {
        return record(failure.outcome, failure.reason);
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    LOG.warn("Payment call failed for order {}", orderId, error);
    return record(Outcome.FAILED, "error");
  }

  private Outcome record(Answer answer) {
    return record(answer.outcome(), answer.reason());
  }

  private Outcome record(Outcome outcome, String reason) {
    Counter.builder("checkout.payment.outcome")
        .tag("outcome", outcome.name())
        .tag("reason", reason)
        .register(meters)
        .increment();
    return outcome;
  }

  /** What a call that the breaker does not count came back with. */
  private record Answer(Outcome outcome, String reason) {}

  /** Returns what the breaker counts as a success; throws {@link GatewayFailure} for the rest. */
  private Answer send(UUID orderId, long amount, Method method, String mockOutcome) {
    HttpStatusCode status;
    try {
      status =
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
    } catch (ResourceAccessException e) {
      if (e.getCause() instanceof HttpTimeoutException
          && !(e.getCause() instanceof HttpConnectTimeoutException)) {
        LOG.warn("Payment gateway timed out for order {}", orderId);
        throw new GatewayFailure(Outcome.TIMED_OUT, "timeout");
      }
      LOG.warn("Payment gateway unreachable for order {}: {}", orderId, e.getMessage());
      throw new GatewayFailure(Outcome.FAILED, "error");
    } catch (RuntimeException e) {
      LOG.warn("Payment call failed for order {}", orderId, e);
      throw new GatewayFailure(Outcome.FAILED, "error");
    }
    if (status.value() == 201) {
      return new Answer(Outcome.SUCCEEDED, "none");
    }
    if (status.is5xxServerError()) {
      LOG.warn("Payment gateway answered {} for order {}", status.value(), orderId);
      throw new GatewayFailure(Outcome.FAILED, "error");
    }
    if (status.value() != 402) {
      LOG.warn("Payment gateway answered {} for order {}", status.value(), orderId);
    }
    return new Answer(Outcome.FAILED, "declined");
  }

  /** Every method, in display order, with its availability. */
  public List<MethodStatus> list() {
    return Arrays.stream(Method.values())
        .map(method -> new MethodStatus(method, isOffered(method)))
        .toList();
  }

  /**
   * Whether the method is enabled by configuration. Does not look at the flag or the breaker: an
   * order paying online while the provider fails is charged and answers {@code PAYMENT_FAILED}.
   */
  public boolean isAvailable(Method method) {
    return !disabled.contains(method.name());
  }

  /**
   * Whether checkout should offer the method now: enabled by configuration and, for the online
   * methods, the {@code ONLINE_PAYMENT} flag is on and breaker {@code payment} is not open. COD is
   * not affected by either.
   */
  private boolean isOffered(Method method) {
    if (!isAvailable(method)) {
      return false;
    }
    if (method == Method.COD) {
      return true;
    }
    return (flags == null || flags.isEnabled(Flag.ONLINE_PAYMENT)) && !breakerOpen();
  }

  private boolean breakerOpen() {
    if (breakerRegistry == null) {
      return false;
    }
    CircuitBreaker.State state = breakerRegistry.circuitBreaker(BREAKER).getState();
    return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
  }
}
