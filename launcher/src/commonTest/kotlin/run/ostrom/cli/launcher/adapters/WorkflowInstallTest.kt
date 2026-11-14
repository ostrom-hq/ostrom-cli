// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.MemoryFiles
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.parseObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The workflow step's Stop hook, installed only where the delivery tool is connected (`workflows.deliver.v1`). */
class WorkflowInstallTest {
    private val files = MemoryFiles()
    private val ostrom = """{"type":"command","command":"\"/opt/ostrom\" hook"""

    private fun layout(delivery: Boolean, todo: Boolean = false, wake: Boolean = false) = SessionLayout(
        temporary = "/tmp/run",
        plugin = join("/tmp/run", "plugin"),
        executable = "/opt/ostrom",
        rules = "",
        arguments = emptyList(),
        pack = null,
        home = "/home/dev",
        variable = { null },
        relay = RelayView("http://127.0.0.1:4100", "relay-token", listOf("ostrom"), false),
        todo = todo,
        waitsWake = wake,
        workflowDelivery = delivery,
    )

    private fun stop(layout: SessionLayout): String? {
        ClaudeAdapter().prepare(layout, files)
        val hooks = checkNotNull(parseObject(files.texts[join("/tmp/run", "plugin", "hooks", "hooks.json")]))
        return hooks.child("hooks")?.get("Stop")?.toString()
    }

    @Test
    fun claudeGetsOneMoreStopEntryBesideTheOthersAndReplacingNone() {
        val step = """{"hooks":[$ostrom workflow-stop","timeout":5}]}"""
        assertEquals("[$step]", stop(layout(delivery = true)))
        val todo = """{"hooks":[$ostrom todo-stop","timeout":150}]}"""
        assertEquals("[$todo,$step]", stop(layout(delivery = true, todo = true)))
        val wake = """{"hooks":[$ostrom wake","timeout":86400}]}"""
        assertEquals("[$wake,$step]", stop(layout(delivery = true, wake = true)))
        val both = """{"hooks":[$ostrom todo-stop --wake","timeout":86400}]}"""
        assertEquals("[$both,$step]", stop(layout(delivery = true, todo = true, wake = true)))
    }

    @Test
    fun claudeWithoutTheToolGetsNoSuchHook() {
        assertNull(stop(layout(delivery = false)))
        assertEquals(false, "workflow-stop" in checkNotNull(stop(layout(delivery = false, todo = true))))
    }
}
