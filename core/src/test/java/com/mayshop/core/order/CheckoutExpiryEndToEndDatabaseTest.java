package com.mayshop.core.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.checkout.Reservations;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * A reservation past {@code expiresAt}, with the fixed clock of {@link OrderSupport}: it reads
 * {@code EXPIRED}, cannot be ordered and gives its stock back in the sweep.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"checkout.sweep.enabled=false", "order.recovery.enabled=false"})
@AutoConfigureMockMvc
@Import(OrderSupport.Configuration.class)
class CheckoutExpiryEndToEndDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private Reservations reservations;

  @BeforeEach
  void clean() {
    OrderFixtures.reset(jdbc);
  }

  @Test
  void anExpiredReservationReadsExpiredCannotBeOrderedAndItsStockIsBackAfterTheSweep()
      throws Exception {
    UUID reservation =
        OrderFixtures.reservation(
            jdbc, OrderSupport.USER, "ACTIVE", OrderSupport.NOW.minusSeconds(1));

    MockHttpServletResponse read =
        mvc.perform(
                get("/api/checkout/reservations/" + reservation)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + OrderSupport.token(OrderSupport.USER)))
            .andReturn()
            .getResponse();
    assertThat(read.getStatus()).isEqualTo(200);
    assertThat(
            JSON.readTree(read.getContentAsString(StandardCharsets.UTF_8)).get("status").asString())
        .isEqualTo("EXPIRED");

    MockHttpServletResponse order =
        mvc.perform(
                post("/api/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + OrderSupport.token(OrderSupport.USER))
                    .header("Idempotency-Key", "7c9e6679-7425-40de-944b-e07fc1f90ae7")
                    .content(OrderFixtures.body(reservation, "COD")))
            .andReturn()
            .getResponse();
    assertThat(order.getStatus()).isEqualTo(410);

    // The 410 released the stock already; the sweep finds nothing left and the stock stays back.
    assertThat(reservations.expireDue(OrderSupport.NOW, 100)).isZero();
    assertThat(OrderFixtures.stock(jdbc)).isEqualTo(5);
    assertThat(
            OrderFixtures.single(
                jdbc, "select status from checkout.reservations where id = ?", reservation))
        .isEqualTo("EXPIRED");
  }

  @Test
  void theSweepGivesTheStockOfAnExpiredReservationBack() {
    UUID reservation =
        OrderFixtures.reservation(
            jdbc, OrderSupport.USER, "ACTIVE", OrderSupport.NOW.minusSeconds(1));

    assertThat(reservations.expireDue(OrderSupport.NOW, 100)).isEqualTo(1);

    assertThat(OrderFixtures.stock(jdbc)).isEqualTo(5);
    assertThat(
            OrderFixtures.single(
                jdbc, "select status from checkout.reservations where id = ?", reservation))
        .isEqualTo("EXPIRED");
  }
}
