package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A real child on macOS and Linux: exit code, arguments passed as they are, missing executable, signal. */
class ProcessLauncherPosixTest {
    private val launcher = ProcessLauncherImpl()

    @Test
    fun theExitCodeOfTheChildIsReturned() {
        assertEquals(ProcessResult.Exited(3), launcher.run(listOf("sh", "-c", "exit 3")))
        assertEquals(ProcessResult.Exited(0), launcher.run(listOf("true")))
    }

    @Test
    fun argumentsReachTheChildUnsplit() {
        val script = "test \"\$1\" = 'a b' && test \"\$2\" = '' && test \"\$3\" = '\"q\"'"
        val result = launcher.run(listOf("sh", "-c", script, "sh", "a b", "", "\"q\""))
        assertEquals(ProcessResult.Exited(0), result)
    }

    @Test
    fun theChildInheritsTheEnvironment() {
        assertEquals(ProcessResult.Exited(0), launcher.run(listOf("sh", "-c", "test -n \"\$PATH\"")))
    }

    @Test
    fun variablesAreSetAndRemovedForTheChild() {
        val script = "test \"\$OSTROM_TEST_SET\" = 'a b' && test -z \"\${OSTROM_TEST_GONE+x}\""
        launcher.run(listOf("true"), mapOf("OSTROM_TEST_GONE" to "here"))
        val environment = mapOf("OSTROM_TEST_SET" to "a b", "OSTROM_TEST_GONE" to null)
        val result = launcher.run(listOf("sh", "-c", script), environment)
        assertEquals(ProcessResult.Exited(0), result)
    }

    @Test
    fun aMissingExecutableIsNotFound() {
        assertIs<ProcessResult.NotFound>(launcher.run(listOf("ostrom-no-such-executable-4f2c")))
    }

    @Test
    fun aChildKilledByASignalEndsWith128PlusTheSignal() {
        assertEquals(ProcessResult.Exited(128 + 15), launcher.run(listOf("sh", "-c", "kill -TERM \$\$")))
    }
}
