// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import run.ostrom.cli.runner.ProcessStarter

/**
 * Starts a remote session's agent on this device (`ostrom remote host`), its stdin and stdout on pipes, in its own
 * process group so that the terminal's Ctrl-C reaches the host only (which asks the server for a pause); null on a
 * platform that hosts no remote session yet (Windows).
 */
expect fun localAgentProcesses(): ProcessStarter?

/** This process's environment, which a remote session's agent inherits (less the host's own variables). */
expect fun processEnvironment(): Map<String, String>
