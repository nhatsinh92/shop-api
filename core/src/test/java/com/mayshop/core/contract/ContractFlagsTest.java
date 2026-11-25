package com.mayshop.core.contract;

import com.mayshop.core.flags.FlagsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** The real flags endpoint matches {@code getFeatureFlags} in the contract. */
@SpringBootTest(properties = {FlagsTestSupport.DB_FREE_EXCLUDES, "spring.data.redis.port=1"})
@AutoConfigureMockMvc
@Import(FlagsTestSupport.NoDatabase.class)
class ContractFlagsTest {

  @Autowired private MockMvc mvc;

  @Test
  void flagsMatchTheGetFeatureFlagsContract() throws Exception {
    var response =
        mvc.perform(MockMvcRequestBuilders.get("/api/flags").accept(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse();
    ContractValidator.core().assertValid(response, "getFeatureFlags");
  }
}
