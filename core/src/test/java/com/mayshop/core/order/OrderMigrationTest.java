package com.mayshop.core.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The {@code orders} schema exists after startup with the tables, the sequence and money as bigint.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OrderMigrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Autowired private JdbcClient jdbc;

  @Test
  void createsTheTablesOfTheOrdersSchema() {
    assertThat(
            jdbc.sql(
                    "select table_name from information_schema.tables where table_schema = 'orders'")
                .query(String.class)
                .list())
        .contains("orders", "order_items", "idempotency_keys", "flyway_schema_history");
  }

  @Test
  void moneyColumnsAreBigint() {
    assertThat(
            jdbc.sql(
                    """
                    select data_type from information_schema.columns
                     where table_schema = 'orders'
                       and ((table_name = 'orders' and column_name in ('subtotal', 'shipping_fee', 'total'))
                         or (table_name = 'order_items' and column_name in ('unit_price', 'line_total')))
                    """)
                .query(String.class)
                .list())
        .hasSize(5)
        .containsOnly("bigint");
  }

  @Test
  void reservationIdIsUniqueAndTheNumberSequenceExists() {
    assertThat(
            jdbc.sql(
                    "select count(*) from pg_indexes where schemaname = 'orders'"
                        + " and tablename = 'orders' and indexdef like '%UNIQUE%reservation_id%'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(jdbc.sql("select nextval('orders.order_number_seq')").query(Long.class).single())
        .isPositive();
  }

  @Test
  void idempotencyKeysUseTheUserAndTheKeyAsPrimaryKey() {
    assertThat(
            jdbc.sql(
                    """
                    select string_agg(kcu.column_name, ',' order by kcu.ordinal_position)
                      from information_schema.table_constraints tc
                      join information_schema.key_column_usage kcu
                        on kcu.constraint_name = tc.constraint_name
                       and kcu.table_schema = tc.table_schema
                     where tc.table_schema = 'orders' and tc.table_name = 'idempotency_keys'
                       and tc.constraint_type = 'PRIMARY KEY'
                    """)
                .query(String.class)
                .single())
        .isEqualTo("user_id,key");
  }
}
