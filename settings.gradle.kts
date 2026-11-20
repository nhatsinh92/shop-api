pluginManagement {
    repositories {
        gradlePluginPortal()
        // Fallback: the plugin portal proxies Maven Central and occasionally returns 404s for it.
        mavenCentral()
    }
}

rootProject.name = "shop-api"

include("gateway", "core", "cart-service")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
