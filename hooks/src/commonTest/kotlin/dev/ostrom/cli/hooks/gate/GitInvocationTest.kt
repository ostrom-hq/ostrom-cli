package dev.ostrom.cli.hooks.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitInvocationTest {
    private fun parse(text: String) = GitInvocation.parse(ShellWords.segments(text).single())

    @Test
    fun readsTheOptionsWrittenBeforeTheVerb() {
        val invocation = parse("git -C a --no-pager -c user.name=x -C b --git-dir=/g --work-tree /w push origin main")
        assertEquals(
            GitInvocation(
                environment = emptyMap(),
                directories = listOf("a", "b"),
                configs = listOf("user.name=x"),
                gitDir = "/g",
                workTree = "/w",
                verb = "push",
                arguments = listOf("origin", "main"),
            ),
            invocation,
        )
        assertEquals("/g2", parse("git --git-dir /g2 status")?.gitDir)
        assertEquals("/w2", parse("git --work-tree=/w2 status")?.workTree)
        assertEquals(listOf("remote.origin.url=URL"), parse("git --config-env=remote.origin.url=URL push")?.configs)
        assertEquals("push", parse("git --namespace ns push")?.verb, "an option with a value takes the next word")
    }

    @Test
    fun readsTheEnvironmentAndTransparentPrefixes() {
        val invocation = parse("FOO=1 env -i GIT_DIR=/x command /usr/bin/git push")
        assertEquals(mapOf("FOO" to "1", "GIT_DIR" to "/x"), invocation?.environment)
        assertEquals("push", invocation?.verb)
        assertEquals("status", parse("git.exe status")?.verb)
    }

    @Test
    fun anythingElseIsNotAGitCall() {
        assertNull(parse("bash -c 'git push origin main'"))
        assertNull(parse("ls -la"))
        assertNull(parse("FOO=1"))
        assertNull(parse("-x=1 git push"), "an option is not an assignment")
        assertNull(parse("1A=b git push"), "not a variable name")
        assertNull(parse("ls -x git push"), "an option after another program")
        val bare = GitInvocation(emptyMap(), emptyList(), emptyList(), null, null, null, emptyList())
        assertEquals(bare, parse("git --version"))
    }

    @Test
    fun aRedirectionIsNamed() {
        assertNull(parse("git -c user.name=x push")?.redirection())
        assertEquals("the environment variable GIT_DIR", parse("GIT_DIR=/x git push")?.redirection())
        assertEquals("the environment variable GIT_CONFIG_KEY_0", parse("GIT_CONFIG_KEY_0=a git push")?.redirection())
        assertNull(parse("GIT_TRACE=1 git push")?.redirection())
        assertEquals("--git-dir", parse("git --git-dir=/x push")?.redirection())
        assertEquals("--work-tree", parse("git --work-tree=/x push")?.redirection())
        assertEquals("-c remote.origin.url", parse("git -c remote.origin.url=x push")?.redirection())
        assertEquals("-c url.x.pushInsteadOf", parse("git -c url.x.pushInsteadOf=y push")?.redirection())
        assertEquals("-c push.default", parse("git -c push.default=matching push")?.redirection())
        assertEquals("-c branch.main.merge", parse("git -c branch.main.merge=refs/heads/x push")?.redirection())
    }
}
