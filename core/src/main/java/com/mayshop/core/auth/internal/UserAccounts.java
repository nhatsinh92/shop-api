package com.mayshop.core.auth.internal;

import java.util.Optional;
import java.util.UUID;

/** Read access to {@code auth.users}. */
public interface UserAccounts {

  /** Finds a user by email, ignoring case. */
  Optional<UserAccount> findByEmail(String email);

  Optional<UserAccount> findById(UUID id);
}
