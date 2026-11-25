package com.mayshop.gateway.fallback;

import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory.Config;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The {@code Breaker} route filter: Spring Cloud Gateway's {@code CircuitBreaker} filter for every
 * request except {@code /api/auth/**}, which the {@code core} route also serves but which must
 * never be cut short by a breaker (a slow catalog must not block login).
 *
 * <p>When the breaker applies, the request records its name under {@link #BREAKER_ATTRIBUTE} so
 * {@link FallbackHandler} only answers failures of calls that really went through a breaker.
 */
@Component
public class BreakerGatewayFilterFactory extends AbstractGatewayFilterFactory<Config> {

  /** Exchange attribute holding the name of the breaker the request went through. */
  public static final String BREAKER_ATTRIBUTE = "gateway.breaker";

  private static final PathPattern AUTH = PathPatternParser.defaultInstance.parse("/api/auth/**");

  private final SpringCloudCircuitBreakerFilterFactory delegate;

  public BreakerGatewayFilterFactory(SpringCloudCircuitBreakerFilterFactory delegate) {
    super(Config.class);
    this.delegate = delegate;
  }

  @Override
  public List<String> shortcutFieldOrder() {
    return delegate.shortcutFieldOrder();
  }

  @Override
  public GatewayFilter apply(Config config) {
    GatewayFilter breaker = delegate.apply(config);
    return (exchange, chain) -> {
      if (AUTH.matches(PathContainer.parsePath(exchange.getRequest().getURI().getRawPath()))) {
        return chain.filter(exchange);
      }
      exchange.getAttributes().put(BREAKER_ATTRIBUTE, config.getName());
      return breaker.filter(exchange, chain);
    };
  }
}
