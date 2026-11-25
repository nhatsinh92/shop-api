package com.mayshop.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ClientIpPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ClientIpProperties.class)
  static class Config {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void theDefaultListIsEmpty() {
    runner.run(context ->
        assertThat(context.getBean(ClientIpProperties.class).trustedProxies()).isEmpty());
  }

  @Test
  void aBlankValueFromTheEnvironmentBindsAnEmptyList() {
    runner
        .withPropertyValues("gateway.client-ip.trusted-proxies=")
        .run(context ->
            assertThat(context.getBean(ClientIpProperties.class).trustedProxies()).isEmpty());
  }

  @Test
  void aCommaSeparatedListOfIpv4AndIpv6CidrsIsBound() {
    runner
        .withPropertyValues("gateway.client-ip.trusted-proxies=10.0.0.0/8, fd00::/8")
        .run(context ->
            assertThat(context.getBean(ClientIpProperties.class).trustedProxies())
                .containsExactly("10.0.0.0/8", "fd00::/8"));
  }

  @Test
  void anInvalidCidrFailsStartupWithAClearMessage() {
    for (String bad :
        new String[] {"10.0.0.0/33", "fd00::/129", "nonsense", "10.0.0.0/x", "example.com/8"}) {
      runner
          .withPropertyValues("gateway.client-ip.trusted-proxies=10.0.0.0/8," + bad)
          .run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessages(context.getStartupFailure()))
                .contains("Invalid trusted proxy '" + bad + "'");
          });
    }
  }

  private static String rootMessages(Throwable failure) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      all.append(t.getMessage()).append('\n');
    }
    return all.toString();
  }
}
