// SPDX-License-Identifier: Apache-2.0
// The cloud runner: the hidden `ostrom cloud runner|agent-supervisor|check-host|credential|machine` processes of a cloud
// session's containers (runner/README.md). Its PTY shim is POSIX; on Windows the commands only say they run on Linux.
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("ostrom.kmp")
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        if (konanTarget.family != Family.MINGW) {
            compilations.getByName("main").cinterops.create("pty") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/pty.def"))
            }
        }
    }
    sourceSets {
        // Shared POSIX code, compiled with each family's own interop (macOS and Linux).
        macosMain.get().kotlin.srcDir("src/posixMain/kotlin")
        linuxMain.get().kotlin.srcDir("src/posixMain/kotlin")
        macosTest.get().kotlin.srcDir("src/posixTest/kotlin")
        linuxTest.get().kotlin.srcDir("src/posixTest/kotlin")
        commonMain.dependencies {
            implementation(projects.platform)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
            implementation(libs.ktor.network)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
        // The terminal link against a real WebSocket server: Ktor's test host has WebSockets on the JVM only.
        jvmTest.dependencies {
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.server.websockets)
        }
    }
}
