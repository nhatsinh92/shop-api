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
@Import(CartReadEndpointsTest.Meters.class)
class CartReadEndpointsTest {

  private static final TestJwks JWKS = new TestJwks();
  private static final String GUEST = "AAAAAAAAAAAAAAAAAAAAAA";
  private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-21T10:15:00Z");

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

  private static ProductInfo product(String sku, long price, Long list, String stock) {
    return new ProductInfo(
        sku,
        sku.toLowerCase(),
        "Name " + sku,
        "https://img.example/" + sku + ".jpg",
        "Red / M",
        new Money(price, "VND"),
        list == null ? null : new Money(list, "VND"),
        stock);
  }

  private static CartSnapshot snapshot(CartLine... lines) {
    return new CartSnapshot(List.of(lines), 3, T1);
  }

  private MvcTestResult getCart(String uri, String authorization, Cookie... cookies) {
    var request = mvc.get().uri(uri);
    if (authorization != null) {
      request = request.header("Authorization", authorization);
    }
    if (cookies.length > 0) {
      request = request.cookie(cookies);
    }
    return request.exchange();
  }

  private void assertContract(MvcTestResult result, String operationId) {
    ContractValidator.cart().assertValid(result.getResponse(), operationId);
  }

  @Test
  void getCartBuildsEnrichedLinesFromRedisQuantitiesAndTheProductLookup() {
    when(carts.read(CartKey.forUser("u1")))
        .thenReturn(
            snapshot(
                new CartLine("SKU-B", 2, T1),
                new CartLine("SKU-A", 3, T0),
                new CartLine("SKU-C", 1, T0)));
    when(products.lookup(any()))
        .thenReturn(
            new ProductLookupResult(
                Map.of(
                    "SKU-A", product("SKU-A", 100_000, 150_000L, "IN_STOCK"),
                    "SKU-B", product("SKU-B", 50_000, null, "LOW"),
                    "SKU-C", product("SKU-C", 70_000, null, "OUT_OF_STOCK")),
                Set.of()));

    MvcTestResult result = getCart("/api/cart", bearer("u1"));

    assertThat(result).hasStatusOk();
    assertThat(result)
        .bodyJson()
        .extractingPath("$.items[*].sku")
        .isEqualTo(List.of("SKU-B", "SKU-A", "SKU-C"));
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(6);
    // Only available lines count: 2 x 50000 + 3 x 100000.
    assertThat(result).bodyJson().extractingPath("$.subtotal.amount").isEqualTo(400_000);
    assertThat(result).bodyJson().extractingPath("$.updatedAt").isEqualTo("2026-11-21T10:15:00Z");
    assertThat(result).bodyJson().extractingPath("$.items[1].unitPrice.amount").isEqualTo(100_000);
    assertThat(result).bodyJson().extractingPath("$.items[1].lineTotal.amount").isEqualTo(300_000);
    assertThat(result).bodyJson().extractingPath("$.items[1].listPrice.amount").isEqualTo(150_000);
    assertThat(result).bodyJson().extractingPath("$.items[1].variantLabel").isEqualTo("Red / M");
    assertThat(result).bodyJson().extractingPath("$.items[1].stockLevel").isEqualTo("IN_STOCK");
    assertThat(result).bodyJson().extractingPath("$.items[1].available").isEqualTo(true);
    assertThat(result).bodyJson().extractingPath("$.items[2].available").isEqualTo(false);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertContract(result, "getCart");
  }

  @Test
  void aSkuUnknownToTheLookupStaysAsAnUnavailableLineWithPlaceholderValues() {
    when(carts.read(CartKey.forUser("u1"))).thenReturn(snapshot(new CartLine("SKU-Gone", 2, T0)));
    when(products.lookup(any())).thenReturn(new ProductLookupResult(Map.of(), Set.of("SKU-Gone")));

    MvcTestResult result = getCart("/api/cart", bearer("u1"));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.items[0].available").isEqualTo(false);
    assertThat(result).bodyJson().extractingPath("$.items[0].name").isEqualTo("SKU-Gone");
    assertThat(result).bodyJson().extractingPath("$.items[0].slug").isEqualTo("sku-gone");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.items[0].imageUrl")
        .isEqualTo("http://localhost:3000/images/cart-placeholder.svg");
    assertThat(result).bodyJson().extractingPath("$.items[0].unitPrice.amount").isEqualTo(0);
    assertThat(result).bodyJson().extractingPath("$.items[0].lineTotal.amount").isEqualTo(0);
    assertThat(result).bodyJson().doesNotHavePath("$.items[0].stockLevel");
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(2);
    assertThat(result).bodyJson().extractingPath("$.subtotal.amount").isEqualTo(0);
    assertContract(result, "getCart");
  }

