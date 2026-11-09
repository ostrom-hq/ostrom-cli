// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

/**
 * Gives back what the process no longer holds before the long part of a launch, the agent's session, which the
 * launcher and its relay spend alive. Kotlin/Native's collector does not run on its own during a launch, so without
 * this the heap keeps every allocation of the launch for the whole session (measured in ostrom-cli#122). Nothing on the
 * JVM.
 */
expect fun reclaimMemory()
