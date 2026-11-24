package com.mayshop.cart.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mayshop.cart.contract.ContractValidator;
import com.mayshop.cart.product.Money;
import com.mayshop.cart.product.ProductInfo;
import com.mayshop.cart.product.ProductLookupException;
import com.mayshop.cart.product.ProductLookupResult;
import com.mayshop.cart.product.ProductLookupService;
import com.mayshop.cart.repository.CartKey;
import com.mayshop.cart.repository.CartLine;
import com.mayshop.cart.repository.CartRepository;
import com.mayshop.cart.repository.CartSnapshot;
import com.mayshop.cart.repository.MergeResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CartMergeEndpointTest.Meters.class)
class CartMergeEndpointTest {

  private static final TestJwks JWKS = new TestJwks();
  private static final String GUEST = "AAAAAAAAAAAAAAAAAAAAAA";
  private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

  @TestConfiguration
  static class Meters {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @DynamicPropertySource
  static void core(DynamicPropertyRegistry registry) {
    registry.add("CORE_URL", JWKS::coreUrl);
  }

  @AfterAll
  static void stop() {
    JWKS.close();
  }

  @Autowired private MockMvcTester mvc;
  @MockitoBean private CartRepository carts;
  @MockitoBean private ProductLookupService products;

  @BeforeEach
  void reset() {
    Mockito.reset(carts, products);
  }

  private static String bearer(String subject) {
    return "Bearer "
        + TestJwks.token(
            JWKS.key, subject, "mayshop-core", "mayshop", Instant.now().plusSeconds(600));
  }

  private static CartSnapshot snapshot(String sku, int quantity) {
    return new CartSnapshot(List.of(new CartLine(sku, quantity, T0)), 1, T0);
  }

  private void productIs(String sku) {
    var info =
        new ProductInfo(
            sku,
            sku.toLowerCase(),
            "Name " + sku,
            "https://img.example/" + sku + ".jpg",
            null,
            new Money(100_000, "VND"),
            null,
            "IN_STOCK");
    when(products.lookup(any())).thenReturn(new ProductLookupResult(Map.of(sku, info), Set.of()));
  }

  private MvcTestResult merge(String authorization, Cookie... cookies) {
    var request = mvc.post().uri("/api/cart/merge");
    if (authorization != null) {
      request = request.header("Authorization", authorization);
    }
    if (cookies.length > 0) {
      request = request.cookie(cookies);
    }
    return request.exchange();
  }

  private static void assertCleared(MvcTestResult result) {
    assertThat(result.getResponse().getHeaders("Set-Cookie"))
        .singleElement()
        .asString()
        .startsWith("guest_cart=;")
        .contains("Path=/", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Lax");
  }

  @Test
  void mergeWithoutBearerAnswers401AndLeavesTheGuestCartUntouched() {
    MvcTestResult result = merge(null, new Cookie("guest_cart", GUEST));

    assertThat(result).hasStatus(401);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTH_UNAUTHENTICATED");
    ContractValidator.cart().assertValid(result.getResponse(), "mergeGuestCart");
    verifyNoInteractions(carts);
  }

  @Test
  void mergeWithoutBearerButWithTheLoggedInHintAnswers401AndLeavesTheGuestCartUntouched() {
    MvcTestResult result =
        merge(null, new Cookie("guest_cart", GUEST), new Cookie("logged_in", "1"));

    assertThat(result).hasStatus(401);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTH_UNAUTHENTICATED");
    verifyNoInteractions(carts);
  }

  @Test
  void mergeWithAValidGuestCookieReturnsTheEnrichedUserCartAndClearsTheCookie() {
    productIs("SKU-A");
    when(carts.merge(CartKey.forUser("u1"), CartKey.forGuest(GUEST)))
        .thenReturn(new MergeResult(snapshot("SKU-A", 3), 0));

    MvcTestResult result = merge(bearer("u1"), new Cookie("guest_cart", GUEST));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.items[0].sku").isEqualTo("SKU-A");
    assertThat(result).bodyJson().extractingPath("$.items[0].name").isEqualTo("Name SKU-A");
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(3);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertCleared(result);
    ContractValidator.cart().assertValid(result.getResponse(), "mergeGuestCart");
  }

  @Test
  void mergeWithoutTheCookieReturnsTheUserCartUnchangedAndSetsNoCookie() {
    productIs("SKU-A");
    when(carts.read(CartKey.forUser("u1"))).thenReturn(snapshot("SKU-A", 2));

    MvcTestResult result = merge(bearer("u1"));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(2);
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    verify(carts, never()).merge(any(), any());
    ContractValidator.cart().assertValid(result.getResponse(), "mergeGuestCart");
  }

  @Test
  void mergeWithAnUnknownGuestIdReturnsTheUserCartUnchangedAndStillClearsTheCookie() {
    productIs("SKU-A");
    when(carts.merge(CartKey.forUser("u1"), CartKey.forGuest(GUEST)))
        .thenReturn(new MergeResult(snapshot("SKU-A", 2), 0));

    MvcTestResult result = merge(bearer("u1"), new Cookie("guest_cart", GUEST));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(2);
    assertCleared(result);
  }

  @Test
  void mergeWithAMalformedGuestIdReturnsTheUserCartUnchangedAndStillClearsTheCookie() {
    productIs("SKU-A");
    when(carts.read(CartKey.forUser("u1"))).thenReturn(snapshot("SKU-A", 2));

    MvcTestResult result = merge(bearer("u1"), new Cookie("guest_cart", "not valid!"));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(2);
    assertCleared(result);
    verify(carts, never()).merge(any(), any());
  }

  @Test
  void mergeAnswers503WhenEnrichmentNeedsCoreAndItIsDown() {
    when(carts.merge(CartKey.forUser("u1"), CartKey.forGuest(GUEST)))
        .thenReturn(new MergeResult(snapshot("SKU-A", 1), 0));
    when(products.lookup(any())).thenThrow(new ProductLookupException("core is down"));

    MvcTestResult result = merge(bearer("u1"), new Cookie("guest_cart", GUEST));

    assertThat(result).hasStatus(503);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    ContractValidator.cart().assertValid(result.getResponse(), "mergeGuestCart");
  }
}
