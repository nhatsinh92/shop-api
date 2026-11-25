package com.mayshop.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

/** Only real IP literals are parsed: host-like tokens must never reach the DNS resolver. */
class CidrLiteralTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ClientIpProperties.class)
  static class Config {}

  private static String resolve(String remote, String forwardedFor) throws Exception {
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/products")
            .remoteAddress(new InetSocketAddress(InetAddress.getByName(remote), 4000))
            .header("X-Forwarded-For", forwardedFor)
            .build();
    return new ClientIpResolver(new ClientIpProperties(List.of("10.0.0.0/8"))).resolve(request);
  }

  @Test
  void hostLikeAndBareNumberTokensAreNotIpLiterals() {
    for (String token :
        new String[] {
          "cafe", "deadbeef", "abc", "10", "1.2.3", "1.2.3.4.5", "256.1.1.1", "localhost"
        }) {
      assertThat(Cidr.literal(token)).as(token).isNull();
    }
  }

  @Test
  void realIpv4AndIpv6LiteralsAreParsed() {
    assertThat(Cidr.literal("203.0.113.9")).isNotNull();
    assertThat(Cidr.literal("::1")).isNotNull();
    assertThat(Cidr.literal("fd00::5")).isNotNull();
    assertThat(Cidr.literal("::ffff:10.0.0.1")).isNotNull();
  }

  @Test
  void aHostLikeForwardedForTokenFallsBackToTheRemoteAddressWithoutALookup() throws Exception {
    assertThat(resolve("10.0.0.5", "cafe")).isEqualTo("10.0.0.5");
    assertThat(resolve("10.0.0.5", "10")).isEqualTo("10.0.0.5");
    assertThat(resolve("10.0.0.5", "cafe, 10.1.1.1")).isEqualTo("10.0.0.5");
  }

  @Test
  void hostLikeAndBareNumberTrustedProxyValuesFailStartup() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(Config.class);
    for (String bad : new String[] {"cafe", "10", "10/8", "deadbeef/16"}) {
      runner
          .withPropertyValues("gateway.client-ip.trusted-proxies=10.0.0.0/8," + bad)
          .run(context -> assertThat(context).hasFailed());
    }
  }
}
