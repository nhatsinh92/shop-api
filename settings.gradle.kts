rootProject.name = "shop-api"

include("gateway", "core", "cart-service")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
