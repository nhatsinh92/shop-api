package com.mayshop.cart.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CoreProductClientTest {

  private StubCore core;

  @BeforeEach
  void start() throws Exception {
    core = new StubCore();
  }

  @AfterEach
  void stop() {
    core.close();
  }

  private CoreProductClient client(Duration timeout) {
    return new CoreProductClient(
        new ProductLookupProperties(core.url(), "secret-token", timeout, null),
        JsonMapper.builder().build());
  }

  @Test
  void callsLookupWithTheInternalTokenAndParsesItems() {
    core.responseBody = "{\"items\":[" + StubCore.item("SKU-1", 100) + "]}";

    List<ProductInfo> items = client(Duration.ofSeconds(2)).lookup(List.of("SKU-1"));

    assertThat(items).hasSize(1);
    assertThat(items.get(0).price().amount()).isEqualTo(100);
    assertThat(core.tokens).containsExactly("secret-token");
    assertThat(core.requestBodies).containsExactly("{\"skus\":[\"SKU-1\"]}");
  }

  @Test
  void defaultTimeoutIs150MillisAndCacheTtlIs60Seconds() {
    ProductLookupProperties defaults = new ProductLookupProperties("http://x", "t", null, null);
    assertThat(defaults.timeout()).isEqualTo(Duration.ofMillis(150));
    assertThat(defaults.cacheTtl()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void timeoutBeyondTheBudgetRaisesLookupException() {
    core.delayMillis = 1_000;
    long start = System.nanoTime();

    assertThatThrownBy(() -> client(Duration.ofMillis(150)).lookup(List.of("SKU-1")))
        .isInstanceOf(ProductLookupException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(800));
  }

  @Test
  void non2xxRaisesLookupException() {
    core.status = 500;
    assertThatThrownBy(() -> client(Duration.ofSeconds(2)).lookup(List.of("SKU-1")))
        .isInstanceOf(ProductLookupException.class)
        .hasMessageContaining("500");
  }

  @Test
  void connectionErrorRaisesLookupException() {
    core.close();
    assertThatThrownBy(() -> client(Duration.ofSeconds(2)).lookup(List.of("SKU-1")))
        .isInstanceOf(ProductLookupException.class);
  }

  @Test
  void aBodyThatIsNotJsonRaisesLookupException() {
    core.responseBody = "<html>";
    assertThatThrownBy(() -> client(Duration.ofSeconds(2)).lookup(List.of("SKU-1")))
        .isInstanceOf(ProductLookupException.class);
  }
}
