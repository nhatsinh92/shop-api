package com.mayshop.cart.product;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class ProductUpdatedConsumerTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ProductUpdatedConsumer consumer =
      new ProductUpdatedConsumer(redis, JsonMapper.builder().build());

  @Test
  void deletesTheKeyWhateverTheChangesAre() {
    consumer.onProductUpdated("{\"sku\":\"SKU-1\",\"changes\":[\"STOCK\"]}");
    consumer.onProductUpdated("{\"sku\":\"SKU-2\",\"changes\":[\"PRICE\",\"DETAIL\"]}");

    verify(redis).delete("product:SKU-1");
    verify(redis).delete("product:SKU-2");
  }

  @Test
  void aMalformedRecordIsSkippedWithoutThrowing() {
    consumer.onProductUpdated("not json");
    consumer.onProductUpdated("{\"changes\":[\"PRICE\"]}");
    consumer.onProductUpdated("{\"sku\":42}");
    consumer.onProductUpdated("[]");
    consumer.onProductUpdated(null);

    verify(redis, never()).delete(ArgumentMatchers.anyString());
  }
}
