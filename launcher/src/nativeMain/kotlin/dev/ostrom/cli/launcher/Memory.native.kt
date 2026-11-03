// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/** A full collection, waited for: about 1 ms for the 15-20 MB of live objects a launch holds. */
@OptIn(NativeRuntimeApi::class)
actual fun reclaimMemory() = GC.collect()
