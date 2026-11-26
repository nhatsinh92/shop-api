package com.mayshop.core;

import java.util.Arrays;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointWebExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Serves {@code health} and {@code info} on the app port. Actuator moves every endpoint to the
 * management port once {@code management.server.port} is set; probes and the demo stack still use
 * the app port for these two, while {@code prometheus} stays on the management port only.
 */
@Configuration(proxyBeanMethods = false)
class AppPortActuatorConfiguration {

  @Bean
  RouterFunction<ServerResponse> appPortActuator(
      HealthEndpointWebExtension health, InfoEndpoint info) {
    return RouterFunctions.route()
        .GET("/actuator/health", request -> health(health, new String[0]))
        .GET(
            "/actuator/health/**",
            request ->
                health(
                    health,
                    Arrays.stream(request.path().substring("/actuator/health".length()).split("/"))
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new)))
        .GET("/actuator/info", request -> ServerResponse.ok().body(info.info()))
        .build();
  }

  private static ServerResponse health(HealthEndpointWebExtension health, String[] path) {
    var response =
        health.health(ApiVersion.LATEST, WebServerNamespace.SERVER, SecurityContext.NONE, path);
    if (response == null) {
      return ServerResponse.notFound().build();
    }
    return ServerResponse.status(HttpStatusCode.valueOf(response.getStatus()))
        .body(response.getBody());
  }
}
