// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.launcher.team.Artifacts
import run.ostrom.cli.launcher.team.Hooks
import run.ostrom.cli.launcher.team.TEAM_HOOKS
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.core.api.models.ServerFeatures
import okio.ByteString.Companion.decodeHex
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom hooks`: list, show, approve and refuse the team hooks delivered to you. */
class TeamHookCommandsTest : SetupKit() {
    private val manifest = "GET /api/v1/hook-delivery"
    private val artifact = "GET /api/v1/hook-delivery/guard/1.0.0"
    private val folder = "/home/maya/.config/ostrom/hooks"
    private val line = "guard 1.0.0: runs check.sh on PreToolUse, Stop (matcher Bash), timeout 30 s"

    private fun routes(vararg hooks: String, files: String = Hooks.artifact()) = mapOf(
        capabilities(ServerFeatures.HOOKS),
        manifest to (200 to """{"memberId":"m1","hooks":[${hooks.joinToString(",")}]}"""),
        artifact to (200 to files),
    )

    private val guard = routes(Hooks.entry())

    private fun text(name: String): String? =
        "$folder/$name".toPath().takeIf(fileSystem::exists)?.let { fileSystem.read(it) { readUtf8() } }

    @Test
    fun theLaunchersFeatureKeyIsTheContractsKey() {
        assertEquals(ServerFeatures.HOOKS, TEAM_HOOKS)
    }

    @Test
    fun theDeliveredHooksAreListedWithYourDecisionOnEachVersion() {
        assertEquals(0, run(guard, "hooks", "list"))
        assertEquals("$line [awaiting your approval]\n", console.out.toString())
        assertErr("Read one with ostrom hooks show NAME, then ostrom hooks approve or refuse NAME.\n")
        console.out.clear()
        assertEquals(0, run(guard, "hooks"), "the list by default")
        assertEquals("$line [awaiting your approval]\n", console.out.toString())
        assertEquals(listOf("GET /api/v1/capabilities", manifest), sent().distinct())
    }

    @Test
    fun aListSaysWhenNothingIsDeliveredAndWhatThisOstromCannotInstall() {
        assertEquals(0, run(routes(Hooks.entry(name = "Bad")), "hooks", "list"))
        assertEquals("", console.out.toString())
        assertErr("No team hook is delivered to you.\n")
        assertErr("1 more cannot be installed by this ostrom: run ostrom update.\n")
        assertFalse(console.err.contains("Read one with"))
        console.out.clear()
        val bare = Hooks.entry(name = "audit").replace("\"matcher\":\"Bash\",", "")
        assertEquals(0, run(routes(bare), "hooks", "list"))
        val expected = "audit 1.0.0: runs check.sh on PreToolUse, Stop, timeout 30 s [awaiting your approval]\n"
        assertEquals(expected, console.out.toString())
    }

    @Test
    fun aVersionReadWholeIsApprovedThenListedApproved() {
        assertEquals(0, run(guard, "hooks", "show", "guard"))
        val out = console.out.toString()
        assertTrue(out.startsWith("$line\ndigest ${Hooks.digest}\n"), out)
        assertOut("\n--- check.sh (executable, 17 bytes) ---\n#!/bin/sh\nexit 0\n\n")
        assertOut("\n--- README.md (file, 8 bytes) ---\nChecks.\n")
        assertOut("\nApprove this version with ostrom hooks approve guard.\n")
        assertEquals("""{"guard":"${Hooks.digest}"}""", text("shown.json"))
        console.out.clear()
        assertEquals(0, run(guard, "hooks", "approve", "guard"))
        assertOut("Approved $line. It runs with your user's rights from your next launch")
        assertEquals("""{"guard":{"digest":"${Hooks.digest}","approved":true}}""", text("approvals.json"))
        console.out.clear()
        console.err.clear()
        assertEquals(0, run(guard, "hooks", "list"))
        assertEquals("$line [approved]\n", console.out.toString())
        assertFalse(console.err.contains("Read one with"))
    }

    @Test
    fun aRefusedVersionIsListedRefusedAndANewOneAwaitsAgain() {
        assertEquals(0, run(guard, "hooks", "refuse", "guard"))
        assertOut("Refused guard 1.0.0. Your agents do not run it; the team is not notified.\n")
        console.out.clear()
        assertEquals(0, run(guard, "hooks", "list"))
        assertEquals("$line [refused]\n", console.out.toString())
        console.out.clear()
        val next = Hooks.entry(digest = "sha256:" + "1".repeat(64))
        assertEquals(0, run(routes(next), "hooks", "list"))
        assertEquals("$line [new version awaiting your approval]\n", console.out.toString())
        assertErr("Read one with ostrom hooks show NAME")
    }

    @Test
    fun anApprovalTakesOnlyTheVersionYouReadOrTheDigestYouGave() {
        assertEquals(64, run(guard, "hooks", "approve", "guard"))
        assertErr("ostrom: Read it first with ostrom hooks show guard, or give the digest you checked with --digest.\n")
        assertEquals(64, run(guard, "hooks", "approve", "guard", "--digest", "sha256:xyz"))
        assertErr("Give --digest as sha256: and 64 hexadecimal digits")
        val other = "1".repeat(64)
        assertEquals(77, run(guard, "hooks", "approve", "guard", "--digest", other))
        assertErr("The server delivers ${Hooks.digest}, not the --digest you gave (sha256:$other).")
        assertNull(text("approvals.json"))
        val hex = Hooks.digest.removePrefix("sha256:").uppercase()
        assertEquals(0, run(guard, "hooks", "approve", "guard", "--digest", " $hex "))
        assertEquals("""{"guard":{"digest":"${Hooks.digest}","approved":true}}""", text("approvals.json"))
        fileSystem.write("$folder/shown.json".toPath()) { writeUtf8("""{"guard":"sha256:$other"}""") }
        assertEquals(77, run(guard, "hooks", "approve", "guard"))
        assertErr("The delivered version changed since you read it (you read sha256:$other, the server now delivers")
        assertEquals(0, run(guard, "hooks", "approve", "guard", "--digest", Hooks.digest))
    }

    @Test
    fun aVersionWhoseFilesDoNotMatchIsNeitherShownNorApproved() {
        val tampered = Hooks.files.map { Artifacts.Source(it.path, it.text + "rm -rf ~\n", it.mode) }
        val refused = routes(Hooks.entry(), files = Hooks.artifact(files = tampered))
        assertEquals(78, run(refused, "hooks", "show", "guard"))
        assertErr("ostrom: The team hook guard 1.0.0 was refused (digest): nothing of it is shown nor approved.\n")
        assertFalse(console.out.contains("rm -rf"))
        assertEquals(78, run(refused, "hooks", "approve", "guard", "--digest", Hooks.digest))
        assertNull(text("approvals.json"))
    }

    @Test
    fun aFileThatIsNotTextIsNotShownAndTheVersionNotRecordedAsRead() {
        val files = Hooks.files + Artifacts.Source("data.bin", "", content = "ff".decodeHex())
        val digest = Artifacts.digest(files, Hooks.definition.digestLine)
        val shown = routes(Hooks.entry(digest = digest), files = Hooks.artifact(digest = digest, files = files))
        assertEquals(0, run(shown, "hooks", "show", "guard"))
        assertOut("\n--- data.bin (file, 1 bytes) ---\n(binary content not shown)\n")
        assertErr("approve it with ostrom hooks approve guard --digest $digest.\n")
        assertNull(text("shown.json"))
    }

    @Test
    fun whatHidesInTheServersWordsIsMadeVisible() {
        val sneaky = Hooks.files.map { Artifacts.Source(it.path, it.text.replace("exit 0", "exit 0‮ #"), it.mode) }
        val digest = Artifacts.digest(sneaky, Hooks.definition.digestLine)
        val routes = routes(Hooks.entry(digest = digest), files = Hooks.artifact(digest = digest, files = sneaky))
        assertEquals(0, run(routes, "hooks", "show", "guard"))
        assertOut("exit 0<U+202E> #\n")
        assertEquals(66, run(routes, "hooks", "show", "gu‮ard"))
        assertErr("ostrom: No team hook named gu<U+202E>ard is delivered to you.\n")
    }

    @Test
    fun onWindowsNothingIsInstalledAndThatIsSaid() {
        operatingSystem = OperatingSystem.WINDOWS
        val windows = withServer + ("OSTROM_HOME" to "/home/maya/.config/ostrom")
        assertEquals(0, run(guard, "hooks", "list", environment = windows))
        assertErr("Team hooks are not run on Windows yet: ostrom installs none of them here.\n")
        assertEquals(0, run(guard, "hooks", "show", "guard", environment = windows))
        assertNull(text("shown.json"), "nothing recorded where nothing runs")
        assertFalse(console.out.contains("Approve this version"))
        assertEquals(70, run(guard, "hooks", "approve", "guard", "--digest", Hooks.digest, environment = windows))
        assertNull(text("approvals.json"))
    }

    @Test
    fun aServerThatDoesNotDeliverHooksOrRefusesIsSaidSo() {
        assertEquals(69, run(mapOf(capabilities("waits.v1")), "hooks", "list"))
        assertErr("ostrom: This Ostrom server does not deliver team hooks.\n")
        val unreadable = mapOf(capabilities(ServerFeatures.HOOKS), manifest to (200 to "{}"))
        assertEquals(69, run(unreadable, "hooks", "list"))
        assertErr("ostrom: The team hooks of this server cannot be read.\n")
        val forbidden = mapOf(
            capabilities(ServerFeatures.HOOKS),
            manifest to (403 to """{"error":{"type":"forbidden","message":"this key lacks hooks:read"}}"""),
        )
        assertEquals(77, run(forbidden, "hooks", "list"))
        assertErr("this key lacks hooks:read")
        val gone = guard + (artifact to (404 to "{}"))
        assertEquals(66, run(gone, "hooks", "show", "guard"))
        assertErr("No team hooks here.")
    }
}
