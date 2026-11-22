package com.mayshop.core.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The real health endpoint of core matches {@code coreHealth} in the contract. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ContractHealthTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Autowired private MockMvc mvc;

  @Test
  void actuatorHealthMatchesTheCoreHealthContract() throws Exception {
    var response =
        mvc.perform(
                MockMvcRequestBuilders.get("/actuator/health").accept(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse();
    ContractValidator.core().assertValid(response, "coreHealth");
  }
}
