// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.cloud

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.api.TerminalChannel
import run.ostrom.cli.api.TerminalMessage
import run.ostrom.cli.launch.ApiServerTransport
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.cloud.CloudSessionUseCase
import run.ostrom.cli.launcher.cloud.CloudStart
import run.ostrom.cli.launcher.cloud.TerminalConnector
import run.ostrom.cli.launcher.cloud.TerminalOutput
import run.ostrom.cli.launcher.cloud.TerminalSocket
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.platform.RawTerminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.IOException

/**
 * Where a cloud command runs.
 *
 * @property serverUrl The Ostrom server, without a trailing slash.
 * @property token The device token.
 * @property cwd The folder `ostrom` runs in, read for the repository when none is named.
 * @property org The id of the organisation the calls act in (`Ostrom-Org`), or null: the person's only one.
 */
data class CloudSite(val serverUrl: String, val token: String, val cwd: String, val org: String? = null)

/** `ostrom <agent> --cloud` and `ostrom attach`; each returns the exit code. */
interface CloudRunner {
    /** Starts [start] in Ostrom's cloud, the repository of [site]'s folder when [CloudStart.repo] is null. */
    suspend fun start(start: CloudStart, site: CloudSite): Int

    /** Attaches this terminal to the cloud session [id]. */
    suspend fun attach(id: String, site: CloudSite): Int
}

/**
 * [CloudRunner] over the API client.
 *
 * @param channels Opens a terminal WebSocket: server URL, path, token, organisation.
 */
class CloudRunnerImpl(
    private val console: Console,
    private val api: (String) -> OstromApi,
    private val channels: suspend (String, String, String, String?) -> TerminalChannel,
    private val terminal: RawTerminal,
    private val capture: ProcessCapture,
) : CloudRunner {
    // `use` hands the client back: the wiring keeps one per server for the whole command, closed with the process
    override suspend fun start(start: CloudStart, site: CloudSite): Int = api(site.serverUrl).use {
        useCase(it, site).start(start.copy(repo = start.repo ?: gitHubOrigin(site.cwd)), site.token)
    }

    override suspend fun attach(id: String, site: CloudSite): Int =
        api(site.serverUrl).use { useCase(it, site).attach(id, site.token) }

    private fun useCase(client: OstromApi, site: CloudSite) = CloudSessionUseCase(
        ServerJson(ApiServerTransport(client, site.org)),
        TerminalConnector { path, token ->
            try {
                ChannelSocket(channels(site.serverUrl, path, token, site.org))
            } catch (e: ServerUnreachableException) {
                throw IOException(e.reason, e)
            }
        },
        terminal,
        { console.err("$it\n") },
    )

    /**
     * The `origin` remote of the repository at [cwd] when it is on GitHub, or null: outside a repository, and for an
     * origin elsewhere (GitLab, an ssh host alias), said, since only a `--repo` named by the developer is refused.
     */
    private suspend fun gitHubOrigin(cwd: String): String? {
        val command = listOf("git", "-C", cwd, "remote", "get-url", "origin")
        // git may wait on a credential helper or a slow disk: never on the caller's thread
        val result = withContext(Dispatchers.IO) { capture.capture(command, GIT_MILLIS) }
        val url = if (result is CaptureResult.Exited && result.code == 0) result.output.trim() else ""
        val onGitHub = url.isNotEmpty() && CloudSessionUseCase.gitHubUrl(url) != null
        if (url.isNotEmpty() && !onGitHub) {
            console.err("ostrom: origin is not on GitHub ($url): the session starts without a repository ($NAME_ONE)\n")
        }
        return if (onGitHub) url else null
    }

    /** How long git may take to name the remote. */
    companion object {
        private const val GIT_MILLIS = 5_000
        private const val NAME_ONE = "--repo names one"
    }
}

/** The launcher's terminal port over the API client's WebSocket. */
class ChannelSocket(private val channel: TerminalChannel) : TerminalSocket {
    override val closeCode: Int? get() = channel.closeCode

    override suspend fun receive(): TerminalOutput? = when (val message = channel.receive()) {
        is TerminalMessage.Output -> TerminalOutput.Bytes(message.bytes)
        is TerminalMessage.Control -> TerminalOutput.Control(message.text)
        null -> null
    }

    override suspend fun send(bytes: ByteArray) = channel.send(bytes)

    override suspend fun sendText(text: String) = channel.sendText(text)

    override suspend fun close() = channel.close()
}
