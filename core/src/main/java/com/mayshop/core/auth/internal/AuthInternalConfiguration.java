package com.mayshop.core.auth.internal;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionProperties.class)
class AuthInternalConfiguration {

  /** BCrypt cost 12, as the epic decides. */
  static final int BCRYPT_COST = 12;

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(BCRYPT_COST);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
