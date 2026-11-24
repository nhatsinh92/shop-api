package com.mayshop.cart.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.mayshop.cart.repository.WriteResult;
import com.mayshop.cart.repository.WriteStatus;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CartItemWriteEndpointsTest.Meters.class)
class CartItemWriteEndpointsTest {

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

  private static ProductInfo product(String sku, String stock) {
    return new ProductInfo(
        sku,
        sku.toLowerCase(),
        "Name " + sku,
        "https://img.example/" + sku + ".jpg",
        null,
        new Money(100_000, "VND"),
        null,
        stock);
  }

  private void productIs(String sku, String stock) {
    when(products.lookup(any()))
        .thenReturn(new ProductLookupResult(Map.of(sku, product(sku, stock)), Set.of()));
  }

  private static WriteResult ok(String sku, int quantity) {
    return new WriteResult(
        WriteStatus.OK, new CartSnapshot(List.of(new CartLine(sku, quantity, T0)), 1, T0));
  }

  private static WriteResult failed(WriteStatus status) {
    return new WriteResult(status, new CartSnapshot(List.of(), 0, Instant.EPOCH));
  }

  private MvcTestResult send(
      String method, String uri, String body, String authorization, Cookie... cookies) {
    var request =
        switch (method) {
          case "POST" -> mvc.post().uri(uri);
          case "PATCH" -> mvc.patch().uri(uri);
          default -> mvc.delete().uri(uri);
        };
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    if (authorization != null) {
      request = request.header("Authorization", authorization);
    }
    if (cookies.length > 0) {
      request = request.cookie(cookies);
    }
    return request.exchange();
  }

  private MvcTestResult add(String body, String authorization, Cookie... cookies) {
    return send("POST", "/api/cart/items", body, authorization, cookies);
  }

  private MvcTestResult update(String sku, String body, String authorization, Cookie... cookies) {
    return send("PATCH", "/api/cart/items/" + sku, body, authorization, cookies);
  }

  private MvcTestResult remove(String sku, String authorization, Cookie... cookies) {
    return send("DELETE", "/api/cart/items/" + sku, null, authorization, cookies);
  }

  private void assertContract(MvcTestResult result, String operationId) {
    ContractValidator.cart().assertValid(result.getResponse(), operationId);
  }

  private void assertProblem(MvcTestResult result, int status, String code, String operationId) {
    assertThat(result).hasStatus(status);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertContract(result, operationId);
  }

  // ---- add ----

  @Test
  void addReturnsTheFullEnrichedCartWithNoStore() {
    productIs("SKU-A", "IN_STOCK");
    when(carts.add(CartKey.forUser("u1"), "SKU-A", 2)).thenReturn(ok("SKU-A", 2));

    MvcTestResult result = add("{\"sku\":\"SKU-A\",\"quantity\":2}", bearer("u1"));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.items[0].sku").isEqualTo("SKU-A");
    assertThat(result).bodyJson().extractingPath("$.items[0].lineTotal.amount").isEqualTo(200_000);
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(2);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    assertContract(result, "addCartItem");
  }

  @Test
  void addOfAnUnknownSkuAnswers404ProductNotFoundAndWritesNothing() {
    when(products.lookup(any())).thenReturn(new ProductLookupResult(Map.of(), Set.of("SKU-X")));

    MvcTestResult result = add("{\"sku\":\"SKU-X\",\"quantity\":1}", bearer("u1"));

    assertProblem(result, 404, "PRODUCT_NOT_FOUND", "addCartItem");
    verify(carts, never()).add(any(), anyString(), anyInt());
  }

  @Test
  void addOfAnOutOfStockSkuAnswers409AndWritesNothing() {
    productIs("SKU-A", "OUT_OF_STOCK");

    MvcTestResult result = add("{\"sku\":\"SKU-A\",\"quantity\":1}", bearer("u1"));

    assertProblem(result, 409, "CART_OUT_OF_STOCK", "addCartItem");
    verify(carts, never()).add(any(), anyString(), anyInt());
  }

