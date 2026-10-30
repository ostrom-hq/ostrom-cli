import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("ostrom.native-module")
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        // One small C shim per family: spawning a child that shares the terminal, and reading stdin with a deadline.
        val shim = if (konanTarget.family == Family.MINGW) "windows" else "posix"
        compilations.getByName("main").cinterops.create("native") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/$shim.def"))
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
        }
        commonTest.dependencies {
            implementation(libs.okio.fakefilesystem)
        }
    }
}
