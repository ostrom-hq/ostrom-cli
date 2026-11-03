// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

/**
 * Gives back what the process no longer holds before the long part of a launch, the agent's session, which the
 * launcher and its relay spend alive. Kotlin/Native's collector does not run on its own during a launch (PERF-CLI,
 * 03/11/2026: no collection before the agent), so the heap only grows: a full collection where the launch leaves its
 * garbage keeps the launcher at 36-37 MB at the agent's start instead of 50-51, for 2 ms. Nothing on the JVM.
 */
expect fun reclaimMemory()
