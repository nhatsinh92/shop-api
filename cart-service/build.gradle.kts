plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation(libs.networknt.json.schema.validator)
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