  @Test
  void addBeyondTenUnitsAnswers422QtyLimitWithLimit10() {
    productIs("SKU-A", "IN_STOCK");
    when(carts.add(any(), eq("SKU-A"), eq(5))).thenReturn(failed(WriteStatus.QTY_LIMIT));

    MvcTestResult result = add("{\"sku\":\"SKU-A\",\"quantity\":5}", bearer("u1"));

    assertProblem(result, 422, "CART_QTY_LIMIT", "addCartItem");
    assertThat(result).bodyJson().extractingPath("$.limit").isEqualTo(10);
  }

  @Test
  void addOfAnElevenUnitRequestAnswers422QtyLimitNot400() {
    MvcTestResult result = add("{\"sku\":\"SKU-A\",\"quantity\":11}", bearer("u1"));

    assertProblem(result, 422, "CART_QTY_LIMIT", "addCartItem");
    assertThat(result).bodyJson().extractingPath("$.limit").isEqualTo(10);
    verifyNoInteractions(carts);
  }

  @Test
  void addOfAFifty1stSkuAnswers422ItemsLimitWithLimit50() {
    productIs("SKU-A", "IN_STOCK");
    when(carts.add(any(), eq("SKU-A"), eq(1))).thenReturn(failed(WriteStatus.ITEMS_LIMIT));

    MvcTestResult result = add("{\"sku\":\"SKU-A\",\"quantity\":1}", bearer("u1"));

    assertProblem(result, 422, "CART_ITEMS_LIMIT", "addCartItem");
    assertThat(result).bodyJson().extractingPath("$.limit").isEqualTo(50);
  }

  @Test
  void addWithAnInvalidBodyAnswers400() {
    for (String body :
        List.of(
            "{}",
            "{\"sku\":\"SKU-A\"}",
            "{\"quantity\":1}",
            "{\"sku\":\"SKU-A\",\"quantity\":0}",
            "{\"sku\":\"SKU-A\",\"quantity\":1.5}",
            "{\"sku\":\"SKU-A\",\"quantity\":\"2\"}",
            "{\"sku\":\"\",\"quantity\":1}",
            "[]",
            "not json")) {
      assertProblem(add(body, bearer("u1")), 400, "VALIDATION_FAILED", "addCartItem");
    }
    verifyNoInteractions(carts);
  }

  @Test
  void addWhileCoreIsDownAndTheSkuIsNotCachedAnswers503WithRetryAfterAndWritesNothing() {
    when(products.lookup(any())).thenThrow(new ProductLookupException("core is down"));

    MvcTestResult result = add("{\"sku\":\"SKU-A\",\"quantity\":1}", bearer("u1"));

    assertProblem(result, 503, "SERVICE_UNAVAILABLE", "addCartItem");
    assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("2");
    verify(carts, never()).add(any(), anyString(), anyInt());
  }

  @Test
  void theFirstGuestAddCreatesAGuestIdAndSetsTheCookie() {
    productIs("SKU-A", "IN_STOCK");
    when(carts.add(any(), eq("SKU-A"), eq(1))).thenReturn(ok("SKU-A", 1));

    MvcTestResult result = add("{\"sku\":\"SKU-A\",\"quantity\":1}", null);

    assertThat(result).hasStatusOk();
    var cookies = result.getResponse().getHeaders("Set-Cookie");
    assertThat(cookies).hasSize(1);
    assertThat(cookies.get(0))
        .matches("guest_cart=[A-Za-z0-9_-]{22}; Path=/; Max-Age=2592000; Expires=.*")
        .contains("HttpOnly", "Secure", "SameSite=Lax");
    var key = ArgumentCaptor.forClass(CartKey.class);
    verify(carts).add(key.capture(), eq("SKU-A"), eq(1));
    String id = cookies.get(0).substring("guest_cart=".length(), "guest_cart=".length() + 22);
    assertThat(key.getValue()).isEqualTo(CartKey.forGuest(id));
    assertContract(result, "addCartItem");
  }

