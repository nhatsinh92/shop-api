package com.mayshop.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class ClientIpResolverTest {

  private static final List<String> PROXIES = List.of("10.0.0.0/8", "fd00::/8");

  private static ClientIpResolver resolver(List<String> proxies) {
    return new ClientIpResolver(new ClientIpProperties(proxies));
  }

  private static String resolve(
      ClientIpResolver resolver, String remote, String... forwardedForHeaders) {
    MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/products");
    try {
      builder.remoteAddress(new InetSocketAddress(InetAddress.getByName(remote), 4000));
    } catch (java.net.UnknownHostException e) {
      throw new IllegalStateException(e);
    }
    for (String header : forwardedForHeaders) {
      builder.header("X-Forwarded-For", header);
    }
    return resolver.resolve(builder.build());
  }

  @Test
  void withAnEmptyListTheRemoteAddressIsUsedAndForwardedForIsIgnored() {
    assertThat(resolve(resolver(List.of()), "10.0.0.5", "203.0.113.9")).isEqualTo("10.0.0.5");
    assertThat(resolve(resolver(List.of()), "198.51.100.4", "203.0.113.9"))
        .isEqualTo("198.51.100.4");
  }

  @Test
  void aTrustedRemoteUsesTheRightMostEntryThatIsNotTrusted() {
    assertThat(resolve(resolver(PROXIES), "10.0.0.5", "203.0.113.9, 10.1.1.1"))
        .isEqualTo("203.0.113.9");
  }

  @Test
  void aSpoofedXForwardedForFromAnUntrustedRemoteDoesNotChangeTheKey() {
    assertThat(resolve(resolver(PROXIES), "198.51.100.4", "203.0.113.9")).isEqualTo("198.51.100.4");
  }

  @Test
  void entriesPrependedByTheClientCannotChooseTheKey() {
    // the client sent "1.2.3.4"; the trusted proxy appended the address it saw
    assertThat(resolve(resolver(PROXIES), "10.0.0.5", "1.2.3.4, 198.51.100.4, 10.2.2.2"))
        .isEqualTo("198.51.100.4");
    assertThat(resolve(resolver(PROXIES), "10.0.0.5", "1.2.3.4", "198.51.100.4"))
        .as("several header lines are one list")
        .isEqualTo("198.51.100.4");
  }

  @Test
  void whenEveryEntryIsTrustedTheLeftMostIsUsed() {
    assertThat(resolve(resolver(PROXIES), "10.0.0.5", "10.9.9.9, 10.8.8.8")).isEqualTo("10.9.9.9");
  }

  @Test
  void aMissingHeaderFallsBackToTheRemoteAddress() {
    assertThat(resolve(resolver(PROXIES), "10.0.0.5")).isEqualTo("10.0.0.5");
  }

  @Test
  void anUnparsableHeaderFallsBackToTheRemoteAddress() {
    assertThat(resolve(resolver(PROXIES), "10.0.0.5", "not-an-ip")).isEqualTo("10.0.0.5");
    assertThat(resolve(resolver(PROXIES), "10.0.0.5", "203.0.113.9, example.com"))
        .isEqualTo("10.0.0.5");
    assertThat(resolve(resolver(PROXIES), "10.0.0.5", "")).isEqualTo("10.0.0.5");
  }

  @Test
  void ipv6ProxiesAndClientsAreSupported() {
    assertThat(resolve(resolver(PROXIES), "fd00::1", "2001:db8::7"))
        .isEqualTo("2001:db8:0:0:0:0:0:7");
    assertThat(resolve(resolver(PROXIES), "2001:db8::1", "203.0.113.9"))
        .isEqualTo("2001:db8:0:0:0:0:0:1");
  }

  @Test
  void anIpv4MappedIpv6AddressMatchesAnIpv4Network() {
    assertThat(resolve(resolver(PROXIES), "::ffff:10.0.0.5", "203.0.113.9"))
        .isEqualTo("203.0.113.9");
  }

  @Test
  void prefixLengthsThatAreNotByteAlignedAreHonoured() {
    ClientIpResolver resolver = resolver(List.of("192.168.4.0/22"));
    assertThat(resolve(resolver, "192.168.7.1", "203.0.113.9")).isEqualTo("203.0.113.9");
    assertThat(resolve(resolver, "192.168.8.1", "203.0.113.9")).isEqualTo("192.168.8.1");
  }
}
