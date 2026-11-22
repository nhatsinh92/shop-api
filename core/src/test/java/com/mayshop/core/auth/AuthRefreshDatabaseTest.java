package com.mayshop.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Refresh against real Postgres and Redis; runs only where Docker is available (CI). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthRefreshDatabaseTest.ClockConfiguration.class)
class AuthRefreshDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  /** A clock the test moves, to log in "in the past" and then refresh after the token expired. */
  static final class OffsetClock extends Clock {
    volatile Duration offset = Duration.ZERO;

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.now().plus(offset);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    @Primary
    OffsetClock testClock() {
      return new OffsetClock();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private PasswordEncoder encoder;
  @Autowired private OffsetClock clock;

  private String createUser() {
    String email = "refresh." + UUID.randomUUID() + "@example.com";
    jdbc.sql(
            "INSERT INTO auth.users (id, email, password_hash, display_name, member_tier)"
                + " VALUES (:id, :email, :hash, 'Refresh User', 'GOLD')")
        .param("id", UUID.randomUUID())
        .param("email", email)
        .param("hash", encoder.encode("pw-123456"))
        .update();
    return email;
  }

  private MockHttpServletResponse login(String email) throws Exception {
    return mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"pw-123456\"}"))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse refresh(String token) throws Exception {
    return mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", token)))
        .andReturn()
        .getResponse();
  }

  private static String cookie(MockHttpServletResponse response, String name) {
    return response.getHeaders("Set-Cookie").stream()
        .filter(c -> c.startsWith(name + "="))
        .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
        .findFirst()
        .orElseThrow();
  }

  private int me(String access) throws Exception {
    return mvc.perform(get("/api/auth/me").cookie(new Cookie("access_token", access)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void refreshAfterTheAccessTokenExpiredRestoresAccess() throws Exception {
    String email = createUser();
    // Log in one hour ago: the access token (10 minutes) is long expired now.
    clock.offset = Duration.ofHours(-1);
    MockHttpServletResponse login = login(email);
    clock.offset = Duration.ZERO;
    assertThat(login.getStatus()).isEqualTo(200);
    assertThat(me(cookie(login, "access_token"))).isEqualTo(401);

    MockHttpServletResponse refreshed = refresh(cookie(login, "refresh_token"));

    assertThat(refreshed.getStatus()).isEqualTo(204);
    assertThat(me(cookie(refreshed, "access_token"))).isEqualTo(200);
  }

  @Test
  void parallelRefreshesInsideTheWindowAllSucceedAndNeverRevokeTheSession() throws Exception {
    String email = createUser();
    String old = cookie(login(email), "refresh_token");
    int count = 5;
    ExecutorService pool = Executors.newFixedThreadPool(count);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return refresh(old);
            }));
      }
      start.countDown();
      List<String> issued = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        MockHttpServletResponse response = future.get();
        assertThat(response.getStatus()).isEqualTo(204);
        issued.add(cookie(response, "refresh_token"));
      }
      for (String token : issued) {
        assertThat(refresh(token).getStatus()).isEqualTo(204);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM auth.sessions s JOIN auth.users u ON u.id = s.user_id"
                        + " WHERE u.email = :email AND s.revoked_at IS NOT NULL")
                .param("email", email)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void aRotatedTokenAfterTheWindowRevokesTheSessionAndItsTokens() throws Exception {
    String email = createUser();
    String old = cookie(login(email), "refresh_token");
    String successor = cookie(refresh(old), "refresh_token");
    // Wait for the 10-second grace key to expire in Redis.
    Thread.sleep(Duration.ofSeconds(11));

    MockHttpServletResponse reuse = refresh(old);

    assertThat(reuse.getStatus()).isEqualTo(401);
    assertThat(reuse.getContentAsString()).contains("AUTH_REFRESH_INVALID");
    assertThat(refresh(successor).getStatus()).isEqualTo(401);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM auth.refresh_tokens r JOIN auth.sessions s"
                        + " ON s.id = r.session_id JOIN auth.users u ON u.id = s.user_id"
                        + " WHERE u.email = :email AND r.revoked_at IS NULL")
                .param("email", email)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void theGraceKeyIsWrittenToRedisWithATtl() throws Exception {
    String email = createUser();
    String old = cookie(login(email), "refresh_token");
    refresh(old);

    var redis =
        new org.springframework.data.redis.core.StringRedisTemplate(
            new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379)));
    ((org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory)
            redis.getConnectionFactory())
        .afterPropertiesSet();
    String hash =
        jdbc.sql("SELECT encode(sha256(convert_to(:raw, 'UTF8')), 'hex')")
            .param("raw", old)
            .query(String.class)
            .single();
    Long ttl = redis.getExpire("auth:grace:" + hash);
    assertThat(ttl).isBetween(1L, 10L);
  }
}
