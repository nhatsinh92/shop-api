package com.mayshop.core.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mayshop.core.checkout.Payments;
import com.mayshop.core.order.internal.PlaceOrder;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Request validation of {@code placeOrder}, without Spring or a database. */
class PlaceOrderParserTest {

  private static final UUID USER = UUID.fromString("0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0001");
  private static final String KEY = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  private static String body(String phone, String district) {
    return """
    {"reservationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
     "shippingOptionId": "STANDARD", "paymentMethod": "COD",
     "shippingAddress": {"fullName": "An", "phone": "%s", "line1": "12 Nguyen Hue",
       "ward": "Ben Nghe", %s "province": "Ho Chi Minh City"}}
    """
        .formatted(phone, district);
  }

  private static InvalidOrderRequestException invalid(String json) {
    var thrown = new InvalidOrderRequestException[1];
    assertThatThrownBy(() -> PlaceOrderParser.parse(USER, KEY, json, null))
        .isInstanceOfSatisfying(InvalidOrderRequestException.class, e -> thrown[0] = e);
    return thrown[0];
  }

  @Test
  void aMissingKeyIsIdempotencyKeyMissing() {
    assertThatThrownBy(() -> PlaceOrderParser.key(null))
        .isInstanceOf(IdempotencyKeyMissingException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-a-uuid", "1-1-1-1-1", "7c9e6679742540de944be07fc1f90ae7"})
  void aKeyThatIsNotAUuidIsIdempotencyKeyMissing(String key) {
    assertThatThrownBy(() -> PlaceOrderParser.key(key))
        .isInstanceOf(IdempotencyKeyMissingException.class);
  }

  @Test
  void aKeyIsNormalisedToLowerCase() {
    assertThat(PlaceOrderParser.key(KEY.toUpperCase(java.util.Locale.ROOT))).isEqualTo(KEY);
  }

  @Test
  void aValidBodyParsesAndDistrictMayBeAbsent() {
    PlaceOrder order = PlaceOrderParser.parse(USER, KEY, body("0901234567", ""), null);
    assertThat(order.paymentMethod()).isEqualTo(Payments.Method.COD);
    assertThat(order.shippingOptionId()).isEqualTo("STANDARD");
    assertThat(order.shippingAddressJson()).doesNotContain("district");
    assertThat(order.note()).isNull();
  }

  @Test
  void districtIsKeptWhenGiven() {
    PlaceOrder order =
        PlaceOrderParser.parse(
            USER, KEY, body("+84901234567", "\"district\": \"District 1\","), null);
    assertThat(order.shippingAddressJson()).contains("\"district\":\"District 1\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0201234567", "090123456", "09012345678", "+840201234567", "abc", ""})
  void aPhoneOutsideThePatternNamesItsField(String phone) {
    var violations = invalid(body(phone, "")).violations();
    assertThat(violations).extracting(v -> v.field()).containsExactly("shippingAddress.phone");
    assertThat(violations.get(0).message()).isEqualTo("must be a valid phone number");
  }

  @Test
  void schemaViolationsAreAllReported() {
    var violations =
        invalid(
                """
                {"reservationId": "nope", "shippingOptionId": "DRONE", "paymentMethod": "CASH",
                 "shippingAddress": {"phone": "0901234567"}, "note": 5}
                """)
            .violations();
    assertThat(violations)
        .extracting(v -> v.field())
        .contains(
            "reservationId",
            "shippingOptionId",
            "paymentMethod",
            "shippingAddress.fullName",
            "shippingAddress.line1",
            "shippingAddress.ward",
            "shippingAddress.province",
            "note");
  }

  @Test
  void aBodyThatIsNotAJsonObjectIsRejected() {
    assertThat(invalid("[]").violations()).extracting(v -> v.field()).containsExactly("body");
    assertThat(invalid("{").violations()).extracting(v -> v.field()).containsExactly("body");
    assertThat(invalid("").violations()).extracting(v -> v.field()).containsExactly("body");
  }

  @Test
  void anUnknownMockOutcomeIsRejected() {
    assertThatThrownBy(() -> PlaceOrderParser.parse(USER, KEY, body("0901234567", ""), "later"))
        .isInstanceOf(InvalidOrderRequestException.class);
  }

  @Test
  void theMockOutcomeAndKeyOrderChangeTheFingerprintOnlyAsExpected() {
    String a = PlaceOrderParser.parse(USER, KEY, body("0901234567", ""), null).fingerprint();
    String same =
        PlaceOrderParser.parse(USER, KEY, body("0901234567", "").replace("\n", " "), null)
            .fingerprint();
    String other = PlaceOrderParser.parse(USER, KEY, body("0901234567", ""), "fail").fingerprint();
    assertThat(same).isEqualTo(a);
    assertThat(other).isNotEqualTo(a);
  }
}