  @Test
  void anEmptyCartAnswersTheEmptyShapeWithoutCallingCore() {
    when(carts.read(CartKey.forUser("u1")))
        .thenReturn(new CartSnapshot(List.of(), 0, Instant.EPOCH));

    MvcTestResult result = getCart("/api/cart", bearer("u1"));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.items").isEqualTo(List.of());
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(0);
    assertThat(result).bodyJson().extractingPath("$.subtotal.amount").isEqualTo(0);
    assertThat(result).bodyJson().extractingPath("$.updatedAt").isNotNull();
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
    verifyNoInteractions(products);
    assertContract(result, "getCart");
  }

  @Test
  void aGuestWithoutACookieAnswersTheEmptyShapeAndNothingIsRead() {
    MvcTestResult result = getCart("/api/cart", null);

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.items").isEqualTo(List.of());
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    verifyNoInteractions(carts, products);
    assertContract(result, "getCart");
  }

  @Test
  void aWellFormedGuestIdWithNoCartAnswersEmptyAndSetsNoCookie() {
    when(carts.read(CartKey.forGuest(GUEST)))
        .thenReturn(new CartSnapshot(List.of(), 0, Instant.EPOCH));

    MvcTestResult result = getCart("/api/cart", null, new Cookie("guest_cart", GUEST));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.items").isEqualTo(List.of());
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    verifyNoInteractions(products);
    assertContract(result, "getCart");
  }

  @Test
  void aGuestReadsTheGuestCart() {
    when(carts.read(CartKey.forGuest(GUEST))).thenReturn(snapshot(new CartLine("SKU-A", 1, T0)));
    when(products.lookup(any()))
        .thenReturn(
            new ProductLookupResult(
                Map.of("SKU-A", product("SKU-A", 10_000, null, "IN_STOCK")), Set.of()));

    MvcTestResult result = getCart("/api/cart", null, new Cookie("guest_cart", GUEST));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(1);
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
  }

  @Test
  void aSignedInCallerIgnoresTheGuestCartCookie() {
    when(carts.read(CartKey.forUser("u1")))
        .thenReturn(new CartSnapshot(List.of(), 0, Instant.EPOCH));

    MvcTestResult result = getCart("/api/cart", bearer("u1"), new Cookie("guest_cart", GUEST));

    assertThat(result).hasStatusOk();
    verify(carts).read(CartKey.forUser("u1"));
    verify(carts, never()).read(CartKey.forGuest(GUEST));
  }

  @Test
  void lookupFailureAnswers503WithRetryAfter() {
    when(carts.read(CartKey.forUser("u1"))).thenReturn(snapshot(new CartLine("SKU-A", 1, T0)));
    when(products.lookup(any())).thenThrow(new ProductLookupException("core is down"));

    MvcTestResult result = getCart("/api/cart", bearer("u1"));

    assertThat(result).hasStatus(503);
    assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("2");
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    assertContract(result, "getCart");
  }

  @Test
  void summaryReadsOnlyTheRedisHashAndNeverTheLookup() {
    when(carts.read(CartKey.forUser("u1")))
        .thenReturn(snapshot(new CartLine("SKU-A", 3, T0), new CartLine("SKU-B", 2, T0)));
    when(products.lookup(any())).thenThrow(new ProductLookupException("core is down"));

    MvcTestResult result = getCart("/api/cart/summary", bearer("u1"));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(5);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
    verifyNoInteractions(products);
    assertContract(result, "getCartSummary");
  }

  @Test
  void summaryOfAGuestWithoutACookieIsZero() {
    MvcTestResult result = getCart("/api/cart/summary", null);

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(0);
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    assertContract(result, "getCartSummary");
  }

  @Test
  void invalidBearerAnswers401ForBothOperations() {
    for (String uri : List.of("/api/cart", "/api/cart/summary")) {
      MvcTestResult result = getCart(uri, "Bearer not-a-jwt");
      assertThat(result).hasStatus(401);
      assertContract(result, uri.endsWith("summary") ? "getCartSummary" : "getCart");
    }
    verifyNoInteractions(carts, products);
  }

  @Test
  void noBearerWithTheLoggedInHintAnswers401ForBothOperations() {
    for (String uri : List.of("/api/cart", "/api/cart/summary")) {
      MvcTestResult result = getCart(uri, null, new Cookie("logged_in", "1"));
      assertThat(result).hasStatus(401);
      assertContract(result, uri.endsWith("summary") ? "getCartSummary" : "getCart");
    }
  }

  @Test
  void summaryAnswers503WhenRedisIsDown() {
    when(carts.read(any()))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

    MvcTestResult result = getCart("/api/cart/summary", bearer("u1"));

    assertThat(result).hasStatus(503);
    assertContract(result, "getCartSummary");
  }
}
