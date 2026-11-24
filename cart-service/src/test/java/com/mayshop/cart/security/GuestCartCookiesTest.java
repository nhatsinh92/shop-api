package com.mayshop.cart.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuestCartCookiesTest {

  private final GuestCartCookies cookies = new GuestCartCookies();

  @Test
  void newIdIs128BitsBase64UrlWithoutPaddingAnd22Characters() {
    String id = cookies.newId();
    assertThat(id).hasSize(22).matches("^[A-Za-z0-9_-]{22}$");
    assertThat(Base64.getUrlDecoder().decode(id)).hasSize(16);
    assertThat(GuestCartCookies.isValid(id)).isTrue();
  }

  @Test
  void newIdsDiffer() {
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      ids.add(cookies.newId());
    }
    assertThat(ids).hasSize(1000);
  }

  @Test
  void setCookieHasTheContractAttributes() {
    String header = cookies.setCookie("AAAAAAAAAAAAAAAAAAAAAA");
    assertThat(header)
        .isEqualTo(
            "guest_cart=AAAAAAAAAAAAAAAAAAAAAA; Path=/; Max-Age=2592000; Expires="
                + expiresOf(header)
                + "; Secure; HttpOnly; SameSite=Lax");
  }

  @Test
  void clearCookieHasMaxAgeZero() {
    assertThat(cookies.clearCookie())
        .startsWith("guest_cart=;")
        .contains("Max-Age=0", "Path=/", "Secure", "HttpOnly", "SameSite=Lax");
  }

  @Test
  void validationRejectsMalformedValues() {
    assertThat(GuestCartCookies.isValid(null)).isFalse();
    assertThat(GuestCartCookies.isValid("short")).isFalse();
    assertThat(GuestCartCookies.isValid("AAAAAAAAAAAAAAAAAAAAAAA")).isFalse();
    assertThat(GuestCartCookies.isValid("AAAAAAAAAAAAAAAAAAAA+/")).isFalse();
  }

  private static String expiresOf(String header) {
    int start = header.indexOf("Expires=") + "Expires=".length();
    return header.substring(start, header.indexOf(';', start));
  }
}
