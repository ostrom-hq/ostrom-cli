import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("ostrom.kmp")
}

// ostrom-core holds the models and the API contract. Until it publishes a version (gradle.properties,
// ostromCoreVersion), this module only knows how to reach the server; a local clone of ostrom-core is used
// instead of the package when it sits next to this clone (settings.gradle.kts).
val ostromCoreVersion = providers.gradleProperty("ostromCoreVersion").orNull?.takeIf { it.isNotBlank() }

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        if (konanTarget.family == Family.LINUX) {
            // Keeps OpenSSL (linked by Ktor's Curl engine) usable until exit; see tls_exit.def.
            compilations.getByName("main").cinterops.create("tlsExit") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/tls_exit.def"))
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
            implementation(libs.coroutines.core)
            if (ostromCoreVersion != null) api("dev.ostrom:ostrom-api:$ostromCoreVersion")
        }
        commonTest.dependencies {
            implementation(projects.platform)
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.test)
        }
    }
}
