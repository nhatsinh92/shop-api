package com.mayshop.core.flags.web;

import com.mayshop.core.flags.FeatureFlags;
import com.mayshop.core.flags.Flag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code getFeatureFlags} of {@code contracts/openapi/core.yaml}. */
@RestController
class FlagsController {

  static final String CACHE_CONTROL = "public, max-age=5";

  private final FeatureFlags flags;

  FlagsController(FeatureFlags flags) {
    this.flags = flags;
  }

  @GetMapping("/api/flags")
  ResponseEntity<FlagsResponse> getFeatureFlags() {
    return ResponseEntity.ok()
        .header("Cache-Control", CACHE_CONTROL)
        .body(
            new FlagsResponse(
                flags.isEnabled(Flag.RECOMMENDATIONS),
                flags.isEnabled(Flag.MEMBER_PRICE),
                flags.isEnabled(Flag.ONLINE_PAYMENT)));
  }

  record FlagsResponse(boolean recommendations, boolean memberPrice, boolean onlinePayment) {}
}
