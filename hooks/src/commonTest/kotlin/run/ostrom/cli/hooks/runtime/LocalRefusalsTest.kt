// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A refusal made on the workstation is said as always, then told to Ostrom once, when there is a session (A6). */
class LocalRefusalsTest {
    private val files = FakeFiles()
    private val calls = FakeCalls()

    private fun refusals(): LocalRefusals {
        val context = contextOf()
        val ids = SessionIds(context.sessionFile, files)
        return LocalRefusals(context, SessionWrites(calls, ids, context.launchId), ids, HookReplies(context.dialect))
    }

    private fun event(session: String?) =
        JsonObject(if (session == null) emptyMap() else mapOf("session_id" to JsonPrimitive(session)))

    @Test
    fun aRefusalWithoutAKnownActionIsToldWithTheUnknownClass() {
        val reply = refusals().deny(event("a1"), null, LocalRefusals.POLICY_HELD, "ls -la", "Ostrom: denied.")
        assertEquals(HookReplies(contextOf().dialect).deny("Ostrom: denied."), reply)
        val body = calls.bodyOf(Routes.LOCAL_DENIALS)
        val expected = """{"sessionId":"a1","class":"unknown","reason":"policy_held","command":"ls -la",""" +
            """"repo":"acme/app"}"""
        assertEquals(expected, body.toString())
        assertEquals(LocalRefusals.DENIAL_MILLIS, calls.calls.single().timeoutMillis)
    }

    @Test
    fun withoutASessionYetNothingIsSentAndTheRefusalStands() {
        val reply = refusals().deny(event(null), "push-main", LocalRefusals.FOREIGN_REPOSITORY, "git push", "denied")
        assertEquals(2, reply.exitCode)
        assertEquals("denied\n", reply.stderr)
        assertEquals(emptyList(), calls.calls, "Ostrom files a refusal under a session: none, nothing sent")
    }
}
