package com.mayshop.core.auth.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserAccounts implements UserAccounts {

  private static final String COLUMNS = "id, email, password_hash, display_name, member_tier";

  private final JdbcClient jdbc;

  JdbcUserAccounts(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<UserAccount> findByEmail(String email) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM auth.users WHERE lower(email) = lower(:email)")
        .param("email", email)
        .query(JdbcUserAccounts::map)
        .optional();
  }

  @Override
  public Optional<UserAccount> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM auth.users WHERE id = :id")
        .param("id", id)
        .query(JdbcUserAccounts::map)
        .optional();
  }

  private static UserAccount map(ResultSet rs, int row) throws SQLException {
    return new UserAccount(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        rs.getString("password_hash"),
        rs.getString("display_name"),
        rs.getString("member_tier"));
  }
}
