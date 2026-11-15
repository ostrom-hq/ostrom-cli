// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import run.ostrom.cli.runner.ProcessStarter

/** Windows hosts no remote session yet: the agent's pipes and process group are POSIX only in this version. */
actual fun localAgentProcesses(): ProcessStarter? = null

/** No agent is started here on Windows ([localAgentProcesses]). */
actual fun processEnvironment(): Map<String, String> = emptyMap()
