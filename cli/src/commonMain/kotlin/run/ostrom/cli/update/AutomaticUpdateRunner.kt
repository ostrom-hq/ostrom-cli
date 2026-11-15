// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * One bounded scheduled attempt (TeamCode's `AutomaticUpdateRunner`): nothing at all when the updates are off, turned
 * off in this process ([disabled]) or run by root; otherwise, holding the worker's lock and within [timeoutMillis],
 * the latest stable release installed when it is newer. A prerelease (a `-` in its version) is never installed.
 */
internal class AutomaticUpdateRunner(
    private val policy: AutomaticUpdateStore,
    private val platform: AutomaticUpdatePlatform,
    private val disabled: Boolean = false,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    /**
     * Runs one attempt: [fetch] reads the signed latest release, [install] installs it with the recorded settings
     * and returns the version selected (null when it already was). The result recorded, or null when nothing ran.
     */
    @Suppress("ReturnCount") // One early return per reason nothing runs.
    suspend fun run(
        fetch: suspend () -> ReleaseCheck?,
        install: suspend (ReleaseCheck, AutomaticUpdateSettings) -> InstalledVersion?,
    ): AutomaticUpdateResult? {
        if (disabled || platform.userId == null || platform.userId == ROOT_USER) return null
        val settings = policy.settings()?.takeIf { it.enabled } ?: return null
        require(settings.os == platform.os && settings.userId == platform.userId)
        val started = now()
        val lock = policy.tryLock(policy.workerLock)
            ?: return AutomaticUpdateResult(started, AutomaticUpdateResult.DEFERRED, diagnostic = WORKER_BUSY)
        return lock.use {
            policy.record(AutomaticUpdateResult(started, AutomaticUpdateResult.STARTED))
            val result = attempt(started, settings, fetch, install)
            policy.record(result)
            result
        }
    }

    private suspend fun attempt(
        started: Long,
        settings: AutomaticUpdateSettings,
        fetch: suspend () -> ReleaseCheck?,
        install: suspend (ReleaseCheck, AutomaticUpdateSettings) -> InstalledVersion?,
    ): AutomaticUpdateResult = try {
        withTimeoutOrNull(timeoutMillis) {
            val candidate = fetch()
            if (candidate == null || !candidate.updateAvailable || '-' in candidate.asset.version) {
                AutomaticUpdateResult(started, AutomaticUpdateResult.NO_UPDATE)
            } else {
                val outcome = if (install(candidate, settings) == null) {
                    AutomaticUpdateResult.NO_UPDATE
                } else {
                    AutomaticUpdateResult.UPDATED
                }
                AutomaticUpdateResult(started, outcome, version = candidate.asset.version)
            }
        } ?: AutomaticUpdateResult(started, AutomaticUpdateResult.FAILED, diagnostic = "automatic_update_timeout")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (stopped: Stop) {
        failed(started, stopped.diagnostic)
    } catch (refused: InstallationException) {
        failed(started, refused.code)
    } catch (_: Exception) {
        // never the failure's message: it may hold a remote body
        AutomaticUpdateResult(started, AutomaticUpdateResult.FAILED, diagnostic = "automatic_update_failed")
    }

    /** FAILED with [diagnostic], or DEFERRED when another installer or a session holds the installation. */
    private fun failed(started: Long, diagnostic: String): AutomaticUpdateResult {
        val deferred = diagnostic in DEFERRING
        val outcome = if (deferred) AutomaticUpdateResult.DEFERRED else AutomaticUpdateResult.FAILED
        return AutomaticUpdateResult(started, outcome, diagnostic = diagnostic)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15 * 60_000L
        val DEFERRING = setOf(InstallationStore.WRITER_BUSY, InstallationStore.BUSY, SESSION_RUNNING)
    }
}
