package com.mayshop.core.flags;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/flags} with no Redis reachable: the defaults answer, and the endpoint is public.
 */
@SpringBootTest(
    properties = {
      FlagsTestSupport.DB_FREE_EXCLUDES,
      "flags.defaults=online-payment=off",
      "spring.data.redis.port=1"
    })
@AutoConfigureMockMvc
@Import(FlagsTestSupport.NoDatabase.class)
class FlagsEndpointTest {

  @Autowired private MockMvc mvc;

  @Test
  void redisBeingDownStillAnswersWithTheDefaults() throws Exception {
    mvc.perform(get("/api/flags").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recommendations").value(true))
        .andExpect(jsonPath("$.memberPrice").value(true))
        .andExpect(jsonPath("$.onlinePayment").value(false));
  }

  @Test
  void answersWithoutATokenAndWithTheSharedCachePolicy() throws Exception {
    mvc.perform(get("/api/flags"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "public, max-age=5"));
  }

  @Test
  void anInvalidTokenIsNotRejected() throws Exception {
    mvc.perform(get("/api/flags").header("Authorization", "Bearer not-a-token"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/flags").cookie(new jakarta.servlet.http.Cookie("access_token", "junk")))
        .andExpect(status().isOk());
  }
}
