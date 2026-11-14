// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The session script on a real PTY: the agent continues the conversation of the session's previous execution. */
class SessionScriptConversationTest {
    private val environment = mapOf("PATH" to "/usr/bin:/bin", "TERM" to "xterm-256color")

    private suspend fun Terminal.readUntil(marker: String): String {
        val seen = StringBuilder()
        while (marker !in seen) seen.append(read()?.decodeToString() ?: break)
        return seen.toString()
    }

    /** Runs [script] without a terminal until it ends, and returns its output. */
    private suspend fun shell(script: String, directory: String = "/tmp"): String {
        val terminal = PtyTerminal.Pipes.start(script, environment, directory)
        val output = StringBuilder()
        while (true) output.append(terminal.read()?.decodeToString() ?: break)
        terminal.close()
        return output.toString()
    }

    /** A directory of its own for a test's fake agent, home and workspace, with the agent [command] on its `PATH`. */
    private suspend fun agentHome(command: String): Pair<String, Map<String, String>> {
        val root = "/tmp/ostrom-conversation-test-${Uuid.random()}"
        shell(
            "mkdir -p $root/bin $root/home $root/work && " +
                "printf '#!/bin/sh\\necho \"$command-args: \$*\"\\n" +
                "echo \"new=\${OSTROM_AGENT_NEW_CONVERSATION-unset}\"\\n' " +
                "> $root/bin/$command && " +
                "chmod +x $root/bin/$command",
        )
        return root to environment + mapOf(
            "PATH" to "$root/bin:/usr/bin:/bin",
            "OSTROM_AGENT_COMMAND" to command,
            "OSTROM_WORKSPACE" to "$root/work",
            "HOME" to "$root/home",
        )
    }

    private suspend fun startAgent(agent: Map<String, String>, directory: String): String {
        val terminal = PtyTerminal.start(SessionScript.FIRST, agent, directory)
        val output = terminal.readUntil("The agent exited")
        terminal.terminate()
        terminal.close()
        return output
    }

    @Test
    fun claudeCodeResumesTheConversationOfThePreviousExecution() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            val (root, base) = agentHome("claude")
            val first = "0b6c6f2e-3a0e-4d6e-9a59-2f8f3c1d4e01"
            val second = "7d1f2b3c-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
            val agent = base + mapOf("OSTROM_AGENT" to "claude-code", "OSTROM_AGENT_PROMPT" to "go on")
            val work = "$root/work"

            val started = startAgent(agent + (SessionScript.NEW_CONVERSATION to first), work)
            assertTrue("claude-args: --session-id $first go on" in started, started)
            // The agent never sees the id the runner offered.
            assertTrue("new=unset" in started, started)
            val state = """{"kind":"claude-code","conversationId":"$first"}"""
            assertTrue(state in shell("cat $root/home/.ostrom/cloud-agent.json"))
            // Without its transcript, the conversation cannot be resumed: a new one starts.
            val fresh = startAgent(agent + (SessionScript.NEW_CONVERSATION to second), work)
            assertTrue("claude-args: --session-id $second go on" in fresh, fresh)
            // Claude Code's transcript, under the project of the directory the agent starts in.
            shell(
                "p=$root/home/.claude/projects/\$(pwd -P | sed 's/[^A-Za-z0-9]/-/g') && mkdir -p \$p && " +
                    "touch \$p/$second.jsonl",
                work,
            )
            val resumed = startAgent(agent + (SessionScript.NEW_CONVERSATION to first), work)
            assertTrue("claude-args: --resume $second go on" in resumed, resumed)
            // Another agent's conversation is never resumed.
            val another = mapOf("OSTROM_AGENT" to "other", SessionScript.NEW_CONVERSATION to first)
            val other = startAgent(agent + another, work)
            assertTrue("claude-args: --session-id $first go on" in other, other)
            shell("rm -rf $root")
        }
    }

    @Test
    fun codexResumesItsLastConversation() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            val (root, agent) = agentHome("codex")
            val work = "$root/work"
            val last = "5f0c7c9e-1b2a-4c3d-8e4f-a5b6c7d8e9f0"
            val older = "11111111-2222-4333-8444-555555555555"

            val started = startAgent(agent + ("OSTROM_AGENT_PROMPT" to "go on"), work)
            assertTrue("codex-args: go on" in started, started)
            val sessions = "$root/home/.codex/sessions"
            shell(
                "mkdir -p $sessions/2026/11/13 $sessions/2026/11/14 && " +
                    "touch $sessions/2026/11/13/rollout-2026-11-13T09-00-00-$older.jsonl " +
                    "$sessions/2026/11/14/rollout-2026-11-14T10-00-00-$last.jsonl",
            )
            val resumed = startAgent(agent + ("OSTROM_AGENT_PROMPT" to "go on"), work)
            assertTrue("codex-args: resume $last go on" in resumed, resumed)
            assertTrue(""""conversationId":"$last"""" in shell("cat $root/home/.ostrom/cloud-agent.json"))
            shell("rm -rf $root")
        }
    }
}
