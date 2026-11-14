// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudHookSettingsTest {
    private val binary = "/opt/ostrom/ostrom"
    private val todo = setOf(CloudMcpSession.TODO)
    private val workflow = setOf(CloudMcpSession.WORKFLOW)

    private fun hooks(settings: String?) =
        Json.parseToJsonElement(settings.orEmpty()).jsonObject.getValue("hooks").jsonObject

    /** The commands of [event], in order, with their timeouts. */
    private fun commands(settings: String?, event: String): List<Pair<String, Int?>> =
        hooks(settings).getValue(event).jsonArray.flatMap { entry ->
            entry.jsonObject.getValue("hooks").jsonArray.map {
                val timeout = it.jsonObject["timeout"]?.jsonPrimitive?.content?.toInt()
                it.jsonObject.getValue("command").jsonPrimitive.content to timeout
            }
        }

    @Test
    fun theTodoAddsItsStopHookAndItsSessionStartReminderNextToTheRunnersOwn() {
        val settings = CloudHookSettings.claude(null, binary, todo)
        assertEquals(setOf("Stop", "SessionEnd", "SessionStart"), hooks(settings).keys)
        assertEquals(
            listOf("'$binary' cloud agent-state" to null, "'$binary' cloud todo-stop" to 150),
            commands(settings, "Stop"),
        )
        assertEquals(listOf("'$binary' cloud agent-state" to null), commands(settings, "SessionEnd"))
        assertEquals(listOf("'$binary' cloud todo-context" to 25), commands(settings, "SessionStart"))
        val start = hooks(settings).getValue("SessionStart").jsonArray.single().jsonObject
        assertEquals("compact|resume", start.getValue("matcher").jsonPrimitive.content)
        assertNull(hooks(settings).getValue("Stop").jsonArray.last().jsonObject["matcher"])
    }

    @Test
    fun theWorkflowAddsItsStopHookAlone() {
        val settings = CloudHookSettings.claude(null, binary, workflow)
        assertEquals(setOf("Stop", "SessionEnd"), hooks(settings).keys)
        assertEquals(
            listOf("'$binary' cloud agent-state" to null, "'$binary' cloud workflow-stop" to 5),
            commands(settings, "Stop"),
        )
    }

    @Test
    fun bothComeAfterTheTeamsHooksEachEventsEntriesSideBySide() {
        val team = TeamHookSettings.claude(
            binary,
            listOf(InstalledHook("audit", TeamHookDefinition(listOf("Stop", "SessionStart"), "startup", "check", 10))),
        )
        val settings = CloudHookSettings.claude(team, binary, todo + workflow)
        assertEquals(
            listOf(
                "\"\$HOME\"/'.ostrom/hooks/audit/check'" to 10,
                "'$binary' cloud agent-state" to null,
                "'$binary' cloud todo-stop" to 150,
                "'$binary' cloud workflow-stop" to 5,
            ),
            commands(settings, "Stop"),
        )
        assertEquals(2, hooks(settings).getValue("SessionStart").jsonArray.size)
        assertEquals(JsonArray::class, hooks(settings).getValue("SessionEnd")::class)
    }

    @Test
    fun withoutTheTodoOrTheWorkflowThereIsNothingToChange() {
        val others = setOf(CloudMcpSession.ARTIFACTS, CloudMcpSession.MEMORY, CloudMcpSession.WAITS)
        assertNull(CloudHookSettings.claude(null, binary, others))
        assertNull(CloudHookSettings.claude("{}", binary, emptySet()))
    }

    @Test
    fun otherSettingsKeysStayAndSettingsWithoutHooksAreCompleted() {
        val settings = CloudHookSettings.claude("""{"env":{"A":"1"},"hooks":null}""", binary, workflow)
        val root = Json.parseToJsonElement(settings.orEmpty()).jsonObject
        assertEquals("1", root.getValue("env").jsonObject.getValue("A").jsonPrimitive.content)
        assertEquals(listOf("'$binary' cloud workflow-stop" to 5), commands(settings, "Stop"))
        val odd = CloudHookSettings.claude("""{"hooks":{"Stop":"x"}}""", binary, workflow)
        assertEquals(listOf("'$binary' cloud workflow-stop" to 5), commands(odd, "Stop"))
    }

    @Test
    fun settingsThatAreNoJsonObjectAreLeftAsTheyAre() {
        for (broken in listOf("", "not json", "[]", "{\"hooks\":")) {
            assertNull(CloudHookSettings.claude(broken, binary, todo), broken)
        }
    }

    @Test
    fun aBinaryPathWithAQuoteIsQuotedForTheShell() {
        val settings = CloudHookSettings.claude(null, "/opt/it's/ostrom", workflow)
        assertEquals("'/opt/it'\\''s/ostrom' cloud workflow-stop", commands(settings, "Stop").last().first)
    }
}
