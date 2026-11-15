// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** The execution's inference credential in the runner's memory (TeamCode's `RunnerInference`). */
class RunnerInferenceTest {
    private val hour = 3_600_000L

    @Test
    fun theCredentialIsRenewedAfterAThirdAndDroppedWhenRefused() = runTest {
        val time = TestTimeSource()
        var fetches = 0
        var refuse: Exception? = null
        val logs = mutableListOf<String>()
        val inference = RunnerInference({
            refuse?.let { throw it }
            fetches++
            RunnerInferenceCredential("credential-$fetches", hour)
        }, logs::add, time)
        assertEquals("credential-1", inference.token())
        time += 60_000.milliseconds
        assertEquals("credential-1", inference.token(), "Reused until renewal is due")
        assertEquals(1, fetches)
        time += (hour / 3 - 60_000).milliseconds
        assertEquals("credential-2", inference.token(), "Renewed after a third of the lifetime the server returned")
        // A network failure keeps the current credential while it is still usable, and retries later.
        time += (hour / 3).milliseconds
        refuse = IllegalStateException("offline")
        assertEquals("credential-2", inference.token())
        assertTrue(inference.available())
        time += (hour - hour / 3 - RunnerInference.SAFETY_MILLIS).milliseconds
        assertNull(inference.token(), "Never offered in its last minutes")
        assertFalse(inference.available(), "The relay answers 503 instead of forwarding without a credential")
        // A refusal by the server (fenced execution) drops it at once; a missing route marks the server unsupported.
        refuse = null
        time += RunnerInference.RETRY_MILLIS.milliseconds
        assertEquals("credential-3", inference.token())
        time += (hour / 3).milliseconds
        refuse = InferenceRefusedException(409, "execution_fenced")
        assertNull(inference.token())
        assertFalse(inference.unsupported)
        time += RunnerInference.RETRY_MILLIS.milliseconds
        refuse = InferenceRefusedException(404, null)
        assertNull(inference.token())
        assertTrue(inference.unsupported)
        time += RunnerInference.RETRY_MILLIS.milliseconds
        assertNull(inference.token())
        assertEquals(1, logs.count { it.contains("does not issue") }, "Said once")
        assertTrue(logs.none { it.contains("credential-") }, "Never logged")
        refuse = null
        time += RunnerInference.RETRY_MILLIS.milliseconds
        assertEquals("credential-4", inference.token())
        assertFalse(inference.unsupported, "A server that issues one again")
    }

    @Test
    fun aRefusalThatDoesNotEndTheExecutionKeepsTheCredentialWhileItIsUsable() = runTest {
        val time = TestTimeSource()
        var refuse: Exception? = null
        val inference = RunnerInference({
            refuse?.let { throw it }
            RunnerInferenceCredential("credential", hour)
        }, time = time)
        assertEquals("credential", inference.token())
        time += (hour / 2).milliseconds
        refuse = InferenceRefusedException(400, null)
        assertEquals("credential", inference.token())
        inference.clear()
        time += RunnerInference.RETRY_MILLIS.milliseconds
        assertNull(inference.token(), "Cleared, then refused")
    }

    @Test
    fun theLifetimeIsBoundedEitherWay() = runTest {
        val time = TestTimeSource()
        var lifetime = 1L
        val inference = RunnerInference({ RunnerInferenceCredential("credential", lifetime) }, time = time)
        assertEquals("credential", inference.token())
        time += (RunnerInference.MIN_LIFETIME_MILLIS * 3 / 4 - 1).milliseconds
        assertEquals("credential", inference.token(), "A minute at least, its last quarter withheld")
        lifetime = Long.MAX_VALUE
        time += RunnerInference.MIN_LIFETIME_MILLIS.milliseconds
        assertEquals("credential", inference.token())
        time += (RunnerInference.MAX_LIFETIME_MILLIS - RunnerInference.SAFETY_MILLIS).milliseconds
        lifetime = hour
        assertEquals("credential", inference.token(), "Renewed: a day at most")
    }

    @Test
    fun whileARenewalIsInFlightTheCurrentCredentialIsServed() = runTest {
        val time = TestTimeSource()
        var fetches = 0
        val gate = CompletableDeferred<Unit>()
        val inference = RunnerInference({
            fetches++
            if (fetches > 1) gate.await()
            RunnerInferenceCredential("credential-$fetches", hour)
        }, time = time)
        assertEquals("credential-1", inference.token())
        time += (hour / 2).milliseconds
        val renewing = async { inference.token() }
        runCurrent()
        assertEquals("credential-1", inference.token(), "Not waiting on the network")
        gate.complete(Unit)
        assertEquals("credential-2", renewing.await())
    }

    @Test
    fun callersWaitingForTheFirstCredentialShareOneFetch() = runTest {
        var fetches = 0
        val gate = CompletableDeferred<Unit>()
        val inference = RunnerInference({
            fetches++
            gate.await()
            RunnerInferenceCredential("credential-$fetches", hour)
        }, time = TestTimeSource())
        val first = async { inference.token() }
        val second = async { inference.token() }
        runCurrent()
        gate.complete(Unit)
        assertEquals("credential-1", first.await())
        assertEquals("credential-1", second.await())
        assertEquals(1, fetches)
    }

    @Test
    fun theCredentialIsNeverPrinted() {
        assertFalse(RunnerInferenceCredential("secret", hour).toString().contains("secret"))
        assertEquals("The inference credential was refused (409 execution_fenced).",
            InferenceRefusedException(409, "execution_fenced").message)
        assertEquals("The inference credential was refused (401).", InferenceRefusedException(401, null).message)
    }
}
