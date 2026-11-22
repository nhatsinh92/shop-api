package com.mayshop.core.auth.web;

import com.mayshop.core.auth.internal.UserAccount;
import java.util.UUID;

/** The {@code UserProfile} schema of the contract. */
record UserProfile(UUID id, String email, String displayName, String memberTier) {

  static UserProfile of(UserAccount user) {
    return new UserProfile(user.id(), user.email(), user.displayName(), user.memberTier());
  }
}
