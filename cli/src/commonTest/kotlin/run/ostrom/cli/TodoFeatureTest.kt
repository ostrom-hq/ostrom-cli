// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.launcher.TODOS
import run.ostrom.cli.launcher.adapters.SessionLayout
import run.ostrom.cli.todo.TODO_SERVER
import run.ostrom.cli.todo.TodoHookRunner
import run.ostrom.core.api.models.ServerFeatures
import kotlin.test.Test
import kotlin.test.assertEquals

/** The launcher depends on no other module: its names of the todo are checked against the todo's and ostrom-core's. */
class TodoFeatureTest {
    @Test
    fun theLauncherNamesTheTodoAsTheServerAndTheTodoModuleDo() {
        assertEquals(ServerFeatures.TODOS, TODOS)
        assertEquals(TODO_SERVER, SessionLayout.TODO_SERVER)
        assertEquals(setOf(TodoHookRunner.STOP, TodoHookRunner.CONTEXT), setOf("todo-stop", "todo-context"))
    }
}
