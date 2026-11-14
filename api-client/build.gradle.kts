// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("ostrom.kmp")
}

// ostrom-core holds the models and the API contract, from GitHub Packages (gradle.properties, ostromCoreVersion).
val ostromCoreVersion = providers.gradleProperty("ostromCoreVersion").get()

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
            implementation(libs.serialization.json)
            api("run.ostrom:ostrom-api:$ostromCoreVersion")
        }
        commonTest.dependencies {
            implementation(projects.platform)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.network)
            implementation(libs.coroutines.test)
        }
        // The in-process WebSocket server of TerminalChannelServerTest: on Kotlin/Native the test host has no WebSocket
        // (NotImplementedError), and its client engine would replace the system one the real HTTPS test checks.
        jvmTest.dependencies {
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.server.websockets)
        }
    }
}
