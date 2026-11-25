package com.mayshop.core.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Flags against a real Redis; runs only where Docker is available (CI). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = FlagsTestSupport.DB_FREE_EXCLUDES)
@AutoConfigureMockMvc
class FlagsRedisDatabaseTest {

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  @Autowired private MockMvc mvc;
  @Autowired private StringRedisTemplate redis;

  @Test
  void hsetRecommendationsOffIsReportedWithinTwoSeconds() throws Exception {
    assertThat(recommendations()).contains("\"recommendations\":true");
    redis.opsForHash().put("flags", "recommendations", "off");
    long deadline = System.nanoTime() + 2_500_000_000L;
    String body = recommendations();
    while (!body.contains("\"recommendations\":false") && System.nanoTime() < deadline) {
      Thread.sleep(100);
      body = recommendations();
    }
    assertThat(body).contains("\"recommendations\":false");
  }

  private String recommendations() throws Exception {
    return mvc.perform(get("/api/flags")).andReturn().getResponse().getContentAsString();
  }
}
