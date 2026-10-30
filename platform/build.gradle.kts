// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("ostrom.kmp")
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        // One small C shim per family: spawning a child that shares the terminal, and reading stdin with a deadline.
        val shim = if (konanTarget.family == Family.MINGW) "windows" else "posix"
        compilations.getByName("main").cinterops.create("native") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/$shim.def"))
        }
        // The system secret store: the Keychain on macOS, libsecret (loaded at run time) on Linux. Windows's
        // Credential Manager is in windows.def.
        val vault = when (konanTarget.family) {
            Family.OSX -> "keychain"
            Family.LINUX -> "libsecret"
            else -> null
        }
        if (vault != null) {
            compilations.getByName("main").cinterops.create("vault") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/$vault.def"))
            }
        }
        if (konanTarget.family == Family.MINGW) {
            // BCryptGenRandom (secureRandomBytes): its header is not part of Kotlin/Native's platform.windows.
            compilations.getByName("main").cinterops.create("random") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/random-windows.def"))
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
            api(libs.okio)
            implementation(libs.cryptography.core)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.openssl3)
        }
        // The JDK provider serves the test-only JVM target; the shipped binaries use OpenSSL 3, linked in.
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
        }
        commonTest.dependencies {
            implementation(libs.okio.fakefilesystem)
        }
    }
}
