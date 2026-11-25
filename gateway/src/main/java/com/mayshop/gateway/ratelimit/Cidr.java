package com.mayshop.gateway.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** An IPv4 or IPv6 network in CIDR notation; a bare address is a single-host network. */
record Cidr(byte[] network, int prefixLength) {

  private static final Pattern IPV4 =
      Pattern.compile(
          "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
  private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

  /** Parses {@code 10.0.0.0/8}, {@code fd00::/8} or a bare address; never resolves host names. */
  static Cidr parse(String text) {
    String value = text == null ? "" : text.trim();
    int slash = value.indexOf('/');
    String address = slash < 0 ? value : value.substring(0, slash);
    InetAddress parsed = literal(address);
    if (parsed == null) {
      throw new IllegalArgumentException(
          "Invalid trusted proxy '"
              + value
              + "': expected an IPv4 or IPv6 CIDR such as 10.0.0.0/8");
    }
    byte[] bytes = parsed.getAddress();
    int max = bytes.length * 8;
    int prefix = max;
    if (slash >= 0) {
      try {
        prefix = Integer.parseInt(value.substring(slash + 1));
      } catch (NumberFormatException e) {
        prefix = -1;
      }
      if (prefix < 0 || prefix > max) {
        throw new IllegalArgumentException(
            "Invalid trusted proxy '" + value + "': prefix length must be 0-" + max);
      }
    }
    return new Cidr(bytes, prefix);
  }

  /**
   * The address for an IP literal, or null when the text is not one. Only a dotted-quad IPv4 (four
   * decimal octets) or text containing a colon (IPv6) reaches {@link InetAddress#getByName}, which
   * never consults the resolver for those forms. Host-like tokens such as {@code cafe} and bare
   * numbers such as {@code 10} are rejected up front.
   */
  static InetAddress literal(String text) {
    if (text == null) {
      return null;
    }
    boolean ipv4 = IPV4.matcher(text).matches();
    boolean ipv6 = text.indexOf(':') >= 0 && IPV6.matcher(text).matches();
    if (!ipv4 && !ipv6) {
      return null;
    }
    try {
      return InetAddress.getByName(text);
    } catch (UnknownHostException | RuntimeException e) {
      return null;
    }
  }

  boolean contains(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length != network.length) {
      return false;
    }
    int whole = prefixLength / 8;
    for (int i = 0; i < whole; i++) {
      if (bytes[i] != network[i]) {
        return false;
      }
    }
    int rest = prefixLength % 8;
    if (rest == 0) {
      return true;
    }
    int mask = 0xFF << (8 - rest) & 0xFF;
    return (bytes[whole] & mask) == (network[whole] & mask);
  }
}
