// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A launch installs the session todo only when the server announces it (`todos.v1`). */
class TodoLaunchTest : AgentLaunchKit() {
    private fun hooks() = filesWhileRunning[join(temporary, "plugin", "hooks", "hooks.json")].orEmpty()

    private fun servers() = filesWhileRunning[join(temporary, "mcp.json")].orEmpty()

    @Test
    fun aServerThatServesTheTodoGetsItsHooksAndServer() = runTest {
        serveALaunch()
        server["GET ${Routes.CAPABILITIES}"] = 200 to """{"apiVersion":1,"features":["waits.v1",{"x":1},"$TODOS"]}"""
        start()
        assertTrue("hook todo-stop" in hooks() && "hook todo-context" in hooks(), hooks())
        assertTrue("\"ostrom-todo\"" in servers(), servers())
        assertEquals("dev-token", server.sent("GET ${Routes.CAPABILITIES}").single().token, "the device's own token")
    }

    @Test
    fun aServerThatDoesNotOrCannotSaySoGetsNone() = runTest {
        for (answer in listOf(200 to """{"apiVersion":1,"features":["waits.v1"]}""", 500 to "{}")) {
            serveALaunch()
            server["GET ${Routes.CAPABILITIES}"] = answer
            start()
            assertFalse("todo" in hooks(), hooks())
            assertFalse("ostrom-todo" in servers(), servers())
        }
    }
}