  @Test
  void aMalformedGuestCookieIsReplacedByANewIdOnAdd() {
    productIs("SKU-A", "IN_STOCK");
    when(carts.add(any(), eq("SKU-A"), eq(1))).thenReturn(ok("SKU-A", 1));

    MvcTestResult result =
        add("{\"sku\":\"SKU-A\",\"quantity\":1}", null, new Cookie("guest_cart", "bad"));

    assertThat(result).hasStatusOk();
    assertThat(result.getResponse().getHeaders("Set-Cookie")).hasSize(1);
  }

  @Test
  void laterGuestWritesWithTheCookieDoNotSetItAgain() {
    productIs("SKU-A", "IN_STOCK");
    when(carts.add(CartKey.forGuest(GUEST), "SKU-A", 1)).thenReturn(ok("SKU-A", 2));

    MvcTestResult result =
        add("{\"sku\":\"SKU-A\",\"quantity\":1}", null, new Cookie("guest_cart", GUEST));

    assertThat(result).hasStatusOk();
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
  }

  @Test
  void aSignedInWriteNeverSetsTheCookieAndIgnoresTheGuestCart() {
    productIs("SKU-A", "IN_STOCK");
    when(carts.add(CartKey.forUser("u1"), "SKU-A", 1)).thenReturn(ok("SKU-A", 1));

    MvcTestResult result =
        add("{\"sku\":\"SKU-A\",\"quantity\":1}", bearer("u1"), new Cookie("guest_cart", GUEST));

    assertThat(result).hasStatusOk();
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
  }

  @Test
  void aFailedFirstGuestAddSetsNoCookie() {
    productIs("SKU-A", "OUT_OF_STOCK");

    MvcTestResult result = add("{\"sku\":\"SKU-A\",\"quantity\":1}", null);

    assertThat(result).hasStatus(409);
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
  }

  // ---- update ----

  @Test
  void updateSetsTheAbsoluteQuantityAndReturnsTheFullCart() {
    productIs("SKU-A", "LOW");
    when(carts.set(CartKey.forUser("u1"), "SKU-A", 1)).thenReturn(ok("SKU-A", 1));

    MvcTestResult result = update("SKU-A", "{\"quantity\":1}", bearer("u1"));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.itemCount").isEqualTo(1);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertContract(result, "updateCartItem");
  }

  @Test
  void updateOfASkuNotInTheCartAnswers404CartItemNotFound() {
    productIs("SKU-A", "IN_STOCK");
    when(carts.set(any(), eq("SKU-A"), eq(2))).thenReturn(failed(WriteStatus.ITEM_NOT_FOUND));

    MvcTestResult result = update("SKU-A", "{\"quantity\":2}", bearer("u1"));

    assertProblem(result, 404, "CART_ITEM_NOT_FOUND", "updateCartItem");
  }

  @Test
  void updateOfAGuestWithoutACartAnswers404WithoutWriting() {
    MvcTestResult result = update("SKU-A", "{\"quantity\":2}", null);

    assertProblem(result, 404, "CART_ITEM_NOT_FOUND", "updateCartItem");
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    verifyNoInteractions(carts);
  }

  @Test
  void updateOfAnOutOfStockSkuAnswers409() {
    productIs("SKU-A", "OUT_OF_STOCK");

    MvcTestResult result = update("SKU-A", "{\"quantity\":2}", bearer("u1"));

    assertProblem(result, 409, "CART_OUT_OF_STOCK", "updateCartItem");
    verify(carts, never()).set(any(), anyString(), anyInt());
  }

  @Test
  void updateAboveTenAnswers422QtyLimitNot400() {
    MvcTestResult result = update("SKU-A", "{\"quantity\":11}", bearer("u1"));

    assertProblem(result, 422, "CART_QTY_LIMIT", "updateCartItem");
    assertThat(result).bodyJson().extractingPath("$.limit").isEqualTo(10);
    verifyNoInteractions(carts);
  }

