package com.mayshop.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.StubServer;
import com.mayshop.gateway.support.Tokens;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class JwksKeySourceTest {

  private final StubServer core = new StubServer();
  private final RSAKey key = Tokens.newKey("key-1");
  private final MutableClock clock = new MutableClock();
  private JwksKeySource source;

  @BeforeEach
  void setUp() {
    core.serveKeys(Tokens.publicSet(key));
    GatewayAuthProperties props =
        new GatewayAuthProperties(
            null, null, core.url() + "/.well-known/jwks.json", null, null, null, null);
    source = new JwksKeySource(WebClient.create(), props, clock);
  }

  @AfterEach
  void tearDown() {
    core.close();
  }

  @Test
  void nothingIsFetchedUntilTheFirstLookup() {
    assertThat(core.jwksFetches()).isZero();
  }

  @Test
  void theKeySetIsCachedForFiveMinutesAndThenFetchedAgain() {
    assertThat(source.find("key-1").block()).isNotNull();
    assertThat(source.find("key-1").block()).isNotNull();
    assertThat(core.jwksFetches()).isEqualTo(1);

    clock.advance(Duration.ofMinutes(4));
    source.find("key-1").block();
    assertThat(core.jwksFetches()).isEqualTo(1);

    clock.advance(Duration.ofMinutes(2));
    source.find("key-1").block();
    assertThat(core.jwksFetches()).isEqualTo(2);
  }

  @Test
  void unknownKidsRefetchAtMostOncePerThirtySeconds() {
    source.find("key-1").block();
    assertThat(core.jwksFetches()).isEqualTo(1);
    clock.advance(Duration.ofSeconds(31));

    for (int i = 0; i < 20; i++) {
      assertThat(source.find("random-" + i).block()).isNull();
    }
    assertThat(core.jwksFetches()).isEqualTo(2);

    clock.advance(Duration.ofSeconds(31));
    assertThat(source.find("another").block()).isNull();
    assertThat(core.jwksFetches()).isEqualTo(3);
  }

  @Test
  void aNewKeyIsFoundAfterOneRefetch() {
    source.find("key-1").block();
    RSAKey rotated = Tokens.newKey("key-2");
    core.serveKeys(Tokens.publicSet(key, rotated));
    clock.advance(Duration.ofSeconds(31));

    assertThat(source.find("key-2").block()).isNotNull();
    assertThat(core.jwksFetches()).isEqualTo(2);
  }

  @Test
  void aFailedFetchYieldsNoKeyAndIsRetriedOnlyAfterTheBackoff() {
    core.failJwks(503);

    assertThat(source.find("key-1").block()).isNull();
    assertThat(source.find("key-1").block()).isNull();
    assertThat(core.jwksFetches()).isEqualTo(1);

    core.failJwks(200);
    clock.advance(Duration.ofSeconds(6));
    assertThat(source.find("key-1").block()).isNotNull();
    assertThat(core.jwksFetches()).isEqualTo(2);
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-22T10:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
