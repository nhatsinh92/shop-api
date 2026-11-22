package com.mayshop.core.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Proves the helper accepts matching responses and fails readably for each kind of violation. */
class ContractValidatorTest {

  private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");

  /** Test-only controller whose responses are deliberately right or wrong. */
  @RestController
  static class FakeController {
    @GetMapping("/fake/health-up")
    ResponseEntity<Map<String, Object>> up() {
      return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/fake/health-teapot")
    ResponseEntity<Map<String, Object>> teapot() {
      return ResponseEntity.status(418).body(Map.of("status", "UP"));
    }

    @GetMapping("/fake/health-missing")
    ResponseEntity<Map<String, Object>> missingProperty() {
      return ResponseEntity.ok(Map.of("other", "UP"));
    }

    @GetMapping("/fake/health-wrong-type")
    ResponseEntity<Map<String, Object>> wrongType() {
      return ResponseEntity.ok(Map.of("status", 42));
    }

    @GetMapping("/fake/problem")
    ResponseEntity<Map<String, Object>> problemAsProblemJson() {
      return ResponseEntity.status(400).contentType(PROBLEM).body(problem());
    }

    @GetMapping("/fake/problem-as-json")
    ResponseEntity<Map<String, Object>> problemAsJson() {
      return ResponseEntity.status(400).contentType(MediaType.APPLICATION_JSON).body(problem());
    }

    @GetMapping("/fake/products")
    ResponseEntity<Map<String, Object>> productsWithNullCursor() {
      return ResponseEntity.ok()
          .header("Cache-Control", "public, max-age=60")
          .header("x-cache-tags", "products")
          .body(Map.of("items", java.util.List.of(), "nextCursor", "null-is-ok-too"));
    }

    @GetMapping("/fake/products-bad-cursor")
    ResponseEntity<Map<String, Object>> productsWithNumericCursor() {
      return ResponseEntity.ok().body(Map.of("items", java.util.List.of(), "nextCursor", 5));
    }

    private static Map<String, Object> problem() {
      return Map.of(
          "type", "about:blank",
          "title", "Validation failed",
          "status", 400,
          "code", "VALIDATION_FAILED");
    }
  }

  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FakeController()).build();
  private final ContractValidator contract = ContractValidator.core();

  private MockHttpServletResponse get(String uri) throws Exception {
    return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri))
        .andReturn()
        .getResponse();
  }

  @Test
  void aMatchingResponsePasses() throws Exception {
    contract.assertValid(get("/fake/health-up"), "coreHealth");
  }

  @Test
  void aProblemResponseAsProblemJsonPasses() throws Exception {
    contract.assertValid(get("/fake/problem"), "listProducts");
  }

  @Test
  void anUndocumentedStatusCodeFails() throws Exception {
    MockHttpServletResponse response = get("/fake/health-teapot");
    assertThat(response.getStatus()).isEqualTo(418);
    assertThatThrownBy(() -> contract.assertValid(response, "listProducts"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("operation listProducts")
        .hasMessageContaining("status 418")
        .hasMessageContaining("not documented");
  }

  @Test
  void aBodyMissingARequiredPropertyFails() throws Exception {
    MockHttpServletResponse response = get("/fake/health-missing");
    assertThatThrownBy(() -> contract.assertValid(response, "coreHealth"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("operation coreHealth")
        .hasMessageContaining("status 200")
        .hasMessageContaining("status");
  }

  @Test
  void aPropertyOfTheWrongTypeFails() throws Exception {
    MockHttpServletResponse response = get("/fake/health-wrong-type");
    assertThatThrownBy(() -> contract.assertValid(response, "coreHealth"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("$.status")
        .hasMessageContaining("string");
  }

  @Test
  void aProblemServedAsApplicationJsonFails() throws Exception {
    MockHttpServletResponse response = get("/fake/problem-as-json");
    assertThatThrownBy(() -> contract.assertValid(response, "listProducts"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("status 400")
        .hasMessageContaining("Content-Type application/json is not documented")
        .hasMessageContaining("application/problem+json");
  }

  @Test
  void anOpenApi31NullableTypeIsEnforced() throws Exception {
    MockHttpServletResponse response = get("/fake/products-bad-cursor");
    assertThatThrownBy(() -> contract.assertValid(response, "listProducts"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("nextCursor");
  }

  @Test
  void anUnknownOperationFails() throws Exception {
    MockHttpServletResponse response = get("/fake/health-up");
    assertThatThrownBy(() -> contract.assertValid(response, "noSuchOperation"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("noSuchOperation");
  }

  @Test
  void aRequiredHeaderThatIsMissingFails() throws Exception {
    ContractValidator custom =
        ContractValidator.forSpec(
            """
            openapi: 3.1.1
            paths:
              /x:
                get:
                  operationId: x
                  responses:
                    '200':
                      description: ok
                      headers:
                        X-Needed:
                          required: true
                          schema: { type: string }
                        X-Count:
                          schema: { type: integer }
                      content:
                        application/json:
                          schema: { type: object }
            """);
    MockHttpServletResponse response = get("/fake/health-up");
    response.setHeader("X-Count", "many");
    assertThatThrownBy(() -> custom.assertValid(response, "x"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("header X-Needed is required")
        .hasMessageContaining("header X-Count");
  }

  @Test
  void aBodyWhereNoneIsDocumentedFails() throws Exception {
    ContractValidator custom =
        ContractValidator.forSpec(
            """
            openapi: 3.1.1
            paths:
              /x:
                get:
                  operationId: x
                  responses:
                    '204': { description: no content }
            """);
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(204);
    response.getWriter().write("{}");
    assertThatThrownBy(() -> custom.assertValid(response, "x"))
        .hasMessageContaining("documents no body");
  }
}
