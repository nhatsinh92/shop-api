package com.mayshop.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JwtConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(JwtConfiguration.class);

  @Test
  void defaultsAreIssuerMayshopCoreAudienceMayshopAndTenMinutes() {
    runner.run(context -> {
      JwtProperties properties = context.getBean(JwtProperties.class);
      assertThat(properties.issuer()).isEqualTo("mayshop-core");
      assertThat(properties.audience()).isEqualTo("mayshop");
      assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(10));
    });
  }

  @Test
  void withoutAPrivateKeyAnEphemeralKeyIsGenerated() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(SigningKeys.class).jwks()).hasSize(1);
    });
  }

  @Test
  void configuredPrivateKeyIsLoadedAndKidIsStableAcrossRestarts() {
    KeyPair pair = TestKeys.pair(2048);
    String[] props = {"auth.jwt.private-key=" + TestKeys.privatePem(pair)};
    String[] kids = new String[2];
    for (int i = 0; i < 2; i++) {
      int n = i;
      runner.withPropertyValues(props).run(c -> kids[n] = c.getBean(SigningKeys.class).kid());
    }
    assertThat(kids[0]).isNotNull().isEqualTo(kids[1]);
  }

  @Test
  void customTtlIssuerAndAudienceAreBound() {
    runner
        .withPropertyValues(
            "auth.jwt.access-token-ttl=5m", "auth.jwt.issuer=iss-x", "auth.jwt.audience=aud-x")
        .run(context -> {
          JwtProperties properties = context.getBean(JwtProperties.class);
          assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(5));
          assertThat(properties.issuer()).isEqualTo("iss-x");
          assertThat(properties.audience()).isEqualTo("aud-x");
        });
  }

  @Test
  void malformedPrivateKeyFailsStartupWithAClearMessage() {
    runner
        .withPropertyValues("auth.jwt.private-key=not a pem")
        .run(context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("AUTH_JWT_PRIVATE_KEY"));
  }

  @Test
  void corruptBase64PrivateKeyFailsStartup() {
    runner
        .withPropertyValues(
            "auth.jwt.private-key=-----BEGIN PRIVATE KEY-----\nQUJDRA==\n-----END PRIVATE KEY-----")
        .run(context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("AUTH_JWT_PRIVATE_KEY"));
  }

  @Test
  void privateKeyShorterThan2048BitsFailsStartup() {
    String pem = TestKeys.privatePem(TestKeys.pair(1024));
    runner
        .withPropertyValues("auth.jwt.private-key=" + pem)
        .run(context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("minimum is 2048"));
  }

  @Test
  void malformedPreviousPublicKeyFailsStartup() {
    runner
        .withPropertyValues("auth.jwt.previous-public-key=garbage")
        .run(context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("AUTH_JWT_PREVIOUS_PUBLIC_KEY"));
  }
}
