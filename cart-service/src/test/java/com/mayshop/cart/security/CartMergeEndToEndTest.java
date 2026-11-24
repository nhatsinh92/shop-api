package com.mayshop.cart.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.mayshop.cart.product.Money;
import com.mayshop.cart.product.ProductInfo;
import com.mayshop.cart.product.ProductLookupResult;
import com.mayshop.cart.product.ProductLookupService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Guest adds two SKUs, then merges with a user token, against a real Redis. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class CartMergeEndToEndTest {

  private static final TestJwks JWKS = new TestJwks();
  private static final String GUEST = "BBBBBBBBBBBBBBBBBBBBBB";

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("CORE_URL", JWKS::coreUrl);
    registry.add("REDIS_HOST", REDIS::getHost);
    registry.add("REDIS_PORT", () -> REDIS.getMappedPort(6379));
  }

  @AfterAll
  static void stop() {
    JWKS.close();
  }

  @Autowired private MockMvcTester mvc;
  @MockitoBean private ProductLookupService products;

  @BeforeEach
  void catalog() {
    when(products.lookup(any()))
        .thenAnswer(call -> {
          Set<String> skus = call.getArgument(0);
          Map<String, ProductInfo> known = new java.util.LinkedHashMap<>();
          skus.forEach(sku -> known.put(sku, product(sku)));
          return new ProductLookupResult(known, Set.of());
        });
  }

  private static ProductInfo product(String sku) {
    return new ProductInfo(
        sku,
        sku.toLowerCase(),
        "Name " + sku,
        "https://img.example/" + sku + ".jpg",
        null,
        new Money(100_000, "VND"),
        null,
        "IN_STOCK");
  }

  private static String bearer(String subject) {
    return "Bearer "
        + TestJwks.token(
            JWKS.key, subject, "mayshop-core", "mayshop", Instant.now().plusSeconds(600));
  }

  private MvcTestResult add(String sku, int quantity, String authorization, Cookie... cookies) {
    var request =
        mvc.post()
            .uri("/api/cart/items")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}");
    if (authorization != null) {
      request = request.header("Authorization", authorization);
    }
    if (cookies.length > 0) {
      request = request.cookie(cookies);
    }
    return request.exchange();
  }

  @Test
  void aGuestCartMergesIntoTheUserCartSummingAndCappingAndASecondMergeChangesNothing() {
    String user = "merge-user-1";
    Cookie guest = new Cookie("guest_cart", GUEST);
    add("SKU-A", 4, null, guest);
    add("SKU-B", 2, null, guest);
    add("SKU-A", 8, bearer(user));
    add("SKU-C", 1, bearer(user));

    MvcTestResult first =
        mvc.post()
            .uri("/api/cart/merge")
            .header("Authorization", bearer(user))
            .cookie(guest)
            .exchange();

    assertThat(first).hasStatusOk();
    assertThat(first).bodyJson().extractingPath("$.items.length()").isEqualTo(3);
    assertThat(first).bodyJson().extractingPath("$.itemCount").isEqualTo(13);
    assertThat(first.getResponse().getHeader("Set-Cookie")).contains("guest_cart=;", "Max-Age=0");
    String firstBody = firstBody(first);
    assertThat(firstBody).contains("\"quantity\":10");

    MvcTestResult second =
        mvc.post()
            .uri("/api/cart/merge")
            .header("Authorization", bearer(user))
            .cookie(guest)
            .exchange();

    assertThat(second).hasStatusOk();
    assertThat(second).bodyJson().extractingPath("$.itemCount").isEqualTo(13);
  }

  private static String firstBody(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
