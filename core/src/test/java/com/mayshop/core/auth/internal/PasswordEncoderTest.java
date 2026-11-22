package com.mayshop.core.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncoderTest {

  @Test
  void passwordsAreHashedWithBcryptCost12() {
    PasswordEncoder encoder = new AuthInternalConfiguration().passwordEncoder();

    String hash = encoder.encode("secret");

    assertThat(hash).startsWith("$2a$12$");
    assertThat(encoder.matches("secret", hash)).isTrue();
    assertThat(encoder.matches("other", hash)).isFalse();
  }
}
