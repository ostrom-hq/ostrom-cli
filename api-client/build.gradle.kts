plugins {
    id("ostrom.native-module")
}

// ostrom-core holds the models and the API contract. Until it publishes a version (gradle.properties,
// ostromCoreVersion), this module only knows how to reach the server; a local clone of ostrom-core is used
// instead of the package when it sits next to this clone (settings.gradle.kts).
val ostromCoreVersion = providers.gradleProperty("ostromCoreVersion").orNull?.takeIf { it.isNotBlank() }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
            implementation(libs.coroutines)
            if (ostromCoreVersion != null) api("run.ostrom:ostrom-core:$ostromCoreVersion")
        }
        commonTest.dependencies {
            implementation(projects.platform)
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.test)
        }
    }
}
