package com.mayshop.core.auth.internal;

import java.util.UUID;

/** A row of {@code auth.users}. */
public record UserAccount(
    UUID id, String email, String passwordHash, String displayName, String memberTier) {}
