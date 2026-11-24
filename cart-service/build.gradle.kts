plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-redpanda")
    testImplementation(libs.archunit.junit5)
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation(libs.networknt.json.schema.validator)
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // kafka-clients pulls compression libraries with known vulnerabilities (dependency-audit);
    // raise them to the fixed releases (same pins as :core).
    constraints {
        implementation("at.yawk.lz4:lz4-java:1.11.4") {
            because("fixes GHSA-343h-94h5-c4wr, GHSA-mcr4-qmvw-px4g and others in 1.10.1")
        }
        implementation("org.xerial.snappy:snappy-java:1.1.10.9") {
            because("fixes GHSA-gwq9-9ffj-mvrv, GHSA-q233-g92q-qxp6 and GHSA-xqrx-qh46-34m7")
        }
    }
}

// Contract tests read the bundled spec (same approach as :core). The Redocly CLI version is read
// from the Makefile when the task is configured, so there is one pin.
val contractsDir = rootProject.layout.projectDirectory.dir("contracts")

val bundleContract by tasks.registering(Exec::class) {
    description = "Bundles contracts/openapi/cart.yaml into contracts/dist/cart.yaml"
    val redoclyVersion =
        Regex("""@redocly/cli@([0-9.]+)""")
            .find(rootProject.file("Makefile").readText())
            ?.groupValues
            ?.get(1)
            ?: error("Redocly CLI version not found in the Makefile")
    inputs.dir(contractsDir.dir("openapi"))
    inputs.property("redoclyVersion", redoclyVersion)
    outputs.file(contractsDir.file("dist/cart.yaml"))
    workingDir = contractsDir.asFile
    commandLine("npx", "--yes", "@redocly/cli@$redoclyVersion", "bundle", "openapi/cart.yaml", "-o", "dist/cart.yaml")
}

tasks.test {
    dependsOn(bundleContract)
    systemProperty("contract.cart.spec", contractsDir.file("dist/cart.yaml").asFile.absolutePath)
}
