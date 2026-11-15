// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import run.ostrom.cli.runner.ProcessStarter

/**
 * Starts a remote session's agent on this device (`ostrom remote host`), its stdin and stdout on pipes, out of the
 * terminal's reach so that its Ctrl-C reaches the host only (which asks the server for a pause): in a process group of
 * its own on macOS and Linux, without a console and in a job object of its own on Windows.
 */
expect fun localAgentProcesses(): ProcessStarter

/** This process's environment, which a remote session's agent inherits (less the host's own variables). */
expect fun processEnvironment(): Map<String, String>
