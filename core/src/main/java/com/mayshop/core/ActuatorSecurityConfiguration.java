package com.mayshop.core;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Chain for {@code /actuator/**} on the app port, ahead of the auth module's chain. It permits
 * every request, so health and info stay open and any other actuator path (for example {@code
 * prometheus}, which lives on the management port only) answers 404 instead of 401.
 */
@Configuration(proxyBeanMethods = false)
class ActuatorSecurityConfiguration {

  @Bean
  @Order(0)
  SecurityFilterChain actuatorSecurity(HttpSecurity http) throws Exception {
    http.securityMatcher(actuatorRequest())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }

  /**
   * {@code /actuator/**}, and the error dispatch of a request to it: the 404 for an unexposed path
   * is re-dispatched to {@code /error}, which would otherwise fall to the JWT chain and answer 401.
   */
  private static RequestMatcher actuatorRequest() {
    RequestMatcher path = PathPatternRequestMatcher.withDefaults().matcher("/actuator/**");
    RequestMatcher errorOfActuator =
        request ->
            request.getDispatcherType() == DispatcherType.ERROR
                && request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri
                && uri.startsWith("/actuator/");
    return request -> path.matches(request) || errorOfActuator.matches(request);
  }
}
