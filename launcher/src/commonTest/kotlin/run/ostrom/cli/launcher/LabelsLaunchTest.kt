// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A launch's labels reach the server only when it keeps them (`sessions.labels.v1`). */
class LabelsLaunchTest : AgentLaunchKit() {
    private fun announce(vararg features: String) {
        val listed = features.joinToString(",") { "\"$it\"" }
        server["GET ${Routes.CAPABILITIES}"] = 200 to """{"apiVersion":1,"features":[$listed]}"""
    }

    @Test
    fun theLabelsAreSentOnTheLaunch() = runTest {
        serveALaunch()
        announce(SESSIONS_LABELS)
        start(labels = mapOf("team" to "core"))
        assertTrue(""""labels":{"team":"core"}""" in server.sent("POST ${Routes.LAUNCHES}").single().body.orEmpty())
        start()
        assertFalse("labels" in server.sent("POST ${Routes.LAUNCHES}").last().body.orEmpty())
    }

    @Test
    fun aServerThatKeepsNoLabelsStopsTheLaunchInsteadOfDroppingThem() = runTest {
        serveALaunch()
        announce("sessions.v1")
        assertEquals(64, start(labels = mapOf("team" to "core")))
        assertTrue(said.any { "does not keep labels" in it }, said.toString())
        assertEquals(0, server.sent("POST ${Routes.LAUNCHES}").size)
    }

    @Test
    fun theRulesOfALabel() {
        assertEquals(mapOf("a" to "b=c"), Labels.parseAll(listOf("a=b=c")).getOrThrow())
        assertEquals(mapOf("a" to "2"), Labels.parseAll(listOf("a=1", "a=2")).getOrThrow())
        assertTrue(Labels.parse("a").isFailure)
        assertTrue(Labels.parseAll(listOf("ok=1", "bad")).isFailure)
        assertTrue(Labels.parse("-a=1").isFailure)
        assertTrue(Labels.parse("a=" + "x".repeat(257)).isFailure)
        assertTrue(Labels.parseAll((1..17).map { "k$it=v" }).isFailure)
        assertTrue(Labels.parseAll((1..16).map { "k$it=v" }).isSuccess)
    }
}
