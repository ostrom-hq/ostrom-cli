package dev.ostrom.cli.platform

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.OsFamily
import kotlin.native.Platform

@OptIn(ExperimentalNativeApi::class)
internal actual fun currentOperatingSystem(): OperatingSystem = when (Platform.osFamily) {
    OsFamily.MACOSX -> OperatingSystem.MACOS
    OsFamily.WINDOWS -> OperatingSystem.WINDOWS
    else -> OperatingSystem.LINUX
}

@OptIn(ExperimentalNativeApi::class)
internal actual fun currentArchitecture(): String = when (Platform.cpuArchitecture) {
    CpuArchitecture.ARM64 -> "arm64"
    CpuArchitecture.X64 -> "x64"
    else -> Platform.cpuArchitecture.name.lowercase()
}
