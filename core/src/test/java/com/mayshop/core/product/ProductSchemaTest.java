package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The product tables, their constraints, and the demo seed loaded into them. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ProductSchemaTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Autowired private JdbcClient jdbc;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void clean() {
    jdbc.sql("delete from product.flash_sales").update();
    jdbc.sql("delete from product.products").update();
    jdbc.sql("delete from product.categories").update();
  }

  @Test
  void migrationsCreateTheProductTables() {
    List<String> tables =
        jdbc.sql("select table_name from information_schema.tables where table_schema = 'product'")
            .query(String.class)
            .list();

    assertThat(tables)
        .contains(
            "categories",
            "products",
            "variants",
            "images",
            "stock",
            "flash_sales",
            "flash_sale_items");
  }

  @Test
  void productSlugAndVariantSkuAreUnique() {
    UUID product = insertProduct("slug-a");
    insertVariant(product, "SKU-A", 1, 100L);

    assertThatThrownBy(() -> insertProduct("slug-a")).hasMessageContaining("products_slug_key");
    assertThatThrownBy(() -> insertVariant(product, "SKU-A", 2, 100L))
        .hasMessageContaining("variants_pkey");
  }

  @Test
  void variantPositionIsUniquePerProduct() {
    UUID product = insertProduct("slug-a");
    insertVariant(product, "SKU-A", 1, 100L);

    assertThatThrownBy(() -> insertVariant(product, "SKU-B", 1, 100L))
        .hasMessageContaining("variants_product_id_position_key");
  }

  @Test
  void priceAndListPriceMustBePositive() {
    UUID product = insertProduct("slug-a");

    assertThatThrownBy(() -> insertVariant(product, "SKU-A", 1, 0L))
        .hasMessageContaining("variants_price_check");
    assertThatThrownBy(() ->
            jdbc.sql(
                    "insert into product.variants (sku, product_id, position, price, list_price)"
                        + " values ('SKU-B', :p, 2, 100, 0)")
                .param("p", product)
                .update())
        .hasMessageContaining("variants_list_price_check");
  }

  @Test
  void stockQuantityCannotBeNegative() {
    UUID product = insertProduct("slug-a");
    insertVariant(product, "SKU-A", 1, 100L);

    assertThatThrownBy(() ->
            jdbc.sql("insert into product.stock (sku, quantity) values ('SKU-A', -1)").update())
        .hasMessageContaining("stock_quantity_check");
  }

  @Test
  void flashSaleMustEndAfterItStarts() {
    assertThatThrownBy(() ->
            jdbc.sql(
                    "insert into product.flash_sales (id, starts_at, ends_at)"
                        + " values (gen_random_uuid(), now(), now())")
                .update())
        .hasMessageContaining("flash_sales_window_check");
  }

  @Test
  void flashSaleItemCannotSellMoreThanItsSaleStock() {
    UUID product = insertProduct("slug-a");
    insertVariant(product, "SKU-A", 1, 100L);
    UUID sale = UUID.randomUUID();
    jdbc.sql(
            "insert into product.flash_sales (id, starts_at, ends_at)"
                + " values (:id, now(), now() + interval '1 hour')")
        .param("id", sale)
        .update();

    assertThatThrownBy(() ->
            jdbc.sql(
                    "insert into product.flash_sale_items (sale_id, sku, sale_price, sale_stock, sold)"
                        + " values (:sale, 'SKU-A', 50, 10, 11)")
                .param("sale", sale)
                .update())
        .hasMessageContaining("flash_sale_items_sold_within_stock_check");
  }

  @Test
  void seedLoadsTheDesignCatalogAndIsIdempotent() {
    var populator =
        new ResourceDatabasePopulator(
            new FileSystemResource(Path.of("..", "seed", "products.sql").toAbsolutePath()));
    populator.execute(dataSource);
    populator.execute(dataSource);

    assertThat(count("product.products")).isEqualTo(10);
    assertThat(count("product.variants")).isEqualTo(12);
    assertThat(count("product.flash_sales")).isEqualTo(1);
    assertThat(count("product.flash_sale_items")).isEqualTo(7);
    assertThat(
            jdbc.sql(
                    "select count(distinct v.product_id) from product.flash_sale_items i"
                        + " join product.variants v on v.sku = i.sku")
                .query(Long.class)
                .single())
        .isEqualTo(5);
    assertThat(
            jdbc.sql(
                    "select count(*) from product.flash_sales where now() between starts_at and ends_at")
                .query(Long.class)
                .single())
        .isEqualTo(1);

    var anc =
        jdbc.sql(
                "select v.sku, v.price, v.list_price, s.quantity, i.sale_price, i.sale_stock, i.sold"
                    + " from product.variants v"
                    + " join product.stock s on s.sku = v.sku"
                    + " join product.flash_sale_items i on i.sku = v.sku"
                    + " where v.product_id = (select id from product.products where slug = 'tai-nghe-khong-day-anc-pro')"
                    + " order by v.position")
            .query()
            .listOfRows();
    assertThat(anc).hasSize(3);
    assertThat(anc)
        .allSatisfy(row -> {
          assertThat(row.get("price")).isEqualTo(1_290_000L);
          assertThat(row.get("list_price")).isEqualTo(1_790_000L);
          assertThat(row.get("quantity")).isEqualTo(40);
          assertThat(row.get("sale_price")).isEqualTo(890_000L);
          assertThat(row.get("sale_stock")).isEqualTo(100);
          assertThat(row.get("sold")).isEqualTo(82);
        });
    assertThat(
            jdbc.sql(
                    "select v.options -> 0 ->> 'value' from product.variants v"
                        + " where v.product_id = (select id from product.products where slug = 'tai-nghe-khong-day-anc-pro')"
                        + " order by v.position")
                .query(String.class)
                .list())
        .containsExactly("Đen nhám", "Trắng sứ", "Xanh đêm");
    assertThat(
            jdbc.sql(
                    "select sku from product.variants where product_id = (select id from product.products where slug = 'tai-nghe-khong-day-anc-pro') order by position limit 1")
                .query(String.class)
                .single())
        .as("summary SKU is the lowest position")
        .isEqualTo("SKU-ANC-PRO-BLK");
    assertThat(
            jdbc.sql("select count(*) from information_schema.schemata where schema_name = 'seed'")
                .query(Long.class)
                .single())
        .isZero();
  }

  private long count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Long.class).single();
  }

  private UUID insertProduct(String slug) {
    jdbc.sql("insert into product.categories (slug, name) values ('c', 'C') on conflict do nothing")
        .update();
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into product.products (id, slug, name, brand, category_slug)"
                + " values (:id, :slug, 'Name', 'Brand', 'c')")
        .param("id", id)
        .param("slug", slug)
        .update();
    return id;
  }

  private void insertVariant(UUID product, String sku, int position, long price) {
    jdbc.sql(
            "insert into product.variants (sku, product_id, position, price)"
                + " values (:sku, :p, :pos, :price)")
        .param("sku", sku)
        .param("p", product)
        .param("pos", position)
        .param("price", price)
        .update();
  }
}
