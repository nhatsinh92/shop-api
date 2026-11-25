package com.mayshop.core.checkout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.mayshop.core.checkout.Payments.Method;
import com.mayshop.core.checkout.Payments.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link Payments#charge} against an in-process WireMock, so it runs without Docker. */
class PaymentsChargeTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private WireMockServer gateway;

  @BeforeEach
  void start() {
    gateway = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    gateway.start();
  }

  @AfterEach
  void stop() {
    gateway.stop();
  }

  private Payments payments(String url, boolean forward) {
    return new Payments("", url, Duration.ofSeconds(3), forward, meters);
  }

  private Payments payments(boolean forward) {
    return payments("http://localhost:" + gateway.port(), forward);
  }

  private Outcome charge(Payments payments, String mockOutcome) {
    return payments.charge(UUID.randomUUID(), 450, Method.CARD, mockOutcome);
  }

  @Test
  void a201AnswerSucceeds() {
    gateway.stubFor(post("/payments").willReturn(aResponse().withStatus(201)));

    assertThat(charge(payments(false), null)).isEqualTo(Outcome.SUCCEEDED);
    assertThat(meters.get("checkout.payment.outcome").tag("outcome", "SUCCEEDED").counter().count())
        .isEqualTo(1);
    assertThat(meters.get("checkout.payment").timer().count()).isEqualTo(1);
  }

  @Test
  void a402AnswerFails() {
    gateway.stubFor(post("/payments").willReturn(aResponse().withStatus(402)));
    assertThat(charge(payments(false), null)).isEqualTo(Outcome.FAILED);
    assertThat(meters.get("checkout.payment.outcome").tag("outcome", "FAILED").counter().count())
        .isEqualTo(1);
  }

  @Test
  void aThirtySecondDelayTimesOutInUnderFourSeconds() {
    gateway.stubFor(
        post("/payments").willReturn(aResponse().withStatus(504).withFixedDelay(30_000)));

    long start = System.nanoTime();
    Outcome outcome = charge(payments(false), null);
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(outcome).isEqualTo(Outcome.TIMED_OUT);
    assertThat(took).isLessThan(Duration.ofSeconds(4));
    assertThat(meters.get("checkout.payment.outcome").tag("outcome", "TIMED_OUT").counter().count())
        .isEqualTo(1);
  }

  @Test
  void a500AnswerFails() {
    gateway.stubFor(post("/payments").willReturn(aResponse().withStatus(500)));
    assertThat(charge(payments(false), null)).isEqualTo(Outcome.FAILED);
  }

  @Test
  void aRefusedConnectionFails() {
    int port = gateway.port();
    gateway.stop();
    assertThat(charge(payments("http://localhost:" + port, false), null)).isEqualTo(Outcome.FAILED);
  }

  @Test
  void theMockOutcomeHeaderIsNotForwardedByDefault() {
    gateway.stubFor(post("/payments").willReturn(aResponse().withStatus(201)));

    charge(payments(false), "fail");

    gateway.verify(postRequestedFor(urlEqualTo("/payments")).withoutHeader("X-Mock-Outcome"));
  }

  @Test
  void theMockOutcomeHeaderIsForwardedWhenEnabled() {
    gateway.stubFor(post("/payments").willReturn(aResponse().withStatus(402)));

    assertThat(charge(payments(true), "fail")).isEqualTo(Outcome.FAILED);

    gateway.verify(
        postRequestedFor(urlEqualTo("/payments")).withHeader("X-Mock-Outcome", equalTo("fail")));
  }
}
