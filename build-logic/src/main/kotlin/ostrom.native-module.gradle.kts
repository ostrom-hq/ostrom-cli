// Every module of the CLI: Kotlin/Native only (no JVM), the four targets of the decision of 30/10.
// A target that cannot build on this host (macosArm64 on Linux) is skipped by kotlin.native.ignoreDisabledTargets.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    compilerOptions {
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
