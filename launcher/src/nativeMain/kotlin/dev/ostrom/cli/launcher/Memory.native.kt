// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/** A full collection, waited for. */
@OptIn(NativeRuntimeApi::class)
actual fun reclaimMemory() = GC.collect()
