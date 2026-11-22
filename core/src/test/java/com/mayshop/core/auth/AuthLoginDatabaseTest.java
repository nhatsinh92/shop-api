package com.mayshop.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Login and /me against the real schema; runs only where Docker is available (CI). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class AuthLoginDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private PasswordEncoder encoder;

  @Test
  void loginWritesASessionAndAHashedRefreshTokenAndMeAcceptsTheCookie() throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO auth.users (id, email, password_hash, display_name, member_tier)"
                + " VALUES (:id, 'Db.User@Example.com', :hash, 'Db User', 'SILVER')")
        .param("id", id)
        .param("hash", encoder.encode("pw-123456"))
        .update();

    MockHttpServletResponse login =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"db.user@example.com\",\"password\":\"pw-123456\"}"))
            .andReturn()
            .getResponse();

    assertThat(login.getStatus()).isEqualTo(200);
    assertThat(
            jdbc.sql("SELECT count(*) FROM auth.sessions WHERE user_id = :id")
                .param("id", id)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    String refresh =
        login.getHeaders("Set-Cookie").stream()
            .filter(c -> c.startsWith("refresh_token="))
            .map(c -> c.substring("refresh_token=".length(), c.indexOf(';')))
            .findFirst()
            .orElseThrow();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM auth.refresh_tokens r JOIN auth.sessions s"
                        + " ON s.id = r.session_id WHERE s.user_id = :id"
                        + " AND r.token_hash = encode(sha256(convert_to(:raw, 'UTF8')), 'hex')")
                .param("id", id)
                .param("raw", refresh)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    String access =
        login.getHeaders("Set-Cookie").stream()
            .filter(c -> c.startsWith("access_token="))
            .map(c -> c.substring("access_token=".length(), c.indexOf(';')))
            .findFirst()
            .orElseThrow();

    MockHttpServletResponse me =
        mvc.perform(get("/api/auth/me").cookie(new Cookie("access_token", access)))
            .andReturn()
            .getResponse();

    assertThat(me.getStatus()).isEqualTo(200);
    assertThat(me.getContentAsString()).contains("Db User");
  }
}
