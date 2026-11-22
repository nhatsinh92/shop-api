plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(
            libs.spring.modulith.bom
                .get()
                .toString(),
        )
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.modulith:spring-modulith-events-jackson")
    implementation("org.springframework.modulith:spring-modulith-events-jdbc")
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-docs")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-redpanda")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.networknt.json.schema.validator)
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // kafka-clients pulls compression libraries with known vulnerabilities (dependency-audit);
    // raise them to the fixed releases.
    constraints {
        implementation("at.yawk.lz4:lz4-java:1.11.4") {
            because("fixes GHSA-343h-94h5-c4wr, GHSA-mcr4-qmvw-px4g and others in 1.10.1")
        }
        implementation("org.xerial.snappy:snappy-java:1.1.10.9") {
            because("fixes GHSA-gwq9-9ffj-mvrv, GHSA-q233-g92q-qxp6 and GHSA-xqrx-qh46-34m7")
        }
    }
}

// Contract tests read the bundled spec. Bundle it here so `:core:test` works on a clean checkout
// and in CI. The Redocly CLI version is read from the Makefile so there is one pin. It is read only
// when the task is configured: the Docker build (infra/app.Dockerfile) has no Makefile and never
// runs the tests, so reading it at configuration time broke `make demo`.
val contractsDir = rootProject.layout.projectDirectory.dir("contracts")

val bundleContract by tasks.registering(Exec::class) {
    description = "Bundles contracts/openapi/core.yaml into contracts/dist/core.yaml"
    val redoclyVersion =
        Regex("""@redocly/cli@([0-9.]+)""")
            .find(rootProject.file("Makefile").readText())
            ?.groupValues
            ?.get(1)
            ?: error("Redocly CLI version not found in the Makefile")
    inputs.dir(contractsDir.dir("openapi"))
    inputs.property("redoclyVersion", redoclyVersion)
    outputs.file(contractsDir.file("dist/core.yaml"))
    workingDir = contractsDir.asFile
    commandLine("npx", "--yes", "@redocly/cli@$redoclyVersion", "bundle", "openapi/core.yaml", "-o", "dist/core.yaml")
}

tasks.test {
    dependsOn(bundleContract)
    systemProperty("contract.core.spec", contractsDir.file("dist/core.yaml").asFile.absolutePath)
}
