package dev.ostrom.cli.hooks.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushReaderTest {
    private val repo = mapOf(
        "/r|symbolic-ref --quiet --short HEAD" to "main",
        "/r|rev-parse --verify --quiet main^{commit}" to "M",
        "/r|rev-parse --verify --quiet HEAD^{commit}" to "M",
        "/r|rev-parse --verify --quiet @^{commit}" to "M",
        "/r|rev-parse --verify --quiet feature^{commit}" to "F",
        "/r|rev-parse --verify --quiet refs/tags/v1^{commit}" to "T",
        "/r|remote get-url origin" to "git@github.com:o/core.git",
        "/r|config branch.main.remote" to "origin",
        "/r|config branch.main.merge" to "refs/heads/main",
    )

    private fun reader(extra: Map<String, String> = emptyMap(), drop: Set<String> = emptySet()) =
        PushReader(FakeGit((repo + extra).filterKeys { it !in drop }), "/home")

    private fun refs(text: String) = reader().read("/r", text)?.refs

    @Test
    fun readsTheCommitsAndRefsAPushSends() {
        val push = reader().read("/r", "git push origin main")
        assertEquals(Push("origin", "o/core", listOf(PushRef("M", "main")), force = false), push)
        assertEquals(listOf("M"), push?.shas)
        assertEquals(listOf("main"), push?.destinations)
    }

    @Test
    fun theTextChangesTheActionDoesNot() {
        val main = listOf(PushRef("M", "main"))
        for (text in listOf(
            "git push origin main 2>&1 | tail -15",
            "git push",
            "git push origin HEAD",
            "git push origin @",
            "git push origin HEAD:main",
            "cd . && git push -u origin main",
            "git push origin main # current branch: main",
            "git -C /r push -o ci.skip --repo=origin +main:refs/heads/main",
            "git push --push-option x origin main >log",
        )) {
            assertEquals(main, refs(text), text)
        }
        assertEquals(listOf(PushRef("F", "main")), refs("git push origin feature:main"))
        assertEquals(listOf(PushRef("T", "v1")), refs("git push origin refs/tags/v1"))
        assertEquals(listOf(PushRef("", "old")), refs("git push origin :old"))
        assertEquals(listOf(PushRef("", "old")), refs("git push --delete origin old"))
        assertEquals(listOf(PushRef("", "old")), refs("git push -d origin refs/heads/old:"))
        assertNull(reader().read("/r", "git status"))
    }

    @Test
    fun aForcedPushIsSeenWhateverItsSpelling() {
        for (text in listOf(
            "git push --force origin main", "git push -f origin main", "git push -uf origin main",
            "git push --force-with-lease origin main", "git push --force-with-lease=main:abc origin main",
            "git push --force-if-includes origin main", "git push origin +main",
        )) {
            assertTrue(reader().read("/r", text)?.force == true, text)
        }
        assertFalse(reader().read("/r", "git push -u -q origin main")?.force == true)
    }

    @Test
    fun aPushOstromCannotTieToCommitsIsRefusedWithWhatToDo() {
        val r = reader()
        assertTrue(assertFailsWith<UnreadablePushException> { r.read("/r", "git commit -am fix && git push origin main") }.message.orEmpty().contains("own command"))
        for (option in listOf("--all", "--mirror", "--branches", "--tags")) {
            assertTrue(assertFailsWith<UnreadablePushException> { r.read("/r", "git push $option") }.message.orEmpty().contains("by name"))
        }
        assertTrue(assertFailsWith<UnreadablePushException> { r.read("/r", "git push origin nope") }.message.orEmpty().contains("names no commit"))
        assertTrue(assertFailsWith<UnreadablePushException> { r.read("/r", "bash -c 'git push origin main'") }.message.orEmpty().contains("plain"))
        assertTrue(
            assertFailsWith<UnreadablePushException> { r.read("/r", "git push origin main; git push upstream main") }
                .message.orEmpty().contains("two remotes"),
        )
    }

    @Test
    fun aBarePushDrivenByTheConfigIsRefused() {
        val pushSpec = reader(mapOf("/r|config --get-all remote.origin.push" to "+refs/heads/a:refs/heads/main\nrefs/heads/b"))
        assertTrue(assertFailsWith<UnreadablePushException> { pushSpec.read("/r", "git push") }.message.orEmpty().contains("remote.origin.push (+refs/heads/a:refs/heads/main, refs/heads/b)"))
        val mirror = reader(mapOf("/r|config --bool remote.origin.mirror" to "true"))
        assertTrue(assertFailsWith<UnreadablePushException> { mirror.read("/r", "git push origin") }.message.orEmpty().contains("mirror"))
        val matching = reader(mapOf("/r|config push.default" to "matching"))
        assertTrue(assertFailsWith<UnreadablePushException> { matching.read("/r", "git push") }.message.orEmpty().contains("matching"))
        assertEquals(listOf(PushRef("M", "main")), reader(mapOf("/r|config push.default" to "simple")).read("/r", "git push")?.refs)
    }

    @Test
    fun aDetachedHeadMustNameTheTarget() {
        val detached = reader(drop = setOf("/r|symbolic-ref --quiet --short HEAD"))
        assertTrue(assertFailsWith<UnreadablePushException> { detached.read("/r", "git push") }.message.orEmpty().contains("detached HEAD"))
        assertTrue(assertFailsWith<UnreadablePushException> { detached.read("/r", "git push origin HEAD") }.message.orEmpty().contains("HEAD:main"))
        assertEquals(listOf(PushRef("M", "main")), detached.read("/r", "git push origin HEAD:main")?.refs)
    }

    @Test
    fun theRemoteComesFromTheBranchElseOrigin() {
        val upstream = reader(mapOf("/r|config branch.main.remote" to "up", "/r|remote get-url up" to "https://github.com/u/core"))
        assertEquals(Push("up", "u/core", listOf(PushRef("M", "main")), false), upstream.read("/r", "git push"))
        val noRemote = reader(drop = setOf("/r|config branch.main.remote", "/r|config branch.main.merge"))
        assertEquals(Push("origin", "o/core", listOf(PushRef("M", "main")), false), noRemote.read("/r", "git push"))
    }

    @Test
    fun theYesNamesWherePushesReallyGo() {
        val pushUrl = reader(mapOf("/r|remote get-url --push origin" to "git@github.com:attacker/elsewhere.git"))
        assertEquals("attacker/elsewhere", pushUrl.read("/r", "git push origin main")?.remoteRepository)
        val url = "https://github.com/x/y.git"
        assertEquals(url, reader().read("/r", "git push $url main")?.remoteRepository, "a URL with no remote of that name")
    }

    @Test
    fun destinationsAreReadEvenBeforeTheCommitsExist() {
        val r = reader()
        assertEquals(listOf("main"), r.destinations("/r", "git commit -am x && git push origin HEAD"))
        assertEquals(listOf("new-branch", "main"), r.destinations("/r", "git push origin new-branch && git push origin main"))
        assertEquals(emptyList(), r.destinations("/r", "ls"))
        assertFailsWith<UnreadablePushException> { r.destinations("/r", "bash -c 'git push'") }
        assertFailsWith<UnreadablePushException> { r.destinations("/r", "git push --all") }
        assertEquals(listOf("main"), r.destinations("/elsewhere", "git -C /r push"), "the -C folder is where git reads")
    }

    @Test
    fun shortRefDropsTheNamespaces() {
        assertEquals("main", PushReader.shortRef("refs/heads/main"))
        assertEquals("v1", PushReader.shortRef("refs/tags/v1"))
        assertEquals("refs/notes/x", PushReader.shortRef("refs/notes/x"))
    }
}