  @Test
  void updateWithZeroOrAFractionAnswers400ValidationFailed() {
    for (String body :
        List.of("{\"quantity\":0}", "{\"quantity\":1.5}", "{\"quantity\":-3}", "{}")) {
      assertProblem(
          update("SKU-A", body, bearer("u1")), 400, "VALIDATION_FAILED", "updateCartItem");
    }
    verifyNoInteractions(carts);
  }

  @Test
  void updateWhileCoreIsDownAndTheSkuIsNotCachedAnswers503AndWritesNothing() {
    when(products.lookup(any())).thenThrow(new ProductLookupException("core is down"));

    MvcTestResult result = update("SKU-A", "{\"quantity\":2}", bearer("u1"));

    assertProblem(result, 503, "SERVICE_UNAVAILABLE", "updateCartItem");
    assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("2");
    verify(carts, never()).set(any(), anyString(), anyInt());
  }

  // ---- remove ----

  @Test
  void removeReturnsTheFullCartAndNeverCallsCore() {
    when(carts.remove(CartKey.forUser("u1"), "SKU-A"))
        .thenReturn(new WriteResult(WriteStatus.OK, new CartSnapshot(List.of(), 2, T0)));

    MvcTestResult result = remove("SKU-A", bearer("u1"));

    assertThat(result).hasStatusOk();
    assertThat(result).bodyJson().extractingPath("$.items").isEqualTo(List.of());
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
    verifyNoInteractions(products);
    assertContract(result, "removeCartItem");
  }

  @Test
  void removeOfASkuNotInTheCartAnswers404CartItemNotFound() {
    when(carts.remove(any(), eq("SKU-A"))).thenReturn(failed(WriteStatus.ITEM_NOT_FOUND));
    when(products.lookup(any())).thenThrow(new ProductLookupException("core is down"));

    MvcTestResult result = remove("SKU-A", bearer("u1"));

    assertProblem(result, 404, "CART_ITEM_NOT_FOUND", "removeCartItem");
    verifyNoInteractions(products);
  }

  @Test
  void removeWhileRedisIsDownAnswers503() {
    when(carts.remove(any(), anyString()))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

    assertProblem(remove("SKU-A", bearer("u1")), 503, "SERVICE_UNAVAILABLE", "removeCartItem");
  }

  // ---- identity ----

  @Test
  void invalidBearerAnswers401ForAllThreeOperations() {
    String bad = "Bearer not-a-jwt";
    assertThat(add("{\"sku\":\"SKU-A\",\"quantity\":1}", bad)).hasStatus(401);
    assertThat(update("SKU-A", "{\"quantity\":1}", bad)).hasStatus(401);
    assertThat(remove("SKU-A", bad)).hasStatus(401);
    assertContract(add("{\"sku\":\"SKU-A\",\"quantity\":1}", bad), "addCartItem");
    assertContract(update("SKU-A", "{\"quantity\":1}", bad), "updateCartItem");
    assertContract(remove("SKU-A", bad), "removeCartItem");
    verifyNoInteractions(carts, products);
  }

  @Test
  void noBearerWithTheLoggedInHintAnswers401ForAllThreeOperations() {
    Cookie hint = new Cookie("logged_in", "1");
    MvcTestResult a = add("{\"sku\":\"SKU-A\",\"quantity\":1}", null, hint);
    MvcTestResult u = update("SKU-A", "{\"quantity\":1}", null, hint);
    MvcTestResult r = remove("SKU-A", null, hint);
    assertThat(a).hasStatus(401);
    assertThat(u).hasStatus(401);
    assertThat(r).hasStatus(401);
    assertContract(a, "addCartItem");
    assertContract(u, "updateCartItem");
    assertContract(r, "removeCartItem");
    verifyNoInteractions(carts, products);
  }
}
