package com.mayshop.gateway.ratelimit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Resolves the client IP used by the rate limits. {@code X-Forwarded-For} counts only when the
 * connection comes from a trusted proxy; then the right-most entry that is not itself a trusted
 * proxy wins (entries a client prepends sit further left and are ignored), the left-most entry is
 * used when every entry is trusted, and a missing or unparsable header falls back to the remote
 * address.
 */
public final class ClientIpResolver {

  /** Used when the connection has no address. */
  static final String UNKNOWN = "unknown";

  private final List<Cidr> trusted;

  /** Creates a resolver for the configured proxies. */
  public ClientIpResolver(ClientIpProperties properties) {
    this.trusted = properties.trustedProxies().stream().map(Cidr::parse).toList();
  }

  /** The client IP of the request as text. */
  public String resolve(ServerHttpRequest request) {
    InetSocketAddress socket = request.getRemoteAddress();
    InetAddress remote = socket == null ? null : socket.getAddress();
    if (remote == null) {
      return UNKNOWN;
    }
    if (!isTrusted(remote)) {
      return remote.getHostAddress();
    }
    List<InetAddress> chain = forwardedChain(request);
    if (chain.isEmpty()) {
      return remote.getHostAddress();
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
      if (!isTrusted(chain.get(i))) {
        return chain.get(i).getHostAddress();
      }
    }
    return chain.get(0).getHostAddress();
  }

  private boolean isTrusted(InetAddress address) {
    return trusted.stream().anyMatch(cidr -> cidr.contains(address));
  }

  /** The parsed {@code X-Forwarded-For} entries, or empty when absent or any entry is invalid. */
  private static List<InetAddress> forwardedChain(ServerHttpRequest request) {
    List<String> headers = request.getHeaders().get("X-Forwarded-For");
    if (headers == null) {
      return List.of();
    }
    List<InetAddress> chain = new ArrayList<>();
    for (String header : headers) {
      for (String part : header.split(",", -1)) {
        InetAddress address = Cidr.literal(part.trim());
        if (address == null) {
          return List.of();
        }
        chain.add(address);
      }
    }
    return chain;
  }
}
