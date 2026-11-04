// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

/** The JVM's collector runs on its own: nothing to do. */
actual fun reclaimMemory() = Unit
